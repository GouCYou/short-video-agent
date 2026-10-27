<script setup lang="ts">
import { computed, ref } from "vue";
import { ElMessage } from "element-plus";
import {
  PhCopy,
  PhCheck,
  PhArrowClockwise,
  PhCaretDown,
  PhFileText,
  PhArrowUpRight,
  PhStackSimple,
  PhCircleNotch,
} from "@phosphor-icons/vue";
import type {
  ChatMessage as Message,
  RunEvent,
  Selection,
} from "../lib/conversation-types";
import { toolLabels } from "../lib/conversation-api";
import { durationText } from "../lib/format";
import ChatMessage from "./ChatMessage.vue";
import ChoiceCards from "./ChoiceCards.vue";
const props = defineProps<{
  message: Message;
  events: RunEvent[];
  timestamp?: string;
  disabled?: boolean;
  canRetry?: boolean;
  regenerating?: boolean;
  liveText?: string;
  conversationId?: string;
}>();
const emit = defineEmits<{
  retry: [];
  select: [choice: Omit<Selection, "messageId">];
}>();
const copied = ref(false),
  expanded = ref(false);
const displayContent = computed(() =>
  props.message.role === "assistant"
    ? props.message.content.replace(/。{2,}(?=已有消息和作品已保留)/g, "。")
    : props.message.content,
);
const thinkingDuration = computed(() =>
  durationText(
    props.message.metadata?.thinkingMs ??
      props.message.metadata?.processingMs ??
      0,
  ),
);
const steps = computed(() =>
  props.events
    .filter(
      (e) => e.runId === props.message.runId && e.type === "tool.completed",
    )
    .map((e) => toolLabels[e.tool || String(e.data.tool)] || "完成处理"),
);
const relatedWorks = computed(() => {
  if (props.message.role !== "assistant") return [];
  const found = new Map<
    string,
    { id: string; title: string; conversationId: string }
  >();
  for (const event of props.events) {
    if (
      event.runId !== props.message.runId ||
      event.type !== "tool.completed" ||
      (event.tool || event.data.tool) !== "read_history_creation"
    )
      continue;
    const output = event.data.output as Record<string, unknown> | undefined;
    if (
      output &&
      typeof output.id === "string" &&
      typeof output.conversationId === "string" &&
      /^[a-z0-9-]{36}$/i.test(output.conversationId) &&
      typeof output.title === "string"
    )
      found.set(output.id, {
        id: output.id,
        title: output.title,
        conversationId: output.conversationId,
      });
  }
  return [...found.values()];
});
const generatedWork = computed(() => {
  if (props.message.role !== "assistant" || !props.conversationId) return null;
  const update = props.events.filter(e => e.runId === props.message.runId && e.type === "artifact.updated").at(-1);
  const artifact = update?.data.artifact as { title?: string; content?: { title?: string }; versionNumber?: number } | undefined;
  if (!artifact) return null;
  return { title: artifact.title || artifact.content?.title || "作品方案", version: artifact.versionNumber };
});
async function copy() {
  try {
    await navigator.clipboard.writeText(displayContent.value);
    copied.value = true;
    setTimeout(() => (copied.value = false), 1800);
  } catch {
    ElMessage.error("复制失败，请重试");
  }
}
</script>
<template>
  <div :id="`message-${message.id}`" class="chat-turn" :class="message.role">
    <time
      v-if="timestamp"
      class="chat-timestamp"
      :datetime="message.createdAt"
      >{{ timestamp }}</time
    >
    <article class="conversation-message" :class="message.role">
      <div class="message-body">
        <template v-if="regenerating">
          <div class="regenerating-status" role="status">
            <PhCircleNotch class="agent-spinner" :size="16" /><span
              >重新生成中</span
            >
          </div>
          <ChatMessage v-if="liveText" :content="liveText" />
        </template>
        <template v-else>
          <div
            v-if="
              message.role === 'assistant' &&
              message.metadata?.thinkingMs !== undefined
            "
            class="message-process"
          >
            <button
              :aria-expanded="expanded"
              class="process-toggle"
              @click="expanded = !expanded"
            >
              <span>思考了 {{ thinkingDuration }}</span
              ><PhCaretDown :size="13" :class="{ rotated: expanded }" />
            </button>
            <div
              class="process-details"
              :class="{ 'is-expanded': expanded }"
              :inert="expanded ? undefined : true"
            >
              <div>
                <p v-if="message.metadata?.summary">
                  {{ message.metadata.summary }}
                </p>
                <p v-else-if="!steps.length">结合当前对话整理并生成回复。</p>
                <ol v-if="steps.length">
                  <li v-for="(step, index) in steps" :key="index">
                    {{ step }}
                  </li>
                </ol>
              </div>
            </div>
          </div>
          <div v-if="message.attachments?.length" class="message-files">
            <span v-for="file in message.attachments" :key="file.id"
              ><PhFileText :size="20" /><span>{{ file.name }}</span></span
            >
          </div>
          <ChatMessage
            v-if="message.role === 'assistant' && message.content"
            :content="displayContent"
          />
          <div v-else-if="message.content" class="message-content">
            {{ message.content }}
          </div>
          <ChoiceCards
            v-for="group in message.choices || []"
            :key="group.id"
            :group="group"
            :disabled="disabled"
            @select="emit('select', $event)"
          />
          <router-link v-if="generatedWork" :to="'/works/' + conversationId" class="message-work-card">
            <PhStackSimple :size="25" /><span><strong>{{ generatedWork.title }}</strong><span>作品方案<span v-if="generatedWork.version"> · V{{ generatedWork.version }}</span></span></span><PhArrowUpRight :size="20" />
          </router-link>
          <div v-if="relatedWorks.length" class="message-related-works">
            <router-link
              v-for="work in relatedWorks"
              :key="work.id"
              :to="`/works/${work.conversationId}`"
              ><PhStackSimple :size="21" /><span>{{ work.title }}</span
              ><PhArrowUpRight :size="17"
            /></router-link>
          </div>
          <div v-if="message.role === 'assistant'" class="message-actions">
            <button
              class="icon-button"
              :aria-label="copied ? '已复制' : '复制回复'"
              :title="copied ? '已复制' : '复制'"
              @click="copy"
            >
              <PhCheck v-if="copied" :size="17" /><PhCopy v-else :size="17" />
            </button>
            <button
              v-if="canRetry"
              class="icon-button"
              aria-label="重新生成回复"
              title="重试"
              :disabled="disabled"
              @click="emit('retry')"
            >
              <PhArrowClockwise :size="17" />
            </button>
          </div>
        </template>
      </div>
    </article>
  </div>
</template>
