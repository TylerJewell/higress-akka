package io.akka.higress;

import static org.assertj.core.api.Assertions.assertThat;

import akka.http.javadsl.model.ContentTypes;
import akka.javasdk.http.StrictResponse;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.higress.application.TokenBudgetEntity;
import io.akka.higress.domain.TokenBudgetState;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 §3.8–§3.13 end to end: the budget across a run of requests, and the shape of
 * a refusal. One request cannot show any of this — the first one is admitted on every
 * reading of the rule.
 *
 * <p>The runtime is shared by every test in this class and a budget is durable, so each
 * test carries its own {@code x-case} header and therefore its own budget key.
 */
public class GatewayEndpointIntegrationTest extends TestKitSupport {

  private static final ScriptedUpstream UPSTREAM = new ScriptedUpstream();

  @BeforeEach
  public void resetTheUpstream() {
    UPSTREAM.reset();
    UPSTREAM.answersWith(200);
  }

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withAdditionalConfig(
            """
            higress.budget.rule-name = "gateway-it"
            higress.budget.global-threshold-tokens = 0
            higress.budget.window-seconds = 60
            higress.budget.header-rules = [
              { header = "x-case", value = "two-then-refuse", threshold-tokens = 100 },
              { header = "x-case", value = "refusal-shape",   threshold-tokens = 100 },
              { header = "x-case", value = "no-tokens",       threshold-tokens = 100 },
              { header = "x-case", value = "crossing-charge", threshold-tokens = 100 }
            ]
            higress.dispatch.max-retries = 0
            higress.dispatch.failure-threshold = 99
            higress.dispatch.credentials = ["cred-a"]
            higress.rejection.status = 429
            higress.rejection.message = "token budget exhausted"
            """)
        .withMockedHttpService("upstream", UPSTREAM::answer);
  }

  private StrictResponse<String> completions(String testCase) {
    return httpClient
        .POST("/v1/chat/completions")
        .addHeader("x-case", testCase)
        .withRequestBody(
            ContentTypes.APPLICATION_JSON,
            "{\"model\":\"openai/gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"
                .getBytes(StandardCharsets.UTF_8))
        .parseResponseBody(b -> new String(b, StandardCharsets.UTF_8))
        .invoke();
  }

  private static String header(StrictResponse<String> r, String name) {
    return r.httpResponse().getHeader(name).map(h -> h.value()).orElse("(absent)");
  }

  private TokenBudgetState budget(String testCase) {
    var key = "higress-token-ratelimit:{gateway-it}:limit_by_header:60:x-case:" + testCase;
    return componentClient.forKeyValueEntity(key).method(TokenBudgetEntity::get).invoke();
  }

  @Test
  public void twoFullBudgetRequestsAreAdmittedAndTheThirdIsRefused() {
    UPSTREAM.reportsTokens(100);

    assertThat(completions("two-then-refuse").status().intValue()).isEqualTo(200);
    assertThat(completions("two-then-refuse").status().intValue()).isEqualTo(200);
    assertThat(completions("two-then-refuse").status().intValue()).isEqualTo(429);

    assertThat(budget("two-then-refuse").spent()).isEqualTo(200);
  }

  @Test
  public void aRefusalCarriesTheStatusTheMessageAndTheResetHeaderAndNothingElse() {
    UPSTREAM.reportsTokens(1_000);
    completions("refusal-shape");
    var refused = completions("refusal-shape");

    assertThat(refused.status().intValue()).isEqualTo(429);
    assertThat(refused.body()).contains("token budget exhausted");
    assertThat(Long.parseLong(header(refused, "X-TokenRateLimit-Reset"))).isBetween(0L, 60L);
    // no remaining-budget figure goes on the wire, and no dispatch happened
    assertThat(header(refused, "X-RateLimit-Remaining")).isEqualTo("(absent)");
    assertThat(header(refused, "X-Higress-Attempts")).isEqualTo("(absent)");
  }

  @Test
  public void anAnswerReportingNoTokensIsNotChargedAtAll() {
    UPSTREAM.reportsTokens(0);
    for (var i = 0; i < 5; i++) {
      assertThat(completions("no-tokens").status().intValue()).isEqualTo(200);
    }
    var state = budget("no-tokens");
    assertThat(state.spent()).isZero();
    assertThat(state.windowStartedAtMillis()).isZero();
  }

  @Test
  public void theChargeThatCrossesTheThresholdLandsInFullAndLaterOnesAreDropped() {
    UPSTREAM.reportsTokens(1_000);
    completions("crossing-charge");
    assertThat(budget("crossing-charge").spent()).isEqualTo(1_000);

    // refused from here on, so nothing further is charged either
    assertThat(completions("crossing-charge").status().intValue()).isEqualTo(429);
    assertThat(budget("crossing-charge").spent()).isEqualTo(1_000);
  }
}
