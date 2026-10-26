package com.shortvideo.agent;

import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class ConversationAiClient {
  private final HttpClient client =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  private static class Control {
    volatile boolean cancelled;
    volatile boolean timedOut;
    volatile CompletableFuture<?> future;
    volatile InputStream stream;

    synchronized void cancel() {
      cancelled = true;
      if (future != null) future.cancel(true);
      if (stream != null)
        try {
          stream.close();
        } catch (IOException ignored) {
        }
    }
  }

  private final ConcurrentMap<String, Control> active = new ConcurrentHashMap<>();
  private final ScheduledExecutorService deadlines =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            var thread = new Thread(r, "conversation-ai-deadlines");
            thread.setDaemon(true);
            return thread;
          });
  private final String base, key;
  private final Json json;

  ConversationAiClient(
      @Value("${app.ai-base-url}") String base,
      @Value("${app.internal-key}") String key,
      Json json) {
    this.base = base.replaceAll("/+$", "");
    this.key = key;
    this.json = json;
  }

  void stream(String runId, Object body, Consumer<Map<String, Object>> event) {
    if (key.isBlank())
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "请先配置 AI_INTERNAL_KEY 并启动 AI 服务");
    var request =
        HttpRequest.newBuilder(URI.create(base + "/internal/conversation/run"))
            .timeout(Duration.ofSeconds(360))
            .header("X-Internal-Key", key)
            .header("Content-Type", "application/json")
            .header("Accept", "application/x-ndjson")
            .POST(HttpRequest.BodyPublishers.ofString(json.write(body), StandardCharsets.UTF_8))
            .build();
    var control = new Control();
    if (active.putIfAbsent(runId, control) != null) throw ApiException.conflict("该轮创作已在执行");
    var deadline =
        deadlines.schedule(
            () -> {
              control.timedOut = true;
              control.cancel();
            },
            300,
            TimeUnit.SECONDS);
    try {
      var future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
      synchronized (control) {
        control.future = future;
        if (control.cancelled) {
          future.cancel(true);
          throw new ConversationService.CancelledRun();
        }
      }
      var response = future.get(360, TimeUnit.SECONDS);
      synchronized (control) {
        control.stream = response.body();
        if (control.cancelled) {
          control.cancel();
          throw new ConversationService.CancelledRun();
        }
      }
      try (var reader =
          new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
        if (response.statusCode() != 200) {
          String error = reader.readLine();
          String detail = "AI 服务请求失败（" + response.statusCode() + "）";
          try {
            var errorBody = json.object(error);
            String value = Json.str(errorBody, "message", Json.str(errorBody, "detail", ""));
            if (!value.isBlank() && value.length() < 500 && !value.contains("sk-"))
              detail += "：" + value;
          } catch (Exception ignored) {
          }
          throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, detail);
        }
        String line;
        long bytes = 0;
        while ((line = reader.readLine()) != null) {
          if (control.cancelled) throw new ConversationService.CancelledRun();
          if (line.isBlank()) continue;
          bytes += line.length();
          if (line.length() > 2_000_000 || bytes > 5_000_000)
            throw ApiException.bad("AI 返回内容超过安全上限");
          event.accept(json.object(line));
        }
      }
    } catch (ConversationService.CancelledRun ex) {
      if (control.timedOut)
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "本轮执行超过 300 秒，请缩小需求后重试");
      throw ex;
    } catch (ApiException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI 对话服务连接中断或超时，请检查服务和模型配置后重试");
    } finally {
      deadline.cancel(false);
      control.cancel();
      active.remove(runId, control);
    }
  }

  void cancel(String runId) {
    var control = active.get(runId);
    if (control != null) control.cancel();
  }

  @PreDestroy
  void close() {
    deadlines.shutdownNow();
    active.values().forEach(Control::cancel);
  }
}
