<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRouter, useRoute } from "vue-router";
import CreationDefaultsPanel from "../components/CreationDefaultsPanel.vue";
import { PhUserCircle, PhSlidersHorizontal, PhBrain, PhShieldCheck } from "@phosphor-icons/vue";
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
const activeTab=computed(()=>["memory","creation","security"].includes(String(route.query.tab)) ? String(route.query.tab) : "account");
const settingsTitle=computed(()=>({account:"个人信息",security:"账户安全",creation:"创作默认值",memory:"画像与记忆"})[activeTab.value]);
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
 <div class="profile-page settings-page">
  <aside class="settings-navigation">
   <div class="settings-user"><el-avatar :size="48" :icon="UserFilled" aria-label="默认头像" /><div><strong>{{auth.user?.nickname || auth.user?.username}}</strong><span>@{{auth.user?.username}}</span></div></div>
   <nav aria-label="个人设置导航">
    <router-link to="/profile" :class="{selected:activeTab==='account'}" :aria-current="activeTab==='account' ? 'page' : undefined"><PhUserCircle :size="21" />个人信息</router-link>
    <router-link to="/profile?tab=security" :class="{selected:activeTab==='security'}" :aria-current="activeTab==='security' ? 'page' : undefined"><PhShieldCheck :size="21" />账户安全</router-link>
    <router-link to="/profile?tab=creation" :class="{selected:activeTab==='creation'}" :aria-current="activeTab==='creation' ? 'page' : undefined"><PhSlidersHorizontal :size="21" />创作默认值</router-link>
    <router-link to="/profile?tab=memory" :class="{selected:activeTab==='memory'}" :aria-current="activeTab==='memory' ? 'page' : undefined"><PhBrain :size="21" />画像与记忆</router-link>
   </nav>
  </aside>
  <main class="settings-content">
   <header class="settings-page-heading"><h1>{{settingsTitle}}</h1></header>
   <Transition name="settings-section" mode="out-in">
    <MemoryPanel v-if="activeTab==='memory'" key="memory" />
    <CreationDefaultsPanel v-else-if="activeTab==='creation'" key="creation" />
     <section v-else-if="activeTab==='account'" key="account" class="settings-card settings-account-panel">
      <el-form label-position="top" @submit.prevent="save">
       <el-form-item label="用户名"><el-input :model-value="auth.user?.username" disabled /></el-form-item>
       <el-form-item label="邮箱"><el-input :model-value="auth.user?.email || '未设置'" disabled /></el-form-item>
       <el-form-item label="创作者昵称"><el-input v-model="nickname" maxlength="32" placeholder="输入你的昵称" /></el-form-item>
       <div class="settings-joined"><span>加入时间</span><time>{{dateTime(auth.user?.createdAt)}}</time></div>
       <footer class="settings-card-footer"><el-button type="primary" :icon="Check" :loading="saving" :disabled="!nickname.trim()" @click="save">保存个人信息</el-button></footer>
      </el-form>
     </section>
     <section v-else key="security" class="settings-card settings-security-panel">
      <header class="settings-card-heading"><h2><el-icon><Lock /></el-icon>修改密码</h2></header>
      <el-form label-position="top" @submit.prevent="changePassword">
       <el-form-item label="当前密码"><el-input v-model="passwords.old" type="password" show-password autocomplete="current-password" maxlength="128" /></el-form-item>
       <div class="settings-password-pair">
        <el-form-item label="新密码"><el-input v-model="passwords.next" type="password" show-password autocomplete="new-password" placeholder="至少 8 位字符" maxlength="128" /></el-form-item>
        <el-form-item label="确认新密码"><el-input v-model="passwords.confirm" type="password" show-password autocomplete="new-password" maxlength="128" /></el-form-item>
       </div>
       <footer class="settings-card-footer"><el-button native-type="submit" :loading="changing" :disabled="!passwords.old || passwords.next.length<8 || passwords.next!==passwords.confirm">更新密码</el-button></footer>
      </el-form>
     </section>
   </Transition>
  </main>
 </div>
</template>
