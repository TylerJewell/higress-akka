package io.akka.higress;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.higress.application.CredentialPoolEntity;
import io.akka.higress.application.DispatchSequence;
import io.akka.higress.application.TokenBudgetEntity;
import io.akka.higress.application.Upstream;
import io.akka.higress.domain.AutoRoutingRule;
import io.akka.higress.domain.DispatchConfig;
import io.akka.higress.domain.ModelRouter;
import io.akka.higress.domain.RoutingConfig;
import io.akka.higress.domain.RoutingDecision;
import io.akka.higress.domain.TokenBudgetState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The port's half of {@code bench/cases.json}. Writes {@code bench/port-answers.json};
 * {@code bench/compare.py} puts it beside the source's answers.
 *
 * <p>Every case is read from the shared file, so the two sides cannot drift apart by
 * one of them being edited.
 */
public class BenchAnswersIntegrationTest extends TestKitSupport {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Path BENCH = Path.of("..", "higress-port", "bench");
  private static final long T0 = 3_000_000L;

  @Test
  public void writeTheAnswersForTheSharedCases() throws Exception {
    var cases = MAPPER.readTree(Files.readAllBytes(BENCH.resolve("cases.json")));

    var answers = MAPPER.createObjectNode();
    answers.set("routing", routingAnswers(cases));
    answers.set("routing_timing_ns", routingTiming(cases));
    answers.set("budget", budgetAnswers(cases));
    answers.set("budget_timing_ns", budgetTiming(cases));
    answers.set("dispatch", dispatchAnswers(cases));

    Files.writeString(
        BENCH.resolve("port-answers.json"),
        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(answers) + "\n");

    assertThat(Files.exists(BENCH.resolve("port-answers.json"))).isTrue();
  }

  // ---------------------------------------------------------------- routing

  private static RoutingConfig routingConfig(JsonNode cases) {
    var c = cases.path("routing").path("config");
    var suffixes = new ArrayList<String>();
    c.path("path_suffixes").forEach(n -> suffixes.add(n.asText()));
    var rules = new ArrayList<AutoRoutingRule>();
    c.path("auto_rules")
        .forEach(n -> rules.add(new AutoRoutingRule(n.path("pattern").asText(), n.path("model").asText())));
    return new RoutingConfig(
        c.path("model_key").asText(),
        optional(c.path("model_header").asText("")),
        optional(c.path("provider_header").asText("")),
        c.path("keep_original_model_name").asBoolean(),
        List.copyOf(suffixes),
        c.path("auto_sentinel").asText(),
        c.path("auto_header").asText(),
        optional(c.path("auto_default_model").asText("")),
        List.copyOf(rules));
  }

  private static Optional<String> optional(String value) {
    return value.isEmpty() ? Optional.empty() : Optional.of(value);
  }

  private ArrayNode routingAnswers(JsonNode cases) {
    var config = routingConfig(cases);
    var modelKey = config.modelKey();
    var out = MAPPER.createArrayNode();
    for (var kase : cases.path("routing").path("cases")) {
      var body = kase.path("body").asText();
      var decision = ModelRouter.decide(config, kase.path("path").asText(), body);
      var row = MAPPER.createObjectNode();
      row.put("name", kase.path("name").asText());
      putOrNull(row, "model_header", decision.flatMap(RoutingDecision::modelHeader));
      putOrNull(row, "provider_header", decision.flatMap(RoutingDecision::providerHeader));
      putOrNull(row, "auto_header", decision.flatMap(RoutingDecision::autoHeader));
      row.put("body_model", bodyModel(decision.map(RoutingDecision::body).orElse(body), modelKey));
      out.add(row);
    }
    return out;
  }

  private static void putOrNull(ObjectNode row, String field, Optional<String> value) {
    if (value.isPresent()) {
      row.put(field, value.get());
    } else {
      row.putNull(field);
    }
  }

  private static String bodyModel(String body, String modelKey) {
    try {
      return MAPPER.readTree(body).path(modelKey).asText("");
    } catch (Exception e) {
      return "";
    }
  }

  private ObjectNode routingTiming(JsonNode cases) {
    var config = routingConfig(cases);
    var routed = "{\"model\":\"openai/gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    var warmup = 20_000;
    var iterations = 200_000;

    for (var i = 0; i < warmup; i++) {
      ModelRouter.decide(config, "/v1/chat/completions", routed);
      ModelRouter.decide(config, "/v1/models", routed);
    }

    var start = System.nanoTime();
    for (var i = 0; i < iterations; i++) {
      ModelRouter.decide(config, "/v1/chat/completions", routed);
    }
    var routedNanos = (System.nanoTime() - start) / iterations;

    start = System.nanoTime();
    for (var i = 0; i < iterations; i++) {
      ModelRouter.decide(config, "/v1/models", routed);
    }
    var baselineNanos = (System.nanoTime() - start) / iterations;

    var timing = MAPPER.createObjectNode();
    timing.put("routed_call", routedNanos);
    timing.put("baseline_call", baselineNanos);
    timing.put("routing_work", routedNanos - baselineNanos);
    timing.put("iterations", iterations);
    timing.put("warmup", warmup);
    return timing;
  }

  // ----------------------------------------------------------------- budget

  private ArrayNode budgetAnswers(JsonNode cases) {
    var threshold = cases.path("budget").path("threshold_tokens").asLong();
    var window = cases.path("budget").path("window_seconds").asLong();
    var out = MAPPER.createArrayNode();

    for (var sequence : cases.path("budget").path("sequences")) {
      var name = sequence.path("name").asText();
      var key = "bench:" + name;
      var admitted = MAPPER.createArrayNode();
      var at = T0;
      for (var tokens : sequence.path("tokens")) {
        var admission =
            componentClient
                .forKeyValueEntity(key)
                .method(TokenBudgetEntity::admit)
                .invoke(new TokenBudgetEntity.Admit(threshold, window, 0, at));
        admitted.add(admission.admitted());
        if (admission.admitted()) {
          componentClient
              .forKeyValueEntity(key)
              .method(TokenBudgetEntity::charge)
              .invoke(new TokenBudgetEntity.Charge(threshold, window, tokens.asLong(), 0, at));
        }
        at++;
      }
      var state = componentClient.forKeyValueEntity(key).method(TokenBudgetEntity::get).invoke();
      var row = MAPPER.createObjectNode();
      row.put("name", name);
      row.set("admitted", admitted);
      row.put("final_counter", state.spent());
      out.add(row);
    }
    return out;
  }

  private ObjectNode budgetTiming(JsonNode cases) {
    var window = cases.path("budget").path("window_seconds").asLong();
    var ceiling = 1_000_000_000_000_000L;

    // The rule itself, with no store in the way — the number to hold beside redis's
    // own accounting for the two scripts.
    // The clock is held still: a moving one would expire the window part-way and
    // start timing a different path.
    var state = TokenBudgetState.empty(ceiling, window);
    for (var i = 0; i < 100_000; i++) {
      state = state.admit(0, T0).state().charge(1, 0, T0);
    }
    state = TokenBudgetState.empty(ceiling, window);
    var iterations = 500_000;
    var start = System.nanoTime();
    for (var i = 0; i < iterations; i++) {
      state = state.admit(0, T0).state().charge(1, 0, T0);
    }
    var ruleNanos = (System.nanoTime() - start) / iterations;
    assertThat(state.spent()).isEqualTo(iterations);

    // The same pair of decisions as one request makes them: two commands on the entity
    // that holds the budget.
    var key = "bench:timing";
    var roundTrips = 2_000;
    for (var i = 0; i < 200; i++) {
      admitAndCharge(key, ceiling, window, T0);
    }
    start = System.nanoTime();
    for (var i = 0; i < roundTrips; i++) {
      admitAndCharge(key, ceiling, window, T0);
    }
    var throughEntityNanos = (System.nanoTime() - start) / roundTrips;

    var timing = MAPPER.createObjectNode();
    timing.put("admit_and_charge_rule_only_ns", ruleNanos);
    timing.put("admit_and_charge_through_entity_ns", throughEntityNanos);
    timing.put("rule_iterations", iterations);
    timing.put("entity_iterations", roundTrips);
    return timing;
  }

  private void admitAndCharge(String key, long ceiling, long window, long at) {
    componentClient
        .forKeyValueEntity(key)
        .method(TokenBudgetEntity::admit)
        .invoke(new TokenBudgetEntity.Admit(ceiling, window, 0, at));
    componentClient
        .forKeyValueEntity(key)
        .method(TokenBudgetEntity::charge)
        .invoke(new TokenBudgetEntity.Charge(ceiling, window, 1, 0, at));
  }

  // --------------------------------------------------------------- dispatch

  /** Answers each attempt from the scenario's script and remembers what it was asked. */
  private static final class ScriptedCalls implements Upstream {
    private final List<Integer> statuses;
    private final List<String> credentials = new ArrayList<>();

    ScriptedCalls(List<Integer> statuses) {
      this.statuses = statuses;
    }

    @Override
    public Answer call(String credential, String path, String body) {
      credentials.add(credential);
      var status = statuses.get(Math.min(credentials.size() - 1, statuses.size() - 1));
      return new Answer(status, "{\"status\":" + status + "}");
    }
  }

  private ArrayNode dispatchAnswers(JsonNode cases) {
    var out = MAPPER.createArrayNode();
    for (var scenario : cases.path("dispatch").path("scenarios")) {
      var name = scenario.path("name").asText();
      var credentials = new ArrayList<String>();
      scenario.path("credentials").forEach(n -> credentials.add(n.asText()));
      var retryOnStatus = new ArrayList<String>();
      scenario.path("retry_on_status").forEach(n -> retryOnStatus.add(n.asText()));
      if (retryOnStatus.isEmpty()) {
        retryOnStatus.addAll(List.of("4..", "5.."));
      }
      var statuses = new ArrayList<Integer>();
      scenario.path("statuses").forEach(n -> statuses.add(n.asInt()));

      var upstream = new ScriptedCalls(statuses);
      var config =
          new DispatchConfig(
              "bench-" + name,
              List.copyOf(credentials),
              scenario.path("max_retries").asInt(),
              List.copyOf(retryOnStatus),
              99,
              600_000);
      var sequence = new DispatchSequence(componentClient, timerScheduler, upstream, config);
      var outcome = sequence.dispatch("/v1/chat/completions", "{}", T0);

      var row = MAPPER.createObjectNode();
      row.put("name", name);
      row.put("upstream_calls", outcome.attempts());
      row.put("final_status", outcome.status());
      row.put("stopped_because", outcome.stoppedBecause().name());
      out.add(row);

      // the pool is per scenario, so nothing carries over
      componentClient
          .forKeyValueEntity(config.providerId())
          .method(CredentialPoolEntity::get)
          .invoke();
    }
    return out;
  }
}
