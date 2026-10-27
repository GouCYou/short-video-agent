<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import { onBeforeRouteLeave, useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  PhArrowLeft,
  PhArrowUp,
  PhPencilSimple,
  PhDownloadSimple,
  PhClockCounterClockwise,
  PhChatTeardropText,
  PhX,
} from "@phosphor-icons/vue";
import { useConversation } from "../composables/useConversation";
import { useChoiceDraft } from "../composables/useChoiceDraft";
import type { Selection } from "../lib/conversation-types";
import { conversationApi } from "../lib/conversation-api";
import { errorMessage } from "../lib/api";
import { dateTime } from "../lib/format";
import type {
  ProductionPackage,
  ArtifactVersion,
} from "../lib/conversation-types";
import ProductionCanvas from "../components/ProductionCanvas.vue";
import ConversationTurn from "../components/ConversationTurn.vue";
import NewsSourceCard from "../components/NewsSourceCard.vue";
import LoadError from "../components/LoadError.vue";
import WorkspacePanels from "../components/WorkspacePanels.vue";
const route = useRoute(),
  router = useRouter(),
  state = useConversation();
const { conversation, running, sending, cancelling, delta, liveEvents } = state;
const draft = ref<ProductionPackage | null>(null),
  baseline = ref(""),
  saving = ref(false),
  composer = ref(""),
  scope = ref("整篇"),
  versionsOpen = ref(false);
const artifact = computed(() => conversation.value?.artifact),
  editing = computed(() => route.query.edit === "1");
const choiceDraft=useChoiceDraft(composer),composerElement=ref<HTMLTextAreaElement>();
function fillComposer(text:string){choiceDraft.clear();composer.value=text;void nextTick(()=>composerElement.value?.focus());}
function choose(messageId:string,selection:Omit<Selection,'messageId'>){const message=conversation.value?.messages.find(m=>m.id===messageId);if(message&&!running.value&&choiceDraft.stage(message,selection))void nextTick(()=>composerElement.value?.focus());}
const dirty = computed(
  () => !!draft.value && JSON.stringify(draft.value) !== baseline.value,
);
const assistantMessages = computed(() =>
  (conversation.value?.messages || []).slice(-6),
);
function resetDraft() {
  if (artifact.value) {
    draft.value = JSON.parse(JSON.stringify(artifact.value.content));
    baseline.value = JSON.stringify(draft.value);
  }
}
watch(
  () => artifact.value?.versionNumber,
  () => {
    if (!dirty.value || saving.value || !editing.value) resetDraft();
  },
);
watch(
  () => String(route.params.id),
  async (id) => {
    draft.value = null;
    baseline.value = "";
    await state.load(id);
    resetDraft();
  },
  { immediate: true },
);
async function saveManual() {
  if (!artifact.value || !draft.value || !conversation.value) return false;
  if (!dirty.value) return true;
  saving.value = true;
  try {
    state.accept(
      await conversationApi.save(
        conversation.value.id,
        draft.value,
        artifact.value.versionNumber,
      ),
    );
    resetDraft();
    return true;
  } catch (e) {
    ElMessage.error(errorMessage(e));
    return false;
  } finally {
    saving.value = false;
  }
}
async function leaveEdit(discard = false) {
  if (running.value) return;
  if (dirty.value) {
    if (discard) {
      try {
        await ElMessageBox.confirm("放弃尚未保存的修改？", "退出修改", {
          confirmButtonText: "放弃修改",
          cancelButtonText: "继续修改",
        });
      } catch {
        return;
      }
      resetDraft();
    } else if (!(await saveManual())) return;
  }
  await router.replace({ query: { ...route.query, edit: undefined } });
}
onBeforeRouteLeave(async () => {
  if (!dirty.value) return true;
  try {
    await ElMessageBox.confirm("尚有未保存的作品修改。", "离开作品？", {
      confirmButtonText: "放弃并离开",
      cancelButtonText: "继续修改",
    });
    return true;
  } catch {
    return false;
  }
});
function revise(value: string) {
  scope.value =
    (
      {
        opening: "开场",
        shooting: "拍摄方案",
        voiceover: "配音方案",
        editing: "剪辑方案",
      } as Record<string, string>
    )[value] || value;
}
function onComposerKey(event: KeyboardEvent) {
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    void ask();
  }
}
async function ask(text = composer.value) {
  if (!text.trim() || running.value || saving.value) return;
  let selection:Selection|undefined;
  try{selection=choiceDraft.selection();}catch(e){ElMessage.warning(errorMessage(e));return;}
  if (!(await saveManual())) return;
  const instruction =
    scope.value === "整篇" ? text : `只调整${scope.value}：${text}`;
  if(await state.send(instruction,selection)){composer.value="";choiceDraft.clear();}
}
async function download() {
  if (!conversation.value || !artifact.value) return;
  try {
    const blob = await conversationApi.export(
      conversation.value.id,
      "markdown",
    );
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = artifact.value.title.replace(/[\\/:*?"<>|]/g, "_") + ".md";
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (e) {
    ElMessage.error(errorMessage(e));
  }
}
async function restore(version: ArtifactVersion) {
  if (!conversation.value || running.value) return;
  try {
    await ElMessageBox.confirm(
      `恢复到 V${version.versionNumber}？当前版本会保留。`,
      "恢复作品",
      { confirmButtonText: "恢复", cancelButtonText: "取消" },
    );
    state.accept(
      await conversationApi.restore(conversation.value.id, version.id),
    );
    resetDraft();
    versionsOpen.value = false;
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorMessage(e));
  }
}
</script>
<template>
  <div
    class="work-detail-page"
    :class="{ 'is-editing': editing }"
    v-loading="state.loading.value"
  >
    <header class="work-detail-toolbar">
      <WorkspacePanels />
      <button class="text-icon-button" @click="router.push('/tasks')">
        <PhArrowLeft :size="19" />我的作品</button
      ><span v-if="artifact" class="work-detail-version"
        >{{ artifact.status === "FINAL" ? "已定稿" : "草稿" }} · V{{
          artifact.versionNumber
        }}</span
      >
      <div class="work-detail-actions">
        <template v-if="!editing"
          ><el-button
            :icon="PhClockCounterClockwise"
            :disabled="!artifact"
            @click="versionsOpen = true"
            >版本</el-button
          ><el-button
            :icon="PhDownloadSimple"
            :disabled="!artifact"
            @click="download"
            >导出</el-button
          ><el-button
            type="primary"
            :icon="PhPencilSimple"
            :disabled="!artifact || running"
            @click="router.replace({ query: { ...route.query, edit: '1' } })"
            >修改</el-button
          ></template
        ><template v-else
          ><el-button :disabled="running || saving" @click="leaveEdit(true)"
            >取消</el-button
          ><el-button
            :loading="saving"
            :disabled="!dirty || running"
            @click="saveManual"
            >保存</el-button
          ><el-button
            type="primary"
            :disabled="running || saving"
            @click="leaveEdit()"
            >完成</el-button
          ></template
        >
      </div>
    </header>
    <LoadError
      v-if="state.error.value"
      :message="state.error.value"
      @retry="state.load(String(route.params.id))"
    />
    <div v-else-if="artifact && draft" class="work-detail-layout">
      <main class="work-detail-document">
        <ProductionCanvas
          v-model="draft"
          :editable="editing && !running && !saving"
          @revise="revise"
        />
        <section v-if="artifact.sources.length" class="work-source-list">
          <h2>引用来源</h2>
          <NewsSourceCard
            v-for="source in artifact.sources"
            :key="source.chunkId"
            :source="source"
          />
        </section>
      </main>
      <aside class="work-agent-panel" :inert="editing ? undefined : true">
        <div class="work-agent-inner">
          <header>
            <h2>与知映一起修改</h2>
            <button
              class="icon-button"
              aria-label="返回原对话"
              @click="router.push('/conversations/' + conversation!.id)"
            >
              <PhChatTeardropText :size="21" />
            </button>
          </header>
          <div class="work-agent-messages">
            <ConversationTurn
              v-for="message in assistantMessages"
              :key="message.id"
              :message="message"
              :conversation-id="conversation?.id"
              :events="liveEvents"
              :disabled="running"
              @select="choose(message.id,$event)"
            />
            <div v-if="running" class="work-agent-live">
              <span v-if="!delta" class="work-agent-thinking"
                >正在修改作品…</span
              >
              <p v-else>{{ delta }}</p>
            </div>
            <LoadError
              v-if="state.streamError.value"
              :message="state.streamError.value"
              @retry="state.refresh()"
            />
          </div>
          <div class="work-agent-compose">
            <div v-if="scope !== '整篇'" class="work-scope-chip">
              {{ scope
              }}<button aria-label="取消修改范围" @click="scope = '整篇'">
                <PhX :size="15" />
              </button>
            </div>
            <div v-if="!running && !composer" class="work-edit-suggestions">
              <button @click="fillComposer('把开场写得更直接，只改开场')">
                开场更直接</button
              ><button
                @click="fillComposer('检查这份方案能否按现有设备拍摄，先给修改建议')"
              >
                检查拍摄可行性
              </button>
            </div>
            <div class="work-agent-input">
              <textarea
                ref="composerElement"
                v-model="composer"
                rows="2"
                maxlength="4000"
                aria-label="作品修改要求"
                placeholder="说说想改哪里"
                :disabled="running"
                @keydown="onComposerKey"
              />
              <div class="composer-actions">
                <button
                  v-if="running"
                  type="button"
                  class="composer-stop-button"
                  aria-label="停止生成"
                  :disabled="cancelling"
                  @click="state.cancel"
                >
                  <span class="stop-square"></span></button
                ><el-button
                  v-else
                  type="primary"
                  circle
                  :icon="PhArrowUp"
                  aria-label="提交修改要求"
                  :disabled="!composer.trim() || sending || saving"
                  @click="ask()"
                />
              </div>
            </div>
          </div>
        </div>
      </aside>
    </div>
    <div
      v-else-if="!state.loading.value && !state.error.value"
      class="works-empty"
    >
      <h2>作品不存在或已删除</h2>
      <el-button @click="router.push('/tasks')">返回我的作品</el-button>
    </div>
    <el-dialog v-model="versionsOpen" title="作品版本" width="min(620px,94vw)"
      ><div class="work-version-history">
        <article
          v-for="version in conversation?.versions || []"
          :key="version.id"
        >
          <strong>V{{ version.versionNumber }}</strong
          ><span>{{ dateTime(version.createdAt) }}</span
          ><el-button :disabled="running || dirty" @click="restore(version)"
            >恢复此版本</el-button
          >
        </article>
      </div></el-dialog
    >
  </div>
</template>
