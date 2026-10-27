<script setup lang="ts">
import { PhSidebarSimple, PhClockCounterClockwise } from "@phosphor-icons/vue";
import { useWorkspace } from "../composables/useWorkspace";
import { useRoute } from "vue-router";
const workspace = useWorkspace(),
  route = useRoute();
</script>
<template>
  <div class="workspace-panel-controls">
    <button
      class="icon-button"
      :aria-label="workspace.toolsExpanded.value ? '收缩工具栏' : '展开工具栏'"
      :aria-expanded="workspace.toolsExpanded.value"
      @click="workspace.toggleTools"
    >
      <PhSidebarSimple :size="21" /></button
    ><button
      v-if="route.meta.workspace"
      class="icon-button"
      :aria-label="
        workspace.historyVisible.value ? '隐藏对话列表' : '显示对话列表'
      "
      :aria-pressed="workspace.historyVisible.value"
      @click="workspace.toggleHistory"
    >
      <PhClockCounterClockwise :size="21" />
    </button>
  </div>
</template>
