package io.akka.higress.application;

import akka.javasdk.client.ComponentClient;
import akka.javasdk.timer.TimerScheduler;
import io.akka.higress.domain.DispatchConfig;
import io.akka.higress.domain.DispatchOutcome;
import io.akka.higress.domain.StatusPattern;
import io.akka.higress.domain.TokenUsage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One request's trip to the upstream, retried on a different credential each time.
 * SPEC-001 §3.14–§3.16, §4.2 and §4.4.
 *
 * <p>The credential pool is read once per attempt rather than once per request: the
 * point of taking a credential out of rotation is to stop sending it traffic, and a
 * snapshot taken before the ejection keeps sending it traffic for the rest of the
 * sequence.
 */
public class DispatchSequence {

  private final ComponentClient componentClient;
  private final TimerScheduler timers;
  private final Upstream upstream;
  private final DispatchConfig config;

  public DispatchSequence(
      ComponentClient componentClient,
      TimerScheduler timers,
      Upstream upstream,
      DispatchConfig config) {
    this.componentClient = componentClient;
    this.timers = timers;
    this.upstream = upstream;
    this.config = config;
  }

  public DispatchOutcome dispatch(String path, String body, long nowMillis) {
    var settings =
        new CredentialPoolEntity.Settings(
            config.credentials(), config.failureThreshold(), config.cooldownMillis());
    var tried = new ArrayList<String>();
    Upstream.Answer answer = null;

    while (true) {
      var picked =
          componentClient
              .forKeyValueEntity(config.providerId())
              .method(CredentialPoolEntity::pick)
              .invoke(new CredentialPoolEntity.Pick(settings, List.copyOf(tried), nowMillis));

      if (picked.credential().isEmpty()) {
        return outcome(answer, tried, DispatchOutcome.StoppedBecause.NO_CREDENTIALS_LEFT);
      }
      var credential = picked.credential().get();
      tried.add(credential);

      answer = upstream.call(credential, path, body);

      if (answer.status() == 200) {
        componentClient
            .forKeyValueEntity(config.providerId())
            .method(CredentialPoolEntity::recordSuccess)
            .invoke(new CredentialPoolEntity.Success(settings, credential));
        return outcome(answer, tried, DispatchOutcome.StoppedBecause.SUCCESS);
      }

      var ejection =
          componentClient
              .forKeyValueEntity(config.providerId())
              .method(CredentialPoolEntity::recordFailure)
              .invoke(new CredentialPoolEntity.Failure(settings, credential, nowMillis));
      ejection.ejected().ifPresent(this::scheduleCooldown);

      if (!StatusPattern.matches(answer.status(), config.retryOnStatus())) {
        return outcome(answer, tried, DispatchOutcome.StoppedBecause.STATUS_NOT_RETRYABLE);
      }
      if (tried.size() > config.maxRetries()) {
        return outcome(answer, tried, DispatchOutcome.StoppedBecause.MAX_RETRIES);
      }
    }
  }

  private void scheduleCooldown(String credential) {
    timers.createSingleTimer(
        "cooldown-" + config.providerId() + "-" + credential,
        Duration.ofMillis(config.cooldownMillis()),
        componentClient
            .forTimedAction()
            .method(CooldownAction::restore)
            .deferred(config.providerId()));
  }

  private DispatchOutcome outcome(
      Upstream.Answer answer, List<String> tried, DispatchOutcome.StoppedBecause reason) {
    if (answer == null) {
      return new DispatchOutcome(
          503,
          "{\"error\":\"no credential available\"}",
          0,
          List.copyOf(tried),
          reason,
          Optional.empty());
    }
    return new DispatchOutcome(
        answer.status(),
        answer.body(),
        tried.size(),
        List.copyOf(tried),
        reason,
        TokenUsage.readFrom(answer.body()));
  }
}
