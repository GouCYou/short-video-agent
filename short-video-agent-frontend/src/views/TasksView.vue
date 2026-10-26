<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  PhPlus,
  PhMagnifyingGlass,
  PhStackSimple,
  PhDotsThree,
  PhPencilSimple,
  PhPushPin,
  PhPushPinSlash,
  PhTrash,
  PhArrowUpRight,
  PhChatTeardropText,
  PhDownloadSimple,
} from "@phosphor-icons/vue";
import { conversationApi } from "../lib/conversation-api";
import { api, errorMessage } from "../lib/api";
import { useWorkspace } from "../composables/useWorkspace";
import { dateTime } from "../lib/format";
import type { Conversation } from "../lib/conversation-types";
import type { Task } from "../lib/types";
import StudioMenu from "../components/StudioMenu.vue";
import LoadError from "../components/LoadError.vue";
import SlidingSegment from "../components/SlidingSegment.vue";
const router = useRouter(),
  workspace = useWorkspace(),
  works = ref<Conversation[]>([]),
  legacy = ref<Task[]>([]),
  search = ref(""),
  status = ref(""),
  busy = ref(false),
  error = ref("");
const filtered = computed(() =>
  works.value
    .filter(
      (c) =>
        (c.artifact?.title || "")
          .toLocaleLowerCase()
          .includes(search.value.trim().toLocaleLowerCase()) &&
        (!status.value || c.artifact?.status === status.value),
    )
    .sort(
      (a, b) =>
        Number(b.pinned) - Number(a.pinned) ||
        Date.parse(b.artifact!.updatedAt) - Date.parse(a.artifact!.updatedAt),
    ),
);
const oldWorks = computed(() =>
  legacy.value.filter(
    (t) =>
      t.result &&
      t.title
        .toLocaleLowerCase()
        .includes(search.value.trim().toLocaleLowerCase()) &&
      (!status.value ||
        (status.value === "FINAL"
          ? t.status === "COMPLETED"
          : t.status !== "COMPLETED")),
  ),
);
async function load() {
  busy.value = true;
  error.value = "";
  try {
    const [conversations, tasks] = await Promise.all([
      conversationApi.list(),
      api.tasks(),
    ]);
    works.value = conversations.filter((c) => c.artifact);
    legacy.value = tasks;
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
function items(c: Conversation) {
  return [
    { id: "open", label: "打开作品", icon: PhArrowUpRight },
    { id: "chat", label: "继续对话", icon: PhChatTeardropText },
    {
      id: "pin",
      label: c.pinned ? "取消置顶" : "置顶作品",
      icon: c.pinned ? PhPushPinSlash : PhPushPin,
    },
    { id: "rename", label: "重命名", icon: PhPencilSimple },
    { id: "export", label: "导出 Markdown", icon: PhDownloadSimple },
    {
      id: "delete",
      label: "删除作品",
      icon: PhTrash,
      danger: true,
      separator: true,
      disabled: c.status === "RUNNING",
    },
  ];
}
async function command(action: string, c: Conversation) {
  try {
    if (action === "open" || action === "chat")
      return router.push(
        `/conversations/${c.id}${action === "open" ? "?work=1" : ""}`,
      );
    if (action === "pin") await conversationApi.pin(c.id, !c.pinned);
    if (action === "rename") {
      const { value } = await ElMessageBox.prompt("", "重命名作品", {
        inputValue: c.artifact!.title,
        inputPattern: /\S/,
        inputErrorMessage: "请输入作品名称",
        confirmButtonText: "保存",
        cancelButtonText: "取消",
      });
      await conversationApi.renameWork(c.id, value.trim());
    }
    if (action === "delete") {
      await ElMessageBox.confirm(
        `删除「${c.artifact!.title}」及版本记录？对话会保留。`,
        "删除作品",
        {
          confirmButtonText: "删除",
          cancelButtonText: "保留",
          type: "warning",
        },
      );
      await conversationApi.deleteWork(c.id);
    }
    if (action === "export") {
      const blob = await conversationApi.export(c.id, "markdown"),
        url = URL.createObjectURL(blob),
        a = document.createElement("a");
      a.href = url;
      a.download = c.artifact!.title.replace(/[\\/:*?"<>|]/g, "_") + ".md";
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      return;
    }
    await Promise.all([load(), workspace.reloadHistory()]);
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorMessage(e));
  }
}
onMounted(load);
</script>
<template>
  <div class="works-page">
    <div class="page-heading">
      <h1>我的作品</h1>
      <el-button type="primary" :icon="PhPlus" @click="router.push('/')"
        >开始创作</el-button
      >
    </div>
    <div class="works-toolbar">
      <div class="works-search">
        <PhMagnifyingGlass :size="19" /><input
          v-model="search"
          placeholder="搜索作品"
          aria-label="搜索作品"
        />
      </div>
      <SlidingSegment
        v-model="status"
        label="作品状态"
        :options="[
          { value: '', label: '全部' },
          { value: 'DRAFT', label: '草稿' },
          { value: 'FINAL', label: '已定稿' },
        ]"
      />
    </div>
    <LoadError v-if="error" :message="error" @retry="load" />
    <div v-else v-loading="busy" class="works-content">
      <div v-if="filtered.length || oldWorks.length" class="works-grid">
        <article v-for="c in filtered" :key="c.id" class="work-card">
          <div class="work-card-top">
            <span class="work-status">{{
              c.artifact!.status === "FINAL" ? "已定稿" : "草稿"
            }}</span
            ><PhPushPin v-if="c.pinned" :size="16" /><StudioMenu
              :items="items(c)"
              :label="c.artifact!.title + ' 的操作'"
              align="end"
              @select="command($event, c)"
              ><template #default="{ open }"
                ><button
                  class="icon-button"
                  :aria-label="c.artifact!.title + ' 的操作'"
                  :aria-expanded="open"
                >
                  <PhDotsThree :size="23" /></button></template
            ></StudioMenu>
          </div>
          <button class="work-card-body" @click="command('open', c)">
            <span class="work-art"
              ><PhStackSimple :size="32" weight="duotone"
            /></span>
            <h2>{{ c.artifact!.title }}</h2>
          </button>
          <div class="work-card-footer">
            <time>{{ dateTime(c.artifact!.updatedAt) }}</time
            ><button @click="command('chat', c)">
              继续对话<PhArrowUpRight :size="15" />
            </button>
          </div>
        </article>
        <article
          v-for="t in oldWorks"
          :key="t.id"
          class="work-card legacy-work"
        >
          <span class="work-status">已有作品</span
          ><button
            class="work-card-body"
            @click="router.push('/tasks/' + t.id)"
          >
            <span class="work-art"><PhStackSimple :size="32" /></span>
            <h2>{{ t.title }}</h2></button
          ><time>{{ dateTime(t.updatedAt) }}</time>
        </article>
      </div>
      <div v-else-if="!busy" class="works-empty">
        <span class="empty-art"><PhStackSimple :size="35" /></span>
        <h2>{{ search || status ? "没有找到作品" : "还没有作品" }}</h2>
        <el-button
          v-if="!search && !status"
          type="primary"
          plain
          :icon="PhPlus"
          @click="router.push('/')"
          >开始创作</el-button
        >
      </div>
    </div>
  </div>
</template>
