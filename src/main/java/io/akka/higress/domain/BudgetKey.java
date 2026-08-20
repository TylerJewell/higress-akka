package io.akka.higress.domain;

/** One budget this request is charged against. The key is the entity id. */
public record BudgetKey(String key, long thresholdTokens, long windowSeconds) {}
