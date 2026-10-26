<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Search, Refresh, UserFilled } from "@element-plus/icons-vue";
import { api, errorMessage } from "../lib/api";
import type { User } from "../lib/types";
import { dateTime } from "../lib/format";
import { useAuth } from "../stores/auth";
import LoadError from "../components/LoadError.vue";
const auth = useAuth(),
  users = ref<User[]>([]),
  busy = ref(true),
  error = ref(""),
  search = ref(""),
  mutating = ref("");
const filtered = computed(() =>
  users.value.filter((u) =>
    `${u.username} ${u.nickname}`
      .toLowerCase()
      .includes(search.value.toLowerCase()),
  ),
);
async function load() {
  busy.value = true;
  error.value = "";
  try {
    users.value = await api.users();
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
onMounted(load);
async function update(user: User, enabled: boolean, role: string) {
  try {
    await ElMessageBox.confirm(
      role !== user.role
        ? `将「${user.nickname || user.username}」设为${role === "ADMIN" ? "管理员" : "普通用户"}？`
        : `${enabled ? "启用" : "停用"}「${user.nickname || user.username}」的账户？`,
      "更改用户权限",
      {
        confirmButtonText: "确认更改",
        cancelButtonText: "取消",
        type: "warning",
      },
    );
    mutating.value = user.id;
    const result = await api.updateUser(user.id, enabled, role);
    users.value = users.value.map((u) => (u.id === result.id ? result : u));
    ElMessage.success("用户设置已更新");
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorMessage(e));
  } finally {
    mutating.value = "";
  }
}
</script>
<template>
  <div>
    <div class="page-heading">
      <div>
        <h1>用户管理</h1>
      </div>
      <el-button :icon="Refresh" :loading="busy" @click="load"
        >刷新列表</el-button
      >
    </div>
    <div class="toolbar panel">
      <el-input
        v-model="search"
        :prefix-icon="Search"
        placeholder="搜索用户名或昵称"
        clearable
        class="search-input"
      /><span class="result-count">共 {{ filtered.length }} 位用户</span>
    </div>
    <LoadError v-if="error" :message="error" @retry="load" />
    <div v-else class="panel" v-loading="busy">
      <el-table :data="filtered" class="clean-table" empty-text="没有匹配的用户"
        ><el-table-column label="创作者" min-width="220"
          ><template #default="{ row }"
            ><div class="user-cell">
              <el-avatar
                :size="40"
                :icon="UserFilled"
                class="avatar"
                aria-label="默认头像"
              />
              <div>
                <strong
                  >{{ row.nickname || row.username
                  }}<el-tag
                    v-if="row.id === auth.user?.id"
                    size="small"
                    effect="plain"
                    class="self-tag"
                    >你</el-tag
                  ></strong
                ><small>@{{ row.username }}</small>
              </div>
            </div></template
          ></el-table-column
        ><el-table-column label="角色" min-width="160"
          ><template #default="{ row }"
            ><el-select
              :model-value="row.role"
              :disabled="row.id === auth.user?.id || mutating === row.id"
              size="small"
              style="max-width: 140px"
              :aria-label="`${row.username} 的角色`"
              @change="(role: string) => update(row, row.enabled, role)"
              ><el-option label="普通用户" value="USER" /><el-option
                label="管理员"
                value="ADMIN" /></el-select></template></el-table-column
        ><el-table-column label="账户状态" min-width="140"
          ><template #default="{ row }"
            ><el-switch
              :model-value="row.enabled"
              :disabled="row.id === auth.user?.id || mutating === row.id"
              :aria-label="`${row.enabled ? '停用' : '启用'}用户 ${row.username}`"
              @change="
                (enabled: string | number | boolean) =>
                  update(row, !!enabled, row.role)
              "
            /><span class="switch-label">{{
              row.enabled ? "正常" : "已停用"
            }}</span></template
          ></el-table-column
        ><el-table-column label="注册时间" min-width="150"
          ><template #default="{ row }">{{
            dateTime(row.createdAt)
          }}</template></el-table-column
        ></el-table
      >
    </div>
  </div>
</template>
