package com.shortvideo.agent;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/conversations")
class ConversationController {
  private final ConversationService conversations;
  private final ConversationAiClient ai;
  private final AuthService auth;
  private final Json json;
  private final AttachmentService attachments;
  private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();
  private final Semaphore connections = new Semaphore(100);

  ConversationController(
      ConversationService conversations,
      ConversationAiClient ai,
      AuthService auth,
      Json json,
      AttachmentService attachments) {
    this.conversations = conversations;
    this.ai = ai;
    this.auth = auth;
    this.json = json;
    this.attachments = attachments;
  }

  @GetMapping
  List<Map<String, Object>> list() {
    return conversations.list();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  Map<String, Object> create(@RequestBody(required = false) Map<String, Object> body) {
    return conversations.create(body == null ? Map.of() : body);
  }

  @GetMapping("/{id}")
  Map<String, Object> detail(@PathVariable String id) {
    return conversations.detail(id);
  }

  @PatchMapping("/{id}")
  Map<String, Object> rename(@PathVariable String id, @RequestBody Map<String, Object> body) {
    return conversations.rename(id, body);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable String id) {
    conversations.delete(id);
  }

  @PostMapping("/{id}/messages")
  @ResponseStatus(HttpStatus.ACCEPTED)
  Map<String, Object> submit(
      @PathVariable String id,
      @RequestBody Map<String, Object> body,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return conversations.submit(id, body, key);
  }

  @PostMapping("/{id}/messages/{messageId}/retry")
  @ResponseStatus(HttpStatus.ACCEPTED)
  Map<String, Object> retry(
      @PathVariable String id,
      @PathVariable String messageId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return conversations.retry(id, messageId, key);
  }

  @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  Map<String, Object> upload(
      @PathVariable String id,
      @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
    return attachments.upload(id, file);
  }

  @DeleteMapping("/{id}/attachments/{fileId}")
  void removeFile(@PathVariable String id, @PathVariable String fileId) {
    attachments.remove(id, fileId);
  }

  @GetMapping("/{id}/events")
  List<Map<String, Object>> events(
      @PathVariable String id, @RequestParam(defaultValue = "0") long after) {
    return conversations.events(id, after);
  }

  @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  ResponseEntity<SseEmitter> stream(
      @PathVariable String id,
      @RequestParam(defaultValue = "0") long after,
      @RequestHeader(value = "Last-Event-ID", required = false) String last,
      @RequestHeader("Authorization") String authorization) {
    conversations.owner(id);
    if (after < 0) throw ApiException.bad("无效事件序号");
    long cursor = after;
    if (last != null && !last.isBlank())
      try {
        cursor = Math.max(cursor, Long.parseLong(last));
      } catch (NumberFormatException ex) {
        throw ApiException.bad("无效事件序号");
      }
    if (!connections.tryAcquire())
      throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "实时连接过多，请稍后重试", 5L);
    var emitter = new SseEmitter(600000L);
    var closed = new AtomicBoolean();
    emitter.onCompletion(() -> closed.set(true));
    emitter.onTimeout(() -> closed.set(true));
    emitter.onError(ex -> closed.set(true));
    String token = authorization.substring(7);
    long first = cursor;
    streamExecutor.submit(
        () -> {
          long next = first, lastHeartbeat = 0;
          try {
            while (!closed.get()) {
              var events = conversations.eventsInternal(id, next, 500);
              for (var event : events) {
                emitter.send(
                    SseEmitter.event()
                        .id(event.get("id").toString())
                        .name("run-event")
                        .data(json.write(event)));
                next = ((Number) event.get("id")).longValue();
              }
              long now = System.currentTimeMillis();
              if (now - lastHeartbeat >= 15000) {
                var current = auth.authenticate(token);
                if (current == null) break;
                conversations.raw(id);
                emitter.send(SseEmitter.event().comment("heartbeat"));
                lastHeartbeat = now;
              }
              Thread.sleep(400);
            }
          } catch (Exception ignored) {
          } finally {
            connections.release();
            emitter.complete();
          }
        });
    return ResponseEntity.ok()
        .header("X-Accel-Buffering", "no")
        .cacheControl(CacheControl.noStore())
        .body(emitter);
  }

  @PostMapping("/{id}/runs/{runId}/cancel")
  Map<String, Object> cancel(@PathVariable String id, @PathVariable String runId) {
    var result = conversations.cancel(id, runId);
    ai.cancel(runId);
    return result;
  }

  @PutMapping("/{id}/artifact")
  Map<String, Object> manual(@PathVariable String id, @RequestBody Map<String, Object> body) {
    return conversations.manual(id, body);
  }

  @PatchMapping("/{id}/artifact")
  Map<String, Object> renameWork(@PathVariable String id, @RequestBody Map<String, Object> body) {
    return conversations.renameWork(id, body);
  }

  @DeleteMapping("/{id}/artifact")
  void deleteWork(@PathVariable String id) {
    conversations.deleteWork(id);
  }

  @PostMapping("/{id}/artifact/restore/{versionId}")
  Map<String, Object> restore(@PathVariable String id, @PathVariable String versionId) {
    return conversations.restore(id, versionId);
  }

  @PostMapping("/{id}/artifact/finalize")
  Map<String, Object> finalizeArtifact(
      @PathVariable String id, @RequestBody Map<String, Object> body) {
    return conversations.finalizeArtifact(id, body);
  }

  @GetMapping("/{id}/artifact/export")
  ResponseEntity<String> export(
      @PathVariable String id, @RequestParam(defaultValue = "markdown") String format) {
    if (!Set.of("markdown", "txt").contains(format))
      throw ApiException.bad("导出格式必须为 markdown 或 txt");
    return ResponseEntity.ok()
        .contentType(
            new MediaType(
                "text", format.equals("markdown") ? "markdown" : "plain", StandardCharsets.UTF_8))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("production-" + id + (format.equals("markdown") ? ".md" : ".txt"))
                .build()
                .toString())
        .body(conversations.export(id, format.equals("markdown")));
  }

  @PreDestroy
  void close() {
    streamExecutor.shutdownNow();
  }
}
