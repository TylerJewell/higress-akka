package io.akka.higress.domain;

import java.util.regex.Pattern;

/** One auto-routing rule: a pattern to try against the user's last words, and the model to use if it matches. */
public record AutoRoutingRule(Pattern pattern, String model) {

  public AutoRoutingRule(String pattern, String model) {
    this(Pattern.compile(pattern), model);
  }
}
