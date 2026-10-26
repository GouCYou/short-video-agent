<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import {
  onBeforeRouteLeave,
  onBeforeRouteUpdate,
  useRoute,
  useRouter,
} from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  ArrowLeft,
  ArrowRight,
  EditPen,
  DocumentCopy,
  Download,
  Check,
  Refresh,
  MagicStick,
  Link,
  Reading,
} from "@element-plus/icons-vue";
import { api, errorMessage } from "../lib/api";
import {
  active,
  contentText,
  dateTime,
  normalizeContent,
  safeUrl,
  stageLabels,
  validateContent,
} from "../lib/format";
import type { Content, Task, Version } from "../lib/types";
import ContentDocument from "../components/ContentDocument.vue";
import StatusBadge from "../components/StatusBadge.vue";
import LoadError from "../components/LoadError.vue";
const route = useRoute(),
  router = useRouter(),
  task = ref<Task | null>(null),
  busy = ref(true),
  error = ref(""),
  actionBusy = ref(false),
  tab = ref("content"),
  selected = ref<number | null>(null),
  editing = ref(false),
  draft = ref<Content | null>(null),
  instruction = ref(""),
  section = ref("ALL"),
  compareOpen = ref(false),
  leftVersion = ref(""),
  rightVersion = ref(""),
  pollError = ref("");
let timer: ReturnType<typeof setTimeout> | undefined,
  disposed = false,
  requestEpoch = 0;
const id = computed(() => String(route.params.id)),
  isActive = computed(() => !!task.value && active(task.value.status)),
  canEdit = computed(() => task.value?.status === "COMPLETED"),
  currentContent = computed(() =>
    task.value?.result ? normalizeContent(task.value.result) : null,
  ),
  versions = computed(() =>
    [...(task.value?.versions || [])].sort(
      (a, b) => b.versionNumber - a.versionNumber,
    ),
  ),
  left = computed(() => versions.value.find((v) => v.id === leftVersion.value)),
  right = computed(() =>
    versions.value.find((v) => v.id === rightVersion.value),
  );
async function load(quiet = false) {
  const requestId = id.value,
    epoch = ++requestEpoch;
  if (!quiet) busy.value = true;
  try {
    const result = await api.task(requestId);
    if (disposed || requestId !== id.value || epoch !== requestEpoch) return;
    task.value = result;
    error.value = "";
    pollError.value = "";
    if (result.status === "WAITING_SELECTION") tab.value = "topics";
    if (!leftVersion.value && result.versions?.length) {
      leftVersion.value = versions.value[0]!.id;
      rightVersion.value = versions.value[1]?.id || versions.value[0]!.id;
    }
  } catch (e) {
    if (disposed || requestId !== id.value || epoch !== requestEpoch) return;
    if (quiet) pollError.value = errorMessage(e);
    else error.value = errorMessage(e);
  } finally {
    if (!disposed && requestId === id.value && epoch === requestEpoch) {
      busy.value = false;
      clearTimeout(timer);
      if (isActive.value || pollError.value)
        timer = setTimeout(() => load(true), 2000);
    }
  }
}
onMounted(() => load());
onUnmounted(() => {
  disposed = true;
  clearTimeout(timer);
  window.removeEventListener("beforeunload", beforeUnload);
});
watch(id, () => {
  editing.value = false;
  actionBusy.value = false;
  selected.value = null;
  leftVersion.value = "";
  rightVersion.value = "";
  task.value = null;
  load();
});
function beforeUnload(e: BeforeUnloadEvent) {
  if (editing.value) {
    e.preventDefault();
    e.returnValue = "";
  }
}
onMounted(() => window.addEventListener("beforeunload", beforeUnload));
async function confirmLeave() {
  if (!editing.value) return true;
  try {
    await ElMessageBox.confirm(
      "当前编辑尚未保存，离开后会丢失这些修改。",
      "离开编辑",
      {
        confirmButtonText: "离开",
        cancelButtonText: "继续编辑",
        type: "warning",
      },
    );
    return true;
  } catch {
    return false;
  }
}
onBeforeRouteLeave(confirmLeave);
onBeforeRouteUpdate(confirmLeave);
async function action(fn: () => Promise<Task>) {
  if (actionBusy.value) return false;
  const requestId = id.value;
  ++requestEpoch;
  clearTimeout(timer);
  actionBusy.value = true;
  try {
    const result = await fn();
    if (disposed || requestId !== id.value) return false;
    task.value = result;
    clearTimeout(timer);
    if (isActive.value) timer = setTimeout(() => load(true), 2000);
    return true;
  } catch (e) {
    ElMessage.error(errorMessage(e));
    return false;
  } finally {
    if (!disposed && requestId === id.value) actionBusy.value = false;
  }
}
async function select() {
  if (selected.value === null) return;
  if (await action(() => api.selectTopic(id.value, selected.value!)))
    tab.value = "content";
}
async function retry() {
  try {
    await ElMessageBox.confirm(
      "重新生成会启动一次新的 AI 调用，已有版本会保留。",
      "重新生成",
      { confirmButtonText: "重新生成", cancelButtonText: "取消" },
    );
    await action(() => api.retry(id.value));
  } catch {
    /* canceled */
  }
}
function startEdit() {
  if (currentContent.value) {
    draft.value = JSON.parse(JSON.stringify(currentContent.value));
    editing.value = true;
  }
}
async function cancelEdit() {
  try {
    await ElMessageBox.confirm("放弃本次未保存的修改？", "取消编辑", {
      confirmButtonText: "放弃修改",
      cancelButtonText: "继续编辑",
    });
    editing.value = false;
    draft.value = null;
  } catch {
    /* canceled */
  }
}
async function save() {
  if (!draft.value) return;
  const validationError = validateContent(draft.value);
  if (validationError) {
    ElMessage.warning(validationError);
    return;
  }
  if (await action(() => api.save(id.value, draft.value!))) {
    editing.value = false;
    ElMessage.success("修改已保存为新版本");
  }
}
async function revise() {
  if (!instruction.value.trim()) return;
  if (
    await action(() =>
      api.revise(id.value, instruction.value.trim(), section.value),
    )
  ) {
    instruction.value = "";
    ElMessage.success("优化任务已开始");
    tab.value = "content";
  }
}
async function restore(v: Version) {
  try {
    await ElMessageBox.confirm(
      `将基于 V${v.versionNumber} 创建一个新版本，当前版本仍会保留。`,
      "使用历史版本",
      { confirmButtonText: "使用此版本", cancelButtonText: "取消" },
    );
    if (await action(() => api.restore(id.value, v.id))) {
      ElMessage.success("已恢复并创建新版本");
      tab.value = "content";
    }
  } catch {
    /* canceled */
  }
}
async function copy() {
  if (!currentContent.value) return;
  try {
    await navigator.clipboard.writeText(contentText(currentContent.value));
    ElMessage.success("完整文案已复制");
  } catch {
    ElMessage.warning("浏览器不允许访问剪贴板，请使用导出功能。");
  }
}
async function download(format: string) {
  try {
    const blob = await api.export(id.value, format);
    const href = URL.createObjectURL(blob),
      link = document.createElement("a");
    link.href = href;
    link.download = `${(task.value?.title || "创作文案").replace(/[\\/:*?"<>|]/g, "_")}.${format === "markdown" ? "md" : "txt"}`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(href), 1000);
  } catch (e) {
    ElMessage.error(errorMessage(e));
  }
}
function versionLabel(v: Version) {
  return v.kind === "MANUAL"
    ? "手动编辑"
    : v.kind === "RESTORE"
      ? "历史恢复"
      : v.kind === "REVISE" || v.kind === "AI_REVISION"
        ? "AI 优化"
        : "AI 生成";
}
</script>
<template>
  <div>
    <el-button
      text
      :icon="ArrowLeft"
      class="back-link"
      @click="router.push('/tasks')"
      >返回我的作品</el-button
    ><LoadError v-if="error" :message="error" @retry="load()" />
    <div v-else-if="!task" v-loading="busy" class="panel loading-panel"></div>
    <template v-else
      ><div class="page-heading task-page-heading">
        <div>
          <div class="task-detail-meta">
            <StatusBadge :status="task.status" /><el-tag
              v-if="
                task.result?.qualityNotes?.some((note) =>
                  /DEMO|演示模式|演示内容/i.test(note),
                )
              "
              type="warning"
              effect="plain"
              >演示内容</el-tag
            >
          </div>
          <h1>{{ task.title || task.topic }}</h1>
          <p>
            {{ task.platform }}<span class="inline-dot">·</span
            >{{ task.duration }} 秒<span class="inline-dot">·</span
            >{{ task.style }}<span class="inline-dot">·</span
            >{{ dateTime(task.createdAt) }} 创建
          </p>
        </div>
        <div class="heading-actions">
          <el-button
            v-if="currentContent && !editing"
            :icon="DocumentCopy"
            @click="copy"
            >复制文案</el-button
          ><el-dropdown v-if="currentContent && !editing" @command="download"
            ><el-button :icon="Download">导出作品</el-button
            ><template #dropdown
              ><el-dropdown-menu
                ><el-dropdown-item command="markdown"
                  >Markdown 文档</el-dropdown-item
                ><el-dropdown-item command="txt"
                  >纯文本 TXT</el-dropdown-item
                ></el-dropdown-menu
              ></template
            ></el-dropdown
          ><el-button
            v-if="canEdit && !editing"
            type="primary"
            :icon="EditPen"
            @click="startEdit"
            >编辑脚本</el-button
          >
        </div>
      </div>
      <el-alert
        v-if="pollError"
        :title="pollError + ' 系统将自动重新连接。'"
        type="warning"
        show-icon
        :closable="false"
        class="mb-24"
      /><el-alert
        v-if="task.status === 'FAILED'"
        title="这次创作遇到了一点问题"
        type="error"
        :closable="false"
        show-icon
        class="mb-24"
        ><p>{{ task.error || "任务执行未成功，请查看运行记录后重试。" }}</p>
        <el-button
          size="small"
          :icon="Refresh"
          :loading="actionBusy"
          @click="retry"
          >重新生成</el-button
        ></el-alert
      >
      <div
        v-if="isActive"
        class="progress-banner"
        role="status"
        aria-live="polite"
      >
        <span class="pulse-spark">✳</span>
        <div>
          <strong>{{
            stageLabels[task.stage] || task.stage || "正在准备创作"
          }}</strong>

          <small v-if="task.events?.length">{{
            task.events[task.events.length - 1]?.message
          }}</small>
        </div>
        <span class="progress-dots"><i></i><i></i><i></i></span>
      </div>
      <div v-if="editing" class="editing-toolbar">
        <span>正在编辑草稿 </span>
        <div>
          <el-button @click="cancelEdit" :disabled="actionBusy"
            >取消编辑</el-button
          ><el-button
            type="primary"
            :icon="Check"
            :loading="actionBusy"
            @click="save"
            >保存新版本</el-button
          >
        </div>
      </div>
      <div class="workspace-layout">
        <div class="panel workspace-main">
          <el-tabs v-model="tab" class="workspace-tabs"
            ><el-tab-pane label="创作内容" name="content"
              ><ContentDocument
                v-if="editing && draft"
                v-model="draft"
                editable
              /><ContentDocument
                v-else-if="currentContent"
                :model-value="currentContent"
              />
              <div v-else class="empty-state tall">
                <span class="empty-art"
                  ><el-icon><MagicStick /></el-icon
                ></span>
                <h3>
                  {{
                    task.status === "WAITING_SELECTION"
                      ? "请选择选题"
                      : "等待生成内容"
                  }}
                </h3>

                <el-button
                  v-if="task.status === 'WAITING_SELECTION'"
                  type="primary"
                  plain
                  @click="tab = 'topics'"
                  >查看选题方向</el-button
                >
              </div></el-tab-pane
            ><el-tab-pane
              :label="`选题方向${task.topics?.length ? ' · ' + task.topics.length : ''}`"
              name="topics"
              ><div class="tab-content">
                <div class="section-title">
                  <div>
                    <h2>
                      {{
                        task.status === "WAITING_SELECTION"
                          ? "哪个方向最接近你的想法？"
                          : "本次选题方向"
                      }}
                    </h2>
                  </div>
                </div>
                <div
                  v-for="(topic, i) in task.topics"
                  :key="i"
                  class="topic-card"
                  :class="{ selected: selected === i }"
                >
                  <button
                    v-if="task.status === 'WAITING_SELECTION'"
                    class="topic-choice"
                    :aria-label="`选择选题 ${topic.title}`"
                    :aria-pressed="selected === i"
                    @click="selected = i"
                  >
                    <span class="topic-number"
                      >方向 {{ String(i + 1).padStart(2, "0") }}</span
                    ><el-icon v-if="selected === i"><Check /></el-icon>
                    <h3>{{ topic.title }}</h3>
                    <p>{{ topic.angle }}</p>
                    <small>{{ topic.reason }}</small>
                  </button>
                  <div v-else class="topic-choice">
                    <span class="topic-number"
                      >方向 {{ String(i + 1).padStart(2, "0") }}</span
                    >
                    <h3>{{ topic.title }}</h3>
                    <p>{{ topic.angle }}</p>
                    <small>{{ topic.reason }}</small>
                  </div>
                </div>
                <div v-if="!task.topics?.length" class="empty-state">
                  <p>选题构思完成后，会显示在这里。</p>
                </div>
                <el-button
                  v-if="task.status === 'WAITING_SELECTION'"
                  type="primary"
                  size="large"
                  :disabled="selected === null"
                  :loading="actionBusy"
                  @click="select"
                  >确认选题，生成脚本<el-icon><ArrowRight /></el-icon
                ></el-button></div></el-tab-pane
            ><el-tab-pane
              :label="`参考资料${task.sources?.length ? ' · ' + task.sources.length : ''}`"
              name="sources"
              ><div class="tab-content">
                <el-alert
                  title="发布前请核实引用资料与事实。"
                  type="info"
                  :closable="false"
                  show-icon
                  class="mb-24"
                />
                <div
                  v-for="(source, i) in task.sources"
                  :key="`${source.chunkId}-${i}`"
                  class="source-card"
                >
                  <span class="source-index">{{
                    String(i + 1).padStart(2, "0")
                  }}</span>
                  <div>
                    <h3>{{ source.documentName }}</h3>
                    <p>{{ source.text }}</p>
                    <a
                      v-if="safeUrl(source.sourceUrl)"
                      :href="safeUrl(source.sourceUrl)"
                      target="_blank"
                      rel="noopener noreferrer"
                      class="source-link"
                      ><el-icon><Link /></el-icon>查看原始来源</a
                    ><small v-else-if="source.sourceUrl">{{
                      source.sourceUrl
                    }}</small
                    ><small v-if="source.score != null" class="source-score"
                      >检索分数 {{ source.score.toFixed(3) }}</small
                    >
                  </div>
                </div>
                <div v-if="!task.sources?.length" class="empty-state">
                  <span class="empty-art"
                    ><el-icon><Reading /></el-icon
                  ></span>
                  <h3>本次尚无引用资料</h3>
                  <p>
                    {{
                      task.ragEnabled
                        ? "可能尚未完成检索，或知识库中未找到足够相关的资料。"
                        : "本次创作未启用知识库辅助。"
                    }}<br />涉及事实的表述，请在发布前核实。
                  </p>
                </div>
              </div></el-tab-pane
            ><el-tab-pane
              :label="`版本记录${versions.length ? ' · ' + versions.length : ''}`"
              name="versions"
              ><div class="tab-content">
                <div class="section-title">
                  <div>
                    <h2>版本记录</h2>
                  </div>
                  <el-button
                    v-if="versions.length > 1"
                    @click="compareOpen = true"
                    >对比版本</el-button
                  >
                </div>
                <div v-for="v in versions" :key="v.id" class="version-card">
                  <span class="version-number">V{{ v.versionNumber }}</span>
                  <div>
                    <strong>{{ versionLabel(v) }}</strong>
                    <p>{{ v.instruction || "生成的完整创作内容" }}</p>
                    <small>{{ dateTime(v.createdAt) }}</small>
                  </div>
                  <el-button
                    size="small"
                    :disabled="!canEdit || editing"
                    :loading="actionBusy"
                    @click="restore(v)"
                    >使用此版本</el-button
                  >
                </div>
                <div v-if="!versions.length" class="empty-state">
                  <p>生成成功或保存修改后，会在这里保留版本。</p>
                </div>
              </div></el-tab-pane
            ><el-tab-pane label="运行记录" name="events"
              ><div class="tab-content">
                <el-timeline v-if="task.events?.length"
                  ><el-timeline-item
                    v-for="(event, i) in task.events"
                    :key="i"
                    :timestamp="dateTime(event.createdAt)"
                    placement="top"
                    :type="i === task.events.length - 1 ? 'primary' : 'info'"
                    ><strong>{{
                      stageLabels[event.stage] || event.stage
                    }}</strong>
                    <p class="event-message">{{ event.message }}</p>
                    <small v-if="event.durationMs != null"
                      >耗时 {{ (event.durationMs / 1000).toFixed(1) }} 秒</small
                    ></el-timeline-item
                  ></el-timeline
                >
                <div v-else class="empty-state">
                  <p>任务开始执行后，运行记录会显示在这里。</p>
                </div>
              </div></el-tab-pane
            ></el-tabs
          >
        </div>
        <aside class="workspace-side">
          <section class="panel brief-panel">
            <h3>创作需求</h3>
            <p class="brief-topic">{{ task.topic }}</p>
            <dl>
              <dt>目标受众</dt>
              <dd>{{ task.audience }}</dd>
              <dt>创作方式</dt>
              <dd>{{ task.mode === "GUIDED" ? "引导创作" : "快速生成" }}</dd>
              <dt>知识库辅助</dt>
              <dd>
                {{ task.ragEnabled ? task.category || "全部分类" : "未启用" }}
              </dd>
              <template v-if="task.requirements"
                ><dt>补充要求</dt>
                <dd>{{ task.requirements }}</dd></template
              >
            </dl>
          </section>
          <section v-if="currentContent" class="panel revise-panel">
            <div class="revise-title">
              <el-icon><MagicStick /></el-icon>
              <h3>AI 优化</h3>
            </div>

            <el-form label-position="top"
              ><el-form-item label="优化范围"
                ><el-select v-model="section" :disabled="!canEdit || editing"
                  ><el-option label="整体内容" value="ALL" /><el-option
                    label="备选标题"
                    value="title" /><el-option
                    label="开场口播"
                    value="opening" /><el-option
                    label="全部脚本片段"
                    value="script" /><el-option
                    v-for="(_, i) in currentContent.segments"
                    :key="i"
                    :label="`仅第 ${i + 1} 个片段`"
                    :value="String(i)" /><el-option
                    label="结尾互动"
                    value="ending" /><el-option
                    label="发布文案"
                    value="caption" /></el-select></el-form-item
              ><el-form-item label="修改要求"
                ><el-input
                  v-model="instruction"
                  type="textarea"
                  :rows="4"
                  maxlength="2000"
                  placeholder="例如：开头用一个更有代入感的问题切入，语气再自然一些。"
                  :disabled="!canEdit || editing" /></el-form-item
              ><el-button
                type="primary"
                plain
                :icon="MagicStick"
                :disabled="!canEdit || editing || !instruction.trim()"
                :loading="actionBusy"
                @click="revise"
                >开始优化</el-button
              ></el-form
            >
          </section>
        </aside>
      </div>
      <el-dialog
        v-model="compareOpen"
        title="对比创作版本"
        width="min(1200px, 95vw)"
        top="5vh"
        ><div class="compare-grid">
          <div>
            <el-select v-model="leftVersion" aria-label="左侧版本"
              ><el-option
                v-for="v in versions"
                :key="v.id"
                :label="`V${v.versionNumber} · ${versionLabel(v)} · ${dateTime(v.createdAt)}`"
                :value="v.id"
            /></el-select>
            <pre v-if="left">{{
              contentText(normalizeContent(left.content))
            }}</pre>
          </div>
          <div>
            <el-select v-model="rightVersion" aria-label="右侧版本"
              ><el-option
                v-for="v in versions"
                :key="v.id"
                :label="`V${v.versionNumber} · ${versionLabel(v)} · ${dateTime(v.createdAt)}`"
                :value="v.id"
            /></el-select>
            <pre v-if="right">{{
              contentText(normalizeContent(right.content))
            }}</pre>
          </div>
        </div></el-dialog
      ></template
    >
  </div>
</template>
