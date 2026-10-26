<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string;
    editable?: boolean;
    rows?: number;
    max?: number;
  }>(),
  { editable: false, rows: 3, max: 10000 },
);
const value = defineModel<string>({ required: true });
</script>
<template>
  <div class="production-field">
    <label>{{ label }}</label
    ><el-input
      v-if="editable"
      v-model="value"
      :aria-label="label"
      type="textarea"
      :rows="rows"
      :maxlength="max"
    />
    <p v-else>{{ value || "—" }}</p>
  </div>
</template>
