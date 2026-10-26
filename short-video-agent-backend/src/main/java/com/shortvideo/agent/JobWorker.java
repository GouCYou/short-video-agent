package com.shortvideo.agent;

import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class JobWorker {
  private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

  record Job(String id, String type, String target, String phase, String payload) {
    String resource() {
      return type + ":" + target;
    }
  }

  private final JdbcTemplate db;
  private final Json json;
  private final AiClient ai;
  private final TaskService tasks;
  private final KnowledgeService knowledge;
  private final TransactionTemplate tx;
  private final ResourceLocks locks;
  private final ExecutorService executor;
  private final Semaphore slots;
  private final String worker = Json.id();
  private final boolean enabled;
  private final ConcurrentMap<String, Job> active = new ConcurrentHashMap<>();

  JobWorker(
      JdbcTemplate db,
      Json json,
      AiClient ai,
      TaskService tasks,
      KnowledgeService knowledge,
      TransactionTemplate tx,
      ResourceLocks locks,
      @Value("${app.workers:2}") int concurrency,
      @Value("${app.worker-enabled:true}") boolean enabled) {
    this.db = db;
    this.json = json;
    this.ai = ai;
    this.tasks = tasks;
    this.knowledge = knowledge;
    this.tx = tx;
    this.locks = locks;
    this.enabled = enabled;
    int count = Math.max(1, Math.min(8, concurrency));
    slots = new Semaphore(count);
    executor = Executors.newFixedThreadPool(count);
  }

  @Scheduled(fixedDelay = 1000)
  void tick() {
    if (!enabled) return;
    try {
      if (slots.availablePermits() == 0) return;
      List<Job> pending =
          db.query(
              "SELECT * FROM jobs WHERE status='QUEUED' ORDER BY created_at LIMIT 20",
              (rs, n) ->
                  new Job(
                      rs.getString("id"),
                      rs.getString("target_type"),
                      rs.getString("target_id"),
                      rs.getString("phase"),
                      rs.getString("payload")));
      for (Job job : pending) {
        if (!slots.tryAcquire()) break;
        boolean lock = false;
        try {
          lock = locks.acquire(job.resource(), worker);
          if (!lock) {
            slots.release();
            continue;
          }
          int changed =
              db.update(
                  "UPDATE jobs SET"
                      + " status='RUNNING',worker_id=?,attempts=attempts+1,updated_at=CURRENT_TIMESTAMP"
                      + " WHERE id=? AND status='QUEUED'",
                  worker,
                  job.id());
          if (changed != 1) {
            locks.release(job.resource(), worker);
            slots.release();
            continue;
          }
          active.put(job.id(), job);
          executor.submit(() -> run(job));
        } catch (Exception ex) {
          if (lock)
            try {
              locks.release(job.resource(), worker);
            } catch (Exception ignored) {
            }
          slots.release();
          throw ex;
        }
      }
    } catch (Exception ex) {
      log.warn("Job dispatch unavailable: {}", ex.getClass().getSimpleName());
    }
  }

  @Scheduled(fixedDelay = 15000)
  void heartbeat() {
    for (Job job : active.values())
      try {
        if (locks.renew(job.resource(), worker))
          db.update(
              "UPDATE jobs SET updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='RUNNING' AND"
                  + " worker_id=?",
              job.id(),
              worker);
      } catch (Exception ex) {
        log.warn("Job heartbeat unavailable: {}", ex.getClass().getSimpleName());
      }
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 1000)
  void recover() {
    if (!enabled) return;
    try {
      Timestamp cutoff = Timestamp.from(Instant.now().minusSeconds(16 * 60));
      var stale =
          db.query(
              "SELECT * FROM jobs WHERE status='RUNNING' AND updated_at<?",
              (rs, n) ->
                  new Job(
                      rs.getString("id"),
                      rs.getString("target_type"),
                      rs.getString("target_id"),
                      rs.getString("phase"),
                      rs.getString("payload")),
              cutoff);
      for (Job job : stale)
        tx.executeWithoutResult(
            s -> {
              int recovered =
                  db.update(
                      "UPDATE jobs SET"
                          + " status='QUEUED',worker_id=NULL,error=NULL,updated_at=CURRENT_TIMESTAMP"
                          + " WHERE id=? AND status='RUNNING' AND updated_at<?",
                      job.id(),
                      cutoff);
              if (recovered > 0 && job.type().equals("TASK")) {
                db.update(
                    "UPDATE tasks SET status='QUEUED',stage='QUEUED',updated_at=CURRENT_TIMESTAMP"
                        + " WHERE id=?",
                    job.target());
                tasks.event(job.target(), "RECOVERY", "检测到中断的执行，已重新排队", null);
              }
            });
    } catch (Exception ex) {
      log.warn("Job recovery unavailable: {}", ex.getClass().getSimpleName());
    }
  }

  private void run(Job job) {
    long start = System.nanoTime();
    try {
      if (job.type().equals("TASK")) generate(job, start);
      else index(job, start);
    } catch (Exception ex) {
      String message = ex instanceof ApiException ? ex.getMessage() : "执行失败，请检查 AI 服务和模型返回格式后重试";
      log.warn("Job {} failed: {}", job.id(), ex.getClass().getSimpleName());
      try {
        tx.executeWithoutResult(
            s -> {
              if (!owns(job)) return;
              db.update(
                  "UPDATE jobs SET"
                      + " status='FAILED',error=?,duration_ms=?,updated_at=CURRENT_TIMESTAMP WHERE"
                      + " id=?",
                  message,
                  elapsed(start),
                  job.id());
              if (job.type().equals("TASK")) {
                db.update(
                    "UPDATE tasks SET"
                        + " status='FAILED',stage='FAILED',error=?,updated_at=CURRENT_TIMESTAMP"
                        + " WHERE id=?",
                    message,
                    job.target());
                tasks.event(job.target(), "FAILED", message, elapsed(start));
              } else
                db.update(
                    "UPDATE documents SET status='FAILED',error=? WHERE id=?",
                    message,
                    job.target());
            });
      } catch (Exception failure) {
        log.error("Failed to persist job failure {}", job.id());
      }
    } finally {
      active.remove(job.id());
      try {
        locks.release(job.resource(), worker);
      } catch (Exception ignored) {
      }
      slots.release();
    }
  }

  private void generate(Job job, long start) {
    tx.executeWithoutResult(
        s -> {
          if (!owns(job)) throw ApiException.conflict("执行租约已失效");
          db.queryForObject(
              "SELECT id FROM tasks WHERE id=? FOR UPDATE", String.class, job.target());
          db.update(
              "UPDATE tasks SET status='RUNNING',stage=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
              job.phase(),
              job.target());
          tasks.event(
              job.target(),
              job.phase(),
              switch (job.phase()) {
                case "PLAN" -> "正在分析需求、检索资料并生成选题";
                case "REVISE" -> "正在按要求优化指定内容";
                default -> "正在生成策划方案与分段脚本";
              },
              null);
        });
    Map<String, Object> task = tasks.byId(job.target()), request = new LinkedHashMap<>();
    request.put("taskId", job.target());
    request.put("phase", job.phase());
    Map<String, Object> brief = new LinkedHashMap<>();
    for (String field :
        List.of(
            "topic",
            "audience",
            "platform",
            "style",
            "duration",
            "requirements",
            "category",
            "ragEnabled",
            "mode")) brief.put(field, task.get(field));
    request.put("task", brief);
    String selected =
        db.queryForObject(
            "SELECT selected_topic FROM tasks WHERE id=?", String.class, job.target());
    if (selected != null) request.put("selectedTopic", json.object(selected));
    if (job.phase().equals("REVISE")) {
      request.put("content", task.get("result"));
      request.put("sources", task.get("sources"));
      request.putAll(json.object(job.payload()));
    }
    Map<String, Object> result = ai.post("/internal/generate", request);
    Object returnedTopics = result.getOrDefault("topics", task.get("topics"));
    Object topics =
        returnedTopics instanceof List<?> list && list.isEmpty() && !job.phase().equals("PLAN")
            ? task.get("topics")
            : returnedTopics;
    Object sources =
        job.phase().equals("REVISE")
            ? task.get("sources")
            : result.getOrDefault("sources", List.of());
    validateTopics(topics, job.phase().equals("PLAN"));
    if (!(sources instanceof List<?>)) throw ApiException.bad("AI 返回的资料引用格式无效");
    Map<String, Object> content = Json.map(result.get("content"));
    if (!job.phase().equals("PLAN")) {
      TaskService.validateContent(content);
      if (job.phase().equals("REVISE"))
        preserveRevision(
            Json.map(task.get("result")),
            content,
            Json.str(json.object(job.payload()), "section", "ALL"));
    }
    tx.executeWithoutResult(
        s -> {
          if (!owns(job)) return;
          db.queryForObject(
              "SELECT id FROM tasks WHERE id=? FOR UPDATE", String.class, job.target());
          String state = job.phase().equals("PLAN") ? "WAITING_SELECTION" : "COMPLETED";
          db.update(
              "UPDATE tasks SET"
                  + " status=?,stage=?,topics=?,sources=?,error=NULL,updated_at=CURRENT_TIMESTAMP"
                  + " WHERE id=?",
              state,
              state,
              json.write(topics),
              json.write(sources),
              job.target());
          if (!job.phase().equals("PLAN")) {
            db.update("UPDATE tasks SET result=? WHERE id=?", json.write(content), job.target());
            tasks.saveVersion(
                job.target(),
                job.phase().equals("REVISE") ? "AI_REVISION" : "GENERATED",
                Json.str(json.object(job.payload()), "instruction", "首次生成"),
                content,
                sources);
          }
          if (result.get("events") instanceof List<?> events)
            for (Object item : events) {
              var e = Json.map(item);
              String stage = Json.str(e, "stage", "AI");
              String message = Json.str(e, "message", "");
              if (stage.length() > 60) stage = stage.substring(0, 60);
              if (message.length() > 20000) message = message.substring(0, 20000);
              Long duration = e.get("durationMs") instanceof Number num ? num.longValue() : null;
              tasks.event(job.target(), stage, message, duration);
            }
          tasks.event(
              job.target(),
              state,
              job.phase().equals("PLAN") ? "选题已生成，请选择一个方向继续" : "创作内容已保存",
              elapsed(start));
          db.update(
              "UPDATE jobs SET"
                  + " status='DONE',duration_ms=?,usage_json=?,updated_at=CURRENT_TIMESTAMP WHERE"
                  + " id=?",
              elapsed(start),
              json.write(result.getOrDefault("usage", Map.of())),
              job.id());
        });
  }

  private void index(Job job, long start) {
    Map<String, Object> response =
        ai.post("/internal/knowledge/index", knowledge.indexRequest(job.target()));
    int count = Json.integer(response, "chunkCount", 0, 1, 100000);
    if (!(response.get("chunks") instanceof List<?> chunks) || chunks.size() != count)
      throw ApiException.bad("AI 返回的知识片段格式无效");
    tx.executeWithoutResult(
        s -> {
          if (!owns(job)) return;
          db.update(
              "UPDATE documents SET status='READY',chunk_count=?,chunks=?,error=NULL WHERE id=?",
              count,
              json.write(response.get("chunks")),
              job.target());
          db.update(
              "UPDATE jobs SET status='DONE',duration_ms=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
              elapsed(start),
              job.id());
        });
  }

  private boolean owns(Job job) {
    var rows =
        db.query(
            "SELECT worker_id FROM jobs WHERE id=? AND status='RUNNING' FOR UPDATE",
            (rs, n) -> rs.getString(1),
            job.id());
    return rows.size() == 1 && worker.equals(rows.getFirst());
  }

  private static long elapsed(long start) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  static void validateTopics(Object value, boolean required) {
    if (!(value instanceof List<?> topics) || topics.size() > 10 || (required && topics.isEmpty()))
      throw ApiException.bad("AI 返回的选题格式无效");
    for (Object topic : topics) {
      Map<String, Object> t = Json.map(topic);
      for (String field : List.of("title", "angle", "reason"))
        if (!(t.get(field) instanceof String v)
            || v.isBlank()
            || v.length() > (field.equals("title") ? 300 : 2000))
          throw ApiException.bad("AI 返回的选题字段无效");
    }
  }

  static void preserveRevision(
      Map<String, Object> original, Map<String, Object> revised, String section) {
    if (section.equals("ALL")) return;
    Set<String> allowed =
        switch (section) {
          case "title" -> Set.of("titles");
          case "script" -> Set.of("segments");
          case "caption" -> Set.of("caption", "tags");
          case "opening", "ending" -> Set.of(section);
          default -> Set.of("segments");
        };
    for (var e : original.entrySet())
      if (!allowed.contains(e.getKey())) revised.put(e.getKey(), e.getValue());
    if (section.matches("\\d+")) {
      int index = Integer.parseInt(section);
      List<?> old = (List<?>) original.get("segments"), next = (List<?>) revised.get("segments");
      if (index >= old.size()) throw ApiException.bad("无效段落索引");
      Object replacement =
          next.size() == old.size() ? next.get(index) : (next.size() == 1 ? next.getFirst() : null);
      if (replacement == null) throw ApiException.bad("AI 返回的局部修改段落数无效");
      List<Object> preserved = new ArrayList<>(old);
      preserved.set(index, replacement);
      revised.put("segments", preserved);
    }
  }

  @PreDestroy
  void close() {
    executor.shutdownNow();
  }
}
