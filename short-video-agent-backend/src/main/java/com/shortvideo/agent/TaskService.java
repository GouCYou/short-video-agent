package com.shortvideo.agent;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class TaskService {
  private final JdbcTemplate db;
  private final Json json;
  private final AuthService auth;
  private final TransactionTemplate tx;

  TaskService(JdbcTemplate db, Json json, AuthService auth, TransactionTemplate tx) {
    this.db = db;
    this.json = json;
    this.auth = auth;
    this.tx = tx;
  }

  Map<String, Object> view(ResultSet rs) throws SQLException {
    Map<String, Object> task = new LinkedHashMap<>(json.object(rs.getString("payload")));
    task.put("id", rs.getString("id"));
    task.put("title", rs.getString("title"));
    task.put("status", rs.getString("status"));
    task.put("stage", rs.getString("stage"));
    task.put("error", rs.getString("error"));
    task.put("topics", json.list(rs.getString("topics")));
    task.put("result", json.object(rs.getString("result")));
    task.put("sources", json.list(rs.getString("sources")));
    task.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
    task.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
    task.put("versions", List.of());
    task.put("events", List.of());
    return task;
  }

  Map<String, Object> byId(String id) {
    return db.query("SELECT * FROM tasks WHERE id=?", (rs, n) -> view(rs), id).stream()
        .findFirst()
        .orElseThrow(ApiException::missing);
  }

  Map<String, Object> owned(String id) {
    if (db.queryForObject(
            "SELECT COUNT(*) FROM tasks WHERE id=? AND user_id=?",
            Integer.class,
            id,
            auth.current().id())
        == 0) throw ApiException.missing();
    return byId(id);
  }

  void lockOwned(String id) {
    if (db.query(
            "SELECT id FROM tasks WHERE id=? AND user_id=? FOR UPDATE",
            (rs, n) -> rs.getString(1),
            id,
            auth.current().id())
        .isEmpty()) throw ApiException.missing();
  }

  List<Map<String, Object>> list(String search, String status, boolean admin) {
    String sql = "SELECT * FROM tasks WHERE 1=1";
    List<Object> args = new ArrayList<>();
    if (!admin) {
      sql += " AND user_id=?";
      args.add(auth.current().id());
    }
    if (search != null && !search.isBlank()) {
      if (search.length() > 200) throw ApiException.bad("搜索词过长");
      sql += " AND (title LIKE ? OR payload LIKE ?)";
      args.add("%" + search + "%");
      args.add("%" + search + "%");
    }
    if (status != null && !status.isBlank()) {
      if (!Set.of("QUEUED", "RUNNING", "WAITING_SELECTION", "COMPLETED", "FAILED").contains(status))
        throw ApiException.bad("无效任务状态");
      sql += " AND status=?";
      args.add(status);
    }
    sql += " ORDER BY created_at DESC LIMIT 500";
    return db.query(sql, (rs, n) -> view(rs), args.toArray());
  }

  Map<String, Object> detail(String id) {
    Map<String, Object> task = owned(id);
    task.put(
        "events",
        db.query(
            "SELECT * FROM task_events WHERE task_id=? ORDER BY id",
            (rs, n) -> {
              Map<String, Object> event = new LinkedHashMap<>();
              event.put("stage", rs.getString("stage"));
              event.put("message", rs.getString("message"));
              event.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
              event.put("durationMs", rs.getObject("duration_ms"));
              return event;
            },
            id));
    task.put("versions", versions(id));
    return task;
  }

  List<Map<String, Object>> versions(String id) {
    return db.query(
        "SELECT * FROM versions WHERE task_id=? ORDER BY version_number DESC",
        (rs, n) -> {
          Map<String, Object> v = new LinkedHashMap<>();
          v.put("id", rs.getString("id"));
          v.put("versionNumber", rs.getInt("version_number"));
          v.put("kind", rs.getString("kind"));
          v.put("instruction", rs.getString("instruction"));
          v.put("content", json.object(rs.getString("content")));
          v.put("sources", json.list(rs.getString("sources")));
          v.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
          return v;
        },
        id);
  }

  Map<String, Object> create(Map<String, Object> body, String requestKey) {
    auth.rateLimit("task:" + auth.current().id(), 40, java.time.Duration.ofHours(1));
    if (requestKey != null && (requestKey.isBlank() || requestKey.length() > 100))
      throw ApiException.bad("无效幂等请求标识");
    Map<String, Object> payload = new LinkedHashMap<>();
    String topic = Json.required(body, "topic", 2000);
    String title =
        Json.optional(body, "title", topic.substring(0, Math.min(60, topic.length())), 200);
    payload.put("topic", topic);
    payload.put("audience", nonBlankDefault(body, "audience", "普通观众", 300));
    payload.put("platform", nonBlankDefault(body, "platform", "抖音", 40));
    payload.put("style", nonBlankDefault(body, "style", "自然清晰", 200));
    payload.put("duration", Json.integer(body, "duration", 60, 10, 600));
    payload.put("requirements", Json.optional(body, "requirements", "", 5000));
    String mode = Json.optional(body, "mode", "GUIDED", 10);
    if (!Set.of("GUIDED", "QUICK").contains(mode)) throw ApiException.bad("无效创作模式");
    payload.put("mode", mode);
    payload.put("category", Json.optional(body, "category", "", 100));
    payload.put("ragEnabled", Json.bool(body, "ragEnabled", true));
    String id =
        tx.execute(
            s -> {
              if (requestKey != null) {
                db.queryForObject(
                    "SELECT id FROM users WHERE id=? FOR UPDATE",
                    String.class,
                    auth.current().id());
                var ids =
                    db.query(
                        "SELECT task_id FROM task_requests WHERE user_id=? AND request_key=?",
                        (rs, n) -> rs.getString(1),
                        auth.current().id(),
                        requestKey);
                if (!ids.isEmpty()) return ids.getFirst();
              }
              String created = Json.id();
              db.update(
                  "INSERT INTO tasks(id,user_id,title,payload,status,stage,topics,sources)"
                      + " VALUES(?,?,?,?,'QUEUED','QUEUED','[]','[]')",
                  created,
                  auth.current().id(),
                  title,
                  json.write(payload));
              if (requestKey != null)
                db.update(
                    "INSERT INTO task_requests(user_id,request_key,task_id) VALUES(?,?,?)",
                    auth.current().id(),
                    requestKey,
                    created);
              enqueue(created, mode.equals("GUIDED") ? "PLAN" : "GENERATE", Map.of());
              event(created, "QUEUED", "创作任务已进入队列", null);
              return created;
            });
    return detail(id);
  }

  void requireIdle(Map<String, Object> task) {
    if (Set.of("QUEUED", "RUNNING").contains(task.get("status").toString()))
      throw ApiException.conflict("任务正在执行，请等待完成");
  }

  Map<String, Object> select(String id, Map<String, Object> body) {
    tx.executeWithoutResult(
        s -> {
          lockOwned(id);
          Map<String, Object> t = byId(id);
          if (!"WAITING_SELECTION".equals(t.get("status")))
            throw ApiException.conflict("当前任务无需选择选题");
          List<?> topics = (List<?>) t.get("topics");
          int index = Json.integer(body, "topicIndex", -1, 0, Math.max(0, topics.size() - 1));
          if (index >= topics.size()) throw ApiException.bad("无效选题索引");
          db.update(
              "UPDATE tasks SET"
                  + " selected_topic=?,status='QUEUED',stage='QUEUED',error=NULL,updated_at=CURRENT_TIMESTAMP"
                  + " WHERE id=?",
              json.write(topics.get(index)),
              id);
          enqueue(id, "GENERATE", Map.of());
          event(id, "QUEUED", "已确认选题，等待生成脚本", null);
        });
    return detail(id);
  }

  Map<String, Object> retry(String id) {
    tx.executeWithoutResult(
        s -> {
          lockOwned(id);
          Map<String, Object> t = byId(id);
          requireIdle(t);
          db.update(
              "UPDATE tasks SET"
                  + " status='QUEUED',stage='QUEUED',error=NULL,selected_topic=NULL,updated_at=CURRENT_TIMESTAMP"
                  + " WHERE id=?",
              id);
          enqueue(id, "GUIDED".equals(t.get("mode")) ? "PLAN" : "GENERATE", Map.of());
          event(id, "QUEUED", "已重新提交创作任务", null);
        });
    return detail(id);
  }

  Map<String, Object> revise(String id, Map<String, Object> body) {
    String instruction = Json.required(body, "instruction", 2000);
    String section = Json.optional(body, "section", "ALL", 30);
    if (!Set.of("ALL", "title", "opening", "script", "ending", "caption").contains(section)
        && !section.matches("\\d{1,3}")) throw ApiException.bad("无效修改范围");
    tx.executeWithoutResult(
        s -> {
          lockOwned(id);
          Map<String, Object> t = byId(id);
          requireIdle(t);
          if (t.get("result") == null) throw ApiException.conflict("请先生成脚本");
          if (section.matches("\\d+")) {
            List<?> segments = (List<?>) Json.map(t.get("result")).get("segments");
            if (Integer.parseInt(section) >= segments.size()) throw ApiException.bad("无效段落索引");
          }
          db.update(
              "UPDATE tasks SET"
                  + " status='QUEUED',stage='QUEUED',error=NULL,updated_at=CURRENT_TIMESTAMP WHERE"
                  + " id=?",
              id);
          enqueue(id, "REVISE", Map.of("instruction", instruction, "section", section));
          event(id, "QUEUED", "局部优化任务已进入队列", null);
        });
    return detail(id);
  }

  Map<String, Object> manual(String id, Map<String, Object> body) {
    Map<String, Object> content = Json.map(body.get("content"));
    validateContent(content);
    tx.executeWithoutResult(
        s -> {
          lockOwned(id);
          var t = byId(id);
          requireIdle(t);
          if (t.get("result") == null) throw ApiException.conflict("请先生成脚本");
          saveVersion(id, "MANUAL", "手动编辑", content, t.get("sources"));
          db.update(
              "UPDATE tasks SET"
                  + " result=?,status='COMPLETED',stage='COMPLETED',error=NULL,updated_at=CURRENT_TIMESTAMP"
                  + " WHERE id=?",
              json.write(content),
              id);
          event(id, "SAVED", "手动修改已保存为新版本", null);
        });
    return detail(id);
  }

  Map<String, Object> restore(String id, String versionId) {
    tx.executeWithoutResult(
        s -> {
          lockOwned(id);
          requireIdle(byId(id));
          var found =
              versions(id).stream()
                  .filter(v -> v.get("id").equals(versionId))
                  .findFirst()
                  .orElseThrow(ApiException::missing);
          saveVersion(
              id,
              "RESTORE",
              "恢复版本 " + found.get("versionNumber"),
              found.get("content"),
              found.get("sources"));
          db.update(
              "UPDATE tasks SET"
                  + " result=?,sources=?,status='COMPLETED',stage='COMPLETED',error=NULL,updated_at=CURRENT_TIMESTAMP"
                  + " WHERE id=?",
              json.write(found.get("content")),
              json.write(found.get("sources")),
              id);
          event(id, "SAVED", "历史版本已恢复为新版本", null);
        });
    return detail(id);
  }

  void delete(String id) {
    tx.executeWithoutResult(
        s -> {
          lockOwned(id);
          requireIdle(byId(id));
          db.update("DELETE FROM jobs WHERE target_type='TASK' AND target_id=?", id);
          db.update("DELETE FROM tasks WHERE id=?", id);
        });
  }

  void enqueue(String id, String phase, Map<String, Object> payload) {
    db.update(
        "INSERT INTO jobs(id,target_type,target_id,phase,payload,status)"
            + " VALUES(?,'TASK',?,?,?,'QUEUED')",
        Json.id(),
        id,
        phase,
        json.write(payload));
  }

  void saveVersion(String id, String kind, String instruction, Object content, Object sources) {
    Integer latest =
        db.queryForObject(
            "SELECT COALESCE(MAX(version_number),0) FROM versions WHERE task_id=?",
            Integer.class,
            id);
    db.update(
        "INSERT INTO versions(id,task_id,version_number,kind,instruction,content,sources)"
            + " VALUES(?,?,?,?,?,?,?)",
        Json.id(),
        id,
        latest + 1,
        kind,
        instruction,
        json.write(content),
        json.write(sources));
  }

  void event(String id, String stage, String message, Long duration) {
    db.update(
        "INSERT INTO task_events(task_id,stage,message,duration_ms) VALUES(?,?,?,?)",
        id,
        stage,
        message,
        duration);
  }

  Map<String, Object> dashboard() {
    List<Map<String, Object>> recent =
        db.query(
            "SELECT * FROM tasks WHERE user_id=? ORDER BY created_at DESC LIMIT 6",
            (rs, n) -> view(rs),
            auth.current().id());
    String id = auth.current().id();
    return Map.of(
        "totalTasks",
        count(id, null),
        "completedTasks",
        count(id, "COMPLETED"),
        "failedTasks",
        count(id, "FAILED"),
        "runningTasks",
        db.queryForObject(
            "SELECT COUNT(*) FROM tasks WHERE user_id=? AND status IN ('QUEUED','RUNNING')",
            Long.class,
            id),
        "recentTasks",
        recent);
  }

  long count(String id, String status) {
    return status == null
        ? db.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id=?", Long.class, id)
        : db.queryForObject(
            "SELECT COUNT(*) FROM tasks WHERE user_id=? AND status=?", Long.class, id, status);
  }

  static String nonBlankDefault(Map<String, Object> body, String field, String fallback, int max) {
    String value = Json.optional(body, field, fallback, max);
    return value.isBlank() ? fallback : value;
  }

  // Match the private Python Content schema so a manually saved version remains valid for AI
  // revision.
  static void validateContent(Map<String, Object> content) {
    Set<String> fields =
        Set.of(
            "titles",
            "opening",
            "positioning",
            "audienceAnalysis",
            "outline",
            "segments",
            "ending",
            "caption",
            "tags",
            "qualityNotes");
    if (!content.keySet().equals(fields)) throw ApiException.bad("内容字段缺失或包含未知字段");
    for (String field : List.of("opening", "positioning", "audienceAnalysis", "ending", "caption"))
      validateText(content.get(field), field, field.equals("caption") ? 10000 : 5000, false);
    validateTextList(content.get("titles"), "titles", 1, 10);
    validateTextList(content.get("outline"), "outline", 1, 30);
    validateTextList(content.get("tags"), "tags", 0, 20);
    validateTextList(content.get("qualityNotes"), "qualityNotes", 0, 30);
    if (!(content.get("segments") instanceof List<?> segments)
        || segments.isEmpty()
        || segments.size() > 30) throw ApiException.bad("脚本段落不能为空且不能超过 30 段");
    for (Object value : segments) {
      Map<String, Object> segment = Json.map(value);
      if (!segment.keySet().equals(Set.of("duration", "visual", "narration", "subtitle")))
        throw ApiException.bad("段落字段缺失或包含未知字段");
      Object duration = segment.get("duration");
      if (!(duration instanceof Number number)
          || !Double.isFinite(number.doubleValue())
          || number.doubleValue() <= 0
          || number.doubleValue() > 600) throw ApiException.bad("段落时长须为大于 0 且不超过 600 的数值");
      validateText(segment.get("visual"), "visual", 5000, false);
      validateText(segment.get("narration"), "narration", 10000, false);
      validateText(segment.get("subtitle"), "subtitle", 5000, true);
    }
  }

  static void validateText(Object value, String name, int max, boolean allowBlank) {
    if (!(value instanceof String text) || text.length() > max || (!allowBlank && text.isBlank()))
      throw ApiException.bad("内容字段无效：" + name + "，长度上限 " + max);
  }

  static void validateTextList(Object value, String name, int min, int max) {
    if (!(value instanceof List<?> list) || list.size() < min || list.size() > max)
      throw ApiException.bad("内容列表无效：" + name);
    for (Object item : list) validateText(item, name, 2000, false);
  }

  String export(String id, boolean markdown) {
    var task = owned(id);
    Map<String, Object> c = Json.map(task.get("result"));
    if (c.isEmpty()) throw ApiException.conflict("暂无可导出的脚本");
    String h = markdown ? "## " : "";
    StringBuilder out = new StringBuilder((markdown ? "# " : "") + task.get("title") + "\n\n");
    for (String field :
        List.of("titles", "opening", "positioning", "audienceAnalysis", "outline")) {
      out.append(h)
          .append(
              switch (field) {
                case "titles" -> "标题备选";
                case "opening" -> "开场";
                case "positioning" -> "内容定位";
                case "audienceAnalysis" -> "受众分析";
                default -> "内容大纲";
              })
          .append("\n");
      Object value = c.get(field);
      if (value instanceof List<?> list)
        list.forEach(item -> out.append("- ").append(item).append("\n"));
      else out.append(value).append("\n");
      out.append("\n");
    }
    out.append(h).append("分段脚本\n\n");
    int n = 0;
    for (Object item : (List<?>) c.get("segments")) {
      var seg = Json.map(item);
      out.append("第 ")
          .append(++n)
          .append(" 段（")
          .append(seg.get("duration"))
          .append(" 秒）\n画面：")
          .append(seg.get("visual"))
          .append("\n口播：")
          .append(seg.get("narration"))
          .append("\n字幕：")
          .append(seg.get("subtitle"))
          .append("\n\n");
    }
    out.append(h)
        .append("结尾\n")
        .append(c.get("ending"))
        .append("\n\n")
        .append(h)
        .append("发布文案\n")
        .append(c.get("caption"))
        .append("\n")
        .append(String.join(" ", ((List<?>) c.get("tags")).stream().map(v -> "#" + v).toList()))
        .append("\n\n")
        .append(h)
        .append("质量检查\n");
    ((List<?>) c.get("qualityNotes")).forEach(v -> out.append("- ").append(v).append("\n"));
    out.append("\n").append(h).append("参考资料\n");
    for (Object item : (List<?>) task.get("sources")) {
      var src = Json.map(item);
      out.append("- ")
          .append(src.get("documentName"))
          .append(" ")
          .append(src.getOrDefault("sourceUrl", ""))
          .append("\n  ")
          .append(src.get("text"))
          .append("\n");
    }
    return out.toString();
  }
}
