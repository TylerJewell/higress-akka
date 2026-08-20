package io.akka.higress.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;

/**
 * Turns a request path and body into a routing decision. SPEC-001 §3.1–§3.7.
 *
 * <p>Nothing here touches the runtime: the decision is a function of the configuration
 * and the request, which is what makes every rule in §3 checkable without one.
 */
public final class ModelRouter {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SEPARATOR = "/";

  private ModelRouter() {}

  public static Optional<RoutingDecision> decide(RoutingConfig config, String path, String body) {
    JsonNode root;
    try {
      root = MAPPER.readTree(body);
    } catch (Exception e) {
      return Optional.empty();
    }
    return decide(config, path, root);
  }

  /** The caller that has already parsed the body hands the parsed form straight in. */
  public static Optional<RoutingDecision> decide(RoutingConfig config, String path, JsonNode root) {
    if (!pathIsRouted(config, path)) {
      return Optional.empty();
    }
    if (root == null || !root.isObject()) {
      return Optional.empty();
    }
    var body = root.toString();
    var model = root.path(config.modelKey()).asText("");
    if (model.isEmpty()) {
      return Optional.empty();
    }
    var declaredMax = root.path("max_tokens").asLong(0);

    if (model.equals(config.autoSentinel())) {
      return Optional.of(autoRoute(config, (ObjectNode) root, body, declaredMax));
    }
    return Optional.of(providerSplit(config, (ObjectNode) root, body, model, declaredMax));
  }

  private static boolean pathIsRouted(RoutingConfig config, String path) {
    var withoutQuery = path;
    var q = withoutQuery.indexOf('?');
    if (q >= 0) {
      withoutQuery = withoutQuery.substring(0, q);
    }
    for (var suffix : config.pathSuffixes()) {
      if (suffix.equals("*") || withoutQuery.endsWith(suffix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The auto-routing branch writes its own header and the body and returns; the model
   * and provider headers belong to the other branch and are not written here.
   */
  private static RoutingDecision autoRoute(
      RoutingConfig config, ObjectNode root, String body, long declaredMax) {
    var lastWords = lastUserText(root);
    var chosen = Optional.<String>empty();
    if (!lastWords.isEmpty()) {
      for (var rule : config.autoRules()) {
        if (rule.pattern().matcher(lastWords).find()) {
          chosen = Optional.of(rule.model());
          break;
        }
      }
    }
    if (chosen.isEmpty()) {
      chosen = config.autoDefaultModel().filter(m -> !m.isEmpty());
    }
    if (chosen.isEmpty()) {
      return new RoutingDecision(
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          config.autoSentinel(),
          body,
          false,
          true,
          declaredMax);
    }
    var model = chosen.get();
    return new RoutingDecision(
        Optional.empty(),
        Optional.empty(),
        Optional.of(model),
        model,
        rewriteModel(root, config.modelKey(), model),
        true,
        true,
        declaredMax);
  }

  private static RoutingDecision providerSplit(
      RoutingConfig config, ObjectNode root, String body, String model, long declaredMax) {
    var modelHeader = config.modelHeader().isPresent() ? Optional.of(model) : Optional.<String>empty();

    var separator = model.indexOf(SEPARATOR);
    if (config.providerHeader().isEmpty() || separator < 0) {
      return new RoutingDecision(
          modelHeader, Optional.empty(), Optional.empty(), model, body, false, false, declaredMax);
    }
    var provider = model.substring(0, separator);
    var bare = model.substring(separator + 1);
    if (config.keepOriginalModelName()) {
      return new RoutingDecision(
          modelHeader,
          Optional.of(provider),
          Optional.empty(),
          model,
          body,
          false,
          false,
          declaredMax);
    }
    return new RoutingDecision(
        modelHeader,
        Optional.of(provider),
        Optional.empty(),
        bare,
        rewriteModel(root, config.modelKey(), bare),
        true,
        false,
        declaredMax);
  }

  /** The last message with role {@code user}, and within it the last part of type {@code text}. */
  private static String lastUserText(JsonNode root) {
    var messages = root.path("messages");
    if (!messages.isArray()) {
      return "";
    }
    var found = "";
    for (var message : messages) {
      if (!"user".equals(message.path("role").asText(""))) {
        continue;
      }
      var content = message.path("content");
      if (content.isArray()) {
        for (var part : content) {
          if ("text".equals(part.path("type").asText(""))) {
            found = part.path("text").asText("");
          }
        }
      } else {
        found = content.asText("");
      }
    }
    return found;
  }

  private static String rewriteModel(ObjectNode root, String modelKey, String model) {
    var copy = root.deepCopy();
    copy.put(modelKey, model);
    return copy.toString();
  }
}
