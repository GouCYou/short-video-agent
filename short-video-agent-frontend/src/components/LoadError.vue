<script setup lang="ts">
defineProps<{ message: string }>();
defineEmits<{ retry: [] }>();
</script>
<template>
  <div class="load-error" role="alert">
    <span class="error-symbol">!</span>
    <div>
      <strong>暂时没有加载成功</strong>
      <p>{{ message }}</p>
      <el-button size="small" @click="$emit('retry')">重新加载</el-button>
    </div>
  </div>
</template>
