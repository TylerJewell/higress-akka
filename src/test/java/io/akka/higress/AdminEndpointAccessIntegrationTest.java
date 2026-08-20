package io.akka.higress;

import static org.assertj.core.api.Assertions.assertThat;

import akka.http.javadsl.model.ContentTypes;
import akka.javasdk.testkit.TestKitSupport;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * A credential pool names the credentials, and what a caller is spending is nobody
 * else's business, so the operator surface is not on the same access footing as the
 * gateway. With access control on, a caller from outside is refused.
 */
public class AdminEndpointAccessIntegrationTest extends TestKitSupport {

  @Test
  public void theOperatorSurfaceIsNotReachableFromOutside() {
    assertThat(statusOf("/admin/credentials/openai")).isEqualTo(403);
    assertThat(statusOf("/admin/budgets?key=anything")).isEqualTo(403);
  }

  @Test
  public void theGatewayItselfIsReachableFromOutside() {
    // the same client, the same run: what differs is which endpoint is being asked
    var response =
        httpClient
            .POST("/v1/chat/completions")
            .withRequestBody(ContentTypes.APPLICATION_JSON, "{}".getBytes(StandardCharsets.UTF_8))
            .parseResponseBody(b -> new String(b, StandardCharsets.UTF_8))
            .invoke();
    assertThat(response.status().intValue()).isNotEqualTo(403);
  }

  private int statusOf(String path) {
    return httpClient
        .GET(path)
        .parseResponseBody(b -> new String(b, StandardCharsets.UTF_8))
        .invoke()
        .status()
        .intValue();
  }
}
