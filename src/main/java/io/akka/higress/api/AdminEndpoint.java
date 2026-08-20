package io.akka.higress.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import io.akka.higress.application.CredentialPoolEntity;
import io.akka.higress.application.TokenBudgetEntity;

/**
 * Reading a budget or a credential pool back out.
 *
 * <p>Separate from the gateway because it is not for the gateway's callers: a credential
 * pool names the credentials, and what a caller is spending is nobody else's business.
 * Other services may ask; the internet may not.
 */
@Acl(allow = @Acl.Matcher(service = "*"))
@HttpEndpoint("/admin")
public class AdminEndpoint extends AbstractHttpEndpoint {

  private final ComponentClient componentClient;

  public AdminEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/budgets")
  public BudgetView budget() {
    var key = requestContext().queryParams().getString("key").orElse("");
    return BudgetView.from(
        componentClient.forKeyValueEntity(key).method(TokenBudgetEntity::get).invoke());
  }

  @Get("/credentials/{providerId}")
  public CredentialPoolView credentials(String providerId) {
    return CredentialPoolView.from(
        componentClient.forKeyValueEntity(providerId).method(CredentialPoolEntity::get).invoke());
  }
}
