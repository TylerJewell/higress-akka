package io.akka.higress.api;

import io.akka.higress.domain.TokenBudgetState;

/** What one budget looks like from outside. */
public record BudgetView(
    long thresholdTokens,
    long windowSeconds,
    long spent,
    long windowStartedAtMillis,
    long reservedTokens) {

  public static BudgetView from(TokenBudgetState state) {
    return new BudgetView(
        state.thresholdTokens(),
        state.windowSeconds(),
        state.spent(),
        state.windowStartedAtMillis(),
        state.reservedTokens());
  }
}
