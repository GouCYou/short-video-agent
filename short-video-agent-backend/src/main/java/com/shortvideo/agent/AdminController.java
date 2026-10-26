package com.shortvideo.agent;

import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
class AdminController {
  private final KnowledgeService knowledge;
  private final JdbcTemplate db;
  private final StringRedisTemplate redis;
  private final AiClient ai;

  AdminController(
      KnowledgeService knowledge, JdbcTemplate db, StringRedisTemplate redis, AiClient ai) {
    this.knowledge = knowledge;
    this.db = db;
    this.redis = redis;
    this.ai = ai;
  }

  @GetMapping("/knowledge/categories")
  List<String> categories() {
    return knowledge.categories();
  }

  @GetMapping("/admin/knowledge")
  List<Map<String, Object>> documents() {
    return knowledge.list();
  }

  @PostMapping("/admin/knowledge")
  @ResponseStatus(HttpStatus.CREATED)
  Map<String, Object> upload(
      @RequestParam MultipartFile file,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String sourceUrl) {
    return knowledge.upload(file, category, sourceUrl);
  }

  @PostMapping("/admin/knowledge/text")
  @ResponseStatus(HttpStatus.CREATED)
  Map<String, Object> text(@RequestBody Map<String, Object> body) {
    return knowledge.text(body);
  }

  @GetMapping("/admin/knowledge/{id}")
  Map<String, Object> document(@PathVariable String id) {
    knowledge.requireShared(id);
    return knowledge.get(id);
  }

  @PutMapping("/admin/knowledge/{id}")
  Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
    knowledge.requireShared(id);
    return knowledge.update(id, body);
  }

  @PostMapping("/admin/knowledge/{id}/reindex")
  Map<String, Object> reindex(@PathVariable String id) {
    knowledge.requireShared(id);
    return knowledge.reindex(id);
  }

  @DeleteMapping("/admin/knowledge/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable String id) {
    knowledge.requireShared(id);
    knowledge.delete(id);
  }

  @PostMapping("/admin/knowledge/search")
  List<Map<String, Object>> search(@RequestBody Map<String, Object> body) {
    return knowledge.search(body);
  }

  @GetMapping("/admin/stats")
  Map<String, Object> stats() {
    return Map.of(
        "users",
        count("users", null),
        "tasks",
        count("tasks", null),
        "documents",
        count("documents", null),
        "failedTasks",
        count("tasks", "FAILED"),
        "completedTasks",
        count("tasks", "COMPLETED"),
        "averageDurationMs",
        Optional.ofNullable(
                db.queryForObject(
                    "SELECT AVG(duration_ms) FROM jobs WHERE target_type='TASK' AND status='DONE'",
                    Double.class))
            .orElse(0d));
  }

  long count(String table, String status) {
    return status == null
        ? db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)
        : db.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE status=?", Long.class, status);
  }

  @GetMapping("/admin/health")
  Map<String, Object> health() {
    Map<String, Object> result = new LinkedHashMap<>();
    try {
      db.queryForObject("SELECT 1", Integer.class);
      result.put("database", "UP");
    } catch (Exception ex) {
      result.put("database", "DOWN");
    }
    try (var connection = redis.getConnectionFactory().getConnection()) {
      result.put("redis", "PONG".equals(connection.ping()) ? "UP" : "DOWN");
    } catch (Exception ex) {
      result.put("redis", "DOWN");
    }
    try {
      Map<String, Object> h = ai.health();
      Map<String, Object> safe = new LinkedHashMap<>();
      for (String key :
          List.of(
              "status",
              "generationConfigured",
              "embeddingConfigured",
              "vectorStore",
              "statusDetail",
              "mode")) if (h.containsKey(key)) safe.put(key, h.get(key));
      result.put("ai", safe);
    } catch (ApiException ex) {
      result.put(
          "ai",
          Map.of(
              "status",
              "DOWN",
              "generationConfigured",
              false,
              "embeddingConfigured",
              false,
              "message",
              ex.getMessage()));
    }
    return result;
  }
}
