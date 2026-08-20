package io.akka.higress;

import static org.assertj.core.api.Assertions.assertThat;

import akka.http.javadsl.model.ContentTypes;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.higress.application.CredentialPoolEntity;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 §3.19: a credential comes back on its own once its cooldown has elapsed —
 * no request has to arrive to trigger the restoration, so nothing here sends one.
 */
public class CooldownIntegrationTest extends TestKitSupport {

  private static final ScriptedUpstream UPSTREAM = new ScriptedUpstream();

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withAdditionalConfig(
            """
            higress.budget.global-threshold-tokens = 0
            higress.budget.header-rules = []
            higress.dispatch.max-retries = 0
            higress.dispatch.retry-on-status = ["5.."]
            higress.dispatch.failure-threshold = 1
            higress.dispatch.cooldown-millis = 1500
            higress.dispatch.credentials = ["cred-a", "cred-b", "cred-c"]
            """)
        .withMockedHttpService("upstream", UPSTREAM::answer);
  }

  @Test
  public void aCredentialIsRestoredByTheCooldownTimerAlone() {
    UPSTREAM.reset();
    UPSTREAM.answersWith(503);

    httpClient
        .POST("/v1/chat/completions")
        .withRequestBody(
            ContentTypes.APPLICATION_JSON,
            "{\"model\":\"openai/gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"
                .getBytes(StandardCharsets.UTF_8))
        .parseResponseBody(b -> new String(b, StandardCharsets.UTF_8))
        .invoke();

    assertThat(UPSTREAM.credentialsSeen()).containsExactly("cred-a");
    assertThat(unavailable()).containsExactly("cred-a");

    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(unavailable()).isEmpty());
  }

  private List<String> unavailable() {
    var pool =
        componentClient.forKeyValueEntity("openai").method(CredentialPoolEntity::get).invoke();
    return pool.credentials().stream()
        .filter(c -> c.unavailableSinceMillis().isPresent())
        .map(c -> c.name())
        .toList();
  }
}
