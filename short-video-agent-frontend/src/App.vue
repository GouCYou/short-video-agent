<script setup lang="ts">
import { onMounted, onUnmounted } from "vue";
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
      'tools-hidden': !workspace.toolsVisible.value,
      'history-visible': workspace.historyVisible.value,
    }"
  >
    <WorkspaceSidebar />
    <ConversationHistory />
    <button
      v-if="workspace.historyVisible.value"
      class="studio-mobile-veil"
      aria-label="隐藏对话列表"
      @click="workspace.toggleHistory"
    />
    <div class="studio-main-shell">
      <header v-if="!route.meta.workspace" class="studio-page-header">
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
