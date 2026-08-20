package io.akka.higress.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** SPEC-001 §3.8–§3.11 and §4.1 — the budget rules, with no runtime involved. */
public class TokenBudgetTest {

  private static final long THRESHOLD = 100;
  private static final long WINDOW_SECONDS = 60;
  private static final long T0 = 1_000_000L;

  private static TokenBudgetState fresh() {
    return TokenBudgetState.empty(THRESHOLD, WINDOW_SECONDS);
  }

  @Test
  public void anEmptyBudgetAdmits() {
    assertThat(fresh().admit(0, T0).admitted()).isTrue();
  }

  @Test
  public void aBudgetAtExactlyItsThresholdStillAdmits() {
    var afterFirst = fresh().charge(THRESHOLD, T0);
    assertThat(afterFirst.spent()).isEqualTo(THRESHOLD);
    assertThat(afterFirst.admit(0, T0 + 1).admitted()).isTrue();
  }

  @Test
  public void theChargeThatCrossesTheThresholdIsAppliedInFull() {
    var state = fresh().charge(THRESHOLD, T0).charge(1_000_000, T0 + 1);
    assertThat(state.spent()).isEqualTo(1_000_100L);
    assertThat(state.admit(0, T0 + 2).admitted()).isFalse();
  }

  @Test
  public void chargesAfterTheThresholdIsCrossedAreDropped() {
    var over = fresh().charge(THRESHOLD, T0).charge(1_000_000, T0 + 1);
    var stillOver = over.charge(50, T0 + 2);
    assertThat(stillOver.spent()).isEqualTo(over.spent());
  }

  @Test
  public void aResponseWithNoUsageIsNotChargedAtAll() {
    var state = fresh().charge(0, T0);
    assertThat(state.spent()).isZero();
    assertThat(state.windowStartedAtMillis()).isZero();
  }

  @Test
  public void theWindowOpensAtTheFirstChargeAndIsNotExtended() {
    var afterAdmit = fresh().admit(0, T0);
    assertThat(afterAdmit.state().windowStartedAtMillis()).isZero();

    var opened = fresh().charge(10, T0);
    assertThat(opened.windowStartedAtMillis()).isEqualTo(T0);

    var later = opened.charge(10, T0 + 30_000);
    assertThat(later.windowStartedAtMillis()).isEqualTo(T0);
    assertThat(later.spent()).isEqualTo(20);
  }

  @Test
  public void theWindowExpiresAndTheBudgetStartsOverOnTheNextCharge() {
    var over = fresh().charge(1_000, T0);
    var pastTheWindow = T0 + WINDOW_SECONDS * 1000 + 1;
    assertThat(over.admit(0, pastTheWindow).admitted()).isTrue();

    var reopened = over.charge(5, pastTheWindow);
    assertThat(reopened.spent()).isEqualTo(5);
    assertThat(reopened.windowStartedAtMillis()).isEqualTo(pastTheWindow);
  }

  @Test
  public void theResetSecondsReportedOnRefusalAreWhatIsLeftOfTheWindow() {
    var over = fresh().charge(1_000, T0);
    var admission = over.admit(0, T0 + 20_000);
    assertThat(admission.admitted()).isFalse();
    assertThat(admission.resetSeconds()).isEqualTo(40);
  }

  @Test
  public void aDeclaredMaximumIsReservedAtAdmissionAndReleasedAtCharge() {
    var first = fresh().admit(100, T0);
    assertThat(first.admitted()).isTrue();
    assertThat(first.state().reservedTokens()).isEqualTo(100);

    // Requests still in flight are judged against spent + reserved rather than
    // against a counter none of them has moved yet. The admission test is still
    // "not strictly greater", so the second still gets in at exactly the ceiling
    // and the third does not — where without reservations all three would.
    var second = first.state().admit(100, T0 + 1);
    assertThat(second.admitted()).isTrue();
    var third = second.state().admit(100, T0 + 2);
    assertThat(third.admitted()).isFalse();

    var charged = first.state().charge(40, 100, T0 + 2);
    assertThat(charged.reservedTokens()).isZero();
    assertThat(charged.spent()).isEqualTo(40);
  }

  @Test
  public void withNoDeclaredMaximumTheBudgetBehavesExactlyAsHigressDoes() {
    // Three admissions before any charge: all three admit, because nothing was
    // reserved and the counter has not moved. This is question-log #13 and the
    // concurrent-admission block of probe_02.
    var state = fresh();
    assertThat(state.admit(0, T0).admitted()).isTrue();
    assertThat(state.admit(0, T0).admitted()).isTrue();
    assertThat(state.admit(0, T0).admitted()).isTrue();

    var afterCharges = state.charge(100, T0).charge(100, T0).charge(100, T0);
    assertThat(afterCharges.spent()).isEqualTo(200);
  }

  @Test
  public void aReleaseWithoutAChargeStillFreesTheReservation() {
    var admitted = fresh().admit(100, T0);
    var released = admitted.state().charge(0, 100, T0 + 1);
    assertThat(released.reservedTokens()).isZero();
    assertThat(released.spent()).isZero();
  }
}
