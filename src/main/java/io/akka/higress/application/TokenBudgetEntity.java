package io.akka.higress.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.higress.domain.TokenBudgetState;

/**
 * One budget key. The entity id is the key, so the runtime serialises every command
 * touching it and the read-modify-write below needs no locking of its own — SPEC-001
 * §3.8–§3.11, and question-log row 1 for why that holds.
 *
 * <p>The clock arrives in the command rather than being read here, so a rule about
 * what happens when a window runs out is checkable without waiting for one to.
 */
@Component(id = "token-budget")
public class TokenBudgetEntity extends KeyValueEntity<TokenBudgetState> {

  public record Admit(long thresholdTokens, long windowSeconds, long declaredMaxTokens, long nowMillis) {}

  public record Charge(
      long thresholdTokens, long windowSeconds, long tokens, long releaseReserved, long nowMillis) {}

  public record Admission(boolean admitted, long resetSeconds, long spent, long thresholdTokens) {}

  @Override
  public TokenBudgetState emptyState() {
    return TokenBudgetState.empty(0, 0);
  }

  public Effect<Admission> admit(Admit command) {
    var state = currentState().withLimits(command.thresholdTokens(), command.windowSeconds());
    var admission = state.admit(command.declaredMaxTokens(), command.nowMillis());
    return effects()
        .updateState(admission.state())
        .thenReply(
            new Admission(
                admission.admitted(),
                admission.resetSeconds(),
                admission.state().spent(),
                command.thresholdTokens()));
  }

  public Effect<TokenBudgetState> charge(Charge command) {
    var state = currentState().withLimits(command.thresholdTokens(), command.windowSeconds());
    var charged = state.charge(command.tokens(), command.releaseReserved(), command.nowMillis());
    return charged.equals(currentState())
        ? effects().reply(charged)
        : effects().updateState(charged).thenReply(charged);
  }

  public ReadOnlyEffect<TokenBudgetState> get() {
    return effects().reply(currentState());
  }
}
