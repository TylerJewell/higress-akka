package io.akka.higress.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 §4.3 — the port anchors its status patterns. Higress does not: it matches
 * anywhere in the string, so under its default patterns a 304 is retried
 * (question-log #24).
 */
public class StatusPatternTest {

  private static final List<String> DEFAULTS = List.of("4..", "5..");

  @Test
  public void aPatternIsAnchoredAtBothEnds() {
    assertThat(StatusPattern.matches(404, List.of("4.."))).isTrue();
    assertThat(StatusPattern.matches(304, List.of("4.."))).isFalse();
    assertThat(StatusPattern.matches(440, List.of("40."))).isFalse();

    // Higress's own default pattern. Anchored it means the 4xx statuses; matched
    // anywhere in the string, as higress matches it, it also picks up 304 and 451.
    assertThat(StatusPattern.matches(404, List.of("4.*"))).isTrue();
    assertThat(StatusPattern.matches(304, List.of("4.*"))).isFalse();
    assertThat(StatusPattern.matches(451, List.of("5.*"))).isFalse();
  }

  @Test
  public void a304IsNotRetriedUnderTheDefaultPatterns() {
    assertThat(StatusPattern.matches(304, DEFAULTS)).isFalse();
    assertThat(StatusPattern.matches(100, DEFAULTS)).isFalse();
    assertThat(StatusPattern.matches(201, DEFAULTS)).isFalse();
    assertThat(StatusPattern.matches(451, DEFAULTS)).isTrue();
    assertThat(StatusPattern.matches(502, DEFAULTS)).isTrue();
  }

  @Test
  public void anExactStatusPatternMatchesOnlyThatStatus() {
    assertThat(StatusPattern.matches(503, List.of("503"))).isTrue();
    assertThat(StatusPattern.matches(500, List.of("503"))).isFalse();
  }

  @Test
  public void anEmptyPatternListMatchesNothing() {
    assertThat(StatusPattern.matches(500, List.of())).isFalse();
  }
}
