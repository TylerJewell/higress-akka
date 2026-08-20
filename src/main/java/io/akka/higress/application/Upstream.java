package io.akka.higress.application;

/** The provider this gateway dispatches to. */
public interface Upstream {

  Answer call(String credential, String path, String body);

  record Answer(int status, String body) {}
}
