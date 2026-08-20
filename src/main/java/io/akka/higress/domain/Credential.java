package io.akka.higress.domain;

import java.util.Optional;

/**
 * One provider credential.
 *
 * <p>{@code unavailableSinceMillis} is present exactly when the credential is out of
 * rotation; the two facts are one field so they cannot disagree.
 */
public record Credential(String name, int consecutiveFailures, Optional<Long> unavailableSinceMillis) {

  public static Credential fresh(String name) {
    return new Credential(name, 0, Optional.empty());
  }

  public boolean available() {
    return unavailableSinceMillis.isEmpty();
  }
}
