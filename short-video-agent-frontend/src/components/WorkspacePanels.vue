<script setup lang="ts">
import { PhSidebarSimple, PhClockCounterClockwise } from "@phosphor-icons/vue";
import { useWorkspace } from "../composables/useWorkspace";
const workspace = useWorkspace();
</script>
<template>
  <div class="workspace-panel-controls">
    <button
      class="icon-button"
      :aria-label="workspace.toolsVisible.value ? '隐藏工具栏' : '显示工具栏'"
      :aria-pressed="workspace.toolsVisible.value"
      @click="workspace.toggleTools"
    >
      <PhSidebarSimple :size="21" /></button
    ><button
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
