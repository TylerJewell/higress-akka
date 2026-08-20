package io.akka.higress.api;

import io.akka.higress.domain.CredentialPoolState;
import java.util.List;
import java.util.Optional;

/** What one provider's credentials look like from outside. */
public record CredentialPoolView(List<CredentialView> credentials, int failureThreshold, long cooldownMillis) {

  public record CredentialView(
      String name, int consecutiveFailures, Optional<Long> unavailableSinceMillis) {}

  public static CredentialPoolView from(CredentialPoolState state) {
    return new CredentialPoolView(
        state.credentials().stream()
            .map(
                c ->
                    new CredentialView(c.name(), c.consecutiveFailures(), c.unavailableSinceMillis()))
            .toList(),
        state.failureThreshold(),
        state.cooldownMillis());
  }
}
