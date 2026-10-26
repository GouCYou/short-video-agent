<script setup lang="ts">
import { computed, ref } from "vue";
const props = defineProps<{
  modelValue: string;
  options: { value: string; label: string }[];
  label: string;
}>();
const emit = defineEmits<{ "update:modelValue": [value: string] }>();
const root = ref<HTMLElement>();
const index = computed(() =>
  Math.max(
    0,
    props.options.findIndex((option) => option.value === props.modelValue),
  ),
);
function move(event: KeyboardEvent) {
  if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
  event.preventDefault();
  const next =
    event.key === "Home"
      ? 0
      : event.key === "End"
        ? props.options.length - 1
        : (index.value +
            (event.key === "ArrowLeft" ? -1 : 1) +
            props.options.length) %
          props.options.length;
  emit("update:modelValue", props.options[next]!.value);
  root.value?.querySelectorAll("button")[next]?.focus();
}
</script>
<template>
  <div
    ref="root"
    class="sliding-segment"
    role="group"
    :aria-label="label"
    :style="{ '--segment-count': options.length, '--segment-index': index }"
    @keydown="move"
  >
    <span class="segment-thumb" aria-hidden="true" />
    <button
      type="button"
      v-for="option in options"
      :key="option.value"
      :aria-pressed="modelValue === option.value"
      :class="{ selected: modelValue === option.value }"
      @click="emit('update:modelValue', option.value)"
    >
      {{ option.label }}
    </button>
  </div>
</template>
