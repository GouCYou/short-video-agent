package com.shortvideo.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.*;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:conversations;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "app.worker-enabled=false",
      "app.admin-username=",
      "app.admin-password=",
      "app.knowledge-dir=target/test-conversation-knowledge"
    })
class ConversationIntegrationTest {
  @Autowired JdbcTemplate db;
  @Autowired Json json;
  @Autowired AuthService auth;
  @Autowired ConversationService service;
  @Autowired TransactionTemplate tx;
  @Autowired CreationSettingsService creationSettings;
  @MockitoBean StringRedisTemplate redis;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    db.update("DELETE FROM conversations");
    db.update("DELETE FROM users");
    var stored = new ConcurrentHashMap<String, String>();
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    doAnswer(
            call -> {
              stored.put(call.getArgument(0), call.getArgument(1));
              return null;
            })
        .when(values)
        .set(anyString(), anyString(), any(Duration.class));
    when(values.get(anyString())).thenAnswer(call -> stored.get(call.getArgument(0)));
    when(values.increment(anyString())).thenReturn(1L);
    when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void deletingConversationRemovesOnlyItsDerivedMemoriesIncludingLegacyItems() {
    user("alice");String alice=auth.current().id(); String id=conversation(); String other=conversation();
    String run=submit(id,"我长期使用手机拍摄");finish(id,run,List.of(),null);
    db.update("INSERT INTO user_memories(user_id,profile_json) VALUES(?,?)",alice,json.write(List.of(
        Map.of("text","手机拍摄","evidence","长期使用手机拍摄"),
        Map.of("text","其他偏好","evidence","其他","conversationIds",List.of(other)),
        Map.of("text","手动设置","evidence","用户编辑","manual",true),
        Map.of("text","共享偏好","evidence","共享","conversationIds",List.of(id,other)))));
    db.update("UPDATE conversations SET summary_text='旧摘要' WHERE id=?",id);
    service.delete(id);
    var items=(List<?>)memory.get().get("items");
    assertThat(items).hasSize(3);
    assertThat(json.write(items)).doesNotContain("手机拍摄",id).contains(other,"手动设置");
    assertThat((List<?>)memory.get().get("conversations")).isEmpty();
  }

  @Test
  void retryClearsDerivedMemoryBeforeBuildingNewContextAndInvalidatesInflightExtraction() {
    user("alice");String alice=auth.current().id();String id=conversation();String run=submit(id,"我的长期设备是手机");finish(id,run,List.of(),null);
    db.update("INSERT INTO user_memories(user_id,profile_json) VALUES(?,?)",alice,json.write(List.of(Map.of("text","手机","evidence","设备是手机","conversationIds",List.of(id)))));
    db.update("UPDATE conversations SET summary_text='过期摘要' WHERE id=?",id);
    String reply=service.messages(id).getLast().get("id").toString();
    var fake=mock(AiClient.class);
    when(fake.post(eq("/internal/conversation/memory"),any())).thenAnswer(invocation->{
      service.retry(id,reply,"retry-memory");
      return Map.of("title","过期标题","summary","过期内容","profile",List.of(Map.of("text","手机","evidence","设备是手机")));
    });
    new MemoryService(db,json,auth,fake,tx).refreshOne();
    assertThat((List<?>)memory.get().get("items")).isEmpty();
    assertThat(service.raw(id).get("summary")).isEqualTo("");
    String active=service.raw(id).get("activeRunId").toString();
    var request=json.object(db.queryForObject("SELECT request_json FROM agent_runs WHERE id=?",String.class,active));
    assertThat(json.write(request.get("memory"))).doesNotContain("手机","过期摘要");
  }

  @Test
  void personalDefaultsAreIsolatedAndConversationOverridesDoNotChangeThem() {
    user("alice");var defaults=ProductionValidator.defaultBrief();defaults.put("duration",90);defaults.put("equipment","手机");
    creationSettings.save(defaults);String id=conversation();
    assertThat(Json.map(service.detail(id).get("brief")).get("duration")).isEqualTo(90);
    var local=new LinkedHashMap<>(defaults);local.put("duration",30);service.updateBrief(id,local);
    assertThat(creationSettings.get().get("duration")).isEqualTo(90);
    creationSettings.reset();assertThat(creationSettings.get().get("duration")).isEqualTo(60);
    assertThat(Json.map(service.detail(id).get("brief")).get("duration")).isEqualTo(30);
    user("bob");assertThat(creationSettings.get().get("equipment")).isEqualTo("");
    assertThatThrownBy(()->service.updateBrief(id,local)).isInstanceOf(ApiException.class);
    assertThatThrownBy(()->service.clearExecutions(id)).isInstanceOf(ApiException.class);
  }

  @Test
  void clearingExecutionVisibilityPreservesEventsAndAllowsNewSteps() {
    user("alice");String id=conversation();String run=submit(id,"先讨论");running(id,run);
    service.live(id,run,"test-worker","tool.started",new LinkedHashMap<>(Map.of("tool","search_news","toolCallId","first")));
    int before=service.events(id,0).size();var cleared=service.clearExecutions(id);
    long cursor=((Number)cleared.get("executionsClearedThrough")).longValue();
    assertThat(cursor).isGreaterThan(0);assertThat(service.events(id,0)).hasSize(before);
    service.live(id,run,"test-worker","tool.started",new LinkedHashMap<>(Map.of("tool","read_news","toolCallId","next")));
    assertThat(service.events(id,cursor)).hasSize(1);
  }

  @Test
  void fourthOptionIsAnActualChoiceAndCountsAreBounded() {
    user("four_options");String id=conversation(),run=submit(id,"给几个方向");
    var options=new ArrayList<Map<String,Object>>();
    for(String label:List.of("A","B","C","D"))options.add(Map.of("label",label,"title","角度"+label,"description","说明"+label,"value","表达"+label));
    finish(id,run,List.of(Map.of("kind","DIRECTION","title","表达方向","options",options)),null);
    var group=Json.map(((List<?>)service.messages(id).getLast().get("choices")).getFirst());
    var option=Json.map(((List<?>)group.get("options")).get(3));
    service.submit(id,Map.of("content","我选择 D：表达D，口语一些","choice",Map.of("messageId",service.messages(id).getLast().get("id"),"groupId",group.get("id"),"optionId",option.get("id"))),null);
    assertThat(Json.map(service.raw(id).get("brief")).get("direction")).isEqualTo("表达D");
    assertThatThrownBy(()->ProductionValidator.choices(List.of(Map.of("kind","TOPIC","title","选题","options",List.of(options.getFirst()))))).isInstanceOf(ApiException.class);
  }

  String user(String name) {
    var response =
        auth.register(
            Map.of("email", name + "@example.com", "username", name, "password", "strong-test-password"),
            new MockHttpServletRequest());
    String token = response.get("token").toString();
    use(token);
    return token;
  }

  void use(String token) {
    var request = new MockHttpServletRequest();
    request.setAttribute("account", auth.authenticate(token));
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  String conversation() {
    return service.create(Map.of()).get("id").toString();
  }

  String submit(String id, String text) {
    return service.submit(id, Map.of("content", text), null).get("runId").toString();
  }

  void running(String id, String run) {
    db.update("UPDATE agent_runs SET status='RUNNING',worker_id='test-worker' WHERE id=?", run);
  }

  Map<String, Object> result(String content, List<?> choices) {
    return Map.of(
        "content",
        content,
        "choices",
        choices,
        "brief",
        ProductionValidator.defaultBrief(),
        "waitingInput",
        !choices.isEmpty(),
        "usage",
        Map.of());
  }

  void finish(String id, String run, List<?> choices, Map<String, Object> staged) {
    running(id, run);
    service.finish(id, run, "test-worker", result("已完成本轮创作", choices), staged);
  }

  static Map<String, Object> group(String kind) {
    return Map.of(
        "kind",
        kind,
        "title",
        "请选择一个方向",
        "options",
        List.of(
            Map.of("label", "A", "title", "角度一", "description", "从身边切入", "value", "方向一"),
            Map.of("label", "B", "title", "角度二", "description", "从误区切入", "value", "方向二"),
            Map.of("label", "C", "title", "角度三", "description", "从实例切入", "value", "方向三")));
  }

  static Map<String, Object> production() {
    var p = new LinkedHashMap<String, Object>();
    p.put("title", "校园兼职科普");
    for (String field :
        List.of("summary", "positioning", "audienceAnalysis", "opening", "ending", "caption"))
      p.put(field, field + "的具体内容");
    p.put("titles", List.of("识别虚假兼职"));
    p.put("tags", List.of("校园"));
    p.put("qualityNotes", List.of("试读确认时长"));
    var segment = new LinkedHashMap<String, Object>();
    segment.put("id", "a5a5af28-6214-4f23-abfa-9a601e211d57");
    segment.put("duration", 60.0);
    for (String field :
        List.of(
            "visual",
            "narration",
            "subtitle",
            "shotSize",
            "camera",
            "action",
            "voiceDirection",
            "editing",
            "material")) segment.put(field, field + "具体指导");
    p.put("segments", List.of(segment));
    p.put(
        "shootingPlan",
        Map.of(
            "approach",
            "手机实拍",
            "locations",
            List.of("宿舍"),
            "equipment",
            List.of("手机"),
            "props",
            List.of(),
            "schedule",
            List.of("先拍开场")));
    p.put(
        "voiceoverPlan",
        Map.of(
            "approach",
            "单人口播",
            "tone",
            "轻松",
            "pace",
            "中速",
            "recordingTips",
            List.of("保持安静"),
            "script",
            "完整连续口播内容"));
    p.put(
        "editingPlan",
        Map.of(
            "approach",
            "按分镜剪辑",
            "rhythm",
            "紧凑",
            "music",
            "轻音乐",
            "soundEffects",
            List.of(),
            "subtitleStyle",
            "清晰字幕",
            "steps",
            List.of("粗剪")));
    return p;
  }

  Map<String, Object> staged() {
    return Map.of(
        "content",
        production(),
        "sources",
        List.of(),
        "kind",
        "GENERATED",
        "instruction",
        "生成完整制作方案");
  }

  @Test
  void newsEvidenceIsRetainedForFollowupAndCannotCrossAccountBoundary() {
    String owner = user("news_owner");
    String id = conversation();
    String run = submit(id, "搜索汽车新闻");
    running(id, run);
    var source = Map.<String,Object>of("documentId", "web-news-one", "documentName", "新闻标题", "chunkId", "web-news-one", "text", "发布时间：2026-10-26T07:20:00Z\n真实新闻片段", "score", 0.0, "sourceUrl", "https://news.cctv.com/story.shtml");
    service.live(id, run, "test-worker", "tool.completed", new LinkedHashMap<>(Map.of("tool", "search_news", "toolCallId", "news-call", "output", Map.of("sources", List.of(source)))));
    assertThat(service.researchSources(id)).hasSize(1);
    assertThat(service.detail(id).get("researchSources").toString()).contains("2026-10-26", "news.cctv.com");
    service.finish(id, run, "test-worker", result("先讨论这条新闻", List.of()), null);
    String next = submit(id, "继续讨论这条新闻");
    var request = json.object(db.queryForObject("SELECT request_json FROM agent_runs WHERE id=?", String.class, next));
    assertThat(request.get("researchSources").toString()).contains("web-news-one", "2026-10-26");
    user("other_news_owner");
    assertThatThrownBy(() -> service.detail(id)).isInstanceOf(ApiException.class);
    assertThat(service.detail(conversation()).get("researchSources")).isEqualTo(List.of());
    use(owner);
  }

  @Test
  void allConversationResourcesAreOwnerBound() {
    String owner = user("alice");
    String id = conversation();
    String run = submit(id, "先讨论选题");
    user("bob");
    assertThat(service.list()).isEmpty();
    assertThatThrownBy(() -> service.detail(id)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.events(id, 0)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.submit(id, Map.of("content", "hello"), null))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.cancel(id, run)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.rename(id, Map.of("title", "forged")))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.export(id, true)).isInstanceOf(ApiException.class);
    use(owner);
    assertThat(service.detail(id).get("activeRunId")).isEqualTo(run);
  }

  @Test
  void canonicalChoicesCustomInputAndStaleSelectionsAreEnforced() {
    user("alice");
    String id = conversation();
    String run = submit(id, "选题");
    finish(id, run, List.of(group("TOPIC")), null);
    var assistant = service.messages(id).getLast();
    var selected = Json.map(((List<?>) assistant.get("choices")).getFirst());
    String messageId = assistant.get("id").toString(), groupId = selected.get("id").toString();
    var forged = Map.of("messageId", messageId, "groupId", groupId, "optionId", Json.id());
    assertThatThrownBy(() -> service.submit(id, Map.of("choice", forged), null))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                service.submit(
                    id,
                    Map.of(
                        "choice",
                        Map.of("messageId", messageId, "groupId", groupId, "customText", " ")),
                    null))
        .isInstanceOf(ApiException.class);
    var choice = Map.of("messageId", messageId, "groupId", groupId, "customText", "宿舍情境演绎");
    String next = service.submit(id, Map.of("choice", choice), "custom-1").get("runId").toString();
    assertThat(Json.map(service.raw(id).get("brief")).get("topic")).isEqualTo("宿舍情境演绎");
    assertThat(service.submit(id, Map.of("choice", choice), "custom-1").get("runId"))
        .isEqualTo(next);
    finish(id, next, List.of(group("DIRECTION")), null);
    assertThat(Json.map(service.raw(id).get("brief")).get("topic")).isEqualTo("宿舍情境演绎");
    assertThatThrownBy(() -> service.submit(id, Map.of("choice", choice), null))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("已选定");
    var direction = service.messages(id).getLast();
    var old = Json.map(((List<?>) direction.get("choices")).getFirst());
    String another = submit(id, "给我新的方向");
    finish(id, another, List.of(group("DIRECTION")), null);
    assertThatThrownBy(
            () ->
                service.submit(
                    id,
                    Map.of(
                        "choice",
                        Map.of(
                            "messageId",
                            direction.get("id"),
                            "groupId",
                            old.get("id"),
                            "optionId",
                            Json.map(((List<?>) old.get("options")).getFirst()).get("id"))),
                    null))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("替代");
  }

  @Test
  void artifactVersionsOptimisticWritesRestoreFinalizeAndExport() {
    user("alice");
    String id = conversation();
    String run = submit(id, "生成完整方案");
    finish(id, run, List.of(), staged());
    var original = service.artifact(id);
    var changed = production();
    changed.put("opening", "手动新开场");
    service.manual(id, Map.of("content", changed, "baseVersion", 1));
    assertThatThrownBy(() -> service.manual(id, Map.of("content", changed, "baseVersion", 1)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("已更新");
    var first = service.versions(id).getLast();
    service.restore(id, first.get("id").toString());
    assertThat(service.artifact(id).get("versionNumber")).isEqualTo(3);
    assertThat(Json.map(service.artifact(id).get("content")).get("opening"))
        .isEqualTo(Json.map(original.get("content")).get("opening"));
    service.finalizeArtifact(id, Map.of("baseVersion", 3));
    assertThat(service.artifact(id).get("status")).isEqualTo("FINAL");
    assertThat(service.versions(id)).hasSize(4);
    assertThat(service.export(id, true))
        .contains("拍摄方案", "配音方案", "剪辑方案", "完整连续口播内容")
        .doesNotContain("null");
  }

  @Test
  void cancelledRunCannotWriteLateEventsMessagesOrArtifacts() {
    user("alice");
    String id = conversation();
    String run = submit(id, "生成方案");
    running(id, run);
    service.live(
        id,
        run,
        "test-worker",
        "tool.started",
        new LinkedHashMap<>(Map.of("tool", "create_production", "toolCallId", "tool-1")));
    service.cancel(id, run);
    int events = service.events(id, 0).size(), messages = service.messages(id).size();
    assertThatThrownBy(
            () ->
                service.live(
                    id,
                    run,
                    "test-worker",
                    "message.delta",
                    new LinkedHashMap<>(Map.of("text", "迟到回复"))))
        .isInstanceOf(ConversationService.CancelledRun.class);
    assertThatThrownBy(
            () -> service.finish(id, run, "test-worker", result("迟到回复", List.of()), staged()))
        .isInstanceOf(ConversationService.CancelledRun.class);
    service.fail(id, run, "test-worker", "迟到异常");
    assertThat(service.artifact(id)).isNull();
    assertThat(service.events(id, 0)).hasSize(events);
    assertThat(service.messages(id)).hasSize(messages);
    String next = submit(id, "重新创作");
    assertThat(next).isNotEqualTo(run);
  }

  @Test
  void workerPersistsLiveEventsBeforeFinalAndRejectsScopeExpansion() throws Exception {
    user("alice");
    String id = conversation();
    String run = submit(id, "生成方案");
    var ai = mock(ConversationAiClient.class);
    var locks = mock(ResourceLocks.class);
    when(locks.acquire(anyString(), anyString())).thenReturn(true);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
        call -> {
          Consumer<Map<String, Object>> emit = call.getArgument(2);
          emit.accept(Map.of("type", "run.started", "data", Map.of("mode", "demo")));
          emit.accept(
              Map.of(
                  "type",
                  "tool.started",
                  "data",
                  Map.of("tool", "create_production", "toolCallId", "t1", "input", Map.of())));
          started.countDown();
          release.await(5, TimeUnit.SECONDS);
          emit.accept(
              Map.of(
                  "type",
                  "artifact.updated",
                  "data",
                  Map.of(
                      "content",
                      production(),
                      "sources",
                      List.of(),
                      "kind",
                      "GENERATED",
                      "scope",
                      "ALL",
                      "instruction",
                      "完整制作")));
          emit.accept(Map.of("type", "final", "data", result("完成", List.of())));
          return null;
        })
        .when(ai)
        .stream(anyString(), any(), any());
    var worker = new ConversationWorker(db, json, service, ai, locks, tx, 1, true);
    try {
      worker.tick();
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(service.events(id, 0))
          .anySatisfy(e -> assertThat(e.get("type")).isEqualTo("tool.started"));
      assertThat(service.artifact(id)).isNull();
      release.countDown();
      awaitIdle(id);
      assertThat(service.artifact(id)).isNotNull();
      String revise = submit(id, "只改开场");
      reset(ai);
      doAnswer(
          call -> {
            Consumer<Map<String, Object>> emit = call.getArgument(2);
            var proposed = production();
            proposed.put("opening", "修改开场");
            proposed.put("caption", "不应改动的发布文案");
            emit.accept(
                Map.of(
                    "type",
                    "tool.started",
                    "data",
                    Map.of(
                        "tool",
                        "revise_production",
                        "toolCallId",
                        "t2",
                        "input",
                        Map.of("scope", "opening"))));
            emit.accept(
                Map.of(
                    "type",
                    "artifact.updated",
                    "data",
                    Map.of(
                        "content",
                        proposed,
                        "sources",
                        List.of(),
                        "kind",
                        "REVISED",
                        "scope",
                        "opening",
                        "instruction",
                        "只改开场")));
            emit.accept(Map.of("type", "final", "data", result("修改完成", List.of())));
            return null;
          })
          .when(ai)
          .stream(anyString(), any(), any());
      worker.tick();
      awaitIdle(id);
      var revised = Json.map(service.artifact(id).get("content"));
      assertThat(revised.get("opening")).isEqualTo("修改开场");
      assertThat(revised.get("caption")).isEqualTo(production().get("caption"));
      assertThat(
              service.events(id, 0).stream().map(e -> ((Number) e.get("id")).longValue()).toList())
          .isSorted();
      long last = ((Number) service.events(id, 0).getLast().get("id")).longValue();
      assertThat(service.events(id, last)).isEmpty();
    } finally {
      release.countDown();
      worker.close();
    }
  }

  @Test
  void staleRunsFailWithoutReexecutingOrOverwritingExistingArtifacts() {
    user("alice");
    String id = conversation();
    String first = submit(id, "制作方案");
    finish(id, first, List.of(), staged());
    String run = submit(id, "下一轮修改");
    running(id, run);
    db.update(
        "UPDATE agent_runs SET updated_at=? WHERE id=?",
        java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1800)),
        run);
    var ai = mock(ConversationAiClient.class);
    var worker =
        new ConversationWorker(db, json, service, ai, mock(ResourceLocks.class), tx, 1, true);
    try {
      worker.recover();
      assertThat(service.raw(id).get("status")).isEqualTo("FAILED");
      assertThat(service.artifact(id).get("versionNumber")).isEqualTo(1);
      assertThat(service.messages(id).getLast().get("content").toString()).contains("服务中断");
      assertThat(db.queryForObject("SELECT status FROM agent_runs WHERE id=?", String.class, run))
          .isEqualTo("FAILED");
      verifyNoInteractions(ai);
    } finally {
      worker.close();
    }
  }

  @Test
  void malformedProductionAndDuplicateSegmentIdsAreRejected() {
    var p = production();
    ProductionValidator.content(p);
    p.put("unknown", "value");
    var unknown = p;
    assertThatThrownBy(() -> ProductionValidator.content(unknown)).isInstanceOf(ApiException.class);
    p = production();
    p.put(
        "segments",
        List.of(
            ((List<?>) p.get("segments")).getFirst(), ((List<?>) p.get("segments")).getFirst()));
    var duplicate = p;
    assertThatThrownBy(() -> ProductionValidator.content(duplicate))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("重复");
  }

  void awaitIdle(String id) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < until) {
      if (!"RUNNING".equals(service.raw(id).get("status"))) return;
      Thread.sleep(20);
    }
    fail("Conversation worker did not finish");
  }

  @Autowired MemoryService memory;

  @Test
  void retryReplacesTheSelectedReplyAndPreservesFollowingMessages() {
    user("retry_owner");
    String id = conversation(), first = submit(id, "第一个问题");
    finish(id, first, List.of(), null);
    var original = service.messages(id).getLast();
    String second = submit(id, "第二个问题");
    finish(id, second, List.of(), null);
    var following = service.messages(id).getLast();
    String retry =
        service.retry(id, original.get("id").toString(), "replace-first").get("runId").toString();
    assertThat(service.raw(id).get("retryingMessageId")).isEqualTo(original.get("id"));
    var request =
        json.object(
            db.queryForObject(
                "SELECT request_json FROM agent_runs WHERE id=?", String.class, retry));
    assertThat((List<?>) request.get("messages")).hasSize(1);
    assertThat((List<?>) request.get("knowledgeDocumentIds")).isEmpty();
    running(id, retry);
    service.finish(id, retry, "test-worker", result("替换后的回答", List.of()), null);
    var rows = service.messages(id);
    assertThat(rows).hasSize(4);
    assertThat(rows.get(1))
        .containsEntry("id", original.get("id"))
        .containsEntry("createdAt", original.get("createdAt"))
        .containsEntry("content", "替换后的回答")
        .containsEntry("runId", retry);
    assertThat(rows.getLast()).isEqualTo(following);
    String cancel =
        service
            .retry(id, original.get("id").toString(), "cancel-replacement")
            .get("runId")
            .toString();
    service.cancel(id, cancel);
    assertThat(service.messages(id)).hasSize(4);
    assertThat(service.messages(id).get(1).get("content")).isEqualTo("替换后的回答");
  }

  @Test
  void pinAttachmentRetryAndPrivateMemory() {
    String token = user("workspace_owner"), id = conversation();
    service.rename(id, Map.of("pinned", true));
    assertThat(service.list().getFirst().get("pinned")).isEqualTo(true);
    String file = Json.id();
    db.update(
        "INSERT INTO conversation_attachments(id,conversation_id,name,size_bytes,extracted_text)"
            + " VALUES(?,?,?,?,?)",
        file,
        id,
        "brief.docx",
        123,
        "摄影设备是手机和三脚架");
    String run =
        service
            .submit(id, Map.of("attachmentIds", List.of(file)), "file-message")
            .get("runId")
            .toString();
    var request =
        json.object(
            db.queryForObject("SELECT request_json FROM agent_runs WHERE id=?", String.class, run));
    assertThat((List<?>) request.get("documents")).hasSize(1);
    finish(id, run, List.of(), null);
    var messages = service.messages(id);
    assertThat((List<?>) messages.getFirst().get("attachments")).hasSize(1);
    assertThat(Json.map(messages.getLast().get("metadata")))
        .containsKeys("thinkingMs", "processingMs");
    String retry =
        service
            .retry(id, messages.getLast().get("id").toString(), "retry-message")
            .get("runId")
            .toString();
    assertThat(service.messages(id)).hasSize(2);
    assertThat(
            service
                .retry(id, messages.getLast().get("id").toString(), "retry-message")
                .get("runId"))
        .isEqualTo(retry);
    finish(id, retry, List.of(), null);
    assertThat(service.messages(id)).hasSize(2);
    assertThat(service.messages(id).getLast().get("id")).isEqualTo(messages.getLast().get("id"));
    memory.update(Map.of("items", List.of(Map.of("text", "喜欢自然风格", "evidence", "用户编辑"))));
    assertThat((List<?>) memory.get().get("items")).hasSize(1);
    user("workspace_other");
    String other = conversation();
    assertThat((List<?>) memory.get().get("items")).isEmpty();
    assertThatThrownBy(
            () ->
                service.submit(
                    other, Map.of("content", "读文件", "attachmentIds", List.of(file)), null))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.rename(id, Map.of("pinned", false)))
        .isInstanceOf(ApiException.class);
    use(token);
    memory.clear();
    assertThat((List<?>) memory.get().get("items")).isEmpty();
  }

  @Test
  void automaticMemoryUsesQuotedUserEvidenceAndPreservesManualTitles() {
    user("memory_creator");
    String id = conversation();
    String run = submit(id, "我长期只用手机拍摄，喜欢自然口语风格");
    finish(id, run, List.of(), null);
    service.rename(id, Map.of("title", "我起的标题"));
    var model = mock(AiClient.class);
    when(model.post(eq("/internal/conversation/memory"), any()))
        .thenReturn(
            Map.of(
                "title",
                "手机自然风格创作",
                "summary",
                "已明确手机拍摄和自然表达偏好。",
                "profile",
                List.of(
                    Map.of("text", "使用手机拍摄", "evidence", "只用手机拍摄"),
                    Map.of("text", "无依据的画像", "evidence", "用户没有说过的话"))));
    var extraction = new MemoryService(db, json, auth, model, tx);
    extraction.refreshOne();
    assertThat(service.raw(id).get("title")).isEqualTo("我起的标题");
    assertThat(service.raw(id).get("summary")).isEqualTo("已明确手机拍摄和自然表达偏好。");
    assertThat((List<?>) extraction.get().get("items")).hasSize(1);
    extraction.clear();
    extraction.refreshOne();
    verify(model, times(1)).post(eq("/internal/conversation/memory"), any());
  }
}
