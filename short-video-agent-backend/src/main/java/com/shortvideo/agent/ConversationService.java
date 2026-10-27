package com.shortvideo.agent;

import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class ConversationService {
  private final JdbcTemplate db;
  private final Json json;
  private final AuthService auth;
  private final TransactionTemplate tx;
  private final MemoryService memory;
  private final AttachmentService attachments;
  private final UserKnowledgeService knowledge;
  private final CreationSettingsService creationSettings;

  ConversationService(
      JdbcTemplate db,
      Json json,
      AuthService auth,
      TransactionTemplate tx,
      MemoryService memory,
      AttachmentService attachments,
      UserKnowledgeService knowledge, CreationSettingsService creationSettings) {
    this.db = db;
    this.json = json;
    this.auth = auth;
    this.tx = tx;
    this.memory = memory;
    this.attachments = attachments;
    this.knowledge = knowledge;
    this.creationSettings = creationSettings;
  }

  Map<String, Object> view(ResultSet rs) throws SQLException {
    var c = new LinkedHashMap<String, Object>();
    for (String key : List.of("id", "title", "status")) c.put(key, rs.getString(key));
    c.put("brief", json.object(rs.getString("brief_json")));
    c.put("activeRunId", rs.getString("active_run_id"));
    c.put("executionsClearedThrough", rs.getLong("executions_cleared_through"));
    var retry =
        rs.getString("active_run_id") == null
            ? List.<String>of()
            : db.query(
                "SELECT retry_message_id FROM agent_runs WHERE id=?",
                (row, n) -> row.getString(1),
                rs.getString("active_run_id"));
    c.put("retryingMessageId", retry.isEmpty() ? null : retry.getFirst());
    c.put("pinned", rs.getBoolean("pinned"));
    c.put("summary", Optional.ofNullable(rs.getString("summary_text")).orElse(""));
    c.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
    c.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
    return c;
  }

  Map<String, Object> raw(String id) {
    return db.query("SELECT * FROM conversations WHERE id=?", (rs, n) -> view(rs), id).stream()
        .findFirst()
        .orElseThrow(ApiException::missing);
  }

  void owner(String id) {
    if (db.queryForObject(
            "SELECT COUNT(*) FROM conversations WHERE id=? AND user_id=?",
            Integer.class,
            id,
            auth.current().id())
        == 0) throw ApiException.missing();
  }

  void lockOwner(String id) {
    if (db.query(
            "SELECT id FROM conversations WHERE id=? AND user_id=? FOR UPDATE",
            (rs, n) -> rs.getString(1),
            id,
            auth.current().id())
        .isEmpty()) throw ApiException.missing();
  }

  void lock(String id) {
    if (db.query(
            "SELECT id FROM conversations WHERE id=? FOR UPDATE", (rs, n) -> rs.getString(1), id)
        .isEmpty()) throw ApiException.missing();
  }

  List<Map<String, Object>> list() {
    return db.query(
        "SELECT * FROM conversations WHERE user_id=? ORDER BY pinned DESC,updated_at DESC LIMIT"
            + " 100",
        (rs, n) -> {
          var c = view(rs);
          var a = artifact(rs.getString("id"));
          if (a != null) {
            var summary = new LinkedHashMap<String, Object>(a);
            summary.remove("content");
            summary.remove("sources");
            c.put("artifact", summary);
          } else c.put("artifact", null);
          return c;
        },
        auth.current().id());
  }

  Map<String, Object> create(Map<String, Object> body) {
    String title = Json.optional(body, "title", "新的创作", 200);
    if (title.isBlank()) title = "新的创作";
    String id = Json.id();
    db.update(
        "INSERT INTO conversations(id,user_id,title,brief_json) VALUES(?,?,?,?)",
        id,
        auth.current().id(),
        title,
        json.write(creationSettings.get()));
    return detail(id);
  }

  Map<String, Object> detail(String id) {
    owner(id);
    var c = raw(id);
    c.put("messages", messages(id));
    c.put("events", eventsInternal(id, 0, 500));
    c.put("artifact", artifact(id));
    c.put("versions", versions(id));
    c.put("researchSources", researchSources(id));
    return c;
  }

  List<Map<String, Object>> messages(String id) {
    return db.query(
        "SELECT * FROM conversation_messages WHERE conversation_id=? ORDER BY created_at,id",
        (rs, n) -> {
          var m = new LinkedHashMap<String, Object>();
          for (String key : List.of("id", "role", "content")) m.put(key, rs.getString(key));
          m.put("choices", json.list(rs.getString("choices_json")));
          m.put("attachments", json.list(rs.getString("attachments_json")));
          m.put(
              "metadata",
              Optional.ofNullable(json.object(rs.getString("metadata_json"))).orElse(Map.of()));
          m.put("runId", rs.getString("run_id"));
          m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
          return m;
        },
        id);
  }

  List<Map<String, Object>> researchSources(String id) {
    var found = new LinkedHashMap<String, Map<String, Object>>();
    var rows = db.queryForList(
        "SELECT data_json FROM run_events WHERE conversation_id=? AND type='tool.completed' ORDER BY id DESC LIMIT 30",
        String.class, id);
    for (String row : rows) {
      var data = json.object(row);
      if (!Set.of("search_news", "read_news").contains(Json.str(data, "tool", ""))) continue;
      var output = Json.map(data.get("output"));
      for (var source : ProductionValidator.sources(output.getOrDefault("sources", List.of()))) {
        if (found.size() >= 30) break;
        found.putIfAbsent(Json.str(source, "chunkId", ""), source);
      }
    }
    return new ArrayList<>(found.values());
  }

  Map<String, Object> artifact(String id) {
    return db
        .query(
            "SELECT * FROM artifacts WHERE conversation_id=?",
            (rs, n) -> {
              var a = new LinkedHashMap<String, Object>();
              a.put("id", rs.getString("id"));
              a.put("title", rs.getString("title"));
              a.put("content", json.object(rs.getString("content_json")));
              a.put("sources", json.list(rs.getString("sources_json")));
              a.put("versionNumber", rs.getInt("version_number"));
              a.put("status", rs.getString("status"));
              a.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
              a.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
              return a;
            },
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  List<Map<String, Object>> versions(String id) {
    return db.query(
        "SELECT v.* FROM artifact_versions v JOIN artifacts a ON a.id=v.artifact_id WHERE"
            + " a.conversation_id=? ORDER BY v.version_number DESC",
        (rs, n) -> {
          var v = new LinkedHashMap<String, Object>();
          v.put("id", rs.getString("id"));
          v.put("versionNumber", rs.getInt("version_number"));
          v.put("kind", rs.getString("kind"));
          v.put("instruction", rs.getString("instruction"));
          v.put("content", json.object(rs.getString("content_json")));
          v.put("sources", json.list(rs.getString("sources_json")));
          v.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
          return v;
        },
        id);
  }

  Map<String, Object> rename(String id, Map<String, Object> body) {
    if (!body.containsKey("title") && !body.containsKey("pinned"))
      throw ApiException.bad("请选择要修改的内容");
    tx.executeWithoutResult(
        s -> {
          lockOwner(id);
          if (body.containsKey("title"))
            db.update(
                "UPDATE conversations SET title=?,title_manual=TRUE,updated_at=CURRENT_TIMESTAMP(6)"
                    + " WHERE id=?",
                Json.required(body, "title", 200),
                id);
          if (body.containsKey("pinned"))
            db.update(
                "UPDATE conversations SET pinned=? WHERE id=?",
                Json.bool(body, "pinned", false),
                id);
        });
    return detail(id);
  }

  Map<String, Object> renameWork(String id, Map<String, Object> body) {
    tx.executeWithoutResult(
        s -> {
          lockOwner(id);
          idle(id);
          var a = artifact(id);
          if (a == null) throw ApiException.missing();
          var content = new LinkedHashMap<>(Json.map(a.get("content")));
          content.put("title", Json.required(body, "title", 200));
          saveArtifact(
              id, "MANUAL", "重命名作品", content, a.get("sources"), a.get("status").toString());
        });
    return detail(id);
  }

  void deleteWork(String id) {
    tx.executeWithoutResult(
        s -> {
          lockOwner(id);
          idle(id);
          db.update("DELETE FROM artifacts WHERE conversation_id=?", id);
        });
  }

  void idle(String id) {
    if ("RUNNING".equals(raw(id).get("status"))) throw ApiException.conflict("当前创作正在执行，请等待完成或先取消");
  }

  void delete(String id) {
    tx.executeWithoutResult(
        s -> {
          lockOwner(id);
          idle(id);
          memory.forgetConversation(id);
          db.update("DELETE FROM conversations WHERE id=?", id);
        });
  }

  Map<String, Object> submit(String id, Map<String, Object> body, String key) {
    return submitInternal(id, body, key, null);
  }

  Map<String, Object> updateBrief(String id, Map<String, Object> brief) {
    ProductionValidator.brief(brief);
    tx.executeWithoutResult(s -> {
      lockOwner(id); idle(id);
      db.update("UPDATE conversations SET brief_json=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",json.write(brief),id);
    });
    return detail(id);
  }

  Map<String, Object> clearExecutions(String id) {
    tx.executeWithoutResult(s -> {
      lockOwner(id);
      Long cursor=db.queryForObject("SELECT COALESCE(MAX(id),0) FROM run_events WHERE conversation_id=?",Long.class,id);
      db.update("UPDATE conversations SET executions_cleared_through=? WHERE id=?",cursor,id);
    });
    return detail(id);
  }

  Map<String, Object> retry(String id, String messageId, String key) {
    owner(id);
    var history = messages(id);
    int index = -1;
    for (int i = 0; i < history.size(); i++)
      if (messageId.equals(history.get(i).get("id"))
          && "assistant".equals(history.get(i).get("role"))) index = i;
    if (index < 0) throw ApiException.missing();
    var user =
        history.subList(0, index).reversed().stream()
            .filter(m -> "user".equals(m.get("role")))
            .findFirst()
            .orElseThrow(ApiException::missing);
    var fileIds =
        ((List<?>) user.get("attachments")).stream().map(item -> Json.map(item).get("id")).toList();
    return submitInternal(
        id,
        Map.of("content", user.get("content"), "attachmentIds", fileIds, "retryOf", messageId),
        key,
        messageId);
  }

  private Map<String, Object> submitInternal(
      String id, Map<String, Object> body, String key, String retryOf) {
    if (key != null && (key.isBlank() || key.length() > 100)) throw ApiException.bad("无效幂等请求标识");
    String content = Json.optional(body, "content", "", 10000);
    Map<String, Object> choice = body.get("choice") == null ? null : Json.map(body.get("choice"));
    if (content.isBlank()
        && choice == null
        && (!(body.get("attachmentIds") instanceof List<?> list) || list.isEmpty()))
      throw ApiException.bad("请填写消息、上传文件或选择一个选项");
    String requestHash = AuthService.hash(json.write(body));
    String runId =
        tx.execute(
            s -> {
              lockOwner(id);
              if (key != null) {
                var previous =
                    db.query(
                        "SELECT run_id,request_hash FROM conversation_requests WHERE"
                            + " conversation_id=? AND request_key=?",
                        (rs, n) -> Map.of("runId", rs.getString(1), "hash", rs.getString(2)),
                        id,
                        key);
                if (!previous.isEmpty()) {
                  if (!requestHash.equals(previous.getFirst().get("hash")))
                    throw ApiException.conflict("该请求标识已用于另一条消息");
                  return previous.getFirst().get("runId");
                }
              }
              idle(id);
              if (retryOf != null
                  && db.queryForObject(
                          "SELECT COUNT(*) FROM conversation_messages WHERE id=? AND"
                              + " conversation_id=? AND role='assistant'",
                          Integer.class,
                          retryOf,
                          id)
                      != 1) throw ApiException.missing();
              var files = attachments.selected(id, body.get("attachmentIds"));
              if (retryOf != null) {
                memory.forgetConversation(id);
                var metadata=Json.map(json.object(db.queryForObject("SELECT metadata_json FROM conversation_messages WHERE id=? AND conversation_id=?",String.class,retryOf,id)));
                metadata.remove("summary");
                db.update("UPDATE conversation_messages SET metadata_json=? WHERE id=? AND conversation_id=?",json.write(metadata),retryOf,id);
              }
              var brief = new LinkedHashMap<String, Object>(Json.map(raw(id).get("brief")));
              Map<String, Object> selection = choice == null ? null : selectChoice(id, choice);
              if (selection != null)
                brief.put(
                    ProductionValidator.briefField(selection.get("kind").toString()),
                    selection.get("value"));
              ProductionValidator.brief(brief);
              String run = Json.id();
              String text =
                  selection == null
                      ? (content.isBlank() ? "请阅读这些文件。" : content)
                      : (content.isBlank() ? "选择：" + selection.get("value") : content);
              if (retryOf == null) insertMessage(id, "user", text, List.of(), run, files, Map.of());
              db.update(
                  "UPDATE conversations SET"
                      + " brief_json=?,status='RUNNING',active_run_id=?,updated_at=CURRENT_TIMESTAMP(6)"
                      + " WHERE id=?",
                  json.write(brief),
                  run,
                  id);
              var request = internalRequest(id, run, brief, selection, retryOf);
              db.update(
                  "INSERT INTO agent_runs(id,conversation_id,status,request_json,retry_message_id)"
                      + " VALUES(?,?,'QUEUED',?,?)",
                  run,
                  id,
                  json.write(request),
                  retryOf);
              if (key != null)
                db.update(
                    "INSERT INTO"
                        + " conversation_requests(conversation_id,request_key,request_hash,run_id)"
                        + " VALUES(?,?,?,?)",
                    id,
                    key,
                    requestHash,
                    run);
              if (selection != null) event(id, run, "brief.updated", Map.of("brief", brief));
              return run;
            });
    return Map.of("conversation", detail(id), "runId", runId);
  }

  private Map<String, Object> selectChoice(String conversationId, Map<String, Object> choice) {
    String messageId = Json.required(choice, "messageId", 36),
        groupId = Json.required(choice, "groupId", 36);
    String optionId = Json.optional(choice, "optionId", "", 36),
        custom = Json.optional(choice, "customText", "", 2000);
    if (optionId.isBlank() == custom.isBlank()) throw ApiException.bad("请选择一个已有选项，或填写自定义内容");
    var rows =
        db.query(
            "SELECT choices_json FROM conversation_messages WHERE id=? AND conversation_id=? AND"
                + " role='assistant'",
            (rs, n) -> rs.getString(1),
            messageId,
            conversationId);
    if (rows.isEmpty()) throw ApiException.missing();
    var groups = json.list(rows.getFirst());
    var group =
        groups.stream()
            .filter(g -> groupId.equals(g.get("id")))
            .findFirst()
            .orElseThrow(ApiException::missing);
    if (!"OPEN".equals(group.get("status"))) throw ApiException.conflict("这组选项已选定或被新选项替代，请使用当前选项");
    String value;
    if (!optionId.isBlank()) {
      var options = (List<?>) group.get("options");
      var option =
          options.stream()
              .map(Json::map)
              .filter(o -> optionId.equals(o.get("id")))
              .findFirst()
              .orElseThrow(() -> ApiException.bad("选项不存在，请重新选择"));
      value = option.get("value").toString();
      group.put("selectedOptionId", optionId);
      group.put("customValue", null);
    } else {
      value = custom;
      group.put("selectedOptionId", null);
      group.put("customValue", custom);
    }
    group.put("status", "SELECTED");
    db.update(
        "UPDATE conversation_messages SET choices_json=? WHERE id=?",
        json.write(groups),
        messageId);
    return Map.of("kind", group.get("kind"), "value", value);
  }

  private Map<String, Object> internalRequest(
      String id,
      String run,
      Map<String, Object> brief,
      Map<String, Object> selection,
      String retryOf) {
    var history = messages(id);
    if (retryOf != null) {
      int index = 0;
      while (index < history.size() && !retryOf.equals(history.get(index).get("id"))) index++;
      history = history.subList(0, index);
    }
    var selected = new LinkedList<Map<String, Object>>();
    int characters = 0;
    for (int i = history.size() - 1; i >= 0 && selected.size() < 40; i--) {
      var m = history.get(i);
      String text = m.get("content").toString();
      if (characters + text.length() > 100000 && !selected.isEmpty()) break;
      if (m.get("attachments") instanceof List<?> files && !files.isEmpty())
        text += "\n本条消息附件：" + json.write(files);
      selected.addFirst(Map.of("role", m.get("role"), "content", text));
      characters += text.length();
    }
    var request = new LinkedHashMap<String, Object>();
    request.put("conversationId", id);
    request.put("runId", run);
    request.put("messages", selected);
    request.put("brief", brief);
    request.put("memory", memory.context(id));
    request.put("conversationSummary", Json.str(raw(id), "summary", ""));
    request.put("researchSources", researchSources(id));
    request.put("historyWorks", memory.works(id));
    request.put("documents", attachments.context(id, history));
    request.put(
        "knowledgeDocumentIds",
        knowledge.idsForUser(
            db.queryForObject("SELECT user_id FROM conversations WHERE id=?", String.class, id)));
    var artifact = artifact(id);
    request.put("artifact", artifact == null ? null : artifact.get("content"));
    request.put("sources", artifact == null ? List.of() : artifact.get("sources"));
    request.put("artifactStatus", artifact == null ? "DRAFT" : artifact.get("status"));
    if (selection != null) request.put("selection", selection);
    return request;
  }

  void insertMessage(String id, String role, String content, Object choices, String run) {
    insertMessage(id, role, content, choices, run, List.of(), Map.of());
  }

  void insertMessage(
      String id,
      String role,
      String content,
      Object choices,
      String run,
      Object files,
      Object metadata) {
    db.update(
        "INSERT INTO"
            + " conversation_messages(id,conversation_id,role,content,choices_json,run_id,attachments_json,metadata_json)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        Json.id(),
        id,
        role,
        content,
        json.write(choices),
        run,
        json.write(files),
        json.write(metadata));
  }

  List<Map<String, Object>> events(String id, long after) {
    owner(id);
    if (after < 0) throw ApiException.bad("无效事件序号");
    return eventsInternal(id, after, 500);
  }

  List<Map<String, Object>> eventsInternal(String id, long after, int limit) {
    return db.query(
        "SELECT * FROM run_events WHERE conversation_id=? AND id>? ORDER BY id LIMIT ?",
        (rs, n) -> {
          var e = new LinkedHashMap<String, Object>();
          e.put("id", rs.getLong("id"));
          e.put("runId", rs.getString("run_id"));
          e.put("type", rs.getString("type"));
          var data = json.object(rs.getString("data_json"));
          e.put("data", data);
          for (String key : List.of("toolCallId", "tool"))
            if (data.containsKey(key)) e.put(key, data.get(key));
          e.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
          return e;
        },
        id,
        after,
        limit);
  }

  void event(String id, String run, String type, Object data) {
    db.update(
        "INSERT INTO run_events(conversation_id,run_id,type,data_json) VALUES(?,?,?,?)",
        id,
        run,
        type,
        json.write(data));
  }

  boolean currentRun(String id, String run, String worker) {
    var rows =
        db.query(
            "SELECT r.status,r.worker_id,c.active_run_id FROM agent_runs r JOIN conversations c ON"
                + " c.id=r.conversation_id WHERE r.id=? AND c.id=?",
            (rs, n) ->
                Map.of(
                    "status",
                    rs.getString(1),
                    "worker",
                    Optional.ofNullable(rs.getString(2)).orElse(""),
                    "active",
                    Optional.ofNullable(rs.getString(3)).orElse("")),
            run,
            id);
    return !rows.isEmpty()
        && "RUNNING".equals(rows.getFirst().get("status"))
        && worker.equals(rows.getFirst().get("worker"))
        && run.equals(rows.getFirst().get("active"));
  }

  void live(String id, String run, String worker, String type, Map<String, Object> data) {
    tx.executeWithoutResult(
        s -> {
          lock(id);
          if (!currentRun(id, run, worker)) throw new CancelledRun();
          if (type.equals("brief.updated")) {
            var brief = Json.map(data.get("brief"));
            ProductionValidator.brief(brief);
            brief = enforceSelection(run, brief);
            data.put("brief", brief);
            db.update(
                "UPDATE conversations SET brief_json=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                json.write(brief),
                id);
          }
          event(id, run, type, data);
        });
  }

  private Map<String, Object> enforceSelection(String run, Map<String, Object> brief) {
    var request =
        json.object(
            db.queryForObject("SELECT request_json FROM agent_runs WHERE id=?", String.class, run));
    var selection = Json.map(request.get("selection"));
    var output = new LinkedHashMap<String, Object>(brief);
    if (!selection.isEmpty())
      output.put(
          ProductionValidator.briefField(selection.get("kind").toString()), selection.get("value"));
    return output;
  }

  void finish(
      String id,
      String run,
      String worker,
      Map<String, Object> result,
      Map<String, Object> staged) {
    String content = Json.optional(result, "content", "", 20000);
    var choices = ProductionValidator.choices(result.getOrDefault("choices", List.of()));
    if (content.isBlank() && choices.isEmpty() && staged == null)
      throw ApiException.bad("AI 未返回可用的回复");
    var brief = Json.map(result.get("brief"));
    ProductionValidator.brief(brief);
    var usage = Json.map(result.get("usage"));
    boolean waiting = Json.bool(result, "waitingInput", !choices.isEmpty()) || !choices.isEmpty();
    if (staged != null) {
      ProductionValidator.content(Json.map(staged.get("content")));
      ProductionValidator.sources(staged.get("sources"));
    }
    tx.executeWithoutResult(
        s -> {
          lock(id);
          if (!currentRun(id, run, worker)) throw new CancelledRun();
          var canonicalBrief = enforceSelection(run, brief);
          supersede(id, choices);
          var timing =
              db.query(
                      "SELECT created_at FROM agent_runs WHERE id=?",
                      (rs, n) -> rs.getTimestamp(1).toInstant().toEpochMilli(),
                      run)
                  .getFirst();
          var first =
              db.query(
                  "SELECT created_at FROM run_events WHERE run_id=? AND type='message.delta' ORDER"
                      + " BY id LIMIT 1",
                  (rs, n) -> rs.getTimestamp(1).toInstant().toEpochMilli(),
                  run);
          long now = System.currentTimeMillis();
          var metadata =
              Map.of(
                  "processingMs",
                  Math.max(0, now - timing),
                  "thinkingMs",
                  Math.max(0, (first.isEmpty() ? now : first.getFirst()) - timing));
          String retryId = retryTarget(run);
          if (retryId == null)
            insertMessage(id, "assistant", content, choices, run, List.of(), metadata);
          else if (db.update(
                  "UPDATE conversation_messages SET"
                      + " content=?,choices_json=?,run_id=?,metadata_json=? WHERE id=? AND"
                      + " conversation_id=? AND role='assistant'",
                  content,
                  json.write(choices),
                  run,
                  json.write(metadata),
                  retryId,
                  id)
              != 1) throw ApiException.missing();
          if (staged != null) {
            var previous = artifact(id);
            String kind = previous == null ? "GENERATED" : "REVISED";
            saveArtifact(
                id,
                kind,
                Json.optional(staged, "instruction", "", 6000),
                Json.map(staged.get("content")),
                staged.get("sources"),
                "DRAFT");
            event(id, run, "artifact.updated", Map.of("artifact", artifact(id)));
          }
          db.update(
              "UPDATE conversations SET"
                  + " status=?,brief_json=?,active_run_id=NULL,updated_at=CURRENT_TIMESTAMP(6)"
                  + " WHERE id=?",
              waiting ? "WAITING_INPUT" : "IDLE",
              json.write(canonicalBrief),
              id);
          db.update(
              "UPDATE agent_runs SET"
                  + " status='COMPLETED',usage_json=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
              json.write(usage),
              run);
          event(
              id,
              run,
              waiting ? "run.waiting_input" : "run.completed",
              Map.of(
                  "messageId",
                  retryId == null ? lastAssistantId(id) : retryId,
                  "brief",
                  canonicalBrief));
        });
  }

  private String lastAssistantId(String id) {
    return db.query(
            "SELECT id FROM conversation_messages WHERE conversation_id=? AND role='assistant'"
                + " ORDER BY created_at DESC,id DESC LIMIT 1",
            (rs, n) -> rs.getString(1),
            id)
        .getFirst();
  }

  private String retryTarget(String run) {
    return db.query(
            "SELECT retry_message_id FROM agent_runs WHERE id=?", (rs, n) -> rs.getString(1), run)
        .getFirst();
  }

  private void supersede(String id, List<Map<String, Object>> newGroups) {
    Set<Object> kinds = new HashSet<>();
    newGroups.forEach(g -> kinds.add(g.get("kind")));
    if (kinds.isEmpty()) return;
    for (var message : messages(id)) {
      var groups = (List<?>) message.get("choices");
      boolean changed = false;
      for (Object item : groups) {
        var group = Json.map(item);
        if ("OPEN".equals(group.get("status")) && kinds.contains(group.get("kind"))) {
          group.put("status", "SUPERSEDED");
          changed = true;
        }
      }
      if (changed)
        db.update(
            "UPDATE conversation_messages SET choices_json=? WHERE id=?",
            json.write(groups),
            message.get("id"));
    }
  }

  Map<String, Object> cancel(String id, String run) {
    tx.executeWithoutResult(
        s -> {
          lockOwner(id);
          var c = raw(id);
          if (!run.equals(c.get("activeRunId"))) {
            Integer done =
                db.queryForObject(
                    "SELECT COUNT(*) FROM agent_runs WHERE id=? AND conversation_id=? AND"
                        + " status='CANCELLED'",
                    Integer.class,
                    run,
                    id);
            if (done == 1) return;
            throw ApiException.conflict("该轮创作已结束或已被替代");
          }
          db.update(
              "UPDATE agent_runs SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
              run);
          db.update(
              "UPDATE conversations SET"
                  + " status='IDLE',active_run_id=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
              id);
          if (retryTarget(run) == null)
            insertMessage(id, "assistant", "本次执行已取消，已有作品与版本均已保留。", List.of(), run);
          event(id, run, "run.cancelled", Map.of("message", "本次执行已取消"));
        });
    return detail(id);
  }

  void fail(String id, String run, String worker, String message) {
    tx.executeWithoutResult(
        s -> {
          lock(id);
          if (!currentRun(id, run, worker)) return;
          db.update(
              "UPDATE agent_runs SET status='FAILED',error=?,updated_at=CURRENT_TIMESTAMP(6) WHERE"
                  + " id=?",
              message,
              run);
          db.update(
              "UPDATE conversations SET"
                  + " status='FAILED',active_run_id=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE"
                  + " id=?",
              id);
          if (retryTarget(run) == null)
            insertMessage(id, "assistant", message.strip().replaceAll("[。.!！?？]+$", "") + "。已有消息和作品已保留，可以重试。", List.of(), run);
          event(id, run, "run.failed", Map.of("message", message));
        });
  }

  void saveArtifact(
      String id,
      String kind,
      String instruction,
      Map<String, Object> content,
      Object sources,
      String status) {
    var current = artifact(id);
    String artifactId = current == null ? Json.id() : current.get("id").toString();
    int number = current == null ? 1 : ((Number) current.get("versionNumber")).intValue() + 1;
    if (current == null)
      db.update(
          "INSERT INTO"
              + " artifacts(id,conversation_id,title,content_json,sources_json,version_number,status)"
              + " VALUES(?,?,?,?,?,?,?)",
          artifactId,
          id,
          content.get("title"),
          json.write(content),
          json.write(sources),
          number,
          status);
    else
      db.update(
          "UPDATE artifacts SET"
              + " title=?,content_json=?,sources_json=?,version_number=?,status=?,updated_at=CURRENT_TIMESTAMP(6)"
              + " WHERE id=?",
          content.get("title"),
          json.write(content),
          json.write(sources),
          number,
          status,
          artifactId);
    db.update(
        "INSERT INTO"
            + " artifact_versions(id,artifact_id,version_number,kind,instruction,content_json,sources_json)"
            + " VALUES(?,?,?,?,?,?,?)",
        Json.id(),
        artifactId,
        number,
        kind,
        instruction,
        json.write(content),
        json.write(sources));
  }

  Map<String, Object> manual(String id, Map<String, Object> body) {
    var content = Json.map(body.get("content"));
    ProductionValidator.content(content);
    int base = Json.integer(body, "baseVersion", 0, 1, Integer.MAX_VALUE);
    tx.executeWithoutResult(
        s -> {
          lockOwner(id);
          idle(id);
          var current = requireArtifact(id);
          if (base != ((Number) current.get("versionNumber")).intValue())
            throw ApiException.conflict("作品已更新，请刷新后再保存");
          saveArtifact(id, "MANUAL", "手动编辑", content, current.get("sources"), "DRAFT");
          db.update("UPDATE conversations SET updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", id);
        });
    return detail(id);
  }

  Map<String, Object> restore(String id, String version) {
    tx.executeWithoutResult(
        s -> {
          lockOwner(id);
          idle(id);
          var v =
              versions(id).stream()
                  .filter(item -> version.equals(item.get("id")))
                  .findFirst()
                  .orElseThrow(ApiException::missing);
          saveArtifact(
              id,
              "RESTORED",
              "恢复版本 " + v.get("versionNumber"),
              Json.map(v.get("content")),
              v.get("sources"),
              "DRAFT");
          db.update("UPDATE conversations SET updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", id);
        });
    return detail(id);
  }

  Map<String, Object> finalizeArtifact(String id, Map<String, Object> body) {
    int base = Json.integer(body, "baseVersion", 0, 1, Integer.MAX_VALUE);
    tx.executeWithoutResult(
        s -> {
          lockOwner(id);
          idle(id);
          var current = requireArtifact(id);
          if (base != ((Number) current.get("versionNumber")).intValue())
            throw ApiException.conflict("作品已更新，请刷新后再定稿");
          saveArtifact(
              id,
              "FINALIZED",
              "确认定稿",
              Json.map(current.get("content")),
              current.get("sources"),
              "FINAL");
          db.update("UPDATE conversations SET updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", id);
        });
    return detail(id);
  }

  Map<String, Object> requireArtifact(String id) {
    var artifact = artifact(id);
    if (artifact == null) throw ApiException.conflict("当前还没有可编辑的作品");
    return artifact;
  }

  String export(String id, boolean markdown) {
    owner(id);
    var artifact = requireArtifact(id);
    var p = Json.map(artifact.get("content"));
    StringBuilder out = new StringBuilder();
    String heading = markdown ? "## " : "";
    out.append(markdown ? "# " : "").append(p.get("title")).append("\n\n");
    for (String field : List.of("summary", "positioning", "audienceAnalysis", "titles", "opening"))
      section(
          out,
          heading,
          switch (field) {
            case "summary" -> "作品概述";
            case "positioning" -> "内容定位";
            case "audienceAnalysis" -> "受众分析";
            case "titles" -> "备选标题";
            default -> "开场";
          },
          p.get(field));
    out.append(heading).append("分段脚本\n\n");
    int index = 0;
    for (Object value : (List<?>) p.get("segments")) {
      var segment = Json.map(value);
      out.append(markdown ? "### " : "")
          .append("第 ")
          .append(++index)
          .append(" 段 · ")
          .append(segment.get("duration"))
          .append(" 秒\n");
      String[][] fields = {
        {"visual", "画面"},
        {"narration", "口播"},
        {"subtitle", "字幕"},
        {"shotSize", "景别"},
        {"camera", "运镜"},
        {"action", "动作"},
        {"voiceDirection", "配音指导"},
        {"editing", "剪辑"},
        {"material", "素材"}
      };
      for (var field : fields)
        out.append(field[1]).append("：").append(segment.get(field[0])).append("\n");
      out.append("\n");
    }
    section(out, heading, "结尾", p.get("ending"));
    section(out, heading, "发布文案", p.get("caption"));
    section(out, heading, "标签", p.get("tags"));
    plan(
        out,
        heading,
        "拍摄方案",
        Json.map(p.get("shootingPlan")),
        new String[][] {
          {"approach", "拍摄方式"},
          {"locations", "场地"},
          {"equipment", "设备"},
          {"props", "道具"},
          {"schedule", "拍摄安排"}
        });
    plan(
        out,
        heading,
        "配音方案",
        Json.map(p.get("voiceoverPlan")),
        new String[][] {
          {"approach", "配音方式"},
          {"tone", "语气"},
          {"pace", "语速"},
          {"recordingTips", "录音提示"},
          {"script", "完整配音稿"}
        });
    plan(
        out,
        heading,
        "剪辑方案",
        Json.map(p.get("editingPlan")),
        new String[][] {
          {"approach", "剪辑方式"},
          {"rhythm", "节奏"},
          {"music", "音乐"},
          {"soundEffects", "音效"},
          {"subtitleStyle", "字幕样式"},
          {"steps", "剪辑步骤"}
        });
    section(out, heading, "质量提示", p.get("qualityNotes"));
    out.append(heading).append("参考资料\n\n");
    for (Object value : (List<?>) artifact.get("sources")) {
      var source = Json.map(value);
      out.append("- ")
          .append(source.get("documentName"))
          .append(" ")
          .append(source.get("sourceUrl"))
          .append("\n  ")
          .append(source.get("text"))
          .append("\n");
    }
    return out.toString();
  }

  private static void section(StringBuilder out, String heading, String label, Object value) {
    out.append(heading).append(label).append("\n\n");
    if (value instanceof List<?> list)
      for (Object item : list) out.append("- ").append(item).append("\n");
    else out.append(value).append("\n");
    out.append("\n");
  }

  private static void plan(
      StringBuilder out,
      String heading,
      String label,
      Map<String, Object> plan,
      String[][] fields) {
    out.append(heading).append(label).append("\n\n");
    for (var field : fields) section(out, "", field[1], plan.get(field[0]));
  }

  static class CancelledRun extends RuntimeException {}
}
