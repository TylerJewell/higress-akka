package io.akka.higress.api;

import com.typesafe.config.Config;
import io.akka.higress.domain.AutoRoutingRule;
import io.akka.higress.domain.BudgetConfig;
import io.akka.higress.domain.DispatchConfig;
import io.akka.higress.domain.HeaderBudgetRule;
import io.akka.higress.domain.RoutingConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** The deployment's routing, budget and dispatch settings, read from {@code application.conf}. */
public record GatewayConfig(
    String upstreamService,
    RoutingConfig routing,
    BudgetConfig budget,
    DispatchConfig dispatch,
    int rejectionStatus,
    String rejectionMessage) {

  private static final Map<Config, GatewayConfig> PARSED = new ConcurrentHashMap<>();

  /**
   * The parsed form of this configuration. Components are constructed per request or
   * per invocation, and parsing compiles every auto-routing pattern, so the result is
   * kept against the configuration it came from.
   */
  public static GatewayConfig of(Config root) {
    return PARSED.computeIfAbsent(root, GatewayConfig::from);
  }

  public static GatewayConfig from(Config root) {
    var c = root.getConfig("higress");
    var r = c.getConfig("routing");
    var rules = new ArrayList<AutoRoutingRule>();
    for (var entry : r.getConfigList("auto-rules")) {
      rules.add(new AutoRoutingRule(entry.getString("pattern"), entry.getString("model")));
    }
    var routing =
        new RoutingConfig(
            r.getString("model-key"),
            optional(r.getString("model-header")),
            optional(r.getString("provider-header")),
            r.getBoolean("keep-original-model-name"),
            List.copyOf(r.getStringList("path-suffixes")),
            r.getString("auto-sentinel"),
            r.getString("auto-header"),
            optional(r.getString("auto-default-model")),
            List.copyOf(rules));

    var b = c.getConfig("budget");
    var headerRules = new ArrayList<HeaderBudgetRule>();
    for (var entry : b.getConfigList("header-rules")) {
      headerRules.add(
          new HeaderBudgetRule(
              entry.getString("header"), entry.getString("value"), entry.getLong("threshold-tokens")));
    }
    var budget =
        new BudgetConfig(
            b.getString("rule-name"),
            b.getLong("global-threshold-tokens"),
            b.getLong("window-seconds"),
            List.copyOf(headerRules));

    var d = c.getConfig("dispatch");
    var dispatch =
        new DispatchConfig(
            d.getString("provider-id"),
            List.copyOf(d.getStringList("credentials")),
            d.getInt("max-retries"),
            List.copyOf(d.getStringList("retry-on-status")),
            d.getInt("failure-threshold"),
            d.getLong("cooldown-millis"));

    var rejection = c.getConfig("rejection");
    return new GatewayConfig(
        c.getString("upstream-service"),
        routing,
        budget,
        dispatch,
        rejection.getInt("status"),
        rejection.getString("message"));
  }

  private static Optional<String> optional(String value) {
    return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
  }
}
