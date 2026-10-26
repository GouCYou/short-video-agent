<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { UserFilled } from "@element-plus/icons-vue";
import {
  PhClockCounterClockwise,
  PhPlus,
  PhChatTeardropText,
  PhStackSimple,
  PhBooks,
  PhSlidersHorizontal,
  PhUser,
  PhSignOut,
  PhDotsThree,
  PhBrain,
} from "@phosphor-icons/vue";
import { useAuth } from "../stores/auth";
import { useWorkspace } from "../composables/useWorkspace";
import StudioMenu from "./StudioMenu.vue";
const auth = useAuth(),
  route = useRoute(),
  router = useRouter(),
  workspace = useWorkspace();
const { toolsVisible, historyVisible } = workspace;
const links = computed(() => [
  {
    path: "/",
    title: "对话创作",
    icon: PhChatTeardropText,
    active:
      route.name === "conversation" || route.name === "conversation-detail",
  },
  {
    path: "/tasks",
    title: "我的作品",
    icon: PhStackSimple,
    active: route.path.startsWith("/tasks"),
  },
  {
    path: "/knowledge",
    title: "知识库",
    icon: PhBooks,
    active: route.path === "/knowledge",
  },
]);
const accountItems = computed(() => [
  { id: "memory", label: "用户画像与记忆", icon: PhBrain },
  { id: "profile", label: "个人设置", icon: PhUser },
  ...(auth.admin
    ? [
        {
          id: "management",
          label: "管理面板",
          icon: PhSlidersHorizontal,
          separator: true,
        },
      ]
    : []),
  { id: "logout", label: "退出登录", icon: PhSignOut, separator: true },
]);
function newChat() {
  if (route.meta.workspace) window.dispatchEvent(new Event("studio:new"));
  else void router.push("/");
}
async function account(action: string) {
  if (action === "management") {
    sessionStorage.setItem("admin:return:" + auth.user?.id, route.fullPath);
    return router.push("/admin");
  }
  if (action !== "logout")
    return router.push(
      action === "memory" ? "/profile?tab=memory" : "/profile",
    );
  try {
    await auth.logout();
  } finally {
    workspace.history.value = [];
    workspace.historyVisible.value = false;
    workspace.toolsVisible.value = true;
    void router.push("/login");
  }
}
</script>
<template>
  <aside
    class="studio-sidebar"
    :aria-hidden="!toolsVisible"
    :inert="toolsVisible ? undefined : true"
  >
    <div class="studio-brand-row">
      <router-link to="/" class="studio-brand" aria-label="知映首页"
        ><span class="brand-mark"><i></i><b></b></span
        ><span class="sidebar-label brand-name">知映</span></router-link
      >
    </div>
    <button class="studio-new" title="开始创作" @click="newChat">
      <PhPlus :size="22" /><span class="sidebar-label">开始创作</span>
    </button>
    <nav class="studio-nav" aria-label="主要导航">
      <router-link
        v-for="link in links"
        :key="link.path"
        :to="link.path"
        :class="{ selected: link.active }"
        :title="link.title"
        ><component :is="link.icon" :size="23" /><span class="sidebar-label">{{
          link.title
        }}</span></router-link
      >
    </nav>
    <button
      class="history-rail-toggle icon-button"
      :class="{ selected: historyVisible }"
      :aria-label="historyVisible ? '隐藏对话列表' : '显示对话列表'"
      @click="workspace.toggleHistory"
    >
      <PhClockCounterClockwise :size="23" />
    </button>
    <div class="studio-account">
      <StudioMenu :items="accountItems" label="账户菜单" @select="account"
        ><template #default="{ open }"
          ><button
            class="studio-account-button"
            aria-label="账户菜单"
            :aria-expanded="open"
          >
            <el-avatar :size="36" :icon="UserFilled" /><span
              class="sidebar-label"
              >{{ auth.user?.nickname || auth.user?.username }}</span
            ><PhDotsThree class="sidebar-label" :size="20" /></button></template
      ></StudioMenu>
    </div>
  </aside>
</template>
