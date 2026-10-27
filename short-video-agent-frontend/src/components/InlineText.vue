<script setup lang="ts">
import { nextTick, ref, watch } from "vue";
const props = withDefaults(
  defineProps<{ label: string; editable?: boolean; multiline?: boolean }>(),
  { editable: false, multiline: true },
);
const value = defineModel<string>({ required: true });
const active = ref(false),
  input = ref<HTMLTextAreaElement>();
let previous = "";
watch(
  () => props.editable,
  (value) => {
    if (!value) active.value = false;
  },
);
function resize() {
  if (input.value) {
    input.value.style.height = "auto";
    input.value.style.height = input.value.scrollHeight + "px";
  }
}
async function edit() {
  if (!props.editable) return;
  previous = value.value;
  active.value = true;
  await nextTick();
  resize();
  input.value?.focus();
}
function keydown(event: KeyboardEvent) {
  if (event.key === "Escape") {
    value.value = previous;
    active.value = false;
  } else if (
    event.key === "Enter" &&
    (!props.multiline || event.metaKey || event.ctrlKey)
  ) {
    event.preventDefault();
    active.value = false;
  }
}
</script>
<template>
  <div
    class="inline-copy"
    :class="{ 'can-edit': editable, 'is-editing': active }"
  >
    <textarea
      v-if="active"
      ref="input"
      v-model="value"
      :aria-label="label"
      rows="1"
      maxlength="10000"
      @input="resize"
      @blur="active = false"
      @keydown="keydown"
    />
    <button
      v-else-if="editable"
      class="inline-copy-button"
      :aria-label="'修改' + label"
      @click="edit"
    >
      {{ value || "点击补充" }}
    </button>
    <p v-else>{{ value || "—" }}</p>
  </div>
</template>
