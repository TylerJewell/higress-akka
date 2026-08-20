package io.akka.higress.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import io.akka.higress.domain.TokenBudgetState;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 §3.8–§3.11 against the entity, as a sequence.
 *
 * <p>A rule about what happens <em>next</em> time is not shown by one input: the first
 * request agrees on every reading of the rule. The unit of comparison here is the run
 * of answers, not any one of them.
 */
public class TokenBudgetEntityTest {

  private static final long THRESHOLD = 100;
  private static final long WINDOW = 60;
  private static final long T0 = 2_000_000L;

  private KeyValueEntityTestKit<TokenBudgetState, TokenBudgetEntity> budget() {
    return KeyValueEntityTestKit.of("higress-token-ratelimit:{t}:global_threshold:60", TokenBudgetEntity::new);
  }

  private static TokenBudgetEntity.Admit admit(long declaredMax, long now) {
    return new TokenBudgetEntity.Admit(THRESHOLD, WINDOW, declaredMax, now);
  }

  private static TokenBudgetEntity.Charge charge(long tokens, long now) {
    return new TokenBudgetEntity.Charge(THRESHOLD, WINDOW, tokens, 0, now);
  }

  @Test
  public void threeRequestsInARowAdmitAdmitRefuse() {
    var kit = budget();

    var first = kit.method(TokenBudgetEntity::admit).invoke(admit(0, T0)).getReply();
    assertThat(first.admitted()).isTrue();
    kit.method(TokenBudgetEntity::charge).invoke(charge(100, T0));

    var second = kit.method(TokenBudgetEntity::admit).invoke(admit(0, T0 + 1)).getReply();
    assertThat(second.admitted()).isTrue();
    kit.method(TokenBudgetEntity::charge).invoke(charge(100, T0 + 1));

    var third = kit.method(TokenBudgetEntity::admit).invoke(admit(0, T0 + 2)).getReply();
    assertThat(third.admitted()).isFalse();
    assertThat(third.resetSeconds()).isEqualTo(60);

    assertThat(kit.method(TokenBudgetEntity::get).invoke().getReply().spent()).isEqualTo(200);
  }

  @Test
  public void theOvershootIsWhateverTheCrossingRequestSpent() {
    var kit = budget();
    kit.method(TokenBudgetEntity::admit).invoke(admit(0, T0));
    kit.method(TokenBudgetEntity::charge).invoke(charge(100, T0));
    kit.method(TokenBudgetEntity::admit).invoke(admit(0, T0 + 1));
    kit.method(TokenBudgetEntity::charge).invoke(charge(1_000_000, T0 + 1));

    assertThat(kit.method(TokenBudgetEntity::get).invoke().getReply().spent()).isEqualTo(1_000_100L);

    // and the next charge, arriving with the counter already over, is dropped
    kit.method(TokenBudgetEntity::charge).invoke(charge(50, T0 + 2));
    assertThat(kit.method(TokenBudgetEntity::get).invoke().getReply().spent()).isEqualTo(1_000_100L);
  }

  @Test
  public void anAnswerWithNoTokensLeavesTheBudgetUntouched() {
    var kit = budget();
    kit.method(TokenBudgetEntity::admit).invoke(admit(0, T0));
    kit.method(TokenBudgetEntity::charge).invoke(charge(0, T0));

    var state = kit.method(TokenBudgetEntity::get).invoke().getReply();
    assertThat(state.spent()).isZero();
    assertThat(state.windowStartedAtMillis()).isZero();
  }

  @Test
  public void theWindowRunsOutAndTheNextRequestStartsAFreshOne() {
    var kit = budget();
    kit.method(TokenBudgetEntity::admit).invoke(admit(0, T0));
    kit.method(TokenBudgetEntity::charge).invoke(charge(1_000, T0));

    var later = T0 + WINDOW * 1000 + 1;
    var admission = kit.method(TokenBudgetEntity::admit).invoke(admit(0, later)).getReply();
    assertThat(admission.admitted()).isTrue();

    kit.method(TokenBudgetEntity::charge).invoke(charge(7, later));
    var state = kit.method(TokenBudgetEntity::get).invoke().getReply();
    assertThat(state.spent()).isEqualTo(7);
    assertThat(state.windowStartedAtMillis()).isEqualTo(later);
  }

  @Test
  public void aReservationSurvivesBetweenAdmissionAndCharge() {
    var kit = budget();
    kit.method(TokenBudgetEntity::admit).invoke(admit(100, T0));
    assertThat(kit.method(TokenBudgetEntity::get).invoke().getReply().reservedTokens()).isEqualTo(100);

    kit.method(TokenBudgetEntity::charge)
        .invoke(new TokenBudgetEntity.Charge(THRESHOLD, WINDOW, 40, 100, T0 + 1));
    var state = kit.method(TokenBudgetEntity::get).invoke().getReply();
    assertThat(state.reservedTokens()).isZero();
    assertThat(state.spent()).isEqualTo(40);
  }
}
