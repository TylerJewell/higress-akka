package io.akka.higress.domain;

/** A budget that applies to requests carrying a particular header value. */
public record HeaderBudgetRule(String header, String value, long thresholdTokens) {}
