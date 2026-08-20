package io.akka.higress;

import static org.assertj.core.api.Assertions.assertThat;

import akka.http.javadsl.model.ContentTypes;
import akka.javasdk.http.StrictResponse;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.higress.application.CredentialPoolEntity;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 §3.15 and §4.4: the credential pool, not the retry ceiling, is what usually
 * ends the sequence, and the two reasons are reported differently.
 */
public class CredentialExhaustionIntegrationTest extends TestKitSupport {

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
            higress.dispatch.max-retries = 5
            higress.dispatch.retry-on-status = ["5.."]
            higress.dispatch.failure-threshold = 1
            higress.dispatch.cooldown-millis = 600000
            higress.dispatch.credentials = ["cred-a", "cred-b"]
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
  public void runningOutOfCredentialsStopsBeforeTheCeiling() {
    var response = completions();
    assertThat(UPSTREAM.credentialsSeen()).containsExactly("cred-a", "cred-b");
    assertThat(header(response, "X-Higress-Attempts")).isEqualTo("2");
    assertThat(header(response, "X-Higress-Stopped-Because")).isEqualTo("NO_CREDENTIALS_LEFT");
  }

  @Test
  public void aCredentialEjectedByAnEarlierRequestIsNotUsedFirstByALaterOne() {
    // One failure ejects, so the first request takes cred-a out of rotation before it
    // moves on to cred-b — and takes cred-b out too. A second request finds nothing
    // available and dispatches on an ejected credential anyway (§3.20), in configured
    // order, because the pool is read at attempt time rather than snapshotted.
    completions();
    assertThat(UPSTREAM.credentialsSeen()).containsExactly("cred-a", "cred-b");

    var pool =
        componentClient.forKeyValueEntity("openai").method(CredentialPoolEntity::get).invoke();
    assertThat(pool.credentials())
        .allSatisfy(c -> assertThat(c.unavailableSinceMillis()).isPresent());

    UPSTREAM.reset();
    UPSTREAM.answersWith(200);
    completions();
    assertThat(UPSTREAM.credentialsSeen()).containsExactly("cred-a");
  }
}
