<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import {
  ArrowRight,
  Plus,
  VideoCamera,
  Collection,
  CircleCheck,
  Timer,
  Warning,
  Reading,
  ShoppingBag,
  Camera,
} from "@element-plus/icons-vue";
import { api, errorMessage } from "../lib/api";
import type { Dashboard } from "../lib/types";
import LoadError from "../components/LoadError.vue";
import TaskTable from "../components/TaskTable.vue";
const router = useRouter(),
  busy = ref(true),
  error = ref(""),
  dashboard = ref<Dashboard | null>(null);
async function load() {
  busy.value = true;
  error.value = "";
  try {
    dashboard.value = await api.dashboard();
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
onMounted(load);
const starters = [
  {
    title: "知识科普",
    label: "把生活中的常见误区讲清楚，帮助观众理解背后的原理",
    icon: Reading,
    color: "sage",
  },
  {
    title: "好物分享",
    label: "分享一件提升生活质量的好物，从真实使用场景出发",
    icon: ShoppingBag,
    color: "peach",
  },
  {
    title: "生活记录",
    label: "记录平凡生活中的一个温暖瞬间，传递真实感受",
    icon: Camera,
    color: "lavender",
  },
];
</script>
<template>
  <div>
    <div class="page-heading">
      <h1>创作总览</h1>
      <el-button
        type="primary"
        size="large"
        :icon="Plus"
        @click="router.push('/tasks/new')"
        >开始创作</el-button
      >
    </div>
    <LoadError v-if="error" :message="error" @retry="load" />
    <template v-else>
      <section class="stats-grid" v-loading="busy">
        <article class="stat-card">
          <span class="stat-icon"
            ><el-icon><Collection /></el-icon
          ></span>
          <div>
            <p>全部创作</p>
            <strong>{{ dashboard?.totalTasks ?? "—" }}</strong>
          </div>
        </article>
        <article class="stat-card">
          <span class="stat-icon sage"
            ><el-icon><CircleCheck /></el-icon
          ></span>
          <div>
            <p>已完成作品</p>
            <strong>{{ dashboard?.completedTasks ?? "—" }}</strong>
          </div>
        </article>
        <article class="stat-card">
          <span class="stat-icon peach"
            ><el-icon><Timer /></el-icon
          ></span>
          <div>
            <p>正在创作</p>
            <strong>{{ dashboard?.runningTasks ?? "—" }}</strong>
          </div>
        </article>
        <article class="stat-card">
          <span class="stat-icon lavender"
            ><el-icon><Warning /></el-icon
          ></span>
          <div>
            <p>待重试任务</p>
            <strong>{{ dashboard?.failedTasks ?? "—" }}</strong>
          </div>
        </article>
      </section>
      <section class="section-block">
        <div class="section-title"><h2>创作方向</h2></div>
        <div class="starter-grid">
          <button
            v-for="starter in starters"
            :key="starter.title"
            class="starter-card"
            @click="
              router.push({
                path: '/tasks/new',
                query: { topic: starter.label },
              })
            "
          >
            <span class="starter-icon" :class="starter.color"
              ><el-icon><component :is="starter.icon" /></el-icon
            ></span>
            <h3>{{ starter.title }}</h3>
            <el-icon><ArrowRight /></el-icon>
          </button>
        </div>
      </section>
      <section class="section-block">
        <div class="section-title">
          <h2>最近创作</h2>
          <el-button text @click="router.push('/tasks')"
            >全部作品<el-icon><ArrowRight /></el-icon
          ></el-button>
        </div>
        <div class="panel" v-loading="busy">
          <TaskTable
            v-if="dashboard?.recentTasks?.length"
            :tasks="dashboard.recentTasks.slice(0, 5)"
          />
          <div v-else class="empty-state">
            <span class="empty-art"
              ><el-icon><VideoCamera /></el-icon
            ></span>
            <h3>暂无作品</h3>
            <el-button
              type="primary"
              :icon="Plus"
              @click="router.push('/tasks/new')"
              >创建作品</el-button
            >
          </div>
        </div>
      </section>
    </template>
  </div>
</template>
