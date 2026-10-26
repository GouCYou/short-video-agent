<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { useRouter, useRoute } from "vue-router";
import MemoryPanel from "../components/MemoryPanel.vue";
import { ElMessage } from "element-plus";
import { Check, Lock, UserFilled } from "@element-plus/icons-vue";
import { api, errorMessage } from "../lib/api";
import { useAuth } from "../stores/auth";
import { dateTime } from "../lib/format";
const router = useRouter(),
  route = useRoute(),
  auth = useAuth(),
  nickname = ref(auth.user?.nickname || ""),
  saving = ref(false),
  changing = ref(false),
  passwords = reactive({ old: "", next: "", confirm: "" });
onMounted(() => {
  nickname.value = auth.user?.nickname || "";
});
async function save() {
  if (!nickname.value.trim()) return;
  saving.value = true;
  try {
    auth.user = await api.updateProfile(nickname.value.trim());
    ElMessage.success("个人信息已更新");
  } catch (e) {
    ElMessage.error(errorMessage(e));
  } finally {
    saving.value = false;
  }
}
async function changePassword() {
  if (passwords.next.length < 8 || passwords.next !== passwords.confirm) {
    ElMessage.warning("新密码至少 8 位，且两次输入需要一致");
    return;
  }
  changing.value = true;
  try {
    await api.password(passwords.old, passwords.next);
    Object.assign(passwords, { old: "", next: "", confirm: "" });
    auth.clear();
    await router.push("/login");
    ElMessage.success("密码已更新，请使用新密码重新登录。");
  } catch (e) {
    ElMessage.error(errorMessage(e));
  } finally {
    changing.value = false;
  }
}
</script>
<template>
  <div class="profile-page">
    <div class="page-heading">
      <div>
        <h1>{{ route.query.tab === "memory" ? "画像与记忆" : "个人设置" }}</h1>
      </div>
    </div>
    <div class="profile-tabs">
      <router-link
        to="/profile"
        :class="{ selected: route.query.tab !== 'memory' }"
        >账户</router-link
      ><router-link
        to="/profile?tab=memory"
        :class="{ selected: route.query.tab === 'memory' }"
        >画像与记忆</router-link
      >
    </div>
    <MemoryPanel v-if="route.query.tab === 'memory'" />
    <div v-else class="profile-layout">
      <section class="panel profile-panel">
        <div class="profile-header">
          <el-avatar
            :size="64"
            :icon="UserFilled"
            class="avatar large"
            aria-label="默认头像"
          />
          <div>
            <h2>{{ auth.user?.nickname || auth.user?.username }}</h2>
            <p>
              @{{ auth.user?.username }} ·
              {{ auth.admin ? "管理员" : "创作者" }}
            </p>
          </div>
        </div>
        <el-form label-position="top" @submit.prevent="save"
          ><el-form-item label="用户名"
            ><el-input
              :model-value="auth.user?.username"
              disabled /></el-form-item
          ><el-form-item label="创作者昵称"
            ><el-input
              v-model="nickname"
              maxlength="32"
              placeholder="输入你的昵称" /></el-form-item
          ><el-form-item label="加入时间"
            ><span class="muted">{{
              dateTime(auth.user?.createdAt)
            }}</span></el-form-item
          ><el-button
            type="primary"
            :icon="Check"
            :loading="saving"
            :disabled="!nickname.trim()"
            @click="save"
            >保存个人信息</el-button
          ></el-form
        >
      </section>
      <section class="panel profile-panel">
        <div class="profile-section-title">
          <el-icon><Lock /></el-icon>
          <h2>账户安全</h2>
        </div>

        <el-form label-position="top" @submit.prevent="changePassword"
          ><el-form-item label="当前密码"
            ><el-input
              v-model="passwords.old"
              type="password"
              show-password
              autocomplete="current-password"
              maxlength="128" /></el-form-item
          ><el-form-item label="新密码"
            ><el-input
              v-model="passwords.next"
              type="password"
              show-password
              autocomplete="new-password"
              placeholder="至少 8 位字符"
              maxlength="128" /></el-form-item
          ><el-form-item label="确认新密码"
            ><el-input
              v-model="passwords.confirm"
              type="password"
              show-password
              autocomplete="new-password"
              maxlength="128" /></el-form-item
          ><el-button
            native-type="submit"
            :loading="changing"
            :disabled="
              !passwords.old ||
              passwords.next.length < 8 ||
              passwords.next !== passwords.confirm
            "
            >更新密码</el-button
          ></el-form
        >
      </section>
    </div>
  </div>
</template>
