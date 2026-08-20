package io.akka.higress.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.higress.domain.CredentialPoolState;
import java.util.List;
import java.util.Optional;

/**
 * One provider's credentials. The entity id is the provider. SPEC-001 §3.17–§3.20.
 *
 * <p>Every command carries the pool's settings so that a pool which has never been
 * used initialises itself on first contact; once it holds credentials the settings on
 * a command update the thresholds but never replace the credentials, which carry the
 * failure counts.
 *
 * <p>Every request touches this entity several times and most of those touches change
 * nothing, so a command that computes a state equal to the one already held replies
 * without persisting it.
 */
@Component(id = "credential-pool")
public class CredentialPoolEntity extends KeyValueEntity<CredentialPoolState> {

  public record Settings(List<String> credentials, int failureThreshold, long cooldownMillis) {}

  public record Pick(Settings settings, List<String> alreadyTried, long nowMillis) {}

  public record Failure(Settings settings, String credential, long nowMillis) {}

  public record Success(Settings settings, String credential) {}

  public record Restore(Settings settings, long nowMillis) {}

  /** The credential to try, absent when every credential has already been tried. */
  public record Picked(Optional<String> credential) {}

  /** Which credential this failure took out of rotation, and for how long. */
  public record Ejection(Optional<String> ejected, long cooldownMillis) {}

  @Override
  public CredentialPoolState emptyState() {
    return CredentialPoolState.of(List.of(), 1, 0);
  }

  public Effect<Picked> pick(Pick command) {
    var state = settled(command.settings()).restoreCooledDown(command.nowMillis());
    var reply = new Picked(state.pick(command.alreadyTried()));
    return state.equals(currentState())
        ? effects().reply(reply)
        : effects().updateState(state).thenReply(reply);
  }

  public Effect<Ejection> recordFailure(Failure command) {
    var change = settled(command.settings()).recordFailure(command.credential(), command.nowMillis());
    var reply = new Ejection(change.ejected(), change.state().cooldownMillis());
    return change.state().equals(currentState())
        ? effects().reply(reply)
        : effects().updateState(change.state()).thenReply(reply);
  }

  public Effect<Done> recordSuccess(Success command) {
    var state = settled(command.settings()).recordSuccess(command.credential());
    return state.equals(currentState())
        ? effects().reply(Done.getInstance())
        : effects().updateState(state).thenReply(Done.getInstance());
  }

  public Effect<CredentialPoolState> restore(Restore command) {
    var state = settled(command.settings()).restoreCooledDown(command.nowMillis());
    return state.equals(currentState())
        ? effects().reply(state)
        : effects().updateState(state).thenReply(state);
  }

  public ReadOnlyEffect<CredentialPoolState> get() {
    return effects().reply(currentState());
  }

  private CredentialPoolState settled(Settings settings) {
    return currentState()
        .withSettings(settings.credentials(), settings.failureThreshold(), settings.cooldownMillis());
  }
}
