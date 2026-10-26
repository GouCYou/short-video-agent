<script setup lang="ts">
import { chatTimestamp, durationText } from "../lib/format";
import WorkspacePanels from "../components/WorkspacePanels.vue";
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import {
  onBeforeRouteLeave,
  onBeforeRouteUpdate,
  useRoute,
  useRouter,
} from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  ArrowUp,
  Check,
  Close,
  Download,
  EditPen,
  Expand,
  Loading,
  Operation,
  Refresh,
  SwitchButton,
  Document,
  Link as LinkIcon,
} from "@element-plus/icons-vue";
import {
  conversationApi,
  packageText,
  toolLabels,
} from "../lib/conversation-api";
import { errorMessage } from "../lib/api";
import { toolSummary } from "../lib/tool-summary";
import type { Source } from "../lib/types";
import { dateTime, safeUrl } from "../lib/format";
import type {
  ArtifactVersion,
  ProductionPackage,
  Selection,
  Attachment,
} from "../lib/conversation-types";
import { useConversation } from "../composables/useConversation";
import ConversationTurn from "../components/ConversationTurn.vue";
import { useWorkspace } from "../composables/useWorkspace";
import {
  PhSparkle as Sparkle,
  PhPaperclip,
  PhX,
  PhFileText,
} from "@phosphor-icons/vue";
import AnimatedDisclosure from "../components/AnimatedDisclosure.vue";
import ProductionDocument from "../components/ProductionDocument.vue";
const route = useRoute(),
  router = useRouter();
const state = useConversation(),
  {
    conversation,
    loading,
    sending,
    cancelling,
    running,
    error,
    streamError,
    pendingText,
    liveEvents,
    delta,
  } = state;
const workspace = useWorkspace(),
  { history, historyError } = workspace;
const rightOpen = ref(!window.matchMedia("(max-width: 1150px)").matches),
  rightTab = ref("execution"),
  composer = ref(""),
  scrollArea = ref<HTMLElement | null>(null),
  composerRef = ref<InstanceType<
    (typeof import("element-plus"))["ElInput"]
  > | null>(null),
  artifactOpen = ref(false),
  artifactSection = ref("timeline"),
  editorActive = ref(false),
  draft = ref<ProductionPackage | null>(null),
  baseVersion = ref(0),
  saving = ref(false),
  versionsOpen = ref(false),
  leftId = ref(""),
  rightId = ref(""),
  uploads = ref<Attachment[]>([]),
  uploading = ref(false),
  fileInput = ref<HTMLInputElement | null>(null);
let disposed = false,
  historyEpoch = 0;
const versions = computed(() =>
    [...(conversation.value?.versions || [])].sort(
      (a, b) => b.versionNumber - a.versionNumber,
    ),
  ),
  leftVersion = computed(() =>
    versions.value.find((v) => v.id === leftId.value),
  ),
  rightVersion = computed(() =>
    versions.value.find((v) => v.id === rightId.value),
  );
const artifact = computed(() => conversation.value?.artifact || null),
  isDemo = computed(() => {
    const latest = [...liveEvents.value]
      .reverse()
      .find((e) => e.type === "run.started" && e.data.mode);
    return latest
      ? latest.data.mode === "demo"
      : !!artifact.value?.content.qualityNotes?.some((note) =>
          /DEMO|演示模式|演示内容/i.test(note),
        );
  });
const sources = computed(() => {
  const found = new Map<string, Source>();
  const add = (items: unknown) => {
    if (!Array.isArray(items)) return;
    for (const item of items) {
      if (
        item &&
        typeof item.chunkId === "string" &&
        typeof item.documentName === "string" &&
        typeof item.text === "string"
      ) {
        found.set(`${item.documentId}:${item.chunkId}`, item as Source);
      }
    }
  };
  for (const event of liveEvents.value) {
    if (
      event.type === "tool.completed" &&
      ["search_knowledge", "read_document"].includes(
        String(event.tool || event.data.tool),
      )
    ) {
      const output = event.data.output as Record<string, unknown> | undefined;
      add(output?.sources);
    }
  }
  add(artifact.value?.sources);
  return [...found.values()];
});
const briefRows = computed(() => {
  const brief = conversation.value?.brief;
  if (!brief) return [];
  const labels: Record<string, string> = {
    topic: "主题",
    direction: "方向",
    audience: "观众",
    duration: "时长",
    platform: "平台",
    style: "风格",
    productionMode: "制作",
    equipment: "设备",
    requirements: "要求",
  };
  return Object.entries(labels).flatMap(([key, label]) => {
    const value = brief[key as keyof typeof brief];
    return value
      ? [
          {
            key,
            label,
            value: String(value) + (key === "duration" ? " 秒" : ""),
          },
        ]
      : [];
  });
});
const tools = computed(() => {
  const map = new Map<
    string,
    {
      id: string;
      runId: string;
      tool: string;
      status: string;
      input: unknown;
      output: unknown;
      durationMs?: number;
      message: string;
    }
  >();
  for (const event of liveEvents.value) {
    if (!event.type.startsWith("tool.")) continue;
    const id = event.toolCallId || String(event.data.toolCallId || event.id),
      tool = event.tool || String(event.data.tool || "");
    const item = map.get(id) || {
      id,
      runId: event.runId,
      tool,
      status: "RUNNING",
      input: null,
      output: null,
      message: "",
    };
    if (event.type === "tool.started") item.input = event.data.input;
    if (event.type === "tool.completed") {
      item.status = "COMPLETED";
      item.output = event.data.output;
      if (typeof event.data.durationMs === "number")
        item.durationMs = event.data.durationMs;
    }
    if (event.type === "tool.failed") {
      item.status = "FAILED";
      item.message = String(event.data.message || "执行失败");
    }
    map.set(id, item);
  }
  for (const item of map.values()) {
    if (item.status !== "RUNNING") continue;
    const stopped = liveEvents.value.some(
      (event) =>
        event.runId === item.runId &&
        [
          "run.cancelled",
          "run.failed",
          "run.completed",
          "run.waiting_input",
        ].includes(event.type),
    );
    if (stopped) {
      item.status = "STOPPED";
      item.message = "已停止";
    }
  }
  return [...map.values()];
});
const runError = computed(() => {
  if (conversation.value?.status !== "FAILED") return "";
  const failure = liveEvents.value
    .filter((event) => event.type === "run.failed")
    .at(-1);
  return String(
    failure?.data.message ||
      failure?.data.error ||
      "本次执行失败，请重新发送消息。",
  );
});
const statusText = computed(() =>
  running.value
    ? "正在执行"
    : conversation.value?.status === "WAITING_INPUT"
      ? "等待选择"
      : conversation.value?.status === "FAILED"
        ? "执行失败"
        : "已就绪",
);
async function loadHistory() {
  const epoch = ++historyEpoch;
  try {
    const list = await conversationApi.list();
    if (!disposed && epoch === historyEpoch) {
      history.value = list;
      historyError.value = "";
    }
  } catch (e) {
    if (!disposed) historyError.value = errorMessage(e);
  }
}
async function loadRoute() {
  const id = typeof route.params.id === "string" ? route.params.id : undefined;
  if (id && conversation.value?.id === id) return;
  artifactOpen.value = false;
  editorActive.value = false;
  await state.load(id);
  if (route.query.work && conversation.value?.artifact) openArtifact();
  uploads.value = [];
  await scrollBottom();
}
onMounted(async () => {
  window.addEventListener("studio:new", newConversation);
  window.addEventListener("studio:history-updated", syncTitle);
  await Promise.all([loadHistory(), loadRoute()]);
});
onUnmounted(() => {
  window.removeEventListener("studio:new", newConversation);
  window.removeEventListener("studio:history-updated", syncTitle);
  disposed = true;
  ++historyEpoch;
});
watch(() => route.params.id, loadRoute);
watch(
  () => [
    conversation.value?.updatedAt,
    conversation.value?.status,
    conversation.value?.title,
    conversation.value?.pinned,
  ],
  () => {
    if (conversation.value) {
      const value = conversation.value;
      history.value = [
        value,
        ...history.value.filter((item) => item.id !== value.id),
      ];
    }
  },
);
watch(
  () => delta.value,
  () => scrollBottom(false),
);
watch(
  () => conversation.value?.messages?.length,
  () => scrollBottom(),
);
watch(
  () => artifact.value?.versionNumber,
  (value, old) => {
    if (value && value !== old && !editorActive.value)
      rightTab.value = "artifact";
  },
);
async function scrollBottom(force = true) {
  await nextTick();
  const element = scrollArea.value;
  if (
    element &&
    (force ||
      element.scrollHeight - element.scrollTop - element.clientHeight < 180)
  )
    element.scrollTop = element.scrollHeight;
}
async function newConversation() {
  if (!(await confirmLeave())) return;
  editorActive.value = false;
  artifactOpen.value = false;
  composer.value = "";
  if (route.path === "/") await state.load();
  else await router.push("/");

  nextTick(() => composerRef.value?.focus());
}
async function send(choice?: Selection) {
  if (running.value || sending.value || uploading.value) return;
  const text = choice ? "" : composer.value.trim();
  if (!choice && !text && !uploads.value.length) return;
  const sent = await state.send(
    text,
    choice,
    (id) => {
      void router.replace(`/conversations/${id}`);
    },
    choice ? [] : uploads.value.map((f) => f.id),
  );
  if (sent) {
    if (!choice) {
      composer.value = "";
      uploads.value = [];
    }
    await loadHistory();
    await scrollBottom();
  }
}
function choose(messageId: string, selection: Omit<Selection, "messageId">) {
  void send({ messageId, ...selection });
}
function keydown(event: KeyboardEvent) {
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    void send();
  }
}
function syncTitle() {
  void state.refresh();
}
async function retryMessage(id: string) {
  await state.retry(id);
  await nextTick();
  document
    .getElementById(`message-${id}`)
    ?.scrollIntoView({ behavior: "smooth", block: "nearest" });
}
function timestampFor(index: number) {
  const all = conversation.value?.messages || [],
    message = all[index];
  if (!message || message.role !== "user") return undefined;
  const previous = all
    .slice(0, index)
    .reverse()
    .find((m) => m.role === "user");
  if (
    previous &&
    Date.parse(message.createdAt) - Date.parse(previous.createdAt) <
      30 * 60 * 1000
  )
    return undefined;
  return chatTimestamp(message.createdAt);
}
async function attachFiles(event: Event) {
  const input = event.target as HTMLInputElement,
    files = [...(input.files || [])];
  input.value = "";
  if (!files.length || uploading.value) return;
  if (uploads.value.length + files.length > 6) {
    ElMessage.warning("每条消息最多上传 6 个文件");
    return;
  }
  uploading.value = true;
  try {
    const id = await state.ensure((id) => {
      void router.replace(`/conversations/${id}`);
    });
    for (const file of files) {
      if (file.size > 20 * 1024 * 1024) {
        ElMessage.warning(`${file.name} 超过 20MB`);
        continue;
      }
      const uploaded = await conversationApi.upload(id, file);
      if (conversation.value?.id === id) uploads.value.push(uploaded);
    }
    await loadHistory();
  } catch (e) {
    ElMessage.error(errorMessage(e));
  } finally {
    uploading.value = false;
  }
}
function dropFiles(event: DragEvent) {
  if (!event.dataTransfer?.files.length || running.value) return;
  void attachFiles({
    target: { files: event.dataTransfer.files, value: "" },
  } as unknown as Event);
}
async function removeUpload(file: Attachment) {
  uploads.value = uploads.value.filter((f) => f.id !== file.id);
  if (conversation.value)
    await conversationApi
      .removeFile(conversation.value.id, file.id)
      .catch(() => {});
}
function openArtifact(edit = false, section = "timeline") {
  artifactSection.value = section;
  if (!artifact.value) return;
  draft.value = JSON.parse(JSON.stringify(artifact.value.content));
  baseVersion.value = artifact.value.versionNumber;
  editorActive.value = edit;
  artifactOpen.value = true;
}
async function confirmLeave() {
  if (!editorActive.value) return true;
  try {
    await ElMessageBox.confirm("尚有未保存的作品修改。", "放弃修改？", {
      confirmButtonText: "放弃修改",
      cancelButtonText: "继续编辑",
      type: "warning",
    });
    return true;
  } catch {
    return false;
  }
}
onBeforeRouteLeave(confirmLeave);
onBeforeRouteUpdate(confirmLeave);
async function closeArtifact(done: () => void) {
  if (await confirmLeave()) {
    editorActive.value = false;
    done();
  }
}
async function saveArtifact() {
  if (!draft.value || !conversation.value || saving.value || running.value)
    return;
  const invalid = validatePackage(draft.value);
  if (invalid) {
    ElMessage.warning(invalid);
    return;
  }
  saving.value = true;
  const id = conversation.value.id;
  try {
    const value = await conversationApi.save(
      id,
      draft.value,
      baseVersion.value,
    );
    if (conversation.value?.id !== id) return;
    state.accept(value);
    draft.value = JSON.parse(JSON.stringify(value.artifact!.content));
    baseVersion.value = value.artifact!.versionNumber;
    editorActive.value = false;
    ElMessage.success("作品已保存为新版本");
  } catch (e) {
    ElMessage.error(errorMessage(e));
  } finally {
    saving.value = false;
  }
}
function showVersions() {
  leftId.value = versions.value[0]?.id || "";
  rightId.value = versions.value[1]?.id || versions.value[0]?.id || "";
  versionsOpen.value = true;
}
async function restore(version: ArtifactVersion) {
  if (!conversation.value) return;
  try {
    await ElMessageBox.confirm(
      `基于 V${version.versionNumber} 创建新版本？`,
      "恢复版本",
      { confirmButtonText: "恢复", cancelButtonText: "取消" },
    );
    state.accept(
      await conversationApi.restore(conversation.value.id, version.id),
    );
    versionsOpen.value = false;
    ElMessage.success("已创建恢复版本");
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorMessage(e));
  }
}
async function finalize() {
  if (!conversation.value || !artifact.value || running.value) return;
  try {
    await ElMessageBox.confirm(
      "将当前作品标记为定稿，并保存一个新版本。",
      "确认定稿",
      { confirmButtonText: "定稿", cancelButtonText: "取消" },
    );
    state.accept(
      await conversationApi.finalize(
        conversation.value.id,
        artifact.value.versionNumber,
      ),
    );
    ElMessage.success("作品已定稿");
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorMessage(e));
  }
}
async function download(format: "markdown" | "txt") {
  if (!conversation.value) return;
  try {
    const blob = await conversationApi.export(conversation.value.id, format),
      url = URL.createObjectURL(blob),
      link = document.createElement("a");
    link.href = url;
    link.download = `${(artifact.value?.title || conversation.value.title).replace(/[\\/:*?"<>|]/g, "_")}.${format === "markdown" ? "md" : "txt"}`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (e) {
    ElMessage.error(errorMessage(e));
  }
}
function versionKind(kind: string) {
  return (
    (
      {
        GENERATED: "生成",
        REVISED: "AI 修改",
        MANUAL: "手动编辑",
        RESTORED: "恢复",
        FINALIZED: "定稿",
      } as Record<string, string>
    )[kind] || kind
  );
}
function validatePackage(value: ProductionPackage) {
  if (
    !value.title.trim() ||
    !value.summary.trim() ||
    !value.opening.trim() ||
    !value.ending.trim() ||
    !value.caption.trim()
  )
    return "请填写标题、摘要、开场、结尾和发布文案。";
  if (
    !value.positioning.trim() ||
    !value.audienceAnalysis.trim() ||
    !value.titles.some((t) => t.trim())
  )
    return "请补充内容定位、受众分析与备选标题。";
  if (!value.segments.length || value.segments.length > 30)
    return "请保留 1–30 个分镜。";
  if (
    value.segments.some(
      (s) =>
        !s.id ||
        !s.visual.trim() ||
        !s.narration.trim() ||
        s.duration <= 0 ||
        s.duration > 600,
    )
  )
    return "请检查每个分镜的画面、口播和时长。";
  if (new Set(value.segments.map((s) => s.id)).size !== value.segments.length)
    return "分镜编号重复，请重新添加对应镜头。";
  if (
    !value.shootingPlan.approach.trim() ||
    !value.voiceoverPlan.approach.trim() ||
    !value.voiceoverPlan.script.trim() ||
    !value.editingPlan.approach.trim()
  )
    return "请补充拍摄方式、配音方式、配音稿与剪辑方式。";
}
</script>
<template>
  <div
    class="conversation-app"
    :class="{
      'execution-collapsed': !rightOpen,
    }"
  >
    <section class="conversation-main">
      <header class="conversation-header">
        <div>
          <WorkspacePanels />
          <h1>{{ conversation?.title || "新对话" }}</h1>
          <el-tag v-if="isDemo" type="warning" effect="plain">演示模式</el-tag>
        </div>
        <el-button
          text
          circle
          :icon="Operation"
          :aria-label="rightOpen ? '收起工作区' : '打开工作区'"
          @click="rightOpen = !rightOpen"
        />
      </header>
      <div
        ref="scrollArea"
        class="conversation-messages"
        v-loading="loading"
        :key="conversation?.id || 'new'"
      >
        <div
          v-if="!conversation?.messages?.length && !pendingText && !loading"
          class="conversation-empty"
        >
          <span class="empty-conversation-icon"
            ><el-icon><Sparkle /></el-icon
          ></span>
          <h2>开始创作</h2>
        </div>
        <ConversationTurn
          v-for="(message, index) in conversation?.messages || []"
          :key="message.id"
          :message="message"
          :events="liveEvents"
          :timestamp="timestampFor(index)"
          :disabled="running || sending"
          can-retry
          :regenerating="
            running && conversation?.retryingMessageId === message.id
          "
          :live-text="
            conversation?.retryingMessageId === message.id ? delta : undefined
          "
          @retry="retryMessage(message.id)"
          @select="choose(message.id, $event)"
        />
        <article v-if="pendingText" class="conversation-message user">
          <div class="message-body">
            <div class="message-content">{{ pendingText }}</div>
          </div>
        </article>
        <article
          v-if="running && !conversation?.retryingMessageId"
          class="conversation-message assistant live-message"
        >
          <div class="message-body">
            <div v-if="delta" class="message-content">
              {{ delta }}<span class="stream-caret"></span>
            </div>
            <div v-else class="run-indicator" role="status">
              <el-icon class="is-loading"><Loading /></el-icon
              ><span>{{
                tools.some((tool) => tool.status === "RUNNING")
                  ? toolLabels[
                      tools.filter((tool) => tool.status === "RUNNING").at(-1)
                        ?.tool || ""
                    ] || "正在执行"
                  : "正在处理"
              }}</span>
            </div>
          </div>
        </article>
        <el-alert
          v-if="error || runError"
          :title="error || runError"
          type="error"
          show-icon
          :closable="false"
          class="conversation-error"
        />
      </div>
      <div
        class="conversation-composer-area"
        @dragover.prevent
        @drop.prevent="dropFiles"
      >
        <div v-if="uploads.length || uploading" class="composer-files">
          <span v-for="file in uploads" :key="file.id"
            ><PhFileText :size="20" /><span>{{ file.name }}</span
            ><button
              class="icon-button"
              :aria-label="'移除 ' + file.name"
              @click="removeUpload(file)"
            >
              <PhX :size="14" /></button></span
          ><span v-if="uploading" role="status" class="file-parsing"
            ><el-icon class="is-loading"><Loading /></el-icon>读取文档中</span
          >
        </div>
        <input
          ref="fileInput"
          type="file"
          hidden
          multiple
          accept=".doc,.docx,.pdf,.ppt,.pptx,.odt,.odp,.rtf,.txt,.md,.csv"
          @change="attachFiles"
        />

        <div
          class="conversation-composer"
          :class="{
            'is-multiline': composer.includes('\n') || composer.length > 80,
          }"
        >
          <button
            class="icon-button attach-button"
            aria-label="上传文档"
            title="上传文档"
            :disabled="uploading || sending || running"
            @click="fileInput?.click()"
          >
            <PhPaperclip :size="22" />
          </button>
          <el-input
            ref="composerRef"
            v-model="composer"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 8 }"
            maxlength="12000"
            placeholder="继续聊聊你的想法"
            aria-label="发送消息"
            :disabled="sending"
            @keydown="keydown"
          />
          <div class="composer-actions">
            <el-button
              v-if="running"
              :icon="SwitchButton"
              class="stop-button"
              :loading="cancelling"
              @click="state.cancel"
              >停止</el-button
            ><el-button
              v-else
              type="primary"
              circle
              :icon="ArrowUp"
              :loading="sending"
              :disabled="(!composer.trim() && !uploads.length) || uploading"
              aria-label="发送"
              @click="send()"
            />
          </div>
        </div>
      </div>
    </section>
    <Transition name="veil"
      ><button
        v-if="rightOpen"
        class="conversation-overlay execution-overlay"
        aria-label="关闭工作区"
        @click="rightOpen = false"
      ></button
    ></Transition>
    <aside
      class="conversation-workspace"
      :class="{ 'is-open': rightOpen }"
      :inert="rightOpen ? undefined : true"
    >
      <div class="workspace-content">
        <header class="right-heading">
          <h2>工作区</h2>
          <el-button
            text
            circle
            :icon="Close"
            aria-label="收起工作区"
            @click="rightOpen = false"
          />
        </header>
        <el-tabs v-model="rightTab" class="right-tabs"
          ><el-tab-pane label="执行" name="execution"
            ><div class="execution-header">
              <span class="execution-status" :class="{ running }"
                ><i></i>{{ statusText }}</span
              ><el-button
                v-if="conversation"
                text
                circle
                :icon="Refresh"
                aria-label="刷新执行状态"
                @click="state.refresh()"
              />
            </div>
            <el-alert
              v-if="streamError"
              title="实时连接重连中，已启用自动刷新。"
              type="warning"
              show-icon
              :closable="false" />
            <div v-if="tools.length" class="tool-timeline">
              <article
                v-for="tool in tools"
                :key="tool.id"
                class="tool-entry"
                :class="tool.status.toLowerCase()"
              >
                <span class="tool-state"
                  ><el-icon v-if="tool.status === 'RUNNING'" class="is-loading"
                    ><Loading /></el-icon
                  ><el-icon v-else-if="tool.status === 'COMPLETED'"
                    ><Check /></el-icon
                  ><el-icon v-else><Close /></el-icon
                ></span>
                <div>
                  <h3>{{ toolLabels[tool.tool] || "执行操作" }}</h3>
                  <p v-if="tool.message" class="tool-failure">
                    {{ tool.message }}
                  </p>
                  <span v-if="tool.durationMs != null" class="tool-duration">{{
                    durationText(tool.durationMs)
                  }}</span>
                  <router-link
                    v-if="
                      tool.tool === 'save_knowledge' &&
                      tool.status === 'COMPLETED'
                    "
                    class="knowledge-tool-link"
                    :to="{
                      path: '/knowledge',
                      query: {
                        document: String(
                          (tool.output as Record<string, unknown>)
                            ?.documentId || '',
                        ),
                      },
                    }"
                    >查看资料</router-link
                  >
                  <AnimatedDisclosure
                    v-if="
                      toolSummary(tool.tool, tool.input, tool.output).length
                    "
                  >
                    <template #summary>查看详情</template>
                    <dl class="tool-summary">
                      <template
                        v-for="(row, index) in toolSummary(
                          tool.tool,
                          tool.input,
                          tool.output,
                        )"
                        :key="index"
                        ><dt>{{ row.label }}</dt>
                        <dd>{{ row.value }}</dd></template
                      >
                    </dl>
                  </AnimatedDisclosure>
                </div>
              </article>
            </div>
            <div v-else class="right-empty">
              <el-icon><Operation /></el-icon>
              <p>暂无执行记录</p>
            </div>
            <AnimatedDisclosure
              v-if="conversation?.brief.topic"
              class="brief-details"
              open
            >
              <template #summary>创意简报</template>
              <dl>
                <template v-for="row in briefRows" :key="row.key"
                  ><dt>{{ row.label }}</dt>
                  <dd>{{ row.value }}</dd></template
                >
              </dl>
            </AnimatedDisclosure>
            <section v-if="artifact" class="artifact-shortcuts">
              <header>
                <h3>制作方案</h3>
                <span>V{{ artifact.versionNumber }}</span
                ><el-button text @click="openArtifact()">展开</el-button>
              </header>
              <button
                v-for="(label, section) in {
                  timeline: '脚本与分镜',
                  shooting: '拍摄方案',
                  voice: '配音方案',
                  editing: '剪辑方案',
                  plan: '内容策划',
                }"
                :key="section"
                @click="openArtifact(false, section)"
              >
                <el-icon><Document /></el-icon><span>{{ label }}</span
                ><el-icon><Expand /></el-icon>
              </button></section></el-tab-pane
          ><el-tab-pane label="作品" name="artifact"
            ><template v-if="artifact"
              ><div class="artifact-heading">
                <span class="artifact-state"
                  >{{ artifact.status === "FINAL" ? "已定稿" : "草稿" }} · V{{
                    artifact.versionNumber
                  }}</span
                ><el-button text :icon="Expand" @click="openArtifact()"
                  >展开</el-button
                >
              </div>
              <ProductionDocument :model-value="artifact.content" compact />
              <div class="artifact-actions">
                <el-button
                  :icon="EditPen"
                  :disabled="running"
                  @click="openArtifact(true)"
                  >编辑</el-button
                ><el-button :disabled="!versions.length" @click="showVersions"
                  >版本</el-button
                ><el-dropdown @command="download"
                  ><el-button :icon="Download">导出</el-button
                  ><template #dropdown
                    ><el-dropdown-menu
                      ><el-dropdown-item command="markdown"
                        >Markdown</el-dropdown-item
                      ><el-dropdown-item command="txt"
                        >TXT 文本</el-dropdown-item
                      ></el-dropdown-menu
                    ></template
                  ></el-dropdown
                ><el-button
                  v-if="artifact.status !== 'FINAL'"
                  type="primary"
                  plain
                  :disabled="running"
                  @click="finalize"
                  >定稿</el-button
                >
              </div></template
            >
            <div v-else class="right-empty">
              <el-icon><Document /></el-icon>
              <p>暂无作品</p>
            </div></el-tab-pane
          ><el-tab-pane label="资料" name="sources"
            ><div
              v-for="(source, index) in sources"
              :key="`${source.chunkId}-${index}`"
              class="conversation-source"
            >
              <h3>{{ source.documentName }}</h3>
              <el-tag
                effect="plain"
                :type="
                  artifact?.sources.some(
                    (item) => item.chunkId === source.chunkId,
                  )
                    ? 'success'
                    : 'info'
                "
                >{{
                  artifact?.sources.some(
                    (item) => item.chunkId === source.chunkId,
                  )
                    ? "已引用"
                    : "检索结果"
                }}</el-tag
              >
              <p>{{ source.text }}</p>
              <a
                v-if="safeUrl(source.sourceUrl)"
                :href="safeUrl(source.sourceUrl)"
                target="_blank"
                rel="noopener noreferrer"
                ><el-icon><LinkIcon /></el-icon>来源</a
              >
            </div>
            <div v-if="!sources.length" class="right-empty">
              <el-icon><Document /></el-icon>
              <p>暂无引用资料</p>
            </div></el-tab-pane
          ></el-tabs
        >
      </div>
    </aside>
    <el-drawer
      v-model="artifactOpen"
      :title="editorActive ? '编辑作品' : artifact?.title || '作品'"
      size="min(1080px, 97vw)"
      :before-close="closeArtifact"
      class="production-drawer"
      ><div class="artifact-drawer-bar">
        <span v-if="artifact" class="artifact-state"
          >{{ artifact.status === "FINAL" ? "已定稿" : "草稿" }} · V{{
            baseVersion
          }}</span
        >
        <div>
          <el-button
            v-if="!editorActive"
            :icon="EditPen"
            :disabled="running"
            @click="editorActive = true"
            >编辑</el-button
          ><el-button
            v-else
            type="primary"
            :loading="saving"
            :disabled="running"
            @click="saveArtifact"
            >保存新版本</el-button
          ><el-button :disabled="editorActive" @click="showVersions"
            >版本</el-button
          ><el-dropdown :disabled="editorActive" @command="download"
            ><el-button :disabled="editorActive" :icon="Download"
              >导出</el-button
            ><template #dropdown
              ><el-dropdown-menu
                ><el-dropdown-item command="markdown">Markdown</el-dropdown-item
                ><el-dropdown-item command="txt"
                  >TXT 文本</el-dropdown-item
                ></el-dropdown-menu
              ></template
            ></el-dropdown
          >
        </div>
      </div>
      <ProductionDocument
        v-if="draft"
        v-model="draft"
        :initial-tab="artifactSection"
        :editable="editorActive" /></el-drawer
    ><el-dialog
      v-model="versionsOpen"
      title="作品版本"
      width="min(1200px, 96vw)"
      top="5vh"
      ><div class="conversation-version-list">
        <button
          v-for="version in versions"
          :key="version.id"
          :class="{ selected: leftId === version.id }"
          @click="leftId = version.id"
        >
          <strong>V{{ version.versionNumber }}</strong
          ><span>{{ versionKind(version.kind) }}</span
          ><span>{{ dateTime(version.createdAt) }}</span>
        </button>
      </div>
      <div class="compare-grid">
        <div>
          <el-select v-model="leftId" aria-label="左侧作品版本"
            ><el-option
              v-for="version in versions"
              :key="version.id"
              :value="version.id"
              :label="`V${version.versionNumber} · ${versionKind(version.kind)}`"
          /></el-select>
          <pre v-if="leftVersion">{{ packageText(leftVersion.content) }}</pre>
        </div>
        <div>
          <el-select v-model="rightId" aria-label="右侧作品版本"
            ><el-option
              v-for="version in versions"
              :key="version.id"
              :value="version.id"
              :label="`V${version.versionNumber} · ${versionKind(version.kind)}`"
          /></el-select>
          <pre v-if="rightVersion">{{ packageText(rightVersion.content) }}</pre>
        </div>
      </div>
      <template #footer
        ><el-button @click="versionsOpen = false">关闭</el-button
        ><el-button
          v-if="leftVersion"
          type="primary"
          :disabled="running || editorActive"
          @click="restore(leftVersion)"
          >恢复 V{{ leftVersion.versionNumber }}</el-button
        ></template
      ></el-dialog
    >
  </div>
</template>
