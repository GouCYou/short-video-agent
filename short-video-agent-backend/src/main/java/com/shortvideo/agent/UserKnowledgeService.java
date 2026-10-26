package com.shortvideo.agent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
class UserKnowledgeService {
  private final JdbcTemplate db;
  private final Json json;
  private final AuthService auth;
  private final AiClient ai;
  private final KnowledgeService knowledge;
  private final TransactionTemplate tx;
  private final ResourceLocks locks;
  private final Path root;
  private final List<Map<String, Object>> defaults;

  UserKnowledgeService(
      JdbcTemplate db,
      Json json,
      AuthService auth,
      AiClient ai,
      KnowledgeService knowledge,
      TransactionTemplate tx,
      ResourceLocks locks,
      @Value("${app.knowledge-dir}") String directory) {
    this.db = db;
    this.json = json;
    this.auth = auth;
    this.ai = ai;
    this.knowledge = knowledge;
    this.tx = tx;
    this.locks = locks;
    root = Path.of(directory).toAbsolutePath().normalize();
    try (var input = new ClassPathResource("knowledge/defaults.json").getInputStream()) {
      defaults = json.list(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException ex) {
      throw new IllegalStateException("Cannot load default knowledge", ex);
    }
  }

  void ensureDefaults(String user) {
    tx.executeWithoutResult(
        s -> {
          db.query("SELECT id FROM users WHERE id=? FOR UPDATE", (rs, n) -> rs.getString(1), user);
          if (db.queryForObject(
                  "SELECT COUNT(*) FROM user_knowledge_settings WHERE user_id=?",
                  Integer.class,
                  user)
              > 0) return;
          for (var template : defaults) insertTemplate(user, template);
          db.update("INSERT INTO user_knowledge_settings(user_id) VALUES(?)", user);
        });
  }

  private void insertTemplate(String user, Map<String, Object> template) {
    db.update(
        "INSERT INTO"
            + " documents(id,user_id,default_key,name,category,source_url,enabled,status,raw_text,chunks)"
            + " VALUES(?,?,?,?,?,'',TRUE,'UNINDEXED',?,'[]')",
        Json.id(),
        user,
        template.get("key"),
        template.get("name"),
        template.get("category"),
        template.get("text"));
  }

  private void owner(String id) {
    if (db.queryForObject(
            "SELECT COUNT(*) FROM documents WHERE id=? AND user_id=?",
            Integer.class,
            id,
            auth.current().id())
        != 1) throw ApiException.missing();
  }

  List<Map<String, Object>> list() {
    ensureDefaults(auth.current().id());
    return db.query(
        "SELECT * FROM documents WHERE user_id=? ORDER BY CASE WHEN default_key IS NULL THEN 1 ELSE"
            + " 0 END,created_at",
        (rs, n) -> view(rs, false),
        auth.current().id());
  }

  private Map<String, Object> view(java.sql.ResultSet rs, boolean full)
      throws java.sql.SQLException {
    var value = knowledge.view(rs, full);
    value.put("isDefault", rs.getString("default_key") != null);
    value.put("originType", rs.getString("origin_type"));
    value.put("originId", rs.getString("origin_id"));
    value.put("chunkSize", rs.getInt("chunk_size"));
    value.put("chunkOverlap", rs.getInt("chunk_overlap"));
    if (full) value.put("text", Optional.ofNullable(rs.getString("raw_text")).orElse(""));
    return value;
  }

  Map<String, Object> get(String id) {
    owner(id);
    return db.query("SELECT * FROM documents WHERE id=?", (rs, n) -> view(rs, true), id).getFirst();
  }

  Map<String, Object> health() {
    var health = ai.health();
    var safe = new LinkedHashMap<String, Object>();
    for (String key : List.of("embeddingConfigured", "vectorStore", "mode", "statusDetail"))
      if (health.containsKey(key)) safe.put(key, health.get(key));
    return safe;
  }

  private Map<String, Object> getForUser(String user, String id) {
    return db
        .query(
            "SELECT * FROM documents WHERE id=? AND user_id=?", (rs, n) -> view(rs, true), id, user)
        .stream()
        .findFirst()
        .orElseThrow(ApiException::missing);
  }

  Map<String, Object> add(Map<String, Object> body) {
    return addForUser(auth.current().id(), body, "MANUAL", null);
  }

  Map<String, Object> addForUser(
      String user, Map<String, Object> body, String origin, String originId) {
    ensureDefaults(user);
    String text = Json.required(body, "text", 200000).replace("\r\n", "\n").trim(),
        name = Json.required(body, "name", 200);
    String hash = AuthService.hash(text),
        category = Json.optional(body, "category", "通用", 100),
        source = knowledge.url(body, "");
    int size = Json.integer(body, "chunkSize", 700, 200, 2000),
        overlap = Json.integer(body, "chunkOverlap", 100, 0, 500);
    if (overlap >= size) throw ApiException.bad("重叠长度须小于分块长度");
    var existing =
        db.query(
            "SELECT id FROM documents WHERE user_id=? AND (content_hash=? OR raw_text=?)",
            (rs, n) -> rs.getString(1),
            user,
            hash,
            text);
    boolean duplicate = !existing.isEmpty();
    String id = duplicate ? existing.getFirst() : Json.id();
    if (!duplicate)
      try {
        db.update(
            "INSERT INTO"
                + " documents(id,user_id,name,category,source_url,enabled,status,raw_text,chunks,content_hash,origin_type,origin_id,chunk_size,chunk_overlap)"
                + " VALUES(?,?,?,?,?,?,'UNINDEXED',?,'[]',?,?,?,?,?)",
            id,
            user,
            name,
            category.isBlank() ? "通用" : category,
            source,
            Json.bool(body, "enabled", true),
            text,
            hash,
            origin,
            originId,
            size,
            overlap);
      } catch (org.springframework.dao.DuplicateKeyException ex) {
        id =
            db.queryForObject(
                "SELECT id FROM documents WHERE user_id=? AND content_hash=?",
                String.class,
                user,
                hash);
        duplicate = true;
      }
    var result = getForUser(user, id);
    result.put("duplicate", duplicate);
    return result;
  }

  Map<String, Object> upload(MultipartFile file, String category, String source) {
    if (file.isEmpty() || file.getSize() > 20L * 1024 * 1024)
      throw ApiException.bad("请选择 20MB 以内的非空文件");
    String name =
        Optional.ofNullable(file.getOriginalFilename()).orElse("document").replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
    if (name.length() > 200) name = name.substring(name.length() - 200);
    String ext =
        name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT) : "";
    if (!Set.of(
            ".txt", ".md", ".csv", ".docx", ".doc", ".pdf", ".pptx", ".ppt", ".odt", ".odp", ".rtf")
        .contains(ext)) throw ApiException.bad("不支持此文件格式");
    Path path = root.resolve("knowledge-upload-" + Json.id() + ext);
    try {
      Files.createDirectories(root);
      file.transferTo(path);
      var parsed = ai.post("/internal/documents/extract", Map.of("path", path.toString()));
      return addForUser(
          auth.current().id(),
          Map.of(
              "name",
              name,
              "category",
              category == null ? "通用" : category,
              "sourceUrl",
              source == null ? "" : source,
              "text",
              Json.required(parsed, "text", 200000)),
          "FILE",
          name);
    } catch (IOException ex) {
      throw ApiException.bad("文件读取失败");
    } finally {
      try {
        Files.deleteIfExists(path);
      } catch (IOException ignored) {
      }
    }
  }

  Map<String, Object> update(String id, Map<String, Object> body) {
    owner(id);
    return locks.with(
        "DOCUMENT:" + id,
        () -> {
          var doc = get(id);
          knowledge.requireIdle(doc);
          String text = Json.optional(body, "text", Json.str(doc, "text", ""), 200000),
              name = Json.optional(body, "name", doc.get("name").toString(), 200);
          String category = Json.optional(body, "category", doc.get("category").toString(), 100),
              source = knowledge.url(body, doc.get("sourceUrl").toString());
          boolean enabled = Json.bool(body, "enabled", (boolean) doc.get("enabled"));
          if (text.isBlank() || name.isBlank() || category.isBlank())
            throw ApiException.bad("名称、分类和正文不能为空");
          int
              size =
                  Json.integer(
                      body, "chunkSize", ((Number) doc.get("chunkSize")).intValue(), 200, 2000),
              overlap =
                  Json.integer(
                      body, "chunkOverlap", ((Number) doc.get("chunkOverlap")).intValue(), 0, 500);
          if (overlap >= size) throw ApiException.bad("重叠长度须小于分块长度");
          String hash = AuthService.hash(text);
          if (!Boolean.TRUE.equals(doc.get("isDefault"))
              && db.queryForObject(
                      "SELECT COUNT(*) FROM documents WHERE user_id=? AND id<>? AND (content_hash=?"
                          + " OR raw_text=?)",
                      Integer.class,
                      auth.current().id(),
                      id,
                      hash,
                      text)
                  > 0) throw ApiException.conflict("知识库已存在相同正文的资料");
          boolean changed =
              !text.equals(doc.get("text"))
                  || !name.equals(doc.get("name"))
                  || size != ((Number) doc.get("chunkSize")).intValue()
                  || overlap != ((Number) doc.get("chunkOverlap")).intValue();
          if (changed) {
            if (((Number) doc.get("chunkCount")).intValue() > 0)
              ai.delete("/internal/knowledge/" + id);
            db.update(
                "UPDATE documents SET"
                    + " name=?,raw_text=?,category=?,source_url=?,enabled=?,content_hash=?,chunk_size=?,chunk_overlap=?,status='UNINDEXED',chunk_count=0,chunks='[]',error=NULL"
                    + " WHERE id=?",
                name,
                text,
                category,
                source,
                enabled,
                Boolean.TRUE.equals(doc.get("isDefault")) ? null : hash,
                size,
                overlap,
                id);
          } else {
            if (((Number) doc.get("chunkCount")).intValue() > 0)
              ai.put(
                  "/internal/knowledge/" + id,
                  Map.of("enabled", enabled, "category", category, "sourceUrl", source));
            db.update(
                "UPDATE documents SET enabled=?,category=?,source_url=? WHERE id=?",
                enabled,
                category,
                source,
                id);
          }
          return get(id);
        });
  }

  Map<String, Object> reindex(String id) {
    owner(id);
    requireConfigured();
    return knowledge.reindex(id);
  }

  void requireConfigured() {
    var state = health();
    if (!"real".equals(state.get("mode")) || !Boolean.TRUE.equals(state.get("embeddingConfigured")))
      throw ApiException.bad("向量模型尚未配置，暂时无法建立检索索引");
    if (!"ok".equals(state.get("vectorStore"))) throw ApiException.bad("向量服务暂不可用，请稍后重试");
  }

  Map<String, Object> indexAll() {
    requireConfigured();
    int count = 0;
    for (var doc : list())
      if (!"INDEXING".equals(doc.get("status")) && (!"READY".equals(doc.get("status")))) {
        knowledge.reindex(doc.get("id").toString());
        count++;
      }
    return Map.of("queued", count);
  }

  void remove(String id) {
    owner(id);
    if (((Number) get(id).get("chunkCount")).intValue() == 0) {
      locks.with(
          "DOCUMENT:" + id,
          () -> {
            knowledge.requireIdle(get(id));
            db.update("DELETE FROM jobs WHERE target_type='DOCUMENT' AND target_id=?", id);
            db.update("DELETE FROM documents WHERE id=?", id);
            return null;
          });
    } else knowledge.delete(id);
  }

  List<Map<String, Object>> restore() {
    String user = auth.current().id();
    ensureDefaults(user);
    return locks.with(
        "KNOWLEDGE_DEFAULTS:" + user,
        () -> {
          for (var doc : list())
            if (Boolean.TRUE.equals(doc.get("isDefault"))) knowledge.requireIdle(doc);
          for (var template : defaults) {
            var ids =
                db.query(
                    "SELECT id FROM documents WHERE user_id=? AND default_key=?",
                    (rs, n) -> rs.getString(1),
                    user,
                    template.get("key"));
            if (ids.isEmpty()) insertTemplate(user, template);
            else
              update(
                  ids.getFirst(),
                  Map.of(
                      "name",
                      template.get("name"),
                      "text",
                      template.get("text"),
                      "category",
                      template.get("category"),
                      "sourceUrl",
                      "",
                      "enabled",
                      true));
          }
          return list();
        });
  }

  List<String> idsForUser(String user) {
    ensureDefaults(user);
    return db.query(
        "SELECT id FROM documents WHERE user_id=? AND enabled=TRUE AND status='READY'",
        (rs, n) -> rs.getString(1),
        user);
  }

  List<Map<String, Object>> search(Map<String, Object> body) {
    return ai.search(
        Map.of(
            "query",
            Json.required(body, "query", 2000),
            "category",
            Json.optional(body, "category", "", 100),
            "limit",
            Json.integer(body, "limit", 5, 1, 20),
            "documentIds",
            idsForUser(auth.current().id())));
  }

  Map<String, Object> preview(Map<String, Object> body) {
    int size = Json.integer(body, "chunkSize", 700, 200, 2000),
        overlap = Json.integer(body, "chunkOverlap", 100, 0, 500);
    if (overlap >= size) throw ApiException.bad("重叠长度须小于分块长度");
    return ai.post(
        "/internal/knowledge/preview",
        Map.of(
            "text",
            Json.required(body, "text", 200000),
            "chunkSize",
            size,
            "chunkOverlap",
            overlap));
  }
}
