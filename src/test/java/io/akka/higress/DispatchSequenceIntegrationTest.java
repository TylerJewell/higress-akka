package io.akka.higress;

import static org.assertj.core.api.Assertions.assertThat;

import akka.http.javadsl.model.ContentTypes;
import akka.javasdk.http.StrictResponse;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 §3.14, §3.15, §3.16 and §4.2 with the runtime running: the retry sequence,
 * counted by what the upstream actually saw.
 */
public class DispatchSequenceIntegrationTest extends TestKitSupport {

  private static final ScriptedUpstream UPSTREAM = new ScriptedUpstream();

  @BeforeEach
  public void resetTheUpstream() {
    UPSTREAM.reset();
    UPSTREAM.answersWith(503);
  }

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withAdditionalConfig(
            """
            higress.budget.global-threshold-tokens = 0
            higress.budget.header-rules = []
            higress.dispatch.max-retries = 2
            higress.dispatch.retry-on-status = ["503"]
            higress.dispatch.failure-threshold = 99
            higress.dispatch.credentials = ["cred-a", "cred-b", "cred-c", "cred-d", "cred-e"]
            """)
        .withMockedHttpService("upstream", UPSTREAM::answer);
  }

  private StrictResponse<String> completions() {
    return httpClient
        .POST("/v1/chat/completions")
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

  @Test
  public void aCeilingOfTwoMakesThreeUpstreamCalls() {
    var response = completions();
    assertThat(UPSTREAM.credentialsSeen()).hasSize(3);
    assertThat(header(response, "X-Higress-Attempts")).isEqualTo("3");
    assertThat(header(response, "X-Higress-Stopped-Because")).isEqualTo("MAX_RETRIES");
    assertThat(response.status().intValue()).isEqualTo(503);
  }

  @Test
  public void eachAttemptUsesACredentialNotYetTried() {
    completions();
    assertThat(UPSTREAM.credentialsSeen()).hasSize(3).doesNotHaveDuplicates();
  }

  @Test
  public void aSuccessfulRetryEndsTheSequenceAndIsReported() {
    UPSTREAM.answersWith(503, 200);
    var response = completions();
    assertThat(UPSTREAM.credentialsSeen()).hasSize(2);
    assertThat(response.status().intValue()).isEqualTo(200);
    assertThat(header(response, "X-Higress-Stopped-Because")).isEqualTo("SUCCESS");
  }

  @Test
  public void aStatusOutsideTheListStopsTheSequenceEvenMidRetry() {
    // Higress applies the list only to the decision to start retrying; from the first
    // retry onward it retries anything that is not 200. SPEC-001 §4.2.
    UPSTREAM.answersWith(503, 500);
    var response = completions();
    assertThat(UPSTREAM.credentialsSeen()).hasSize(2);
    assertThat(header(response, "X-Higress-Stopped-Because")).isEqualTo("STATUS_NOT_RETRYABLE");
    assertThat(response.status().intValue()).isEqualTo(500);
  }

  @Test
  public void theRoutingDecisionIsReportedOnTheAnswer() {
    UPSTREAM.answersWith(200);
    var response = completions();
    assertThat(header(response, "x-model")).isEqualTo("openai/gpt-4o");
    assertThat(header(response, "x-provider")).isEqualTo("openai");
  }
}
