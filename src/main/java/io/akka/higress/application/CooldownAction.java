package io.akka.higress.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timedaction.TimedAction;
import com.typesafe.config.Config;
import io.akka.higress.api.GatewayConfig;

/**
 * Restores credentials whose cooldown has elapsed. SPEC-001 §3.19: the restoration
 * happens on its own, without a request having to arrive to trigger it.
 */
@Component(id = "cooldown")
public class CooldownAction extends TimedAction {

  private final ComponentClient componentClient;
  private final GatewayConfig config;

  public CooldownAction(ComponentClient componentClient, Config config) {
    this.componentClient = componentClient;
    this.config = GatewayConfig.of(config);
  }

  public Effect restore(String providerId) {
    var dispatch = config.dispatch();
    componentClient
        .forKeyValueEntity(providerId)
        .method(CredentialPoolEntity::restore)
        .invoke(
            new CredentialPoolEntity.Restore(
                new CredentialPoolEntity.Settings(
                    dispatch.credentials(), dispatch.failureThreshold(), dispatch.cooldownMillis()),
                System.currentTimeMillis()));
    return effects().done();
  }
}
