package com.shortvideo.agent;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
class KnowledgeService {
  private final JdbcTemplate db;
  private final Json json;
  private final AiClient ai;
  private final TransactionTemplate tx;
  private final Path root;
  private final ResourceLocks locks;

  KnowledgeService(
      JdbcTemplate db,
      Json json,
      AiClient ai,
      TransactionTemplate tx,
      ResourceLocks locks,
      @Value("${app.knowledge-dir}") String root) {
    this.db = db;
    this.json = json;
    this.ai = ai;
    this.tx = tx;
    this.locks = locks;
    this.root = Path.of(root).toAbsolutePath().normalize();
    try {
      Files.createDirectories(this.root);
    } catch (IOException ex) {
      throw new IllegalStateException("Cannot create knowledge directory", ex);
    }
  }

  Map<String, Object> view(ResultSet rs, boolean chunks) throws SQLException {
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("id", rs.getString("id"));
    doc.put("name", rs.getString("name"));
    doc.put("category", rs.getString("category"));
    doc.put("sourceUrl", rs.getString("source_url"));
    doc.put("enabled", rs.getBoolean("enabled"));
    doc.put("status", rs.getString("status"));
    doc.put("chunkCount", rs.getInt("chunk_count"));
    doc.put("error", rs.getString("error"));
    doc.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
    if (chunks) doc.put("chunks", json.list(rs.getString("chunks")));
    return doc;
  }

  Map<String, Object> get(String id) {
    return db.query("SELECT * FROM documents WHERE id=?", (rs, n) -> view(rs, true), id).stream()
        .findFirst()
        .orElseThrow(ApiException::missing);
  }

  List<Map<String, Object>> list() {
    return db.query(
        "SELECT * FROM documents WHERE user_id IS NULL ORDER BY created_at DESC",
        (rs, n) -> view(rs, false));
  }

  void requireShared(String id) {
    if (db.queryForObject(
            "SELECT COUNT(*) FROM documents WHERE id=? AND user_id IS NULL", Integer.class, id)
        != 1) throw ApiException.missing();
  }

  List<String> categories() {
    return db.query(
        "SELECT DISTINCT category FROM documents WHERE user_id IS NULL AND enabled=TRUE AND"
            + " status='READY' ORDER BY category",
        (rs, n) -> rs.getString(1));
  }

  String url(Map<String, Object> body, String fallback) {
    String url = Json.optional(body, "sourceUrl", fallback, 2000);
    if (!url.isEmpty() && !url.matches("https?://[^\\s]+"))
      throw ApiException.bad("资料来源须为 http 或 https 链接");
    return url;
  }

  Map<String, Object> upload(MultipartFile file, String category, String sourceUrl) {
    if (file.isEmpty() || file.getSize() > 20L * 1024 * 1024)
      throw ApiException.bad("请选择 20MB 以内的非空文件");
    String name =
        Optional.ofNullable(file.getOriginalFilename()).orElse("document").replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
    if (name.length() > 200) name = name.substring(name.length() - 200);
    String ext =
        name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT) : "";
    if (!Set.of(".txt", ".md", ".docx", ".pdf").contains(ext))
      throw ApiException.bad("仅支持 TXT、Markdown、DOCX 和文本型 PDF");
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("category", category == null ? "通用" : category);
    metadata.put("sourceUrl", sourceUrl == null ? "" : sourceUrl);
    String cat = Json.required(metadata, "category", 100), source = url(metadata, "");
    String id = Json.id();
    Path path = root.resolve(id + ext).normalize();
    if (!path.startsWith(root)) throw ApiException.bad("无效文件路径");
    try {
      file.transferTo(path);
      return insert(id, name, cat, source, path.toString(), null);
    } catch (IOException ex) {
      throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "文件保存失败，请检查知识库目录权限");
    } catch (RuntimeException ex) {
      try {
        Files.deleteIfExists(path);
      } catch (IOException ignored) {
      }
      throw ex;
    }
  }

  Map<String, Object> text(Map<String, Object> body) {
    String name = Json.required(body, "name", 200),
        text = Json.required(body, "text", 2_000_000),
        category = Json.optional(body, "category", "通用", 100);
    if (category.isEmpty()) category = "通用";
    return insert(Json.id(), name, category, url(body, ""), null, text);
  }

  Map<String, Object> insert(
      String id, String name, String category, String sourceUrl, String path, String text) {
    tx.executeWithoutResult(
        s -> {
          db.update(
              "INSERT INTO"
                  + " documents(id,name,category,source_url,enabled,status,storage_path,raw_text,chunks)"
                  + " VALUES(?,?,?,?,TRUE,'INDEXING',?,?,'[]')",
              id,
              name,
              category,
              sourceUrl,
              path,
              text);
          enqueue(id);
        });
    return get(id);
  }

  void enqueue(String id) {
    db.update(
        "INSERT INTO jobs(id,target_type,target_id,phase,payload,status)"
            + " VALUES(?,'DOCUMENT',?,'INDEX','{}','QUEUED')",
        Json.id(),
        id);
  }

  void requireIdle(Map<String, Object> doc) {
    if ("INDEXING".equals(doc.get("status"))) throw ApiException.conflict("资料正在索引，请等待完成");
  }

  Map<String, Object> reindex(String id) {
    return locks.with("DOCUMENT:" + id, () -> reindexLocked(id));
  }

  private Map<String, Object> reindexLocked(String id) {
    tx.executeWithoutResult(
        s -> {
          lock(id);
          requireIdle(get(id));
          db.update("UPDATE documents SET status='INDEXING',error=NULL WHERE id=?", id);
          enqueue(id);
        });
    return get(id);
  }

  void lock(String id) {
    if (db.query("SELECT id FROM documents WHERE id=? FOR UPDATE", (rs, n) -> rs.getString(1), id)
        .isEmpty()) throw ApiException.missing();
  }

  Map<String, Object> update(String id, Map<String, Object> body) {
    return locks.with("DOCUMENT:" + id, () -> updateLocked(id, body));
  }

  private Map<String, Object> updateLocked(String id, Map<String, Object> body) {
    Map<String, Object> doc = get(id);
    requireIdle(doc);
    boolean enabled = Json.bool(body, "enabled", (Boolean) doc.get("enabled"));
    String category = Json.optional(body, "category", doc.get("category").toString(), 100);
    if (category.isBlank()) throw ApiException.bad("分类不能为空");
    String source = url(body, doc.get("sourceUrl").toString());
    ai.put(
        "/internal/knowledge/" + id,
        Map.of("enabled", enabled, "category", category, "sourceUrl", source));
    tx.executeWithoutResult(
        s -> {
          lock(id);
          requireIdle(get(id));
          db.update(
              "UPDATE documents SET enabled=?,category=?,source_url=? WHERE id=?",
              enabled,
              category,
              source,
              id);
        });
    return get(id);
  }

  void delete(String id) {
    locks.with(
        "DOCUMENT:" + id,
        () -> {
          deleteLocked(id);
          return null;
        });
  }

  private void deleteLocked(String id) {
    Map<String, Object> doc = get(id);
    requireIdle(doc);
    ai.delete("/internal/knowledge/" + id);
    String path =
        db.queryForObject("SELECT storage_path FROM documents WHERE id=?", String.class, id);
    tx.executeWithoutResult(
        s -> {
          lock(id);
          requireIdle(get(id));
          db.update("DELETE FROM jobs WHERE target_type='DOCUMENT' AND target_id=?", id);
          db.update("DELETE FROM documents WHERE id=?", id);
        });
    if (path != null) {
      try {
        Path file = Path.of(path).toAbsolutePath().normalize();
        if (file.startsWith(root)) Files.deleteIfExists(file);
      } catch (IOException ignored) {
      }
    }
  }

  List<Map<String, Object>> search(Map<String, Object> body) {
    String query = Json.required(body, "query", 2000),
        category = Json.optional(body, "category", "", 100);
    int limit = Json.integer(body, "limit", 5, 1, 20);
    List<Map<String, Object>> sources =
        ai.search(Map.of("query", query, "category", category, "limit", limit));
    return sources.stream()
        .filter(
            src -> {
              String id = Json.str(src, "documentId", "");
              return db.queryForObject(
                      "SELECT COUNT(*) FROM documents WHERE id=? AND user_id IS NULL AND"
                          + " enabled=TRUE AND status='READY'"
                          + (category.isBlank() ? "" : " AND category=?"),
                      Integer.class,
                      category.isBlank() ? new Object[] {id} : new Object[] {id, category})
                  > 0;
            })
        .toList();
  }

  Map<String, Object> indexRequest(String id) {
    return db
        .query(
            "SELECT * FROM documents WHERE id=?",
            (rs, n) -> {
              Map<String, Object> payload = new LinkedHashMap<>();
              payload.put("documentId", id);
              payload.put("chunkSize", rs.getInt("chunk_size"));
              payload.put("chunkOverlap", rs.getInt("chunk_overlap"));
              payload.put("ownerId", Optional.ofNullable(rs.getString("user_id")).orElse(""));
              payload.put("name", rs.getString("name"));
              payload.put("category", rs.getString("category"));
              payload.put("sourceUrl", rs.getString("source_url"));
              payload.put("enabled", rs.getBoolean("enabled"));
              if (rs.getString("storage_path") != null)
                payload.put("path", rs.getString("storage_path"));
              else payload.put("text", rs.getString("raw_text"));
              return payload;
            },
            id)
        .stream()
        .findFirst()
        .orElseThrow(ApiException::missing);
  }
}
