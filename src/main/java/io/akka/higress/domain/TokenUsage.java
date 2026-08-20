package io.akka.higress.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;

/**
 * What an answer cost. SPEC-001 §3.10: an answer that reports no usage, or reports a
 * total of zero, is not charged at all — not charged zero, not charged.
 */
public record TokenUsage(long inputTokens, long outputTokens, long totalTokens) {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static Optional<TokenUsage> readFrom(String body) {
    try {
      var usage = MAPPER.readTree(body).path("usage");
      if (usage.isMissingNode()) {
        return Optional.empty();
      }
      var input = usage.path("prompt_tokens").asLong(usage.path("input_tokens").asLong(0));
      var output = usage.path("completion_tokens").asLong(usage.path("output_tokens").asLong(0));
      var total = usage.path("total_tokens").asLong(input + output);
      return total > 0 ? Optional.of(new TokenUsage(input, output, total)) : Optional.empty();
    } catch (Exception e) {
      return Optional.empty();
    }
  }
}
