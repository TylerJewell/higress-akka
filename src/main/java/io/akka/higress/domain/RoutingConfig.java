package io.akka.higress.domain;

import java.util.List;
import java.util.Optional;

/**
 * How a request's model name is turned into a routing decision.
 *
 * @param modelKey the body field holding the model name
 * @param modelHeader header to receive the caller's model name unchanged, if configured
 * @param providerHeader header to receive the part before the first separator, if configured
 * @param keepOriginalModelName leave the body's model name alone even when it was split
 * @param pathSuffixes only requests whose path ends with one of these are routed
 * @param autoSentinel the model name that asks for a model to be chosen
 * @param autoHeader header written on the auto-routing path, and only there
 * @param autoDefaultModel used when the sentinel is present and no rule matches
 * @param autoRules tried in order; the first match wins
 */
public record RoutingConfig(
    String modelKey,
    Optional<String> modelHeader,
    Optional<String> providerHeader,
    boolean keepOriginalModelName,
    List<String> pathSuffixes,
    String autoSentinel,
    String autoHeader,
    Optional<String> autoDefaultModel,
    List<AutoRoutingRule> autoRules) {}
