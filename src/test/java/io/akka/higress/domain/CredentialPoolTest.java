package io.akka.higress.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3.17, §3.18, §3.19, §3.20 — the credential pool rules. */
public class CredentialPoolTest {

  private static final long T0 = 5_000_000L;
  private static final long COOLDOWN = 600_000L;

  private static CredentialPoolState pool(int failureThreshold, String... names) {
    return CredentialPoolState.of(List.of(names), failureThreshold, COOLDOWN);
  }

  @Test
  public void aSuccessResetsTheConsecutiveFailureCount() {
    var p = pool(2, "a");
    var afterFail = p.recordFailure("a", T0).state();
    assertThat(afterFail.available()).containsExactly("a");

    var afterSuccess = afterFail.recordSuccess("a");
    var afterSecondFail = afterSuccess.recordFailure("a", T0 + 2);
    assertThat(afterSecondFail.ejected()).isEmpty();
    assertThat(afterSecondFail.state().available()).containsExactly("a");
  }

  @Test
  public void twoConsecutiveFailuresEjectAtAThresholdOfTwo() {
    var p = pool(2, "a", "b");
    var once = p.recordFailure("a", T0).state();
    var twice = once.recordFailure("a", T0 + 1);
    assertThat(twice.ejected()).contains("a");
    assertThat(twice.state().available()).containsExactly("b");
  }

  @Test
  public void ejectionResetsTheFailureCountAndRecordsWhenItHappened() {
    var p = pool(1, "a", "b");
    var ejected = p.recordFailure("a", T0).state();
    assertThat(ejected.credentials().stream().filter(c -> c.name().equals("a")).findFirst().orElseThrow())
        .satisfies(
            c -> {
              assertThat(c.consecutiveFailures()).isZero();
              assertThat(c.unavailableSinceMillis()).contains(T0);
            });
  }

  @Test
  public void aFailureOnAnAlreadyEjectedCredentialChangesNothing() {
    var p = pool(1, "a", "b");
    var ejected = p.recordFailure("a", T0).state();
    var again = ejected.recordFailure("a", T0 + 1);
    assertThat(again.ejected()).isEmpty();
    assertThat(again.state()).isEqualTo(ejected);
  }

  @Test
  public void whenEveryCredentialIsEjectedOneIsStillOffered() {
    var p = pool(1, "a");
    var ejected = p.recordFailure("a", T0).state();
    assertThat(ejected.available()).isEmpty();
    assertThat(ejected.pick(List.of())).contains("a");
  }

  @Test
  public void pickPrefersAnAvailableCredentialOverAnEjectedOne() {
    var p = pool(1, "a", "b");
    var ejected = p.recordFailure("a", T0).state();
    assertThat(ejected.pick(List.of())).contains("b");
  }

  @Test
  public void pickSkipsCredentialsAlreadyTriedOnThisRequest() {
    var p = pool(5, "a", "b", "c");
    assertThat(p.pick(List.of("a", "b"))).contains("c");
    assertThat(p.pick(List.of("a", "b", "c"))).isEmpty();
  }

  @Test
  public void aCooledDownCredentialIsRestoredAndAnUncooledOneIsNot() {
    var p = pool(1, "a", "b");
    var ejected = p.recordFailure("a", T0).state();

    var tooSoon = ejected.restoreCooledDown(T0 + COOLDOWN - 1);
    assertThat(tooSoon.available()).containsExactly("b");

    var cooled = ejected.restoreCooledDown(T0 + COOLDOWN);
    assertThat(cooled.available()).containsExactlyInAnyOrder("a", "b");
  }
}
