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
      "spring.datasource.url=jdbc:h2:mem:business;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "app.worker-enabled=false",
      "app.admin-username=",
      "app.admin-password=",
      "app.knowledge-dir=target/test-knowledge"
    })
class BusinessIntegrationTest {
  @Autowired JdbcTemplate db;
  @Autowired AuthService auth;
  @Autowired TaskService tasks;
  @Autowired Json json;
  @Autowired org.springframework.core.env.Environment environment;
  @Autowired org.springframework.transaction.support.TransactionTemplate tx;
  @Autowired KnowledgeService knowledge;
  @MockitoBean StringRedisTemplate redis;
  ValueOperations<String, String> values;
  Map<String, String> stored;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    db.update("DELETE FROM jobs");
    db.update("DELETE FROM tasks");
    db.update("DELETE FROM users");
    db.update("DELETE FROM documents");
    stored = new ConcurrentHashMap<>();
    values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    doAnswer(
            a -> {
              stored.put(a.getArgument(0), a.getArgument(1));
              return null;
            })
        .when(values)
        .set(anyString(), anyString(), any(Duration.class));
    when(values.get(anyString())).thenAnswer(a -> stored.get(a.getArgument(0)));
    when(values.increment(anyString()))
        .thenAnswer(
            a -> {
              String key = a.getArgument(0);
              long count = Long.parseLong(stored.getOrDefault(key, "0")) + 1;
              stored.put(key, Long.toString(count));
              return count;
            });
    when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
    when(redis.delete(anyString())).thenAnswer(a -> stored.remove(a.getArgument(0)) != null);
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.resetRequestAttributes();
  }

  String register(String username) {
    var result =
        auth.register(
            Map.of("email", username + "@example.com", "username", username, "password", "strong-password-123", "nickname", username),
            new MockHttpServletRequest());
    String token = result.get("token").toString();
    use(token);
    return token;
  }

  void use(String token) {
    var request = new MockHttpServletRequest();
    request.setAttribute("account", auth.authenticate(token));
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  Map<String, Object> create(String key) {
    return tasks.create(Map.of("topic", "大学生如何识别虚假兼职", "mode", "GUIDED", "duration", 60), key);
  }

  Map<String, Object> content() {
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("titles", List.of("兼职避坑"));
    c.put("opening", "开场");
    c.put("positioning", "科普");
    c.put("audienceAnalysis", "大学生");
    c.put("outline", List.of("警示"));
    c.put(
        "segments",
        List.of(
            Map.of("duration", 60, "visual", "校园", "narration", "不要预付费用", "subtitle", "警惕预付费")));
    c.put("ending", "结束");
    c.put("caption", "说明");
    c.put("tags", List.of("防诈骗"));
    c.put("qualityNotes", List.of("估计时长"));
    return c;
  }

  @Test
  void sessionsAreOpaqueAndRevokedOnPasswordChangeAndDisable() {
    String token = register("alice");
    assertThat(token).hasSize(43);
    assertThat(stored).doesNotContainKey("sva:session:" + token);
    assertThat(auth.authenticate(token)).isNotNull();
    auth.changePassword(
        Map.of("oldPassword", "strong-password-123", "newPassword", "replacement-password"));
    assertThat(auth.authenticate(token)).isNull();
    var login =
        auth.login(
            Map.of("username", "alice", "password", "replacement-password"),
            new MockHttpServletRequest());
    String next = login.get("token").toString();
    db.update("UPDATE users SET enabled=FALSE WHERE username='alice'");
    assertThat(auth.authenticate(next)).isNull();
  }

  @Test
  void ownerIsolationIdempotencyAndPersistentQueue() {
    String alice = register("alice");
    Map<String, Object> first = create("same-request");
    assertThat(create("same-request").get("id")).isEqualTo(first.get("id"));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM jobs", Integer.class)).isEqualTo(1);
    String id = first.get("id").toString();
    assertThatThrownBy(() -> tasks.retry(id))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("正在执行");
    register("bob");
    assertThat(tasks.list(null, null, false)).isEmpty();
    assertThatThrownBy(() -> tasks.detail(id)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> tasks.export(id, true)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> tasks.delete(id)).isInstanceOf(ApiException.class);
    use(alice);
    assertThat(tasks.detail(id).get("status")).isEqualTo("QUEUED");
  }

  @Test
  void manualVersionsAreImmutableRestoreCreatesVersionAndSourcesSurvive() {
    register("alice");
    String id = create(null).get("id").toString();
    List<Map<String, Object>> sources =
        List.of(
            Map.of(
                "documentId",
                "deleted-document",
                "documentName",
                "官方反诈资料",
                "chunkId",
                "chunk-1",
                "text",
                "不预付费用",
                "score",
                0.9,
                "sourceUrl",
                "https://example.org"));
    db.update(
        "UPDATE tasks SET status='COMPLETED',result=?,sources=? WHERE id=?",
        json.write(content()),
        json.write(sources),
        id);
    Map<String, Object> first = tasks.manual(id, Map.of("content", content()));
    var firstVersion = Json.map(((List<?>) first.get("versions")).getFirst());
    String firstVersionId = firstVersion.get("id").toString();
    Map<String, Object> edited = content();
    edited.put("opening", "修改后的开场");
    tasks.manual(id, Map.of("content", edited));
    var restored = tasks.restore(id, firstVersionId);
    List<?> versions = (List<?>) restored.get("versions");
    assertThat(versions).hasSize(3);
    assertThat(Json.map(restored.get("result")).get("opening")).isEqualTo("开场");
    assertThat(Json.map(Json.map(versions.get(1)).get("content")).get("opening"))
        .isEqualTo("修改后的开场");
    assertThat(restored.get("sources")).isEqualTo(sources);
    assertThat(tasks.export(id, true)).contains("官方反诈资料", "不预付费用");
  }

  @Test
  void guidedSelectionQueuesOnlyOnceAndChecksIndex() {
    register("alice");
    String id = create(null).get("id").toString();
    db.update(
        "UPDATE tasks SET status='WAITING_SELECTION',topics=? WHERE id=?",
        json.write(List.of(Map.of("title", "兼职", "angle", "预付费用", "reason", "适合大学生"))),
        id);
    assertThatThrownBy(() -> tasks.select(id, Map.of("topicIndex", 2)))
        .isInstanceOf(ApiException.class);
    assertThat(tasks.select(id, Map.of("topicIndex", 0)).get("status")).isEqualTo("QUEUED");
    assertThatThrownBy(() -> tasks.select(id, Map.of("topicIndex", 0)))
        .isInstanceOf(ApiException.class);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM jobs", Integer.class)).isEqualTo(2);
  }

  @Test
  void concurrentCreateWithSameIdempotencyKeyDoesNotDuplicateJobs() throws Exception {
    String token = register("alice");
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    var start = new java.util.concurrent.CountDownLatch(1);
    try {
      java.util.concurrent.Callable<String> action =
          () -> {
            use(token);
            start.await();
            try {
              return create("concurrent-request").get("id").toString();
            } finally {
              RequestContextHolder.resetRequestAttributes();
            }
          };
      var first = executor.submit(action);
      var second = executor.submit(action);
      start.countDown();
      assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS))
          .isEqualTo(second.get(10, java.util.concurrent.TimeUnit.SECONDS));
      assertThat(db.queryForObject("SELECT COUNT(*) FROM jobs", Integer.class)).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void workerPersistsSuccessAndActionableFailures() throws Exception {
    register("alice");
    String id = create(null).get("id").toString();
    var ai = mock(AiClient.class);
    var locks = mock(ResourceLocks.class);
    when(locks.acquire(anyString(), anyString())).thenReturn(true);
    when(locks.renew(anyString(), anyString())).thenReturn(true);
    when(ai.post(eq("/internal/generate"), any()))
        .thenReturn(
            Map.of(
                "topics",
                List.of(Map.of("title", "兼职", "angle", "预付费用", "reason", "适合大学生")),
                "sources",
                List.of(),
                "events",
                List.of(),
                "usage",
                Map.of()));
    var worker = new JobWorker(db, json, ai, tasks, knowledge, tx, locks, 1, true);
    try {
      worker.tick();
      awaitState(id, "WAITING_SELECTION");
      assertThat(db.queryForObject("SELECT status FROM jobs WHERE target_id=?", String.class, id))
          .isEqualTo("DONE");
      tasks.select(id, Map.of("topicIndex", 0));
      when(ai.post(eq("/internal/generate"), any()))
          .thenReturn(
              Map.of(
                  "topics", List.of(), "content", content(), "sources", List.of(), "events",
                  List.of(), "usage", Map.of()));
      worker.tick();
      awaitState(id, "COMPLETED");
      assertThat(tasks.versions(id)).hasSize(1);
      assertThat((List<?>) tasks.owned(id).get("topics")).hasSize(1);
      tasks.revise(id, Map.of("instruction", "优化开场", "section", "opening"));
      when(ai.post(eq("/internal/generate"), any()))
          .thenThrow(
              new ApiException(
                  org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "模型 API key 尚未配置"));
      worker.tick();
      awaitState(id, "FAILED");
      assertThat(tasks.owned(id).get("error")).isEqualTo("模型 API key 尚未配置");
      assertThat(tasks.versions(id)).hasSize(1);
    } finally {
      worker.close();
    }
  }

  void awaitState(String id, String expected) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (expected.equals(tasks.owned(id).get("status"))) return;
      Thread.sleep(20);
    }
    assertThat(tasks.owned(id).get("status")).isEqualTo(expected);
  }

  @Test
  void staleRunningJobIsRecoveredFromPersistentLease() {
    register("alice");
    String id = create(null).get("id").toString();
    db.update("UPDATE tasks SET status='RUNNING',stage='PLAN' WHERE id=?", id);
    db.update(
        "UPDATE jobs SET status='RUNNING',worker_id='lost-worker',updated_at=? WHERE target_id=?",
        java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1800)),
        id);
    var worker =
        new JobWorker(
            db,
            json,
            mock(AiClient.class),
            tasks,
            knowledge,
            tx,
            mock(ResourceLocks.class),
            1,
            true);
    try {
      worker.recover();
      assertThat(tasks.owned(id).get("status")).isEqualTo("QUEUED");
      assertThat(db.queryForObject("SELECT status FROM jobs WHERE target_id=?", String.class, id))
          .isEqualTo("QUEUED");
      assertThat((List<?>) tasks.detail(id).get("events"))
          .anySatisfy(event -> assertThat(Json.map(event).get("stage")).isEqualTo("RECOVERY"));
    } finally {
      worker.close();
    }
  }

  @Test
  void fractionalSegmentDurationsAreAcceptedButInvalidNumbersRejected() {
    var c = content();
    c.put(
        "segments",
        List.of(Map.of("duration", 15.5, "visual", "校园", "narration", "口播", "subtitle", "字幕")));
    assertThatCode(() -> TaskService.validateContent(c)).doesNotThrowAnyException();
    c.put(
        "segments",
        List.of(
            Map.of("duration", Double.NaN, "visual", "校园", "narration", "口播", "subtitle", "字幕")));
    assertThatThrownBy(() -> TaskService.validateContent(c)).isInstanceOf(ApiException.class);
  }

  @Test
  void knowledgeReadinessAndMetadataFailuresKeepDatabaseStateConsistent() {
    var ai = mock(AiClient.class);
    var locks = mock(ResourceLocks.class);
    doAnswer(
            call -> {
              java.util.function.Supplier<?> action = call.getArgument(1);
              return action.get();
            })
        .when(locks)
        .with(anyString(), any());
    var service = new KnowledgeService(db, json, ai, tx, locks, "target/test-knowledge");
    var doc = service.text(Map.of("name", "创作资料", "text", "请核实信息来源。", "category", "科普"));
    String id = doc.get("id").toString();
    assertThat(service.categories()).isEmpty();
    assertThatThrownBy(() -> service.reindex(id)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.update(id, Map.of("enabled", false)))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ApiException.class);
    verifyNoInteractions(ai);
    db.update("UPDATE documents SET status='READY',chunk_count=1 WHERE id=?", id);
    assertThat(service.categories()).containsExactly("科普");
    doThrow(new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "AI 服务不可达"))
        .when(ai)
        .put(anyString(), any());
    assertThatThrownBy(() -> service.update(id, Map.of("enabled", false)))
        .isInstanceOf(ApiException.class);
    assertThat(service.get(id).get("enabled")).isEqualTo(true);
    reset(ai);
    assertThat(service.update(id, Map.of("enabled", false)).get("enabled")).isEqualTo(false);
    assertThat(service.categories()).isEmpty();
    assertThat(service.reindex(id).get("status")).isEqualTo("INDEXING");
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE target_type='DOCUMENT' AND target_id=?",
                Integer.class,
                id))
        .isEqualTo(2);
  }

  @Test
  void seedDefaultsDoNotCreateCredentialsOrPromoteExistingUsers() {
    assertThat(db.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isZero();
    register("existing");
    var seed = new AuthService(db, redis, tx, "existing", "configured-admin-password", 7, 10, 3600);
    seed.run(new org.springframework.boot.DefaultApplicationArguments());
    assertThat(auth.byUsername("existing").role()).isEqualTo("USER");
    var blank = new AuthService(db, redis, tx, "", "", 7, 10, 3600);
    blank.run(new org.springframework.boot.DefaultApplicationArguments());
    assertThat(db.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
  }

  @Test
  void nativeTomcatForwardingTrustsPrivateProxyButIgnoresPublicSpoofing() throws Exception {
    var valve = new org.apache.catalina.valves.RemoteIpValve();
    valve.setInternalProxies(
        environment.getRequiredProperty("server.tomcat.remoteip.internal-proxies"));
    valve.setRequestAttributesEnabled(false);
    var next = mock(org.apache.catalina.Valve.class);
    valve.setNext(next);
    var seen = new java.util.ArrayList<String>();
    doAnswer(
            call -> {
              org.apache.catalina.connector.Request request = call.getArgument(0);
              seen.add(request.getRemoteAddr());
              return null;
            })
        .when(next)
        .invoke(any(), any());
    for (String sender : List.of("10.1.2.3", "127.0.0.1", "203.0.113.9")) {
      var connector = new org.apache.catalina.connector.Connector();
      var request = new org.apache.catalina.connector.Request(connector);
      var coyote = new org.apache.coyote.Request();
      request.setCoyoteRequest(coyote);
      request.setRemoteAddr(sender);
      request.setRemoteHost(sender);
      coyote.scheme().setString("http");
      coyote.serverName().setString("localhost");
      coyote.setServerPort(8080);
      coyote.getMimeHeaders().addValue("X-Forwarded-For").setString("198.51.100.5");
      valve.invoke(request, new org.apache.catalina.connector.Response());
    }
    assertThat(seen).containsExactly("198.51.100.5", "198.51.100.5", "203.0.113.9");
  }

  @Test
  void registrationLimitsArePerClientConfigurableAndReportActualRetryDelay() {
    var limited = new AuthService(db, redis, tx, "", "", 7, 2, 60);
    var firstClient = new MockHttpServletRequest();
    firstClient.setRemoteAddr("198.51.100.10");
    // Input errors must not consume a valid registration attempt.
    assertThatThrownBy(() -> limited.register(Map.of("username", "incomplete"), firstClient))
        .isInstanceOf(ApiException.class);
    verify(values, never()).increment(anyString());
    String rateKey = "sva:rate:" + AuthService.hash("register:198.51.100.10");
    when(redis.getExpire(eq(rateKey), eq(java.util.concurrent.TimeUnit.SECONDS))).thenReturn(42L);
    limited.register(Map.of("email", "first_user@example.com", "username", "first_user", "password", "valid-password"), firstClient);
    limited.register(Map.of("email", "second_user@example.com", "username", "second_user", "password", "valid-password"), firstClient);
    ApiException blocked =
        catchThrowableOfType(
            () ->
                limited.register(
                    Map.of("email", "third_user@example.com", "username", "third_user", "password", "valid-password"), firstClient),
            ApiException.class);
    assertThat(blocked.status).isEqualTo(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
    assertThat(blocked.retryAfterSeconds).isEqualTo(42);
    assertThat(blocked.getMessage()).contains("42 秒");
    assertThat(new Errors().api(blocked).getHeaders().getFirst("Retry-After")).isEqualTo("42");
    when(redis.getExpire(eq(rateKey), eq(java.util.concurrent.TimeUnit.SECONDS))).thenReturn(9L);
    ApiException later =
        catchThrowableOfType(
            () ->
                limited.register(
                    Map.of("email", "third_user@example.com", "username", "third_user", "password", "valid-password"), firstClient),
            ApiException.class);
    assertThat(later.retryAfterSeconds).isEqualTo(9);
    // A caller-supplied XFF is not interpreted in the auth service.
    firstClient.addHeader("X-Forwarded-For", "198.51.100.11");
    assertThatThrownBy(
            () ->
                limited.register(
                    Map.of("email", "third_user@example.com", "username", "third_user", "password", "valid-password"), firstClient))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("9 秒");
    var secondClient = new MockHttpServletRequest();
    secondClient.setRemoteAddr("198.51.100.11");
    assertThat(
            limited.register(
                Map.of("email", "third_user@example.com", "username", "third_user", "password", "valid-password"), secondClient))
        .containsKey("token");
    assertThat(
            limited.login(
                Map.of("email", "first_user@example.com", "username", "first_user", "password", "valid-password"), firstClient))
        .containsKey("token");
  }

  @Test
  void manualContentMustRemainCompatibleWithAiSchema() {
    var unknown = content();
    unknown.put("extra", "not allowed");
    assertThatThrownBy(() -> TaskService.validateContent(unknown)).isInstanceOf(ApiException.class);
    var longOpening = content();
    longOpening.put("opening", "x".repeat(5001));
    assertThatThrownBy(() -> TaskService.validateContent(longOpening))
        .isInstanceOf(ApiException.class);
    var titles = content();
    titles.put("titles", java.util.Collections.nCopies(11, "title"));
    assertThatThrownBy(() -> TaskService.validateContent(titles)).isInstanceOf(ApiException.class);
    var segments = content();
    segments.put(
        "segments",
        java.util.Collections.nCopies(31, ((List<?>) segments.get("segments")).getFirst()));
    assertThatThrownBy(() -> TaskService.validateContent(segments))
        .isInstanceOf(ApiException.class);
    var blank = content();
    blank.put("outline", List.of(" "));
    assertThatThrownBy(() -> TaskService.validateContent(blank)).isInstanceOf(ApiException.class);
    register("alice");
    var created =
        tasks.create(Map.of("topic", "主题", "audience", " ", "style", "", "platform", ""), null);
    assertThat(created)
        .containsEntry("audience", "普通观众")
        .containsEntry("style", "自然清晰")
        .containsEntry("platform", "抖音");
  }

  @Test
  void invalidContentAndPasswordsAreRejected() {
    assertThatThrownBy(() -> TaskService.validateContent(Map.of("titles", List.of("x"))))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> auth.validatePassword("short")).isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                auth.register(
                    Map.of("username", "../escape", "password", "strong-password-123"),
                    new MockHttpServletRequest()))
        .isInstanceOf(ApiException.class);
  }
  @Test
  void registrationRequiresValidUniqueNormalizedEmailAndKeepsLegacyLogin() {
    var request = new MockHttpServletRequest();
    var input = new HashMap<String,Object>(Map.of("username","email_user","password","strong-password-123"));
    assertThatThrownBy(() -> auth.register(input,request)).isInstanceOf(ApiException.class).hasMessageContaining("邮箱");
    for (String invalid : List.of("invalid","a@b","a..b@example.com","a@-example.com","a b@example.com")) {
      input.put("email",invalid);
      assertThatThrownBy(() -> auth.register(input,request)).isInstanceOf(ApiException.class).hasMessageContaining("邮箱");
    }
    input.put("email"," Creator@Example.COM ");
    var session=auth.register(input,request);
    assertThat(Json.map(session.get("user"))).containsEntry("email","creator@example.com");
    input.put("username","another_email_user"); input.put("email","CREATOR@EXAMPLE.COM");
    assertThatThrownBy(() -> auth.register(input,request)).isInstanceOf(ApiException.class).hasMessageContaining("邮箱已被注册");
    register("legacy_user");
    db.update("UPDATE users SET email=NULL WHERE username=?","legacy_user");
    var old=auth.login(Map.of("username","legacy_user","password","strong-password-123"),request);
    assertThat(Json.map(old.get("user"))).containsEntry("email","");
  }

}
