package io.akka.higress.domain;

/**
 * One budget key's state. SPEC-001 §3.8–§3.11 and §4.1.
 *
 * <p>{@code windowStartedAtMillis} of zero means no window is open — the state a key
 * that has never been charged, or whose window has run out, is in.
 */
public record TokenBudgetState(
    long thresholdTokens,
    long windowSeconds,
    long spent,
    long windowStartedAtMillis,
    long reservedTokens) {

  public static TokenBudgetState empty(long thresholdTokens, long windowSeconds) {
    return new TokenBudgetState(thresholdTokens, windowSeconds, 0, 0, 0);
  }

  public record Admission(boolean admitted, long resetSeconds, TokenBudgetState state) {}

  /**
   * Whether the request may proceed, and the state to keep. Nothing is charged here:
   * the cost is not known until the answer is.
   */
  public Admission admit(long declaredMaxTokens, long nowMillis) {
    var current = afterAnyWindowExpiry(nowMillis);
    var admitted = current.spent + current.reservedTokens <= current.thresholdTokens;
    var kept =
        admitted
            ? current.withReserved(current.reservedTokens + Math.max(0, declaredMaxTokens))
            : current;
    return new Admission(admitted, current.resetSeconds(nowMillis), kept);
  }

  public TokenBudgetState charge(long tokens, long nowMillis) {
    return charge(tokens, 0, nowMillis);
  }

  /**
   * Apply the answer's cost and release whatever this request reserved. A charge is
   * applied only while the counter is not already past the threshold, so the charge
   * that crosses the line lands in full and every later one is dropped.
   */
  public TokenBudgetState charge(long tokens, long releaseReserved, long nowMillis) {
    var current =
        afterAnyWindowExpiry(nowMillis)
            .withReserved(Math.max(0, reservedTokens - Math.max(0, releaseReserved)));
    if (tokens <= 0 || current.spent > current.thresholdTokens) {
      return current;
    }
    var windowStart = current.windowStartedAtMillis == 0 ? nowMillis : current.windowStartedAtMillis;
    return new TokenBudgetState(
        current.thresholdTokens,
        current.windowSeconds,
        current.spent + tokens,
        windowStart,
        current.reservedTokens);
  }

  /** Seconds left in the open window, or the whole window when none is open. */
  public long resetSeconds(long nowMillis) {
    if (windowStartedAtMillis == 0) {
      return windowSeconds;
    }
    var elapsed = (nowMillis - windowStartedAtMillis) / 1000;
    return Math.max(0, windowSeconds - elapsed);
  }

  public TokenBudgetState withLimits(long thresholdTokens, long windowSeconds) {
    return new TokenBudgetState(
        thresholdTokens, windowSeconds, spent, windowStartedAtMillis, reservedTokens);
  }

  private TokenBudgetState afterAnyWindowExpiry(long nowMillis) {
    if (windowStartedAtMillis == 0 || nowMillis - windowStartedAtMillis < windowSeconds * 1000) {
      return this;
    }
    return new TokenBudgetState(thresholdTokens, windowSeconds, 0, 0, reservedTokens);
  }

  private TokenBudgetState withReserved(long reserved) {
    return new TokenBudgetState(
        thresholdTokens, windowSeconds, spent, windowStartedAtMillis, reserved);
  }
}
