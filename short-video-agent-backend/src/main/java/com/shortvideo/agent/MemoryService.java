package com.shortvideo.agent;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class MemoryService {
  final JdbcTemplate db;
  final Json json;
  private final AuthService auth;
  private final AiClient ai;
  private final TransactionTemplate tx;

  MemoryService(JdbcTemplate db, Json json, AuthService auth, AiClient ai, TransactionTemplate tx) {
    this.db = db;
    this.json = json;
    this.auth = auth;
    this.ai = ai;
    this.tx = tx;
  }

  Map<String, Object> profile(String user) {
    return db
        .query(
            "SELECT * FROM user_memories WHERE user_id=?",
            (rs, n) -> {
              var value = new LinkedHashMap<String, Object>();
              value.put("enabled", rs.getBoolean("enabled"));
              value.put("items", json.list(rs.getString("profile_json")));
              value.put("revision", rs.getInt("revision"));
              value.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
              return value;
            },
            user)
        .stream()
        .findFirst()
        .orElseGet(
            () -> new LinkedHashMap<>(Map.of("enabled", true, "items", List.of(), "revision", 0)));
  }

  Map<String, Object> get() {
    var p = profile(auth.current().id());
    p.put(
        "conversations",
        db.query(
            "SELECT id,title,summary_text,updated_at FROM conversations WHERE user_id=? AND"
                + " summary_text IS NOT NULL AND summary_text<>'' ORDER BY updated_at DESC LIMIT"
                + " 100",
            (rs, n) ->
                Map.of(
                    "id",
                    rs.getString(1),
                    "title",
                    rs.getString(2),
                    "summary",
                    rs.getString(3),
                    "updatedAt",
                    rs.getTimestamp(4).toInstant().toString()),
            auth.current().id()));
    return p;
  }

  Map<String, Object> update(Map<String, Object> body) {
    String user = auth.current().id();
    var current = profile(user);
    boolean enabled = Json.bool(body, "enabled", (boolean) current.get("enabled"));
    Object items = body.getOrDefault("items", current.get("items"));
    if (!(items instanceof List<?> list) || list.size() > 30) throw ApiException.bad("画像最多保留 30 条");
    var checked = new ArrayList<Map<String, Object>>();
    for (Object item : (List<?>) items) {
      var entry = Json.map(item);
      String text=Json.required(entry,"text",500), evidence=Json.optional(entry,"evidence","用户编辑",500);
      var old=((List<?>)current.get("items")).stream().map(Json::map)
          .filter(i->text.equals(i.get("text")) && evidence.equals(i.get("evidence"))).findFirst();
      var saved=new LinkedHashMap<String,Object>(Map.of("text",text,"evidence",evidence));
      if(old.isPresent()) saved.putAll(old.get());
      else saved.put("manual",true);
      checked.add(saved);
    }
    tx.executeWithoutResult(
        s -> {
          ensure(user);
          db.update(
              "UPDATE user_memories SET"
                  + " enabled=?,profile_json=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP(6)"
                  + " WHERE user_id=?",
              enabled,
              json.write(checked),
              user);
        });
    return get();
  }

  void clear() {
    String user = auth.current().id();
    tx.executeWithoutResult(
        s -> {
          db.query("SELECT id FROM conversations WHERE user_id=? ORDER BY id FOR UPDATE",(rs,n)->rs.getString(1),user);
          ensure(user);
          db.update(
              "UPDATE user_memories SET"
                  + " profile_json='[]',revision=revision+1,updated_at=CURRENT_TIMESTAMP(6) WHERE"
                  + " user_id=?",
              user);
          // Mark existing turns as processed, so clearing does not immediately recreate old
          // memories.
          db.update(
              "UPDATE conversations SET summary_text=NULL,memory_version=(SELECT COUNT(*) FROM"
                  + " agent_runs r WHERE r.conversation_id=conversations.id AND"
                  + " r.status='COMPLETED') WHERE user_id=?",
              user);
        });
  }

  private void ensure(String user) {
    db.update(
        "INSERT INTO user_memories(user_id,profile_json) SELECT ?,'[]' WHERE NOT EXISTS (SELECT 1"
            + " FROM user_memories WHERE user_id=?)",
        user,
        user);
  }

  private List<String> origins(String user, Map<String,Object> item) {
    if(Boolean.TRUE.equals(item.get("manual")) || "用户编辑".equals(item.get("evidence"))) return List.of();
    if(item.get("conversationIds") instanceof List<?> list) return list.stream().map(Object::toString).toList();
    String evidence=Objects.toString(item.get("evidence"),"");
    if(evidence.isBlank()) return List.of();
    // Backfill provenance for older extracted items from literal user evidence.
    return db.query("SELECT DISTINCT m.conversation_id FROM conversation_messages m JOIN conversations c ON c.id=m.conversation_id WHERE c.user_id=? AND m.role='user' AND LOCATE(?,m.content)>0",(rs,n)->rs.getString(1),user,evidence);
  }

  void forgetConversation(String id) {
    String user=db.queryForObject("SELECT user_id FROM conversations WHERE id=?",String.class,id);
    ensure(user);
    db.query("SELECT user_id FROM user_memories WHERE user_id=? FOR UPDATE",(rs,n)->rs.getString(1),user);
    var kept=new ArrayList<Map<String,Object>>();
    for(Object value:(List<?>)profile(user).get("items")) {
      var item=new LinkedHashMap<String,Object>(Json.map(value));
      var original=origins(user,item);
      var remaining=original.stream().filter(source->!source.equals(id)).toList();
      if(!original.contains(id)) { kept.add(item); continue; }
      if(!remaining.isEmpty()) {item.put("conversationIds",remaining);kept.add(item);}
    }
    db.update("UPDATE user_memories SET profile_json=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP(6) WHERE user_id=?",json.write(kept),user);
    db.update("UPDATE conversations SET summary_text=NULL,memory_version=(SELECT COUNT(*) FROM agent_runs r WHERE r.conversation_id=conversations.id AND r.status='COMPLETED') WHERE id=?",id);
  }

  Map<String, Object> context(String conversation) {
    String user =
        db.queryForObject(
            "SELECT user_id FROM conversations WHERE id=?", String.class, conversation);
    var p = profile(user);
    if (!(boolean) p.get("enabled"))
      return Map.of("enabled", false, "profile", List.of(), "summaries", List.of());
    var summaries =
        db.query(
            "SELECT id,title,summary_text FROM conversations WHERE user_id=? AND id<>? AND"
                + " summary_text IS NOT NULL AND summary_text<>'' ORDER BY updated_at DESC LIMIT"
                + " 30",
            (rs, n) ->
                Map.of("id", rs.getString(1), "title", rs.getString(2), "summary", rs.getString(3)),
            user,
            conversation);
    return Map.of("enabled", true, "profile", p.get("items"), "summaries", summaries);
  }

  List<Map<String, Object>> works(String conversation) {
    String user =
        db.queryForObject(
            "SELECT user_id FROM conversations WHERE id=?", String.class, conversation);
    return db.query(
        "SELECT a.*,c.title AS conversation_title FROM artifacts a JOIN conversations c ON"
            + " c.id=a.conversation_id WHERE c.user_id=? AND c.id<>? ORDER BY a.updated_at DESC"
            + " LIMIT 100",
        (rs, n) -> {
          var content = json.object(rs.getString("content_json"));
          return Map.<String, Object>of(
              "id",
              rs.getString("id"),
              "conversationId",
              rs.getString("conversation_id"),
              "title",
              rs.getString("title"),
              "summary",
              Json.str(content, "summary", ""),
              "content",
              content,
              "updatedAt",
              rs.getTimestamp("updated_at").toInstant().toString());
        },
        user,
        conversation);
  }

  void refreshOne() {
    var candidates =
        db.query(
            "SELECT c.id,c.user_id,c.summary_text,(SELECT COUNT(*) FROM agent_runs r WHERE"
                + " r.conversation_id=c.id AND r.status='COMPLETED') AS turns FROM conversations c"
                + " WHERE c.status<>'RUNNING' AND c.memory_version<(SELECT COUNT(*) FROM agent_runs"
                + " r WHERE r.conversation_id=c.id AND r.status='COMPLETED') ORDER BY c.updated_at"
                + " DESC LIMIT 1",
            (rs, n) ->
                Map.of(
                    "id",
                    rs.getString(1),
                    "user",
                    rs.getString(2),
                    "summary",
                    Optional.ofNullable(rs.getString(3)).orElse(""),
                    "turns",
                    rs.getInt(4)));
    if (candidates.isEmpty()) return;
    var c = candidates.getFirst();
    String id = c.get("id").toString(), user = c.get("user").toString();
    var p = profile(user);
    var assistant =
        db.query(
            "SELECT id,metadata_json FROM conversation_messages WHERE conversation_id=? AND"
                + " role='assistant' ORDER BY created_at DESC,id DESC LIMIT 1",
            (rs, n) ->
                Map.of(
                    "id",
                    rs.getString(1),
                    "metadata",
                    Optional.ofNullable(rs.getString(2)).orElse("{}")),
            id);
    var messages =
        new ArrayList<>(
            db.query(
                "SELECT role,content FROM conversation_messages WHERE conversation_id=? ORDER BY"
                    + " created_at DESC,id DESC LIMIT 30",
                (rs, n) -> Map.of("role", rs.getString(1), "content", rs.getString(2)),
                id));
    Collections.reverse(messages);
    boolean enabled = (boolean) p.get("enabled");
    var result =
        ai.post(
            "/internal/conversation/memory",
            Map.of(
                "messages",
                messages,
                "summary",
                enabled ? c.get("summary") : "",
                "profile",
                enabled ? p.get("items") : List.of()));
    String title = Json.required(result, "title", 80),
        summary = Json.required(result, "summary", 6000);
    var newItems = new ArrayList<Map<String, Object>>();
    if (result.get("profile") instanceof List<?> entries)
      for (Object entry : entries) {
        var item = Json.map(entry);
        String evidence = Json.required(item, "evidence", 500);
        // New facts require a literal quote from a user, never an assistant suggestion.
        boolean quoted =
            messages.stream()
                .anyMatch(m -> m.get("role").equals("user") && m.get("content").contains(evidence));
        String text = Json.required(item, "text", 500);
        boolean existing =
            ((List<?>) p.get("items"))
                .stream()
                    .map(Json::map)
                    .anyMatch(
                        old ->
                            text.equals(old.get("text")) && evidence.equals(old.get("evidence")));
        if ((quoted || existing)
            && newItems.size() < 30
            && !(text + evidence).matches("(?s).*sk-[A-Za-z0-9_-]{8,}.*"))
          {
            var saved=new LinkedHashMap<String,Object>(Map.of("text",text,"evidence",evidence));
            var old=((List<?>)p.get("items")).stream().map(Json::map)
                .filter(i->text.equals(i.get("text")) && evidence.equals(i.get("evidence"))).findFirst();
            var sources=new LinkedHashSet<String>();
            if(old.isPresent()) { saved.putAll(old.get()); sources.addAll(origins(user,old.get())); }
            if(quoted && !Boolean.TRUE.equals(saved.get("manual"))) sources.add(id);
            if(!sources.isEmpty()) saved.put("conversationIds",new ArrayList<>(sources));
            newItems.add(saved);
          }
      }
    tx.executeWithoutResult(
        s -> {
          var current=db.query("SELECT status FROM conversations WHERE id=? FOR UPDATE",(rs,n)->rs.getString(1),id);
          if(current.isEmpty() || current.getFirst().equals("RUNNING")) return;
          Integer turns=db.queryForObject("SELECT COUNT(*) FROM agent_runs WHERE conversation_id=? AND status='COMPLETED'",Integer.class,id);
          if(!Objects.equals(turns,c.get("turns"))) return;
          // A cleared or edited profile invalidates any extraction already in flight.
          if (!profile(user).get("revision").equals(p.get("revision"))) return;
          ensure(user);
          db.query(
              "SELECT user_id FROM user_memories WHERE user_id=? FOR UPDATE",
              (rs, n) -> rs.getString(1),
              user);
          if (!profile(user).get("revision").equals(p.get("revision"))) return;
          String responseSummary = Json.optional(result, "responseSummary", "", 1500);
          if (!responseSummary.isBlank() && !assistant.isEmpty()) {
            var metadata =
                new LinkedHashMap<String, Object>(
                    json.object(assistant.getFirst().get("metadata")));
            metadata.put("summary", responseSummary.replaceAll("sk-[A-Za-z0-9_-]{8,}", "[已隐藏]"));
            db.update(
                "UPDATE conversation_messages SET metadata_json=? WHERE id=?",
                json.write(metadata),
                assistant.getFirst().get("id"));
          }
          if (enabled) {
            db.update(
                "UPDATE user_memories SET"
                    + " profile_json=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP(6) WHERE"
                    + " user_id=?",
                json.write(newItems),
                user);
            db.update(
                "UPDATE conversations SET summary_text=? WHERE id=?",
                summary.replaceAll("sk-[A-Za-z0-9_-]{8,}", "[已隐藏]"),
                id);
          }
          db.update(
              "UPDATE conversations SET memory_version=?,title=CASE WHEN title_manual=FALSE THEN ?"
                  + " ELSE title END WHERE id=?",
              c.get("turns"),
              title.replaceAll("sk-[A-Za-z0-9_-]{8,}", "[已隐藏]"),
              id);
        });
  }
}
