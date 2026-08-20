package io.akka.higress.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3.12 — which budget keys one request touches, and in what order. */
public class BudgetKeySetTest {

  private static final BudgetConfig CONFIG =
      new BudgetConfig(
          "probe",
          1000,
          60,
          List.of(
              new HeaderBudgetRule("x-user", "alice", 100),
              new HeaderBudgetRule("x-tenant", "acme", 500)));

  @Test
  public void theGlobalKeyIsEvaluatedFirst() {
    var keys = BudgetKeys.forRequest(CONFIG, Map.of("x-user", "alice", "x-tenant", "acme"));
    assertThat(keys).extracting(BudgetKey::key)
        .containsExactly(
            "higress-token-ratelimit:{probe}:global_threshold:60",
            "higress-token-ratelimit:{probe}:limit_by_header:60:x-user:alice",
            "higress-token-ratelimit:{probe}:limit_by_header:60:x-tenant:acme");
  }

  @Test
  public void everyMatchedKeyIsChargedTheSameAmount() {
    var keys = BudgetKeys.forRequest(CONFIG, Map.of("x-user", "alice", "x-tenant", "acme"));
    assertThat(keys).extracting(BudgetKey::thresholdTokens).containsExactly(1000L, 100L, 500L);
    assertThat(keys).allSatisfy(k -> assertThat(k.windowSeconds()).isEqualTo(60L));
  }

  @Test
  public void aRuleWhoseHeaderIsAbsentContributesNoKey() {
    var keys = BudgetKeys.forRequest(CONFIG, Map.of("x-user", "alice"));
    assertThat(keys).extracting(BudgetKey::key)
        .containsExactly(
            "higress-token-ratelimit:{probe}:global_threshold:60",
            "higress-token-ratelimit:{probe}:limit_by_header:60:x-user:alice");
  }

  @Test
  public void aRuleWhoseHeaderValueDoesNotMatchContributesNoKey() {
    var keys = BudgetKeys.forRequest(CONFIG, Map.of("x-user", "bob"));
    assertThat(keys).hasSize(1);
  }

  @Test
  public void aGlobalThresholdOfZeroMeansThereIsNoGlobalKey() {
    var noGlobal = new BudgetConfig("probe", 0, 60, CONFIG.headerRules());
    var keys = BudgetKeys.forRequest(noGlobal, Map.of("x-user", "alice"));
    assertThat(keys).extracting(BudgetKey::key)
        .containsExactly("higress-token-ratelimit:{probe}:limit_by_header:60:x-user:alice");
  }

  @Test
  public void aRequestMatchingNothingTouchesNoKeyAtAll() {
    var noGlobal = new BudgetConfig("probe", 0, 60, CONFIG.headerRules());
    assertThat(BudgetKeys.forRequest(noGlobal, Map.of())).isEmpty();
  }
}
