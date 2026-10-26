package com.shortvideo.agent;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ConversationAiClientTest {
  @Test
  void ndjsonIsConsumedBeforeTheUpstreamResponseCompletes() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var release = new CountDownLatch(1);
    var received = new CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor();
    server.createContext(
        "/internal/conversation/run",
        exchange -> {
          try {
            assertThat(exchange.getRequestHeaders().getFirst("X-Internal-Key"))
                .isEqualTo("test-only-internal-key");
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, 0);
            var output = exchange.getResponseBody();
            output.write(
                "{\"type\":\"message.delta\",\"data\":{\"text\":\"live fragment\"}}\n"
                    .getBytes(StandardCharsets.UTF_8));
            output.flush();
            release.await(5, TimeUnit.SECONDS);
            output.write(
                "{\"type\":\"final\",\"data\":{\"content\":\"complete\"}}\n"
                    .getBytes(StandardCharsets.UTF_8));
            output.close();
          } catch (Exception ex) {
            exchange.close();
          }
        });
    server.start();
    var json = new Json(new ObjectMapper());
    var client =
        new ConversationAiClient(
            "http://127.0.0.1:" + server.getAddress().getPort(), "test-only-internal-key", json);
    var frames = new CopyOnWriteArrayList<Map<String, Object>>();
    try {
      var run =
          executor.submit(
              () ->
                  client.stream(
                      "stream-test",
                      Map.of("runId", "stream-test"),
                      frame -> {
                        frames.add(frame);
                        received.countDown();
                      }));
      assertThat(received.await(3, TimeUnit.SECONDS)).isTrue();
      assertThat(run.isDone()).isFalse();
      assertThat(frames).hasSize(1);
      release.countDown();
      run.get(5, TimeUnit.SECONDS);
      assertThat(frames.stream().map(frame -> frame.get("type")).toList())
          .containsExactly("message.delta", "final");
    } finally {
      release.countDown();
      client.close();
      executor.shutdownNow();
      server.stop(0);
    }
  }
}
