package io.akka.higress.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.RawHeader;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpClientProvider;
import akka.javasdk.timer.TimerScheduler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.typesafe.config.Config;
import io.akka.higress.application.DispatchSequence;
import io.akka.higress.application.HttpUpstream;
import io.akka.higress.application.TokenBudgetEntity;
import io.akka.higress.domain.BudgetKey;
import io.akka.higress.domain.BudgetKeys;
import io.akka.higress.domain.DispatchOutcome;
import io.akka.higress.domain.ModelRouter;
import io.akka.higress.domain.RoutingDecision;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The gateway's own surface: the path a caller reaches, rather than a filter sitting in
 * front of one. SPEC-001 §3.13 for the refusal, §4.6 for what the change of runtime
 * moves.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/v1")
public class GatewayEndpoint extends AbstractHttpEndpoint {

  private static final String COMPLETIONS_PATH = "/v1/chat/completions";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ComponentClient componentClient;
  private final GatewayConfig config;
  private final DispatchSequence dispatchSequence;

  public GatewayEndpoint(
      ComponentClient componentClient,
      TimerScheduler timers,
      HttpClientProvider httpClientProvider,
      Config rawConfig) {
    this.componentClient = componentClient;
    // A fresh endpoint instance is built per request, and parsing this compiles every
    // auto-routing pattern, so it is parsed once per configuration rather than per call.
    this.config = GatewayConfig.of(rawConfig);
    this.dispatchSequence =
        new DispatchSequence(
            componentClient,
            timers,
            new HttpUpstream(httpClientProvider.httpClientFor(config.upstreamService())),
            config.dispatch());
  }

  @Post("/chat/completions")
  public HttpResponse completions(JsonNode requestBody) {
    var now = System.currentTimeMillis();
    var decision = ModelRouter.decide(config.routing(), COMPLETIONS_PATH, requestBody);
    var dispatchedBody = decision.map(RoutingDecision::body).orElseGet(requestBody::toString);
    var declaredMax = decision.map(RoutingDecision::declaredMaxTokens).orElse(0L);

    var keys = BudgetKeys.forRequest(config.budget(), requestHeaders(config));
    var admitted = new ArrayList<BudgetKey>(keys.size());
    for (var key : keys) {
      var admission =
          componentClient
              .forKeyValueEntity(key.key())
              .method(TokenBudgetEntity::admit)
              .invoke(
                  new TokenBudgetEntity.Admit(
                      key.thresholdTokens(), key.windowSeconds(), declaredMax, now));
      if (!admission.admitted()) {
        releaseReservations(admitted, declaredMax, now);
        return refusal(admission.resetSeconds());
      }
      admitted.add(key);
    }

    var outcome = dispatchSequence.dispatch(COMPLETIONS_PATH, dispatchedBody, now);

    var charged = outcome.usage().map(u -> u.totalTokens()).orElse(0L);
    for (var key : admitted) {
      componentClient
          .forKeyValueEntity(key.key())
          .method(TokenBudgetEntity::charge)
          .invoke(
              new TokenBudgetEntity.Charge(
                  key.thresholdTokens(), key.windowSeconds(), charged, declaredMax, now));
    }

    return answer(outcome, decision);
  }

  private void releaseReservations(List<BudgetKey> keys, long declaredMax, long now) {
    for (var key : keys) {
      componentClient
          .forKeyValueEntity(key.key())
          .method(TokenBudgetEntity::charge)
          .invoke(
              new TokenBudgetEntity.Charge(
                  key.thresholdTokens(), key.windowSeconds(), 0, declaredMax, now));
    }
  }

  /** Only collected when some budget rule can use it. */
  private Map<String, String> requestHeaders(GatewayConfig config) {
    if (config.budget().headerRules().isEmpty()) {
      return Map.of();
    }
    var headers = new HashMap<String, String>();
    for (var h : requestContext().allRequestHeaders()) {
      headers.put(h.name(), h.value());
    }
    return headers;
  }

  private HttpResponse refusal(long resetSeconds) {
    var body = JsonNodeFactory.instance.objectNode().put("error", config.rejectionMessage());
    return HttpResponse.create()
        .withStatus(StatusCodes.get(config.rejectionStatus()))
        .addHeader(RawHeader.create("X-TokenRateLimit-Reset", Long.toString(resetSeconds)))
        .withEntity(ContentTypes.APPLICATION_JSON, writeJson(body));
  }

  private static byte[] writeJson(JsonNode node) {
    try {
      return MAPPER.writeValueAsBytes(node);
    } catch (Exception e) {
      throw new IllegalStateException("could not render the refusal body", e);
    }
  }

  private HttpResponse answer(DispatchOutcome outcome, Optional<RoutingDecision> decision) {
    var response =
        HttpResponse.create()
            .withStatus(StatusCodes.get(outcome.status()))
            .addHeader(RawHeader.create("X-Higress-Attempts", Integer.toString(outcome.attempts())))
            .addHeader(
                RawHeader.create("X-Higress-Credentials", String.join(",", outcome.credentialsUsed())))
            .addHeader(RawHeader.create("X-Higress-Stopped-Because", outcome.stoppedBecause().name()))
            .withEntity(ContentTypes.APPLICATION_JSON, outcome.body().getBytes(StandardCharsets.UTF_8));

    if (decision.isPresent()) {
      var d = decision.get();
      if (d.modelHeader().isPresent() && config.routing().modelHeader().isPresent()) {
        response =
            response.addHeader(
                RawHeader.create(config.routing().modelHeader().get(), d.modelHeader().get()));
      }
      if (d.providerHeader().isPresent() && config.routing().providerHeader().isPresent()) {
        response =
            response.addHeader(
                RawHeader.create(config.routing().providerHeader().get(), d.providerHeader().get()));
      }
      if (d.autoHeader().isPresent()) {
        response =
            response.addHeader(RawHeader.create(config.routing().autoHeader(), d.autoHeader().get()));
      }
    }
    return response;
  }
}
