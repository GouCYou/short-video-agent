<script setup lang="ts">
import { ref, useId, watch } from "vue";
import { PhCaretRight } from "@phosphor-icons/vue";
const props = withDefaults(defineProps<{ open?: boolean }>(), { open: false });
const expanded = ref(props.open);
const id = useId();
watch(
  () => props.open,
  (value) => {
    expanded.value = value;
  },
);
</script>
<template>
  <section class="animated-disclosure" :class="{ 'is-expanded': expanded }">
    <button
      type="button"
      class="disclosure-toggle"
      :aria-expanded="expanded"
      :aria-controls="id"
      @click="expanded = !expanded"
    >
      <PhCaretRight class="disclosure-caret" :size="16" aria-hidden="true" />
      <span class="disclosure-label"><slot name="summary" /></span>
    </button>
    <div
      class="disclosure-motion"
      :class="{ 'is-expanded': expanded }"
      :aria-hidden="!expanded"
      :inert="expanded ? undefined : true"
    >
      <div :id="id" class="disclosure-content"><slot /></div>
    </div>
  </section>
</template>
