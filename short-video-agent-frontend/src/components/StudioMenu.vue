<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, type Component } from "vue";
export interface MenuItem {
  id: string;
  label: string;
  icon?: Component;
  danger?: boolean;
  disabled?: boolean;
  separator?: boolean;
}
const props = defineProps<{
  items: MenuItem[];
  label: string;
  align?: "start" | "end";
}>();
const emit = defineEmits<{ select: [id: string] }>();
const open = ref(false),
  trigger = ref<HTMLElement>(),
  panel = ref<HTMLElement>(),
  left = ref(0),
  top = ref(0);
function close(restore = false) {
  open.value = false;
  if (restore) trigger.value?.querySelector<HTMLElement>("button")?.focus();
}
async function toggle() {
  if (open.value) return close();
  const rect = trigger.value!.getBoundingClientRect();
  left.value = Math.max(
    8,
    Math.min(
      window.innerWidth - 240,
      props.align === "end" ? rect.right - 224 : rect.left,
    ),
  );
  top.value = Math.min(rect.bottom + 8, window.innerHeight - 12);
  open.value = true;
  await nextTick();
  if (
    panel.value &&
    top.value + panel.value.offsetHeight > window.innerHeight - 8
  )
    top.value = Math.max(8, rect.top - panel.value.offsetHeight - 8);
  panel.value
    ?.querySelector<HTMLButtonElement>("button:not(:disabled)")
    ?.focus();
}
function outside(e: PointerEvent) {
  if (
    !trigger.value?.contains(e.target as Node) &&
    !panel.value?.contains(e.target as Node)
  )
    close();
}
function key(e: KeyboardEvent) {
  if (e.key === "Escape") {
    e.preventDefault();
    close(true);
  }
  if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(e.key) || !panel.value)
    return;
  e.preventDefault();
  const buttons = [
    ...panel.value.querySelectorAll<HTMLButtonElement>("button:not(:disabled)"),
  ];
  const i = buttons.indexOf(document.activeElement as HTMLButtonElement);
  buttons[
    e.key === "Home"
      ? 0
      : e.key === "End"
        ? buttons.length - 1
        : (i + (e.key === "ArrowUp" ? -1 : 1) + buttons.length) % buttons.length
  ]?.focus();
}
const dismiss = () => close();
document.addEventListener("pointerdown", outside);
window.addEventListener("resize", dismiss);
onBeforeUnmount(() => {
  document.removeEventListener("pointerdown", outside);
  window.removeEventListener("resize", dismiss);
});
</script>
<template>
  <span
    ref="trigger"
    class="studio-menu-trigger"
    @click.stop="toggle"
    @keydown.esc="close(true)"
  >
    <slot :open="open" />
  </span>
  <Teleport to="body"
    ><Transition name="menu-pop">
      <div
        v-if="open"
        ref="panel"
        class="studio-menu"
        role="menu"
        :aria-label="label"
        :style="{ left: left + 'px', top: top + 'px' }"
        @keydown="key"
      >
        <template v-for="item in items" :key="item.id">
          <div
            v-if="item.separator"
            class="studio-menu-divider"
            role="separator"
          />
          <button
            role="menuitem"
            :disabled="item.disabled"
            :class="{ danger: item.danger }"
            @click="
              emit('select', item.id);
              close(true);
            "
          >
            <component v-if="item.icon" :is="item.icon" :size="18" /><span>{{
              item.label
            }}</span>
          </button>
        </template>
      </div>
    </Transition></Teleport
  >
</template>
