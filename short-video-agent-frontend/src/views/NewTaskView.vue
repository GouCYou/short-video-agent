<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  ArrowLeft,
  ArrowRight,
  MagicStick,
  Reading,
  Select,
} from "@element-plus/icons-vue";
import { api, errorMessage } from "../lib/api";
import type { TaskInput } from "../lib/types";
const route = useRoute(),
  router = useRouter(),
  busy = ref(false),
  error = ref(""),
  categories = ref<string[]>([]);
const form = reactive<TaskInput>({
  title: "",
  topic: typeof route.query.topic === "string" ? route.query.topic : "",
  audience: "",
  platform: "抖音",
  style: "自然亲切",
  duration: 60,
  requirements: "",
  mode: "GUIDED",
  category: "",
  ragEnabled: true,
});
let submittedPayload = "",
  idempotencyKey = "";
const valid = computed(
  () =>
    form.topic.trim().length >= 5 &&
    form.audience.trim().length >= 2 &&
    !!form.platform &&
    !!form.style &&
    form.duration >= 15 &&
    form.duration <= 600,
);
onMounted(async () => {
  try {
    categories.value = await api.categories();
    if (typeof route.query.from === "string") {
      const source = await api.task(route.query.from);
      for (const key of Object.keys(form) as (keyof TaskInput)[])
        Object.assign(form, { [key]: source[key] });
      form.title = `${source.title || source.topic}（副本）`.slice(0, 100);
    }
  } catch (e) {
    error.value = errorMessage(e);
  }
});
async function submit() {
  if (!valid.value || busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    const payload = {
      ...form,
      title: form.title.trim() || form.topic.trim().slice(0, 48),
      topic: form.topic.trim(),
      audience: form.audience.trim(),
    };
    const serialized = JSON.stringify(payload);
    if (serialized !== submittedPayload) {
      idempotencyKey = crypto.randomUUID();
      submittedPayload = serialized;
    }
    const task = await api.create(payload, idempotencyKey);
    await router.push(`/tasks/${task.id}`);
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div class="narrow-page creation-page">
    <el-button text :icon="ArrowLeft" class="back-link" @click="router.back()"
      >返回</el-button
    >
    <div class="page-heading"><h1>新建创作</h1></div>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
      class="mb-24"
    /><el-form
      class="creation-grid"
      label-position="top"
      @submit.prevent="submit"
      ><section class="panel creation-form">
        <h2 class="form-section-heading">创作需求</h2>
        <el-form-item label="创作主题" required
          ><el-input
            v-model="form.topic"
            type="textarea"
            :rows="5"
            placeholder="例如：给大学新生分享 3 个校园防诈骗场景"
            maxlength="2000" /></el-form-item
        ><el-form-item label="作品名称"
          ><el-input
            v-model="form.title"
            placeholder="留空则使用创作主题"
            maxlength="100" /></el-form-item
        ><el-form-item label="目标受众" required
          ><el-input
            v-model="form.audience"
            placeholder="例如：18–24 岁的大学生"
            maxlength="200" /></el-form-item
        ><el-form-item label="补充要求"
          ><el-input
            v-model="form.requirements"
            type="textarea"
            :rows="4"
            placeholder="选填：内容重点、开场方式等"
            maxlength="2000"
        /></el-form-item>
      </section>
      <aside class="panel creation-settings">
        <h2 class="form-section-heading">生成设置</h2>
        <div class="mode-options">
          <button
            type="button"
            class="mode-card"
            :class="{ selected: form.mode === 'GUIDED' }"
            :aria-pressed="form.mode === 'GUIDED'"
            @click="form.mode = 'GUIDED'"
          >
            <el-icon><Select /></el-icon><strong>引导创作</strong
            ><span class="mode-radio"></span></button
          ><button
            type="button"
            class="mode-card"
            :class="{ selected: form.mode === 'QUICK' }"
            :aria-pressed="form.mode === 'QUICK'"
            @click="form.mode = 'QUICK'"
          >
            <el-icon><MagicStick /></el-icon><strong>快速生成</strong
            ><span class="mode-radio"></span>
          </button>
        </div>
        <el-form-item label="发布平台" required
          ><el-select v-model="form.platform"
            ><el-option
              v-for="p in ['抖音', '小红书', '哔哩哔哩', '快手', '微信视频号']"
              :key="p"
              :value="p"
              :label="p" /></el-select></el-form-item
        ><el-form-item label="表达风格" required
          ><el-select
            v-model="form.style"
            allow-create
            filterable
            default-first-option
            placeholder="选择或输入风格"
            ><el-option
              v-for="s in [
                '自然亲切',
                '轻松幽默',
                '专业严谨',
                '故事叙述',
                '温暖治愈',
                '简洁直接',
              ]"
              :key="s"
              :value="s"
              :label="s" /></el-select></el-form-item
        ><el-form-item label="视频时长" required
          ><el-select v-model="form.duration"
            ><el-option
              v-for="d in [15, 30, 60, 90, 120, 180, 300, 600]"
              :key="d"
              :label="d < 60 ? `${d} 秒` : `${d / 60} 分钟`"
              :value="d" /></el-select
        ></el-form-item>
        <div class="rag-option">
          <div>
            <el-icon><Reading /></el-icon><strong>知识库辅助</strong>
          </div>
          <el-switch
            v-model="form.ragEnabled"
            aria-label="使用知识库辅助创作"
          />
        </div>
        <el-form-item v-if="form.ragEnabled" label="知识分类"
          ><el-select v-model="form.category" clearable placeholder="全部分类"
            ><el-option
              v-for="category in categories"
              :key="category"
              :label="category"
              :value="category" /></el-select
        ></el-form-item>
        <div class="form-submit">
          <el-button
            type="primary"
            size="large"
            native-type="submit"
            :loading="busy"
            :disabled="!valid"
            >开始创作<el-icon><ArrowRight /></el-icon
          ></el-button>
        </div></aside
    ></el-form>
  </div>
</template>
