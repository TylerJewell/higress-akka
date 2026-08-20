package io.akka.higress.application;

import akka.javasdk.http.HttpClient;
import akka.javasdk.http.StrictResponse;

/** Dispatches over HTTP, with the credential in the usual bearer header. */
public class HttpUpstream implements Upstream {

  private final HttpClient client;

  public HttpUpstream(HttpClient client) {
    this.client = client;
  }

  @Override
  public Answer call(String credential, String path, String body) {
    StrictResponse<String> response =
        client
            .POST(path)
            .addHeader("Authorization", "Bearer " + credential)
            .withRequestBody(
                akka.http.javadsl.model.ContentTypes.APPLICATION_JSON,
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .parseResponseBody(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
            .invoke();
    return new Answer(response.status().intValue(), response.body());
  }
}
