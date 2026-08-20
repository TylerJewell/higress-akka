package io.akka.higress.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3.1–§3.7 — the routing decision, with no runtime involved. */
public class ModelRoutingTest {

  private static final RoutingConfig CONFIG =
      new RoutingConfig(
          "model",
          Optional.of("x-model"),
          Optional.of("x-provider"),
          false,
          List.of("/v1/chat/completions"),
          "higress/auto",
          "x-higress-llm-model",
          Optional.empty(),
          List.of());

  private static RoutingConfig withAutoRules(String defaultModel, AutoRoutingRule... rules) {
    return new RoutingConfig(
        CONFIG.modelKey(),
        CONFIG.modelHeader(),
        CONFIG.providerHeader(),
        CONFIG.keepOriginalModelName(),
        CONFIG.pathSuffixes(),
        CONFIG.autoSentinel(),
        CONFIG.autoHeader(),
        defaultModel.isEmpty() ? Optional.empty() : Optional.of(defaultModel),
        List.of(rules));
  }

  private static RoutingDecision decide(RoutingConfig config, String path, String body) {
    return ModelRouter.decide(config, path, body).orElseThrow();
  }

  @Test
  public void modelHeaderKeepsTheCallersName() {
    var d = decide(CONFIG, "/v1/chat/completions", "{\"model\":\"openai/gpt-4o\"}");
    assertThat(d.modelHeader()).contains("openai/gpt-4o");
    assertThat(d.chosenModel()).isEqualTo("gpt-4o");
  }

  @Test
  public void providerHeaderIsAbsentWithoutASeparator() {
    var d = decide(CONFIG, "/v1/chat/completions", "{\"model\":\"gpt-4o\"}");
    assertThat(d.providerHeader()).isEmpty();
    assertThat(d.modelHeader()).contains("gpt-4o");
    assertThat(d.chosenModel()).isEqualTo("gpt-4o");
    assertThat(d.bodyRewritten()).isFalse();
  }

  @Test
  public void providerHeaderIsEmptyWhenTheNameStartsWithASeparator() {
    var d = decide(CONFIG, "/v1/chat/completions", "{\"model\":\"/gpt-4o\"}");
    assertThat(d.providerHeader()).contains("");
    assertThat(d.chosenModel()).isEqualTo("gpt-4o");
  }

  @Test
  public void onlyTheFirstSeparatorSplits() {
    var d = decide(CONFIG, "/v1/chat/completions", "{\"model\":\"bedrock/us/claude-3\"}");
    assertThat(d.providerHeader()).contains("bedrock");
    assertThat(d.chosenModel()).isEqualTo("us/claude-3");
    assertThat(d.modelHeader()).contains("bedrock/us/claude-3");
  }

  @Test
  public void keepingTheOriginalNameLeavesTheBodyAlone() {
    var keep =
        new RoutingConfig(
            "model",
            Optional.of("x-model"),
            Optional.of("x-provider"),
            true,
            List.of("/v1/chat/completions"),
            "higress/auto",
            "x-higress-llm-model",
            Optional.empty(),
            List.of());
    var d = decide(keep, "/v1/chat/completions", "{\"model\":\"openai/gpt-4o\"}");
    assertThat(d.providerHeader()).contains("openai");
    assertThat(d.chosenModel()).isEqualTo("openai/gpt-4o");
    assertThat(d.bodyRewritten()).isFalse();
  }

  @Test
  public void theFirstMatchingRuleWins() {
    var config =
        withAutoRules(
            "qwen-turbo",
            new AutoRoutingRule("(?i)code|program", "qwen-coder"),
            new AutoRoutingRule("(?i)code", "second-rule-should-not-win"));
    var d =
        decide(
            config,
            "/v1/chat/completions",
            "{\"model\":\"higress/auto\",\"messages\":[{\"role\":\"user\",\"content\":\"write some code\"}]}");
    assertThat(d.chosenModel()).isEqualTo("qwen-coder");
  }

  @Test
  public void theLastUserMessageAndItsLastTextPartAreMatched() {
    var config =
        withAutoRules(
            "qwen-turbo",
            new AutoRoutingRule("(?i)code|program", "qwen-coder"),
            new AutoRoutingRule("(?i)translate", "qwen-mt"));

    var acrossMessages =
        decide(
            config,
            "/v1/chat/completions",
            "{\"model\":\"higress/auto\",\"messages\":["
                + "{\"role\":\"user\",\"content\":\"please translate this\"},"
                + "{\"role\":\"assistant\",\"content\":\"sure\"},"
                + "{\"role\":\"user\",\"content\":\"actually write code\"}]}");
    assertThat(acrossMessages.chosenModel()).isEqualTo("qwen-coder");

    var withinOneMessage =
        decide(
            config,
            "/v1/chat/completions",
            "{\"model\":\"higress/auto\",\"messages\":[{\"role\":\"user\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"write code\"},"
                + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"http://x/y.png\"}},"
                + "{\"type\":\"text\",\"text\":\"please translate\"}]}]}");
    assertThat(withinOneMessage.chosenModel()).isEqualTo("qwen-mt");
  }

  @Test
  public void theAutoPathWritesOnlyItsOwnHeader() {
    var config = withAutoRules("qwen-turbo", new AutoRoutingRule("(?i)code", "qwen-coder"));
    var d =
        decide(
            config,
            "/v1/chat/completions",
            "{\"model\":\"higress/auto\",\"messages\":[{\"role\":\"user\",\"content\":\"code\"}]}");
    assertThat(d.autoRouted()).isTrue();
    assertThat(d.autoHeader()).contains("qwen-coder");
    assertThat(d.modelHeader()).isEmpty();
    assertThat(d.providerHeader()).isEmpty();
  }

  @Test
  public void withNoRuleMatchingTheDefaultModelIsUsed() {
    var config = withAutoRules("qwen-turbo", new AutoRoutingRule("(?i)translate", "qwen-mt"));
    var d =
        decide(
            config,
            "/v1/chat/completions",
            "{\"model\":\"higress/auto\",\"messages\":[{\"role\":\"user\",\"content\":\"what is the weather\"}]}");
    assertThat(d.chosenModel()).isEqualTo("qwen-turbo");
    assertThat(d.autoHeader()).contains("qwen-turbo");
  }

  @Test
  public void noRuleAndNoDefaultLeavesTheRequestAlone() {
    var config = withAutoRules("", new AutoRoutingRule("(?i)translate", "qwen-mt"));
    var d =
        decide(
            config,
            "/v1/chat/completions",
            "{\"model\":\"higress/auto\",\"messages\":[{\"role\":\"user\",\"content\":\"what is the weather\"}]}");
    assertThat(d.chosenModel()).isEqualTo("higress/auto");
    assertThat(d.autoHeader()).isEmpty();
    assertThat(d.modelHeader()).isEmpty();
    assertThat(d.providerHeader()).isEmpty();
    assertThat(d.bodyRewritten()).isFalse();
  }

  @Test
  public void theQueryStringIsStrippedBeforeTheSuffixTest() {
    var d = decide(CONFIG, "/v1/chat/completions?stream=true", "{\"model\":\"openai/gpt-4o\"}");
    assertThat(d.providerHeader()).contains("openai");
  }

  @Test
  public void aPathThatDoesNotMatchAnySuffixIsNotRouted() {
    assertThat(ModelRouter.decide(CONFIG, "/v1/models", "{\"model\":\"openai/gpt-4o\"}")).isEmpty();
  }

  @Test
  public void theSentinelIsMatchedExactlyNotAsAPrefix() {
    var config = withAutoRules("qwen-turbo", new AutoRoutingRule("(?i)code", "qwen-coder"));
    var d =
        decide(
            config,
            "/v1/chat/completions",
            "{\"model\":\"higress/auto-fast\",\"messages\":[{\"role\":\"user\",\"content\":\"code\"}]}");
    assertThat(d.autoRouted()).isFalse();
    assertThat(d.providerHeader()).contains("higress");
    assertThat(d.chosenModel()).isEqualTo("auto-fast");
  }

  @Test
  public void anAbsentOrEmptyModelFieldIsNotRouted() {
    assertThat(ModelRouter.decide(CONFIG, "/v1/chat/completions", "{\"messages\":[]}")).isEmpty();
    assertThat(ModelRouter.decide(CONFIG, "/v1/chat/completions", "{\"model\":\"\"}")).isEmpty();
  }

  @Test
  public void theRewrittenBodyCarriesTheChosenModelAndNothingElseChanges() {
    var d =
        decide(
            CONFIG,
            "/v1/chat/completions",
            "{\"model\":\"openai/gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":64}");
    assertThat(d.bodyRewritten()).isTrue();
    assertThat(d.body()).contains("\"model\":\"gpt-4o\"");
    assertThat(d.body()).contains("\"max_tokens\":64");
    assertThat(d.body()).contains("\"content\":\"hi\"");
  }

  @Test
  public void theDeclaredMaximumIsReadFromTheBodyWhenPresent() {
    var withMax =
        decide(
            CONFIG,
            "/v1/chat/completions",
            "{\"model\":\"openai/gpt-4o\",\"max_tokens\":64}");
    assertThat(withMax.declaredMaxTokens()).isEqualTo(64L);

    var without = decide(CONFIG, "/v1/chat/completions", "{\"model\":\"openai/gpt-4o\"}");
    assertThat(without.declaredMaxTokens()).isZero();
  }
}
