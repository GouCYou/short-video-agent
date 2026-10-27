package com.shortvideo.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.request.*;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:userknowledge;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "app.worker-enabled=false",
      "app.admin-username=",
      "app.admin-password=",
      "app.knowledge-dir=target/test-user-knowledge"
    })
class UserKnowledgeIntegrationTest {
  @Autowired JdbcTemplate db;
  @Autowired Json json;
  @Autowired AuthService auth;
  @Autowired UserKnowledgeService library;
  @Autowired KnowledgeService legacy;
  @Autowired KnowledgeIngestionService ingestion;
  @Autowired ConversationService conversations;
  @MockitoBean StringRedisTemplate redis;
  @MockitoBean AiClient ai;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    db.update("DELETE FROM users");
    var stored = new ConcurrentHashMap<String, String>();
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    doAnswer(
            c -> {
              stored.put(c.getArgument(0), c.getArgument(1));
              return null;
            })
        .when(values)
        .set(anyString(), anyString(), any(Duration.class));
    when(values.get(anyString())).thenAnswer(c -> stored.get(c.getArgument(0)));
    when(values.increment(anyString())).thenReturn(1L);
    when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
    when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    when(ai.health())
        .thenReturn(Map.of("embeddingConfigured", false, "vectorStore", "ok", "mode", "real"));
    when(ai.search(anyMap())).thenReturn(List.of());
  }

  @AfterEach
  void clear() {
    RequestContextHolder.resetRequestAttributes();
  }

  String user(String name) {
    var session =
        auth.register(
            Map.of("email", name + "@example.com", "username", name, "password", "strong-password-123"),
            new MockHttpServletRequest());
    String token = session.get("token").toString();
    use(token);
    return token;
  }

  void use(String token) {
    var request = new MockHttpServletRequest();
    request.setAttribute("account", auth.authenticate(token));
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  @Test
  void defaultsAreEditableRestorableAndIsolatedForEveryRole() {
    String alice = user("knowledge_alice");
    var preset = library.list();
    assertThat(preset).hasSize(3);
    String id = preset.getFirst().get("id").toString();
    String original = library.get(id).get("text").toString();
    assertThat(original).isNotBlank();
    library.update(id, Map.of("text", "自己修改的知识正文"));
    assertThat(library.get(id))
        .containsEntry("text", "自己修改的知识正文")
        .containsEntry("status", "UNINDEXED");
    String custom = library.add(Map.of("name", "我的资料", "text", "自己的领域资料")).get("id").toString();
    library.remove(preset.get(1).get("id").toString());
    assertThat(library.list()).hasSize(3);
    String bob = user("knowledge_bob");
    assertThat(library.list()).hasSize(3);
    assertThatThrownBy(() -> library.get(id)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> library.update(id, Map.of("text", "越权修改")))
        .isInstanceOf(ApiException.class);
    use(alice);
    db.update("UPDATE users SET role='ADMIN' WHERE id=?", auth.current().id());
    use(alice);
    library.restore();
    assertThat(library.list()).hasSize(4);
    assertThat(library.get(id)).containsEntry("text", original);
    assertThat(library.get(custom)).containsEntry("text", "自己的领域资料");
    assertThat(legacy.list()).isEmpty();
    assertThatThrownBy(() -> legacy.requireShared(id)).isInstanceOf(ApiException.class);
    use(bob);
    assertThat(library.list()).hasSize(3);
    verify(ai, never()).delete(anyString());
  }

  @Test
  void retrievalUsesOnlyServerOwnedReadyEnabledDocuments() {
    String alice = user("search_alice");
    String id = library.list().getFirst().get("id").toString();
    db.update("UPDATE documents SET status='READY' WHERE id=?", id);
    user("search_bob");
    String other = library.list().getFirst().get("id").toString();
    db.update("UPDATE documents SET status='READY' WHERE id=?", other);
    use(alice);
    library.search(Map.of("query", "如何开场", "documentIds", List.of(other)));
    verify(ai).search(argThat(body -> Json.map(body).get("documentIds").equals(List.of(id))));
    assertThat(legacy.indexRequest(id)).containsEntry("ownerId", auth.current().id());
    assertThatThrownBy(() -> library.indexAll())
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("向量模型尚未配置");
    library.update(id, Map.of("enabled", false));
    assertThat(library.idsForUser(auth.current().id())).isEmpty();
  }

  @Test
  void bulkImportReportsPartialFailuresAndPortableBackupDeduplicatesPerUser() {
    String alice = user("bulk_alice");
    when(ai.post(eq("/internal/documents/extract"), any()))
        .thenReturn(Map.of("text", "为主体选择柔和侧光，保留真实的环境声音。"));
    var first =
        new org.springframework.mock.web.MockMultipartFile(
            "files", "拍摄指南.docx", "application/octet-stream", new byte[] {1});
    var duplicate =
        new org.springframework.mock.web.MockMultipartFile(
            "files", "重复指南.pdf", "application/pdf", new byte[] {1});
    var invalid =
        new org.springframework.mock.web.MockMultipartFile(
            "files", "unknown.exe", "application/octet-stream", new byte[] {1});
    var result = ingestion.batch(List.of(first, duplicate, invalid), "拍摄方法", 600, 80);
    var items = ((List<?>) result.get("items")).stream().map(Json::map).toList();
    assertThat(items).hasSize(3);
    assertThat(items.get(1)).containsEntry("duplicate", true);
    assertThat(items.getLast()).containsEntry("status", "FAILED");
    String id = items.getFirst().get("documentId").toString();
    assertThat(library.get(id)).containsEntry("chunkSize", 600).containsEntry("chunkOverlap", 80);
    library.update(id, Map.of("enabled", false));
    var disabled = ingestion.batch(List.of(duplicate), "拍摄方法", 600, 80);
    assertThat(Json.map(((List<?>) disabled.get("items")).getFirst()))
        .containsEntry("enabled", false)
        .containsEntry("duplicate", true);
    String exported = json.write(ingestion.export());
    assertThat(exported).doesNotContain("userId", "documentId", "content_hash");
    user("bulk_bob");
    var pack =
        new org.springframework.mock.web.MockMultipartFile(
            "files",
            "知识库.json",
            "application/json",
            exported.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var imported = ingestion.batch(List.of(pack), "通用", 700, 100);
    assertThat((List<?>) imported.get("items")).hasSize(4);
    assertThat(library.list()).hasSize(4);
    ingestion.batch(List.of(pack), "通用", 700, 100);
    assertThat(library.list()).hasSize(4);
    assertThatThrownBy(() -> library.get(id)).isInstanceOf(ApiException.class);
    use(alice);
    assertThat(library.list()).hasSize(4);
  }

  @Test
  void runToolCopiesExactAttachmentAndUserTextOnlyIntoTheRunOwnersLibrary() {
    String alice = user("agent_library");
    String cid = conversations.create(Map.of()).get("id").toString(), file = Json.id();
    db.update(
        "INSERT INTO conversation_attachments(id,conversation_id,name,size_bytes,extracted_text)"
            + " VALUES(?,?,?,?,?)",
        file,
        cid,
        "拍摄方法.docx",
        100,
        "原始文件的全文，保留用户的每一句原文。");
    String run =
        conversations
            .submit(cid, Map.of("content", "将这个文件存入知识库", "attachmentIds", List.of(file)), null)
            .get("runId")
            .toString();
    db.update("UPDATE agent_runs SET status='RUNNING' WHERE id=?", run);
    RequestContextHolder.resetRequestAttributes();
    var saved = ingestion.saveFromRun(run, Map.of("documentId", file, "name", "拍摄方法"));
    String id = saved.get("documentId").toString();
    assertThat(saved).containsEntry("indexingAvailable", false);
    assertThat(ingestion.saveFromRun(run, Map.of("documentId", file, "name", "拍摄方法")))
        .containsEntry("documentId", id)
        .containsEntry("duplicate", true);
    assertThatThrownBy(() -> ingestion.saveFromRun(run, Map.of("documentId", Json.id())))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> ingestion.saveFromRun(run, Map.of("text", "模型编造的新知识", "name", "错误资料")))
        .isInstanceOf(ApiException.class);
    use(alice);
    assertThat(library.get(id))
        .containsEntry("text", "原始文件的全文，保留用户的每一句原文。")
        .containsEntry("originType", "CHAT_FILE");
    conversations.cancel(cid, run);
    assertThatThrownBy(() -> ingestion.saveFromRun(run, Map.of("documentId", file)))
        .isInstanceOf(ApiException.class);
    String next =
        conversations
            .submit(cid, Map.of("content", "请把这段原文加入知识库：固定机位记录咖啡萃取过程。"), null)
            .get("runId")
            .toString();
    db.update("UPDATE agent_runs SET status='RUNNING' WHERE id=?", next);
    String textId =
        ingestion
            .saveFromRun(next, Map.of("text", "固定机位记录咖啡萃取过程。", "name", "咖啡拍摄"))
            .get("documentId")
            .toString();
    assertThat(library.get(textId))
        .containsEntry("originType", "CHAT_TEXT")
        .containsEntry("text", "固定机位记录咖啡萃取过程。");
    user("agent_library_other");
    assertThatThrownBy(() -> library.get(textId)).isInstanceOf(ApiException.class);
  }

  @Test
  void internalKnowledgeWritesRequireTheServiceKey() {
    var mocked = mock(KnowledgeIngestionService.class);
    var controller = new ConversationKnowledgeController(mocked, "private-service-key");
    assertThatThrownBy(() -> controller.save("run", "", Map.of())).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> controller.save("run", "wrong-key", Map.of()))
        .isInstanceOf(ApiException.class);
    verifyNoInteractions(mocked);
  }
}
