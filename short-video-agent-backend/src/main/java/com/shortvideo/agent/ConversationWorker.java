package com.shortvideo.agent;

import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class ConversationWorker {
  record Run(String id, String conversationId, String request) {
    String resource() {
      return "CONVERSATION:" + conversationId;
    }
  }

  private static final Logger log = LoggerFactory.getLogger(ConversationWorker.class);
  private final JdbcTemplate db;
  private final Json json;
  private final ConversationService conversations;
  private final ConversationAiClient ai;
  private final ResourceLocks locks;
  private final TransactionTemplate tx;
  private final boolean enabled;
  private final String worker = Json.id();
  private final ExecutorService executor;
  private final Semaphore slots;
  private final ConcurrentMap<String, Run> active = new ConcurrentHashMap<>();

  ConversationWorker(
      JdbcTemplate db,
      Json json,
      ConversationService conversations,
      ConversationAiClient ai,
      ResourceLocks locks,
      TransactionTemplate tx,
      @Value("${app.workers:2}") int count,
      @Value("${app.worker-enabled:true}") boolean enabled) {
    this.db = db;
    this.json = json;
    this.conversations = conversations;
    this.ai = ai;
    this.locks = locks;
    this.tx = tx;
    this.enabled = enabled;
    int concurrency = Math.max(1, Math.min(8, count));
    executor = Executors.newFixedThreadPool(concurrency);
    slots = new Semaphore(concurrency);
  }

  @Scheduled(fixedDelay = 700)
  void tick() {
    if (!enabled || slots.availablePermits() == 0) return;
    try {
      var runs =
          db.query(
              "SELECT * FROM agent_runs WHERE status='QUEUED' ORDER BY created_at LIMIT 20",
              (rs, n) ->
                  new Run(
                      rs.getString("id"),
                      rs.getString("conversation_id"),
                      rs.getString("request_json")));
      for (var run : runs) {
        if (!slots.tryAcquire()) break;
        boolean acquired = false;
        try {
          acquired = locks.acquire(run.resource(), worker);
          if (!acquired) {
            slots.release();
            continue;
          }
          Boolean claimed =
              tx.execute(
                  s -> {
                    conversations.lock(run.conversationId());
                    var c = conversations.raw(run.conversationId());
                    if (!run.id().equals(c.get("activeRunId"))) return false;
                    return db.update(
                            "UPDATE agent_runs SET"
                                + " status='RUNNING',worker_id=?,attempts=attempts+1,updated_at=CURRENT_TIMESTAMP(6)"
                                + " WHERE id=? AND status='QUEUED'",
                            worker,
                            run.id())
                        == 1;
                  });
          if (!Boolean.TRUE.equals(claimed)) {
            locks.release(run.resource(), worker);
            slots.release();
            continue;
          }
          active.put(run.id(), run);
          executor.submit(() -> execute(run));
        } catch (Exception ex) {
          if (acquired)
            try {
              locks.release(run.resource(), worker);
            } catch (Exception ignored) {
            }
          slots.release();
          throw ex;
        }
      }
    } catch (Exception ex) {
      log.warn("Conversation dispatch unavailable: {}", ex.getClass().getSimpleName());
    }
  }

  @Scheduled(fixedDelay = 15000)
  void heartbeat() {
    for (var run : active.values())
      try {
        if (locks.renew(run.resource(), worker))
          db.update(
              "UPDATE agent_runs SET updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND"
                  + " status='RUNNING' AND worker_id=?",
              run.id(),
              worker);
      } catch (Exception ex) {
        log.warn("Conversation heartbeat unavailable: {}", ex.getClass().getSimpleName());
      }
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 1500)
  void recover() {
    if (!enabled) return;
    Timestamp cutoff = Timestamp.from(Instant.now().minusSeconds(16 * 60));
    try {
      var stale =
          db.query(
              "SELECT * FROM agent_runs WHERE status='RUNNING' AND updated_at<?",
              (rs, n) ->
                  new Run(
                      rs.getString("id"),
                      rs.getString("conversation_id"),
                      rs.getString("request_json")),
              cutoff);
      for (var run : stale)
        tx.executeWithoutResult(
            s -> {
              conversations.lock(run.conversationId());
              var current =
                  db.query(
                      "SELECT worker_id FROM agent_runs WHERE id=? AND status='RUNNING' AND"
                          + " updated_at<? FOR UPDATE",
                      (rs, n) -> rs.getString(1),
                      run.id(),
                      cutoff);
              if (!current.isEmpty())
                conversations.fail(
                    run.conversationId(), run.id(), current.getFirst(), "上次执行因服务中断而结束，请重新发送需求");
            });
    } catch (Exception ex) {
      log.warn("Conversation recovery unavailable: {}", ex.getClass().getSimpleName());
    }
  }

  private void execute(Run run) {
    var artifact = new AtomicReference<Map<String, Object>>();
    var finalSeen = new AtomicBoolean();
    var request = json.object(run.request());
    var workingContent =
        new AtomicReference<Map<String, Object>>(Json.map(request.get("artifact")));
    var workingSources =
        new AtomicReference<List<Map<String, Object>>>(
            ProductionValidator.sources(request.getOrDefault("sources", List.of())));
    var mutationScope = new AtomicReference<String>();
    int[] chars = {0};
    try {
      ai.stream(
          run.id(),
          json.object(run.request()),
          frame -> {
            if (finalSeen.get()) throw ApiException.bad("AI 返回了重复的最终结果");
            if (!conversations.currentRun(run.conversationId(), run.id(), worker))
              throw new ConversationService.CancelledRun();
            String type = Json.required(frame, "type", 40);
            var data = new LinkedHashMap<String, Object>(Json.map(frame.get("data")));
            switch (type) {
              case "run.started" -> {
                String mode = Json.required(data, "mode", 10);
                if (!Set.of("real", "demo").contains(mode)) throw ApiException.bad("AI 模式无效");
                conversations.live(
                    run.conversationId(),
                    run.id(),
                    worker,
                    type,
                    new LinkedHashMap<>(Map.of("mode", mode)));
              }
              case "message.delta" -> {
                String text = Json.str(data, "text", "");
                chars[0] += text.length();
                if (chars[0] > 20000) throw ApiException.bad("AI 回复长度超过上限");
                if (!text.isEmpty())
                  conversations.live(
                      run.conversationId(),
                      run.id(),
                      worker,
                      type,
                      new LinkedHashMap<>(Map.of("text", text)));
              }
              case "brief.updated" ->
                  conversations.live(run.conversationId(), run.id(), worker, type, data);
              case "tool.started", "tool.completed", "tool.failed" -> {
                String tool = Json.required(data, "tool", 100);
                if (!Set.of(
                        "update_brief",
                        "propose_options",
                        "search_knowledge",
                        "create_production",
                        "revise_production",
                        "check_production",
                        "read_document",
                        "save_knowledge",
                        "search_history_creations",
                        "read_history_creation",
                        "read_user_profile")
                    .contains(tool)) throw ApiException.bad("AI 调用了未注册的工具");
                Json.required(data, "toolCallId", 200);
                if (type.equals("tool.started") && tool.equals("create_production"))
                  mutationScope.set("ALL");
                if (type.equals("tool.started") && tool.equals("revise_production"))
                  mutationScope.set(Json.required(Json.map(data.get("input")), "scope", 100));
                if (json.write(data).length() > 200000) throw ApiException.bad("工具事件内容超过上限");
                conversations.live(
                    run.conversationId(), run.id(), worker, type, Json.map(redact(data)));
              }
              case "artifact.updated" -> {
                String scope = Json.required(data, "scope", 100);
                if (mutationScope.get() == null || !scope.equals(mutationScope.get()))
                  throw ApiException.bad("作品更新范围与实际执行工具不一致");
                var proposed = Json.map(data.get("content"));
                var guarded =
                    workingContent.get().isEmpty()
                        ? proposed
                        : ProductionValidator.mergeRevision(workingContent.get(), proposed, scope);
                ProductionValidator.content(guarded);
                var sources = ProductionValidator.sources(data.get("sources"));
                var retained = new LinkedHashMap<String, Map<String, Object>>();
                if (!workingContent.get().isEmpty())
                  for (var source : workingSources.get())
                    retained.put(source.get("documentId") + ":" + source.get("chunkId"), source);
                for (var source : sources)
                  retained.putIfAbsent(
                      source.get("documentId") + ":" + source.get("chunkId"), source);
                var snapshots = new ArrayList<Map<String, Object>>(retained.values());
                ProductionValidator.sources(snapshots);
                data.put("content", guarded);
                data.put("sources", snapshots);
                workingContent.set(guarded);
                workingSources.set(snapshots);
                artifact.set(data);
                mutationScope.set(null);
              }
              case "final" -> {
                conversations.finish(run.conversationId(), run.id(), worker, data, artifact.get());
                finalSeen.set(true);
              }
              case "error" -> {
                String message = Json.optional(data, "message", "AI 执行失败", 500);
                if (message.contains("sk-")) message = "AI 模型请求失败，请检查服务端模型配置";
                throw ApiException.bad(message);
              }
              default -> throw ApiException.bad("AI 返回未知事件类型");
            }
          });
      if (!finalSeen.get()) throw ApiException.bad("AI 对话流意外结束，未收到最终结果");
    } catch (ConversationService.CancelledRun ignored) {
    } catch (Exception ex) {
      String message = ex instanceof ApiException ? ex.getMessage() : "对话执行失败，请稍后重试";
      try {
        conversations.fail(run.conversationId(), run.id(), worker, message);
      } catch (Exception failure) {
        log.warn("Failed to persist conversation error {}", run.id());
      }
    } finally {
      active.remove(run.id());
      ai.cancel(run.id());
      try {
        locks.release(run.resource(), worker);
      } catch (Exception ignored) {
      }
      slots.release();
    }
  }

  private static Object redact(Object value) {
    if (value instanceof Map<?, ?> map) {
      var safe = new LinkedHashMap<String, Object>();
      map.forEach(
          (key, item) -> {
            String name = key.toString();
            String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            if (!normalized.matches(
                ".*(apikey|password|secret|authorization|systemprompt|reasoning|credential).*"))
              safe.put(name, redact(item));
          });
      return safe;
    }
    if (value instanceof List<?> list)
      return list.stream().map(ConversationWorker::redact).toList();
    if (value instanceof String text) return text.replaceAll("sk-[A-Za-z0-9_-]{8,}", "[已隐藏]");
    return value;
  }

  @PreDestroy
  void close() {
    executor.shutdownNow();
    active.keySet().forEach(ai::cancel);
  }
}
