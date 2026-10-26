<script setup lang="ts">
import { computed, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ArrowRight, Lock, User } from "@element-plus/icons-vue";
import { api, errorMessage } from "../lib/api";
import { useAuth } from "../stores/auth";
const register = ref(false),
  username = ref(""),
  password = ref(""),
  nickname = ref(""),
  busy = ref(false),
  error = ref("");
const route = useRoute(),
  router = useRouter(),
  auth = useAuth();
const canSubmit = computed(
  () =>
    username.value.trim().length >= 3 &&
    (register.value ? password.value.length >= 8 : password.value.length > 0) &&
    (!register.value || !!nickname.value.trim()),
);
async function submit() {
  if (!canSubmit.value) return;
  busy.value = true;
  error.value = "";
  try {
    const session = register.value
      ? await api.register(
          username.value.trim(),
          password.value,
          nickname.value.trim(),
        )
      : await api.login(username.value.trim(), password.value);
    auth.accept(session);
    const target =
      typeof route.query.redirect === "string" &&
      route.query.redirect.startsWith("/") &&
      !route.query.redirect.startsWith("//")
        ? route.query.redirect
        : "/";
    await router.push(target);
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
function toggle() {
  register.value = !register.value;
  error.value = "";
}
</script>
<template>
  <div class="auth-page">
    <section class="auth-art">
      <div class="brand">
        <span class="brand-mark"><i></i><b></b></span><span>知映</span>
      </div>
      <div class="auth-visual" aria-hidden="true">
        <div class="visual-orbit"></div>
        <div class="visual-orbit second"></div>
        <div class="visual-tile tile-back"></div>
        <div class="visual-tile tile-front">
          <div class="visual-play"></div>
        </div>
        <div class="visual-circle"></div>
        <div class="visual-cross"></div>
      </div>
    </section>
    <section class="auth-form-area">
      <div class="auth-form">
        <h1>{{ register ? "创建账户" : "登录知映" }}</h1>
        <el-alert
          v-if="error"
          :title="error"
          type="error"
          show-icon
          :closable="false"
          class="mb-24"
        /><el-form label-position="top" @submit.prevent="submit"
          ><el-form-item label="用户名"
            ><el-input
              v-model="username"
              :prefix-icon="User"
              placeholder="3–32 位字母、数字或下划线"
              autocomplete="username"
              maxlength="32"
              size="large"
              required /></el-form-item
          ><el-form-item v-if="register" label="昵称"
            ><el-input
              v-model="nickname"
              placeholder="输入昵称"
              autocomplete="nickname"
              maxlength="32"
              size="large"
              required /></el-form-item
          ><el-form-item label="密码"
            ><el-input
              v-model="password"
              :prefix-icon="Lock"
              type="password"
              show-password
              :placeholder="register ? '至少 8 位字符' : '输入密码'"
              :autocomplete="register ? 'new-password' : 'current-password'"
              maxlength="128"
              size="large"
              required /></el-form-item
          ><el-button
            type="primary"
            size="large"
            native-type="submit"
            :loading="busy"
            :disabled="!canSubmit"
            class="auth-submit"
            >{{ register ? "创建账户" : "登录"
            }}<el-icon><ArrowRight /></el-icon></el-button
        ></el-form>
        <p class="auth-toggle">
          {{ register ? "已有账户？" : "还没有账户？"
          }}<button class="text-link" @click="toggle">
            {{ register ? "登录" : "注册账户" }}
          </button>
        </p>
      </div>
    </section>
  </div>
</template>
