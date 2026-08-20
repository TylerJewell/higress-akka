package io.akka.higress.domain;

import java.util.List;

/**
 * How a failed dispatch is retried and when a credential is taken out of rotation.
 *
 * @param maxRetries retries after the original attempt, so {@code n} permits {@code n+1}
 *     upstream calls in all (SPEC-001 §3.14)
 * @param retryOnStatus anchored patterns; the same list decides whether a failure counts
 *     towards taking a credential out of rotation
 */
public record DispatchConfig(
    String providerId,
    List<String> credentials,
    int maxRetries,
    List<String> retryOnStatus,
    int failureThreshold,
    long cooldownMillis) {}
