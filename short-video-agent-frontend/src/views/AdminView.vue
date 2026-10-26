<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { Refresh, UserFilled } from "@element-plus/icons-vue";
import {
  PhUsers,
  PhUserCheck,
  PhUserMinus,
  PhShieldCheck,
  PhArrowUpRight,
} from "@phosphor-icons/vue";
import { api, errorMessage } from "../lib/api";
import type { User } from "../lib/types";
import { dateTime } from "../lib/format";
import LoadError from "../components/LoadError.vue";
const router = useRouter(),
  users = ref<User[]>([]),
  busy = ref(true),
  error = ref("");
const stats = computed(() => [
  { label: "全部用户", value: users.value.length, icon: PhUsers },
  {
    label: "正常账户",
    value: users.value.filter((u) => u.enabled).length,
    icon: PhUserCheck,
  },
  {
    label: "已停用",
    value: users.value.filter((u) => !u.enabled).length,
    icon: PhUserMinus,
  },
  {
    label: "管理员",
    value: users.value.filter((u) => u.role === "ADMIN").length,
    icon: PhShieldCheck,
  },
]);
const recent = computed(() =>
  [...users.value]
    .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
    .slice(0, 6),
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
</script>
<template>
  <div class="admin-overview">
    <div class="page-heading">
      <h1>管理概览</h1>
      <el-button :icon="Refresh" :loading="busy" @click="load">刷新</el-button>
    </div>
    <LoadError v-if="error" :message="error" @retry="load" />
    <template v-else
      ><section class="admin-stat-grid" v-loading="busy">
        <article
          v-for="stat in stats"
          :key="stat.label"
          class="admin-stat-card"
        >
          <span class="admin-stat-icon"
            ><component :is="stat.icon" :size="25"
          /></span>
          <p>{{ stat.label }}</p>
          <strong>{{ busy ? "—" : stat.value }}</strong>
        </article>
      </section>
      <section class="panel admin-recent-users">
        <div class="section-title">
          <h2>最近加入</h2>
          <el-button text @click="router.push('/admin/users')"
            >查看全部<PhArrowUpRight :size="18"
          /></el-button>
        </div>
        <el-table :data="recent" class="clean-table" empty-text="暂无用户"
          ><el-table-column label="用户" min-width="250"
            ><template #default="{ row }"
              ><div class="user-cell">
                <el-avatar :size="40" :icon="UserFilled" />
                <div>
                  <strong>{{ row.nickname || row.username }}</strong
                  ><span class="admin-username">@{{ row.username }}</span>
                </div>
              </div></template
            ></el-table-column
          ><el-table-column label="角色" min-width="140"
            ><template #default="{ row }">{{
              row.role === "ADMIN" ? "管理员" : "普通用户"
            }}</template></el-table-column
          ><el-table-column label="状态" min-width="140"
            ><template #default="{ row }"
              ><el-tag
                :type="row.enabled ? 'success' : 'info'"
                effect="plain"
                >{{ row.enabled ? "正常" : "已停用" }}</el-tag
              ></template
            ></el-table-column
          ><el-table-column label="加入时间" min-width="180"
            ><template #default="{ row }">{{
              dateTime(row.createdAt)
            }}</template></el-table-column
          ></el-table
        >
      </section></template
    >
  </div>
</template>
