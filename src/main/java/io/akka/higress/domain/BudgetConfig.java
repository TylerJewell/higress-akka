package io.akka.higress.domain;

import java.util.List;

/**
 * The budgets one deployment enforces.
 *
 * @param globalThresholdTokens zero means there is no global budget
 */
public record BudgetConfig(
    String ruleName,
    long globalThresholdTokens,
    long windowSeconds,
    List<HeaderBudgetRule> headerRules) {}
