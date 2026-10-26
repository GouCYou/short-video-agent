<script setup lang="ts">
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { UserFilled } from "@element-plus/icons-vue";
import {
  PhChatTeardropText as ChatDotRound,
  PhStackSimple as Collection,
  PhBooks as Files,
  PhSlidersHorizontal as Setting,
  PhSignOut as SwitchButton,
  PhPlus as Plus,
} from "@phosphor-icons/vue";
import { useAuth } from "../stores/auth";
const auth = useAuth(),
  router = useRouter();
defineEmits<{ new: [] }>();
async function logout() {
  try {
    await auth.logout();
  } catch {
    ElMessage.warning("已退出当前账户");
  }
  await router.push("/login");
}
</script>
<template>
  <nav class="workspace-rail" aria-label="工作空间导航">
    <router-link to="/" class="rail-logo" aria-label="知映首页"
      ><span class="brand-mark"><i></i><b></b></span></router-link
    ><el-tooltip content="新建对话" placement="right"
      ><el-button
        :icon="Plus"
        circle
        type="primary"
        aria-label="新建对话"
        @click="$emit('new')" /></el-tooltip
    ><router-link to="/" class="rail-link active" aria-label="对话创作"
      ><el-icon><ChatDotRound /></el-icon></router-link
    ><router-link to="/tasks" class="rail-link" aria-label="我的作品"
      ><el-icon><Collection /></el-icon></router-link
    ><router-link
      v-if="auth.admin"
      to="/admin/knowledge"
      class="rail-link"
      aria-label="知识资料库"
      ><el-icon><Files /></el-icon></router-link
    ><router-link
      v-if="auth.admin"
      to="/admin"
      class="rail-link"
      aria-label="管理概览"
      ><el-icon><Setting /></el-icon
    ></router-link>
    <div class="rail-account">
      <el-dropdown
        placement="right-end"
        @command="
          (command: string) =>
            command === 'logout' ? logout() : router.push('/profile')
        "
        ><button class="rail-profile" aria-label="账户菜单">
          <el-avatar
            :size="38"
            :icon="UserFilled"
            aria-label="默认头像"
          /></button
        ><template #dropdown
          ><el-dropdown-menu
            ><el-dropdown-item command="profile">个人设置</el-dropdown-item
            ><el-dropdown-item command="logout" :icon="SwitchButton" divided
              >退出登录</el-dropdown-item
            ></el-dropdown-menu
          ></template
        ></el-dropdown
      >
    </div>
  </nav>
</template>
