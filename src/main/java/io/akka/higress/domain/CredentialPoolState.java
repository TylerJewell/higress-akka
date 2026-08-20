package io.akka.higress.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** One provider's credentials. SPEC-001 §3.17–§3.20. */
public record CredentialPoolState(
    List<Credential> credentials, int failureThreshold, long cooldownMillis) {

  public static CredentialPoolState of(
      List<String> names, int failureThreshold, long cooldownMillis) {
    return new CredentialPoolState(
        names.stream().map(Credential::fresh).toList(), failureThreshold, cooldownMillis);
  }

  /** The state after a failure, and the credential that failure took out of rotation, if any. */
  public record PoolChange(CredentialPoolState state, Optional<String> ejected) {}

  public List<String> available() {
    return credentials.stream().filter(Credential::available).map(Credential::name).toList();
  }

  /**
   * The credential to try next. Available ones are preferred; when every credential is
   * out of rotation one of them is offered anyway, because being out of rotation
   * changes which credential is preferred, never whether the request is attempted.
   *
   * <p>Chosen in configured order rather than at random — see SPEC-001 §4.7.
   */
  public Optional<String> pick(List<String> alreadyTried) {
    var fromAvailable =
        credentials.stream()
            .filter(Credential::available)
            .map(Credential::name)
            .filter(n -> !alreadyTried.contains(n))
            .findFirst();
    if (fromAvailable.isPresent()) {
      return fromAvailable;
    }
    return credentials.stream()
        .map(Credential::name)
        .filter(n -> !alreadyTried.contains(n))
        .findFirst();
  }

  public PoolChange recordFailure(String name, long nowMillis) {
    var updated = new ArrayList<Credential>(credentials.size());
    var ejected = Optional.<String>empty();
    for (var c : credentials) {
      if (!c.name().equals(name) || !c.available()) {
        updated.add(c);
        continue;
      }
      var failures = c.consecutiveFailures() + 1;
      if (failures >= failureThreshold) {
        updated.add(new Credential(c.name(), 0, Optional.of(nowMillis)));
        ejected = Optional.of(c.name());
      } else {
        updated.add(new Credential(c.name(), failures, Optional.empty()));
      }
    }
    return new PoolChange(
        new CredentialPoolState(List.copyOf(updated), failureThreshold, cooldownMillis), ejected);
  }

  public CredentialPoolState recordSuccess(String name) {
    var updated =
        credentials.stream()
            .map(
                c ->
                    c.name().equals(name) && c.consecutiveFailures() != 0
                        ? new Credential(c.name(), 0, c.unavailableSinceMillis())
                        : c)
            .toList();
    return new CredentialPoolState(updated, failureThreshold, cooldownMillis);
  }

  public CredentialPoolState restoreCooledDown(long nowMillis) {
    var updated =
        credentials.stream()
            .map(
                c ->
                    c.unavailableSinceMillis()
                            .filter(since -> nowMillis - since >= cooldownMillis)
                            .isPresent()
                        ? Credential.fresh(c.name())
                        : c)
            .toList();
    return new CredentialPoolState(updated, failureThreshold, cooldownMillis);
  }

  public CredentialPoolState withSettings(
      List<String> names, int failureThreshold, long cooldownMillis) {
    if (!credentials.isEmpty()) {
      return new CredentialPoolState(credentials, failureThreshold, cooldownMillis);
    }
    return of(names, failureThreshold, cooldownMillis);
  }
}
