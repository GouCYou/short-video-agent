package com.shortvideo.agent;

import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
class AttachmentService {
  private final JdbcTemplate db;
  private final AuthService auth;
  private final AiClient ai;
  private final Path root;

  AttachmentService(
      JdbcTemplate db, AuthService auth, AiClient ai, @Value("${app.knowledge-dir}") String root) {
    this.db = db;
    this.auth = auth;
    this.ai = ai;
    this.root = Path.of(root).toAbsolutePath().normalize().resolve("attachments");
  }

  void owner(String conversation) {
    if (db.queryForObject(
            "SELECT COUNT(*) FROM conversations WHERE id=? AND user_id=?",
            Integer.class,
            conversation,
            auth.current().id())
        != 1) throw ApiException.missing();
  }

  Map<String, Object> upload(String conversation, MultipartFile file) {
    owner(conversation);
    if (file.isEmpty() || file.getSize() > 20L * 1024 * 1024)
      throw ApiException.bad("请选择 20MB 以内的非空文件");
    if (db.queryForObject(
            "SELECT COUNT(*) FROM conversation_attachments WHERE conversation_id=?",
            Integer.class,
            conversation)
        >= 50) throw ApiException.bad("每个对话最多上传 50 个文件");
    String name =
        Optional.ofNullable(file.getOriginalFilename()).orElse("document").replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
    if (name.length() > 200) name = name.substring(name.length() - 200);
    String ext =
        name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT) : "";
    if (!Set.of(
            ".docx", ".doc", ".pdf", ".pptx", ".ppt", ".odt", ".odp", ".rtf", ".txt", ".md", ".csv")
        .contains(ext)) throw ApiException.bad("支持 Word、PDF、PowerPoint、ODT、ODP、RTF 和文本文件");
    String id = Json.id();
    Path path = root.resolve(id + ext);
    try {
      Files.createDirectories(root);
      file.transferTo(path);
      var result = ai.post("/internal/documents/extract", Map.of("path", path.toString()));
      String text = Json.required(result, "text", 200000);
      db.update(
          "INSERT INTO conversation_attachments(id,conversation_id,name,size_bytes,extracted_text)"
              + " VALUES(?,?,?,?,?)",
          id,
          conversation,
          name,
          file.getSize(),
          text);
      return Map.of("id", id, "name", name, "size", file.getSize(), "characters", text.length());
    } catch (java.io.IOException ex) {
      throw ApiException.bad("文件保存失败，请重试");
    } finally {
      try {
        Files.deleteIfExists(path);
      } catch (java.io.IOException ignored) {
      }
    }
  }

  List<Map<String, Object>> selected(String conversation, Object value) {
    if (value == null) return List.of();
    if (!(value instanceof List<?> ids) || ids.size() > 6) throw ApiException.bad("每条消息最多附加 6 个文件");
    var result = new ArrayList<Map<String, Object>>();
    var seen = new HashSet<String>();
    for (Object item : (List<?>) value) {
      if (!(item instanceof String id) || !seen.add(id)) throw ApiException.bad("文件标识无效或重复");
      var rows =
          db.query(
              "SELECT id,name,size_bytes,LENGTH(extracted_text) FROM conversation_attachments WHERE"
                  + " id=? AND conversation_id=?",
              (rs, n) ->
                  Map.<String, Object>of(
                      "id",
                      rs.getString(1),
                      "name",
                      rs.getString(2),
                      "size",
                      rs.getLong(3),
                      "characters",
                      rs.getInt(4)),
              id,
              conversation);
      if (rows.isEmpty()) throw ApiException.bad("附件不存在或不属于当前对话");
      result.add(rows.getFirst());
    }
    return result;
  }

  List<Map<String, Object>> context(String conversation, List<Map<String, Object>> messages) {
    var ids = new LinkedHashSet<String>();
    for (var message : messages)
      if (message.get("attachments") instanceof List<?> items)
        for (Object item : items) ids.add(Json.str(Json.map(item), "id", ""));
    var result = new ArrayList<Map<String, Object>>();
    for (String id : ids)
      result.addAll(
          db.query(
              "SELECT id,name,extracted_text FROM conversation_attachments WHERE id=? AND"
                  + " conversation_id=?",
              (rs, n) ->
                  Map.of("id", rs.getString(1), "name", rs.getString(2), "text", rs.getString(3)),
              id,
              conversation));
    return result;
  }

  void remove(String conversation, String id) {
    owner(conversation);
    db.update(
        "DELETE FROM conversation_attachments WHERE id=? AND conversation_id=? AND id NOT IN"
            + " (SELECT id FROM (SELECT a.id FROM conversation_attachments a JOIN"
            + " conversation_messages m ON a.conversation_id=m.conversation_id WHERE"
            + " m.attachments_json LIKE CONCAT('%',a.id,'%')) used_files)",
        id, conversation);
  }
}
