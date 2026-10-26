<script setup lang="ts">
import { computed } from "vue";
withDefaults(defineProps<{ label: string; editable?: boolean }>(), {
  editable: false,
});
const value = defineModel<string[]>({ required: true });
const lines = computed({
  get: () => value.value.join("\n"),
  set: (text: string) => (value.value = text.split("\n")),
});
</script>
<template>
  <div class="production-field">
    <label>{{ label }}</label
    ><el-input
      v-if="editable"
      v-model="lines"
      :aria-label="label"
      type="textarea"
      :rows="3"
      maxlength="20000"
    />
    <ul v-else-if="value.length">
      <li v-for="(item, i) in value" :key="i">{{ item }}</li>
    </ul>
    <p v-else>—</p>
  </div>
</template>
