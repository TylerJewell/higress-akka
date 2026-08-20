package io.akka.higress;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A provider that answers from a script instead of a model.
 *
 * <p>The network and the model are the two things the port is allowed to stand in for
 * — a timing that includes a real model measures that model. The credential each
 * attempt carried is recorded here, so the retry sequence is counted by what the
 * upstream saw rather than by what the port says it did.
 */
public final class ScriptedUpstream {

  private final AtomicReference<List<Integer>> statuses = new AtomicReference<>(List.of(200));
  private final CopyOnWriteArrayList<String> credentialsSeen = new CopyOnWriteArrayList<>();
  private final AtomicLong tokensPerAnswer = new AtomicLong(0);

  public HttpResponse answer(HttpRequest request) {
    credentialsSeen.add(
        request.getHeader("Authorization").map(h -> h.value().replace("Bearer ", "")).orElse("(none)"));
    var script = statuses.get();
    var status = script.get(Math.min(credentialsSeen.size() - 1, script.size() - 1));
    return HttpResponse.create()
        .withStatus(StatusCodes.get(status))
        .withEntity(ContentTypes.APPLICATION_JSON, body(status).getBytes(StandardCharsets.UTF_8));
  }

  private String body(int status) {
    var tokens = tokensPerAnswer.get();
    if (status != 200 || tokens <= 0) {
      return "{\"status\":" + status + "}";
    }
    return "{\"status\":200,\"usage\":{\"prompt_tokens\":"
        + tokens / 2
        + ",\"completion_tokens\":"
        + (tokens - tokens / 2)
        + ",\"total_tokens\":"
        + tokens
        + "}}";
  }

  public void reset() {
    credentialsSeen.clear();
    statuses.set(List.of(200));
    tokensPerAnswer.set(0);
  }

  public void answersWith(Integer... script) {
    statuses.set(List.of(script));
  }

  public void reportsTokens(long total) {
    tokensPerAnswer.set(total);
  }

  public List<String> credentialsSeen() {
    return List.copyOf(credentialsSeen);
  }
}
