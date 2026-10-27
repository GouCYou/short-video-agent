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
  Loading,
  Operation,
  Refresh,
} from "@element-plus/icons-vue";
import {
  conversationApi,
  packageText,
  toolLabels,
} from "../lib/conversation-api";
import { errorMessage } from "../lib/api";
import StudioConfirm from "../components/StudioConfirm.vue";
import { workspaceToolGroups, workspaceSources } from "../lib/workspace-feed";
import { toolSummary } from "../lib/tool-summary";
import { dateTime } from "../lib/format";
import type {
  ArtifactVersion,
  ProductionPackage,
  Selection,
  Attachment,
} from "../lib/conversation-types";
import { useConversation } from "../composables/useConversation";
import { useChoiceDraft } from "../composables/useChoiceDraft";
import ConversationTurn from "../components/ConversationTurn.vue";
import { useWorkspace } from "../composables/useWorkspace";
import {
  PhSparkle as Sparkle,
  PhPaperclip,
  PhX,
  PhFileText,
  PhStackSimple,
  PhSlidersHorizontal,
  PhTrash,
} from "@phosphor-icons/vue";
import AnimatedDisclosure from "../components/AnimatedDisclosure.vue";
import ProductionCanvas from "../components/ProductionCanvas.vue";
import NewsSourceCard from "../components/NewsSourceCard.vue";
import StudioMenu from "../components/StudioMenu.vue";
import WorkAttachmentPicker from "../components/WorkAttachmentPicker.vue";
import CreationRequirementsEditor from "../components/CreationRequirementsEditor.vue";
import { creationSettingsApi } from "../lib/creation-settings";
import type { Brief } from "../lib/conversation-types";
const workPickerOpen = ref(false);
const requirementsOpen=ref(false), requirementsDraft=ref<Brief>(), requirementsSaving=ref(false), clearingExecutions=ref(false);
async function openRequirements(){
  try{requirementsDraft.value=JSON.parse(JSON.stringify(conversation.value?.brief || await creationSettingsApi.get()));requirementsOpen.value=true;}catch(e){ElMessage.error(errorMessage(e));}
}
async function saveRequirements(){
  if(!requirementsDraft.value || running.value)return;
  requirementsSaving.value=true;
  try{const id=await state.ensure(id=>{void router.replace('/conversations/'+id);});const value=await creationSettingsApi.updateBrief(id,requirementsDraft.value);if(conversation.value?.id!==id)return;state.accept(value);requirementsOpen.value=false;ElMessage.success('当前对话要求已更新');}catch(e){ElMessage.error(errorMessage(e));}finally{requirementsSaving.value=false;}
}
async function resetRequirements(){try{requirementsDraft.value=await creationSettingsApi.get();}catch(e){ElMessage.error(errorMessage(e));}}
const clearConfirmOpen=ref(false),clearTarget=ref('');
function clearExecutions(){
 if(!conversation.value||clearingExecutions.value)return;
 clearTarget.value=conversation.value.id;clearConfirmOpen.value=true;
}
async function confirmClearExecutions(){
 const id=clearTarget.value;
 if(clearingExecutions.value||conversation.value?.id!==id){clearConfirmOpen.value=false;return;}
 clearingExecutions.value=true;
 try{const value=await creationSettingsApi.clearExecutions(id);if(conversation.value?.id===id)state.accept(value);clearConfirmOpen.value=false;}catch(e){ElMessage.error(errorMessage(e));}finally{clearingExecutions.value=false;}
}
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
const choiceDraft=useChoiceDraft(composer);
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
const sources = computed(() => workspaceSources(liveEvents.value,conversation.value?.researchSources,artifact.value?.sources,conversation.value?.executionsClearedThrough));
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
const tools = computed(() => workspaceToolGroups(liveEvents.value,conversation.value?.executionsClearedThrough));
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
const visibleError = computed(() => {
  const message = error.value || runError.value;
  const last = conversation.value?.messages?.at(-1);
  return message && last?.role === "assistant" && last.content.includes(message)
    ? ""
    : message;
});
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
  choiceDraft.clear();
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
  try{choice=choice||choiceDraft.selection();}catch(e){ElMessage.warning(errorMessage(e));return;}
  const text = composer.value.trim();
  if (!choice && !text && !uploads.value.length) return;
  const sent = await state.send(
    text,
    choice,
    (id) => {
      void router.replace(`/conversations/${id}`);
    },
    uploads.value.map((f) => f.id),
  );
  if (sent) {
    {
      composer.value = "";
      uploads.value = [];
      choiceDraft.clear();
    }
    await loadHistory();
    await scrollBottom();
  }
}
function choose(messageId: string, selection: Omit<Selection, "messageId">) {
  const message=conversation.value?.messages.find(m=>m.id===messageId);
  if(!message||running.value||sending.value||!choiceDraft.stage(message,selection))return;
  void nextTick(()=>{composerRef.value?.focus();const input=composerRef.value?.textarea;input?.setSelectionRange(composer.value.length,composer.value.length);});
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
  await uploadFiles(files);
}
async function uploadFiles(files: File[]) {
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
        <button
          type="button"
          class="icon-button workspace-toggle"
          :aria-label="rightOpen ? '收起工作区' : '打开工作区'"
          :title="rightOpen ? '收起工作区' : '打开工作区'"
          :aria-expanded="rightOpen"
          @click="rightOpen = !rightOpen"
        ><PhSlidersHorizontal :size="19" /></button>
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
          :conversation-id="conversation?.id"
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
          v-if="visibleError"
          :title="visibleError"
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
          <StudioMenu :items="[{ id: 'document', label: '上传文档', icon: PhFileText }, { id: 'work', label: '已有作品', icon: PhStackSimple }, {id:'requirements',label:'创作要求',icon:PhSlidersHorizontal}]" label="添加附件" @select="(action) => action === 'requirements' ? openRequirements() : action === 'work' ? workPickerOpen = true : fileInput?.click()">
          <button
            class="icon-button attach-button"
            aria-label="添加附件"
            title="添加附件"
            :disabled="uploading || sending || running"
          >
            <PhPaperclip :size="22" />
          </button>
          </StudioMenu>
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
            <button
              v-if="running"
              type="button"
              class="composer-stop-button"
              aria-label="停止生成"
              :aria-busy="cancelling"
              :disabled="cancelling"
              @click="state.cancel"
            >
              <span class="stop-square" aria-hidden="true"></span>
            </button>
            <el-button
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
    <StudioConfirm v-model="clearConfirmOpen" title="清空工作区记录？" description="执行记录和历史搜到的新闻将一起清空。" note="聊天与作品会保留。" :busy="clearingExecutions" @confirm="confirmClearExecutions" />
    <WorkAttachmentPicker v-model="workPickerOpen" @select="file => uploadFiles([file])" />
    <el-dialog v-model="requirementsOpen" title="当前对话的创作要求" width="min(760px,94vw)" :close-on-click-modal="!requirementsSaving">
      <CreationRequirementsEditor v-if="requirementsDraft" v-model="requirementsDraft" current-conversation :disabled="requirementsSaving || running" />
      <template #footer><el-button :disabled="requirementsSaving" @click="resetRequirements">恢复个人默认</el-button><el-button :disabled="requirementsSaving" @click="requirementsOpen=false">取消</el-button><el-button type="primary" :loading="requirementsSaving" :disabled="running" @click="saveRequirements">应用到当前对话</el-button></template>
    </el-dialog>
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
          <div class="workspace-heading-actions" role="group" aria-label="工作区操作">
          <button class="icon-button" aria-label="清空执行记录" title="清空执行记录" :disabled="(!tools.length && !sources.length) || clearingExecutions" @click="clearExecutions"><PhTrash :size="19" /></button>
          <el-button
            v-if="conversation"
            text
            circle
            :icon="Refresh"
            aria-label="刷新执行记录"
            title="刷新执行记录"
            @click="state.refresh()"
          />
          <el-button
            text
            circle
            :icon="Close"
            aria-label="收起工作区"
            title="收起工作区"
            @click="rightOpen = false"
          />
          </div>
        </header>
        <div class="workspace-feed">
          <el-alert
            v-if="streamError"
            title="连接正在恢复"
            type="warning"
            :closable="false"
          />
          <article
            v-for="tool in tools"
            :key="tool.id"
            class="workspace-tool-card"
            :class="tool.status.toLowerCase()"
          >
            <div class="workspace-tool-line">
              <span class="workspace-tool-state"
                ><el-icon v-if="tool.status === 'RUNNING'" class="is-loading"
                  ><Loading /></el-icon
                ><el-icon v-else-if="tool.status === 'COMPLETED'"
                  ><Check /></el-icon
                ><el-icon v-else><Close /></el-icon></span
              ><strong>{{ toolLabels[tool.tool] || "执行操作" }}</strong
              ><span v-if="tool.durationMs != null" class="tool-duration">{{
                durationText(tool.durationMs)
              }}</span>
            </div>
            <p v-if="tool.message" class="tool-failure">{{ tool.message }}</p>
            <router-link
              v-if="
                tool.tool === 'save_knowledge' && tool.status === 'COMPLETED'
              "
              class="knowledge-tool-link"
              :to="{
                path: '/knowledge',
                query: {
                  document: String(
                    (tool.output as Record<string, unknown>)?.documentId || '',
                  ),
                },
              }"
              >查看资料</router-link
            >
            <AnimatedDisclosure><template #summary>查看详情</template>
              <section v-for="(call,index) in tool.calls" :key="call.id" class="tool-call-detail">
                <header v-if="tool.calls.length>1"><span>第 {{index+1}} 次</span><span>{{({RUNNING:'执行中',COMPLETED:'已完成',FAILED:'失败',STOPPED:'已停止'})[call.status as 'RUNNING'|'COMPLETED'|'FAILED'|'STOPPED']}}</span></header>
                <p v-if="call.message" class="tool-failure">{{call.message}}</p>
                <dl class="tool-summary"><template v-for="(row,index) in toolSummary(call.tool,call.input,call.output)" :key="index"><dt>{{row.label}}</dt><dd>{{row.value}}</dd></template></dl>
              </section>
            </AnimatedDisclosure>
          </article>
          <NewsSourceCard
            v-for="source in sources"
            :key="source.chunkId"
            :source="source"
            compact
          />
          <article v-if="artifact" class="workspace-work-card">
            <span class="work-status"
              >{{ artifact.status === "FINAL" ? "已定稿" : "草稿" }} · V{{
                artifact.versionNumber
              }}</span
            ><router-link :to="'/works/' + conversation!.id"
              ><h3>{{ artifact.title }}</h3></router-link
            >
            <p>{{ artifact.content.summary }}</p>
            <div class="workspace-work-actions">
              <el-button @click="router.push('/works/' + conversation!.id)"
                >查看作品</el-button
              ><el-button
                :disabled="running"
                @click="router.push('/works/' + conversation!.id + '?edit=1')"
                >修改</el-button
              ><el-button
                v-if="artifact.status !== 'FINAL'"
                text
                :disabled="running"
                @click="finalize"
                >定稿</el-button
              >
            </div>
          </article>
          <AnimatedDisclosure
            v-if="conversation?.brief.topic"
            class="workspace-brief"
            ><template #summary>创作要求</template>
            <dl class="tool-summary">
              <template v-for="row in briefRows" :key="row.key"
                ><dt>{{ row.label }}</dt>
                <dd>{{ row.value }}</dd></template
              >
            </dl></AnimatedDisclosure
          >
          <div
            v-if="!tools.length && !sources.length && !artifact"
            class="right-empty"
          >
            <el-icon><Operation /></el-icon>
            <p>暂无执行记录</p>
          </div>
        </div>
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
      <ProductionCanvas
        v-if="draft"
        v-model="draft"
        :initial-tab="artifactSection"
        @revise="async () => { if (await confirmLeave()) { artifactOpen = false; await router.push('/works/' + conversation!.id + '?edit=1'); } }"
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
