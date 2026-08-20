package io.akka.higress.domain;

import java.util.List;
import java.util.Optional;

/**
 * What the dispatch sequence did. SPEC-001 §3.16: "gave up at the ceiling" and "ran out
 * of credentials" have to be distinguishable, so the reason is reported rather than
 * inferred from the attempt count.
 */
public record DispatchOutcome(
    int status,
    String body,
    int attempts,
    List<String> credentialsUsed,
    StoppedBecause stoppedBecause,
    Optional<TokenUsage> usage) {

  public enum StoppedBecause {
    SUCCESS,
    MAX_RETRIES,
    NO_CREDENTIALS_LEFT,
    STATUS_NOT_RETRYABLE
  }
}
