<script setup lang="ts">
import { computed, ref } from "vue";
import { Check, Close } from "@element-plus/icons-vue";
import type { ChoiceGroup } from "../lib/conversation-types";
const props = defineProps<{ group: ChoiceGroup; disabled?: boolean }>();
const emit = defineEmits<{
  select: [
    selection: { groupId: string; optionId?: string; customText?: string },
  ];
}>();
const customOpen = ref(false),
  customText = ref("");
const locked = computed(() => props.disabled || props.group.status !== "OPEN");
function select(optionId: string) {
  if (!locked.value) emit("select", { groupId: props.group.id, optionId });
}
function custom() {
  if (locked.value || !customText.value.trim()) return;
  emit("select", {
    groupId: props.group.id,
    customText: customText.value.trim(),
  });
  customOpen.value = false;
  customText.value = "";
}
</script>
<template>
  <section
    class="choice-group"
    :class="{ superseded: group.status === 'SUPERSEDED' }"
  >
    <h3>{{ group.title }}</h3>
    <div class="choice-options">
      <button
        v-for="option in group.options"
        :key="option.id"
        class="choice-option"
        :class="{ selected: group.selectedOptionId === option.id }"
        :disabled="locked"
        :aria-pressed="group.selectedOptionId === option.id"
        @click="select(option.id)"
      >
        <span class="choice-letter">{{ option.label }}</span
        ><span class="choice-copy"
          ><strong>{{ option.title }}</strong
          ><span v-if="option.description">{{ option.description }}</span></span
        ><el-icon v-if="group.selectedOptionId === option.id"
          ><Check
        /></el-icon></button
      ><button
        class="choice-option custom-option"
        :class="{ selected: !!group.customValue }"
        :disabled="locked"
        :aria-expanded="customOpen"
        @click="customOpen = !customOpen"
      >
        <span class="choice-letter">D</span
        ><span class="choice-copy"
          ><strong>自定义</strong
          ><span v-if="group.customValue">{{ group.customValue }}</span></span
        ><el-icon v-if="group.customValue"><Check /></el-icon>
      </button>
    </div>
    <Transition name="reveal">
      <form
        v-if="customOpen && !locked"
        class="custom-choice-form"
        @submit.prevent="custom"
      >
        <el-input
          v-model="customText"
          type="textarea"
          :rows="2"
          :maxlength="2000"
          :aria-label="`${group.title}自定义内容`"
          placeholder="输入你的选择"
          autofocus
        />
        <div>
          <el-button :icon="Close" @click="customOpen = false">取消</el-button
          ><el-button
            type="primary"
            native-type="submit"
            :disabled="!customText.trim()"
            >确定</el-button
          >
        </div>
      </form>
    </Transition>
  </section>
</template>
