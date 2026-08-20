package io.akka.higress.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Which budgets one request touches, and in what order. SPEC-001 §3.12.
 *
 * <p>The order decides which budget is reported when more than one is over, so it is
 * fixed: the global budget first, then the header rules in configured order.
 *
 * <p>The key strings follow higress's own layout so that a deployment's keys read the
 * same on both sides.
 */
public final class BudgetKeys {

  private static final String PREFIX = "higress-token-ratelimit";

  private BudgetKeys() {}

  public static List<BudgetKey> forRequest(BudgetConfig config, Map<String, String> headers) {
    var keys = new ArrayList<BudgetKey>();
    if (config.globalThresholdTokens() > 0) {
      keys.add(
          new BudgetKey(
              "%s:{%s}:global_threshold:%d".formatted(PREFIX, config.ruleName(), config.windowSeconds()),
              config.globalThresholdTokens(),
              config.windowSeconds()));
    }
    for (var rule : config.headerRules()) {
      var actual = headerValue(headers, rule.header());
      if (actual == null || !actual.equals(rule.value())) {
        continue;
      }
      keys.add(
          new BudgetKey(
              "%s:{%s}:limit_by_header:%d:%s:%s"
                  .formatted(PREFIX, config.ruleName(), config.windowSeconds(), rule.header(), actual),
              rule.thresholdTokens(),
              config.windowSeconds()));
    }
    return List.copyOf(keys);
  }

  private static String headerValue(Map<String, String> headers, String name) {
    for (var e : headers.entrySet()) {
      if (e.getKey().equalsIgnoreCase(name)) {
        return e.getValue();
      }
    }
    return null;
  }
}
