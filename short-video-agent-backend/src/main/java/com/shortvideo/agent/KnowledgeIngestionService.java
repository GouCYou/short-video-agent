package com.shortvideo.agent;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
class KnowledgeIngestionService {
  private final UserKnowledgeService library;
  private final AuthService auth;
  private final Json json;
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final KnowledgeService indexing;

  KnowledgeIngestionService(
      UserKnowledgeService library,
      AuthService auth,
      Json json,
      JdbcTemplate db,
      TransactionTemplate tx,
      KnowledgeService indexing) {
    this.library = library;
    this.auth = auth;
    this.json = json;
    this.db = db;
    this.tx = tx;
    this.indexing = indexing;
  }

  Map<String, Object> batch(List<MultipartFile> files, String category, int size, int overlap) {
    if (files.isEmpty() || files.size() > 20) throw ApiException.bad("每次可导入 1–20 个文件");
    if (files.stream().mapToLong(MultipartFile::getSize).sum() > 100L * 1024 * 1024)
      throw ApiException.bad("单次导入总量不能超过 100MB");
    if (size < 200 || size > 2000 || overlap < 0 || overlap > 500 || overlap >= size)
      throw ApiException.bad("分块设置无效");
    var results = new ArrayList<Map<String, Object>>();
    for (var file : files) {
      try {
        if (Optional.ofNullable(file.getOriginalFilename())
            .orElse("")
            .toLowerCase(Locale.ROOT)
            .endsWith(".json")) results.addAll(importPackage(file));
        else {
          var doc = library.upload(file, category, "");
          if (!Boolean.TRUE.equals(doc.get("duplicate"))) {
            doc =
                library.update(
                    doc.get("id").toString(), Map.of("chunkSize", size, "chunkOverlap", overlap));
            doc.put("duplicate", false);
          }
          results.add(receipt(doc, file.getOriginalFilename()));
        }
      } catch (ApiException ex) {
        results.add(
            Map.of(
                "name",
                Optional.ofNullable(file.getOriginalFilename()).orElse("文件"),
                "status",
                "FAILED",
                "error",
                ex.getMessage().replaceFirst("^AI 服务请求失败（[0-9]+）：?", "")));
      }
    }
    boolean available = canIndex();
    for (var result : results) if (result.containsKey("documentId")) autoIndex(result, available);
    return Map.of("items", results, "indexingAvailable", available);
  }

  private Map<String, Object> receipt(Map<String, Object> doc, String fallback) {
    var result = new LinkedHashMap<String, Object>();
    result.put("documentId", doc.get("id"));
    result.put("enabled", doc.get("enabled"));
    result.put("name", doc.getOrDefault("name", fallback));
    result.put("duplicate", Boolean.TRUE.equals(doc.get("duplicate")));
    result.put("status", doc.get("status"));
    return result;
  }

  private boolean canIndex() {
    try {
      var h = library.health();
      return "real".equals(h.get("mode"))
          && Boolean.TRUE.equals(h.get("embeddingConfigured"))
          && "ok".equals(h.get("vectorStore"));
    } catch (ApiException ex) {
      return false;
    }
  }

  private void autoIndex(Map<String, Object> receipt, boolean available) {
    if (available && List.of("UNINDEXED", "FAILED").contains(receipt.get("status")))
      try {
        indexing.reindex(receipt.get("documentId").toString());
        receipt.put("status", "INDEXING");
      } catch (ApiException ex) {
        receipt.put("indexError", ex.getMessage());
      }
  }

  private List<Map<String, Object>> importPackage(MultipartFile file) {
    if (file.getSize() > 20L * 1024 * 1024) throw ApiException.bad("知识包不能超过 20MB");
    Map<String, Object> body;
    try {
      body = json.object(new String(file.getBytes(), StandardCharsets.UTF_8));
    } catch (Exception ex) {
      throw ApiException.bad("知识包不是有效 JSON");
    }
    if (!"zhiying-knowledge".equals(body.get("format"))
        || !Integer.valueOf(1).equals(body.get("version"))
        || !(body.get("documents") instanceof List<?> docs)
        || docs.isEmpty()
        || docs.size() > 100) throw ApiException.bad("请选择知映导出的知识包（最多 100 份资料）");
    var values = ((List<?>) body.get("documents")).stream().map(Json::map).toList();
    if (values.stream().mapToInt(d -> Json.str(d, "text", "").length()).sum() > 2000000)
      throw ApiException.bad("知识包正文总量超过限制");
    var result = new ArrayList<Map<String, Object>>();
    for (var value : values)
      try {
        var doc = library.addForUser(auth.current().id(), value, "IMPORT", null);
        result.add(receipt(doc, "知识资料"));
      } catch (ApiException ex) {
        result.add(
            Map.of(
                "name",
                Json.str(value, "name", "知识资料"),
                "status",
                "FAILED",
                "error",
                ex.getMessage().replaceFirst("^AI 服务请求失败（[0-9]+）：?", "")));
      }
    return result;
  }

  Map<String, Object> export() {
    var docs = new ArrayList<Map<String, Object>>();
    for (var row : library.list()) {
      var full = library.get(row.get("id").toString());
      var doc = new LinkedHashMap<String, Object>();
      for (String key :
          List.of("name", "text", "category", "sourceUrl", "enabled", "chunkSize", "chunkOverlap"))
        doc.put(key, full.get(key));
      docs.add(doc);
    }
    return Map.of("format", "zhiying-knowledge", "version", 1, "documents", docs);
  }

  Map<String, Object> saveFromRun(String run, Map<String, Object> input) {
    var receipt =
        tx.execute(
            status -> {
              var rows =
                  db.query(
                      "SELECT r.request_json,r.conversation_id,c.user_id FROM agent_runs r JOIN"
                          + " conversations c ON c.id=r.conversation_id WHERE r.id=? AND"
                          + " r.status='RUNNING' AND c.active_run_id=r.id AND c.status='RUNNING'"
                          + " FOR UPDATE",
                      (rs, n) ->
                          Map.of(
                              "request",
                              rs.getString(1),
                              "conversation",
                              rs.getString(2),
                              "user",
                              rs.getString(3)),
                      run);
              if (rows.isEmpty()) throw ApiException.conflict("当前对话任务已结束，未写入知识库");
              var row = rows.getFirst();
              var request = json.object(row.get("request"));
              String documentId = Json.optional(input, "documentId", "", 100),
                  text = Json.optional(input, "text", "", 200000),
                  name = Json.optional(input, "name", "", 200);
              String origin = "CHAT_TEXT", originId = row.get("conversation");
              if (!documentId.isBlank()) {
                if (!text.isBlank()) throw ApiException.bad("只能选择文件或文字作为来源");
                var allowed = (List<?>) request.getOrDefault("documents", List.of());
                if (allowed.stream().noneMatch(doc -> documentId.equals(Json.map(doc).get("id"))))
                  throw ApiException.missing();
                var docs =
                    db.query(
                        "SELECT name,extracted_text FROM conversation_attachments WHERE id=? AND"
                            + " conversation_id=?",
                        (rs, n) -> Map.of("name", rs.getString(1), "text", rs.getString(2)),
                        documentId,
                        row.get("conversation"));
                if (docs.isEmpty()) throw ApiException.missing();
                text = docs.getFirst().get("text");
                if (name.isBlank()) name = docs.getFirst().get("name");
                origin = "CHAT_FILE";
                originId = documentId;
              } else {
                String source = text.replace("\r\n", "\n").trim();
                var messages = (List<?>) request.getOrDefault("messages", List.of());
                if (source.isBlank()
                    || messages.stream()
                        .map(Json::map)
                        .noneMatch(
                            m ->
                                "user".equals(m.get("role"))
                                    && Json.str(m, "content", "")
                                        .replace("\r\n", "\n")
                                        .contains(source)))
                  throw ApiException.bad("入库文字须来自用户提供的原文");
                text = source;
                if (name.isBlank()) name = text.substring(0, Math.min(30, text.length()));
              }
              var doc =
                  library.addForUser(
                      row.get("user"),
                      Map.of(
                          "name",
                          name,
                          "text",
                          text,
                          "category",
                          Json.optional(input, "category", "通用", 100)),
                      origin,
                      originId);
              return receipt(doc, name);
            });
    boolean available = canIndex();
    autoIndex(receipt, available);
    receipt.put("indexingAvailable", available);
    return receipt;
  }
}
