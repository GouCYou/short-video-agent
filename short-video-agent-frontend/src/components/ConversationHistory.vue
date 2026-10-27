<script setup lang="ts">
import { computed, onMounted, onUnmounted, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  PhPlus,
  PhX,
  PhDotsThree,
  PhPushPin,
  PhPushPinSlash,
  PhPencilSimple,
  PhTrash,
  PhCircleNotch,
} from "@phosphor-icons/vue";
import { useWorkspace } from "../composables/useWorkspace";
import { useAuth } from "../stores/auth";
import { conversationApi } from "../lib/conversation-api";
import { errorMessage } from "../lib/api";
import type { Conversation } from "../lib/conversation-types";
import StudioMenu from "./StudioMenu.vue";
const workspace = useWorkspace(),
  auth = useAuth(),
  route = useRoute(),
  router = useRouter();
const list = computed(() =>
  [...workspace.history.value].sort(
    (a, b) =>
      Number(b.pinned) - Number(a.pinned) ||
      Date.parse(b.updatedAt) - Date.parse(a.updatedAt),
  ),
);
function newChat() {
  if (route.meta.workspace) window.dispatchEvent(new Event("studio:new"));
  else void router.push("/");
}
function menu(c: Conversation) {
  return [
    {
      id: "pin",
      label: c.pinned ? "取消置顶" : "置顶对话",
      icon: c.pinned ? PhPushPinSlash : PhPushPin,
    },
    { id: "rename", label: "重命名", icon: PhPencilSimple },
    {
      id: "delete",
      label: "删除对话",
      icon: PhTrash,
      danger: true,
      disabled: c.status === "RUNNING",
      separator: true,
    },
  ];
}
async function command(action: string, c: Conversation) {
  try {
    if (action === "pin") await conversationApi.pin(c.id, !c.pinned);
    else if (action === "rename") {
      const { value } = await ElMessageBox.prompt("", "重命名对话", {
        inputValue: c.title,
        inputPattern: /\S/,
        inputErrorMessage: "请输入名称",
        confirmButtonText: "保存",
        cancelButtonText: "取消",
      });
      await conversationApi.rename(c.id, value.trim());
    } else {
      await ElMessageBox.confirm(`删除「${c.title}」及关联作品？`, "删除对话", {
        confirmButtonText: "删除",
        cancelButtonText: "保留",
        type: "warning",
      });
      await conversationApi.remove(c.id);
      if (route.params.id === c.id) await router.push("/");
    }
    await workspace.reloadHistory();
    window.dispatchEvent(new Event("studio:history-updated"));
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorMessage(e));
  }
}
function reload() {
  if (auth.loggedIn && workspace.historyVisible.value)
    void workspace.reloadHistory();
}
let timer: ReturnType<typeof setInterval> | undefined;
onMounted(() => {
  reload();
  timer = setInterval(reload, 3000);
  window.addEventListener("studio:history-updated", reload);
  window.addEventListener("focus", reload);
});
onUnmounted(() => {
  clearInterval(timer);
  window.removeEventListener("studio:history-updated", reload);
  window.removeEventListener("focus", reload);
});
watch(() => workspace.historyVisible.value, reload);
watch(
  () => auth.user?.id,
  () => {
    workspace.history.value = [];
    reload();
  },
);
</script>
<template>
  <aside
    class="workspace-history-panel"
    aria-label="对话列表"
    :aria-hidden="!workspace.historyVisible.value"
    :inert="workspace.historyVisible.value ? undefined : true"
  >
    <div class="history-panel-inner">
      <header class="history-panel-header">
        <button class="history-new-chat" @click="newChat">
          <PhPlus :size="19" />新对话</button
        ><button
          class="icon-button"
          aria-label="隐藏对话列表"
          @click="workspace.toggleHistory"
        >
          <PhX :size="19" />
        </button>
      </header>
      <div class="studio-history-list">
        <div
          v-for="c in list"
          :key="c.id"
          class="studio-history-row"
          :class="{ selected: route.params.id === c.id }"
        >
          <button
            class="studio-history-title"
            :title="c.title"
            @click="router.push('/conversations/' + c.id)"
          >
            {{ c.title }}</button
          ><PhCircleNotch
            v-if="c.status === 'RUNNING' || c.activeRunId"
            :size="16"
            class="agent-spinner history-running"
            role="status"
            :aria-label="c.title + ' 正在生成'"
          /><PhPushPin
            v-if="c.pinned"
            :size="14"
            class="history-pin"
          /><StudioMenu
            :items="menu(c)"
            :label="c.title + ' 的操作'"
            @select="command($event, c)"
            ><template #default="{ open }"
              ><button
                class="icon-button history-more"
                :class="{ 'menu-open': open }"
                :aria-label="c.title + ' 的操作'"
                :aria-expanded="open"
              >
                <PhDotsThree :size="20" weight="bold" /></button></template
          ></StudioMenu>
        </div>
        <button
          v-if="workspace.historyError.value"
          class="history-reload"
          @click="workspace.reloadHistory"
        >
          重新加载对话
        </button>
      </div>
    </div>
  </aside>
</template>
