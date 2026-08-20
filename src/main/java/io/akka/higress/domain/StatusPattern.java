package io.akka.higress.domain;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Whether a response status is one the operator asked to retry on.
 *
 * <p>Patterns are anchored at both ends, so {@code 4..} means the 4xx statuses and
 * nothing else. A three-character status has no meaningful substring, so an unanchored
 * pattern can only match things nobody intends — see SPEC-001 §4.3.
 */
public final class StatusPattern {

  private static final Map<String, Pattern> COMPILED = new ConcurrentHashMap<>();

  private StatusPattern() {}

  public static boolean matches(int status, List<String> patterns) {
    var text = Integer.toString(status);
    for (var p : patterns) {
      if (COMPILED.computeIfAbsent(p, Pattern::compile).matcher(text).matches()) {
        return true;
      }
    }
    return false;
  }
}
