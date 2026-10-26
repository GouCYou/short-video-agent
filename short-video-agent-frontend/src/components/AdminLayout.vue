<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { UserFilled } from "@element-plus/icons-vue";
import {
  PhChartBar,
  PhUsers,
  PhSidebarSimple,
  PhArrowUUpLeft,
  PhSignOut,
} from "@phosphor-icons/vue";
import { useAuth } from "../stores/auth";
import { useWorkspace } from "../composables/useWorkspace";
import StudioMenu from "./StudioMenu.vue";
const route = useRoute(),
  router = useRouter(),
  auth = useAuth(),
  workspace = useWorkspace(),
  collapsed = ref(false);
const links = [
  { path: "/admin", title: "管理概览", icon: PhChartBar },
  { path: "/admin/users", title: "用户管理", icon: PhUsers },
];
function returnToUser() {
  const path = sessionStorage.getItem("admin:return:" + auth.user?.id) || "/";
  void router.push(
    path.startsWith("/") &&
      !path.startsWith("//") &&
      !path.startsWith("/admin") &&
      !path.startsWith("/login")
      ? path
      : "/",
  );
}
async function logout() {
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
  <div class="admin-shell" :class="{ 'is-collapsed': collapsed }">
    <aside class="admin-sidebar" aria-label="管理导航">
      <router-link to="/admin" class="admin-brand" aria-label="知映管理首页"
        ><span class="brand-mark"><i></i><b></b></span
        ><span class="admin-nav-label">知映管理</span></router-link
      >
      <nav class="admin-nav" aria-label="管理功能">
        <router-link
          v-for="link in links"
          :key="link.path"
          :to="link.path"
          :class="{ selected: route.path === link.path }"
          :title="collapsed ? link.title : undefined"
          ><component :is="link.icon" :size="22" /><span
            class="admin-nav-label"
            >{{ link.title }}</span
          ></router-link
        >
      </nav>
      <div class="admin-sidebar-bottom">
        <button class="admin-return" title="返回用户端" @click="returnToUser">
          <PhArrowUUpLeft :size="22" /><span class="admin-nav-label"
            >返回用户端</span
          >
        </button>
        <div class="admin-session">
          <el-avatar :size="36" :icon="UserFilled" /><span
            class="admin-nav-label"
            >{{ auth.user?.nickname || auth.user?.username }}</span
          >
        </div>
      </div>
    </aside>
    <div class="admin-main">
      <header class="admin-header">
        <button
          class="icon-button"
          :aria-label="collapsed ? '展开管理导航' : '收起管理导航'"
          @click="collapsed = !collapsed"
        >
          <PhSidebarSimple :size="22" />
        </button>
        <div class="admin-breadcrumb">
          <router-link to="/admin">管理面板</router-link
          ><span aria-hidden="true">/</span><span>{{ route.meta.title }}</span>
        </div>
        <StudioMenu
          :items="[
            { id: 'return', label: '返回用户端', icon: PhArrowUUpLeft },
            {
              id: 'logout',
              label: '退出登录',
              icon: PhSignOut,
              separator: true,
            },
          ]"
          label="管理账户菜单"
          @select="$event === 'return' ? returnToUser() : logout()"
          ><template #default="{ open }"
            ><button
              class="admin-account icon-button"
              aria-label="管理账户菜单"
              :aria-expanded="open"
            >
              <el-avatar :size="34" :icon="UserFilled" /></button></template
        ></StudioMenu>
      </header>
      <main class="admin-content">
        <router-view v-slot="{ Component }"
          ><Transition name="page" mode="out-in"
            ><component :is="Component" :key="String(route.name)" /></Transition
        ></router-view>
      </main>
    </div>
  </div>
</template>
