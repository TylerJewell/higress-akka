package io.akka.higress;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.higress.api.BudgetView;
import io.akka.higress.api.CredentialPoolView;
import io.akka.higress.application.TokenBudgetEntity;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The operator surface: reading a budget and a credential pool back out.
 *
 * <p>Access control is switched off here so the handlers themselves are exercised;
 * that the internet cannot reach them is checked separately, in
 * {@link AdminEndpointAccessIntegrationTest}.
 */
public class AdminEndpointIntegrationTest extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withAclDisabled();
  }

  @Test
  public void aBudgetIsReadableByItsKey() {
    var key = "higress-token-ratelimit:{admin-it}:global_threshold:60";
    componentClient
        .forKeyValueEntity(key)
        .method(TokenBudgetEntity::charge)
        .invoke(new TokenBudgetEntity.Charge(100, 60, 42, 0, 7_000_000L));

    var view =
        httpClient
            .GET("/admin/budgets?key=" + java.net.URLEncoder.encode(key, StandardCharsets.UTF_8))
            .responseBodyAs(BudgetView.class)
            .invoke()
            .body();

    assertThat(view.spent()).isEqualTo(42);
    assertThat(view.thresholdTokens()).isEqualTo(100);
    assertThat(view.windowStartedAtMillis()).isEqualTo(7_000_000L);
  }

  @Test
  public void aPoolThatHasNeverBeenUsedReadsAsEmptyRatherThanFailing() {
    var view =
        httpClient
            .GET("/admin/credentials/never-used")
            .responseBodyAs(CredentialPoolView.class)
            .invoke()
            .body();

    assertThat(view.credentials()).isEmpty();
  }
}
