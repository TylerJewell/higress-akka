package io.akka.higress.domain;

import java.util.Optional;

/**
 * What one request's model name resolved to.
 *
 * <p>{@code providerHeader} is an {@code Optional} of a possibly-empty string because
 * absent and empty are different answers: a name with no separator sets no provider
 * header at all, and a name beginning with a separator sets it to the empty string.
 */
public record RoutingDecision(
    Optional<String> modelHeader,
    Optional<String> providerHeader,
    Optional<String> autoHeader,
    String chosenModel,
    String body,
    boolean bodyRewritten,
    boolean autoRouted,
    long declaredMaxTokens) {}
