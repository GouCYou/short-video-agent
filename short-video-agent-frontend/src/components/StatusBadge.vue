<script setup lang="ts">
import { statusLabels } from "../lib/format";
import type { TaskStatus } from "../lib/types";
defineProps<{ status: TaskStatus }>();
</script>
<template>
  <span class="status-badge" :class="status.toLowerCase()"
    ><i></i>{{ statusLabels[status] || status }}</span
  >
</template>
