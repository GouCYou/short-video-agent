<script setup lang="ts">
import { useRouter } from "vue-router";
import { ArrowRight, VideoCamera } from "@element-plus/icons-vue";
import type { Task } from "../lib/types";
import { dateTime } from "../lib/format";
import StatusBadge from "./StatusBadge.vue";
defineProps<{ tasks: Task[]; admin?: boolean }>();
const router = useRouter();
</script>
<template>
  <div class="task-list">
    <div class="task-list-head">
      <span>作品 / 创作需求</span><span>创作设置</span><span>状态</span
      ><span>更新时间</span><span></span>
    </div>
    <div v-for="task in tasks" :key="task.id" class="task-row">
      <div class="task-main">
        <span class="task-glyph" aria-hidden="true"
          ><el-icon><VideoCamera /></el-icon
        ></span>
        <div>
          <button
            v-if="!admin"
            class="text-link task-title"
            @click="router.push(`/tasks/${task.id}`)"
          >
            {{ task.title || task.topic }}</button
          ><strong v-else class="task-title">{{
            task.title || task.topic
          }}</strong>
          <p>{{ task.topic }}</p>
        </div>
      </div>
      <div class="task-setting">
        <span>{{ task.platform }} · {{ task.duration }} 秒</span
        ><small>{{ task.style }}</small>
      </div>
      <StatusBadge :status="task.status" /><time>{{
        dateTime(task.updatedAt)
      }}</time
      ><slot name="actions" :task="task"
        ><el-button
          v-if="!admin"
          text
          circle
          :icon="ArrowRight"
          :aria-label="`打开作品 ${task.title}`"
          @click="router.push(`/tasks/${task.id}`)"
      /></slot>
    </div>
  </div>
</template>
