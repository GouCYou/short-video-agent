<script setup lang="ts">
import { computed, onMounted, onUnmounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import WorkspacePanels from "./components/WorkspacePanels.vue";
import ConversationHistory from "./components/ConversationHistory.vue";
import { useAuth } from "./stores/auth";
import { useWorkspace } from "./composables/useWorkspace";
import WorkspaceSidebar from "./components/WorkspaceSidebar.vue";
import AdminLayout from "./components/AdminLayout.vue";
const route = useRoute(),
  router = useRouter(),
  auth = useAuth(),
  workspace = useWorkspace();
const historyOpen = computed(
  () => !!route.meta.workspace && workspace.historyVisible.value,
);
const expired = () => {
  if (!auth.loggedIn) return;
  auth.clear();
  workspace.history.value = [];
  void router.push({ name: "login", query: { redirect: route.fullPath } });
  ElMessage.warning("登录已过期，请重新登录。");
};
onMounted(() => window.addEventListener("session-expired", expired));
onUnmounted(() => window.removeEventListener("session-expired", expired));
</script>
<template>
  <router-view v-if="route.meta.public" />
  <AdminLayout v-else-if="route.meta.admin" />
  <div
    v-else
    class="studio-shell"
    :class="{
      'tools-expanded': workspace.toolsExpanded.value,
      'history-visible': historyOpen,
    }"
  >
    <WorkspaceSidebar />
    <ConversationHistory v-if="route.meta.workspace" />
    <button
      v-if="historyOpen || workspace.toolsExpanded.value"
      class="studio-mobile-veil"
      :aria-label="historyOpen ? '隐藏对话列表' : '收缩工具栏'"
      @click="historyOpen ? workspace.toggleHistory() : workspace.toggleTools()"
    />
    <div class="studio-main-shell">
      <header
        v-if="!route.meta.workspace && route.name !== 'work'"
        class="studio-page-header"
      >
        <WorkspacePanels /><span>{{ route.meta.title }}</span>
      </header>
      <router-view v-slot="{ Component }"
        ><Transition name="page" mode="out-in"
          ><component
            :is="Component"
            :key="route.meta.workspace ? 'conversation' : String(route.name)"
            :class="{ 'studio-page': !route.meta.workspace }" /></Transition
      ></router-view>
    </div>
  </div>
</template>
