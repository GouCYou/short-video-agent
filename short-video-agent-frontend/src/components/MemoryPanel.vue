<script setup lang="ts">
import { onMounted, onUnmounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  PhBrain,
  PhTrash,
  PhPlus,
  PhArrowUpRight,
  PhCheck,
} from "@phosphor-icons/vue";
import { conversationApi } from "../lib/conversation-api";
import { errorMessage } from "../lib/api";
import type { UserMemory } from "../lib/conversation-types";
import LoadError from "./LoadError.vue";
const router = useRouter(),
  memory = ref<UserMemory>(),
  error = ref(""),
  busy = ref(false),
  saving = ref(false),
  dirty = ref(false);
let timer: ReturnType<typeof setInterval> | undefined,
  disposed = false;
async function load(quiet = false) {
  if (!quiet) busy.value = true;
  try {
    const updated = await conversationApi.memory();
    if (disposed || (quiet && dirty.value)) return;
    memory.value = updated;
    dirty.value = false;
    error.value = "";
  } catch (e) {
    if (!quiet) error.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
async function save() {
  if (!memory.value || saving.value) return;
  saving.value = true;
  try {
    memory.value = await conversationApi.updateMemory({
      enabled: memory.value.enabled,
      items: memory.value.items.filter((i) => i.text.trim()),
    });
    dirty.value = false;
    ElMessage.success("记忆已更新");
  } catch (e) {
    ElMessage.error(errorMessage(e));
    await load();
  } finally {
    saving.value = false;
  }
}
async function clear() {
  try {
    await ElMessageBox.confirm(
      "清空用户画像和已保存的聊天摘要？历史对话与作品会保留。",
      "清空记忆",
      {
        confirmButtonText: "清空记忆",
        cancelButtonText: "保留",
        type: "warning",
      },
    );
    await conversationApi.clearMemory();
    await load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorMessage(e));
  }
}
onMounted(() => {
  void load();
  timer = setInterval(() => {
    if (!dirty.value && !saving.value) void load(true);
  }, 10000);
});
onUnmounted(() => {
  disposed = true;
  clearInterval(timer);
});
</script>
<template>
  <div class="memory-panel" v-loading="busy">
    <LoadError v-if="error" :message="error" @retry="load()" />
    <template v-if="memory"
      ><section class="panel memory-profile">
        <div class="memory-heading">
          <div>
            <PhBrain :size="24" />
            <h2>用户画像</h2>
          </div>
          <el-switch
            v-model="memory.enabled"
            aria-label="自动记忆"
            :disabled="saving"
            @change="save"
          />
        </div>
        <p class="memory-description">记住你的创作偏好，在之后的对话中延续。</p>
        <div class="memory-items">
          <div
            v-for="(item, index) in memory.items"
            :key="index"
            class="memory-item"
          >
            <el-input
              v-model="item.text"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 4 }"
              maxlength="500"
              :aria-label="'创作偏好 ' + (index + 1)"
              @input="dirty = true"
            /><button
              class="icon-button"
              :aria-label="'移除偏好 ' + (index + 1)"
              @click="
                memory.items.splice(index, 1);
                dirty = true;
              "
            >
              <PhTrash :size="18" />
            </button>
          </div>
          <p v-if="!memory.items.length" class="memory-empty">
            还没有保存的创作偏好
          </p>
        </div>
        <div class="memory-actions">
          <el-button
            :icon="PhPlus"
            text
            :disabled="memory.items.length >= 30"
            @click="
              memory.items.push({ text: '', evidence: '用户编辑' });
              dirty = true;
            "
            >添加偏好</el-button
          ><el-button
            type="primary"
            :icon="PhCheck"
            :loading="saving"
            @click="save"
            >保存画像</el-button
          >
        </div>
      </section>
      <section class="panel memory-summaries">
        <div class="memory-heading">
          <h2>聊天记忆</h2>
          <el-button text :icon="PhTrash" @click="clear">清空记忆</el-button>
        </div>
        <div
          v-for="item in memory.conversations"
          :key="item.id"
          class="memory-summary"
        >
          <button @click="router.push('/conversations/' + item.id)">
            <strong>{{ item.title }}</strong
            ><PhArrowUpRight :size="17" />
          </button>
          <p>{{ item.summary }}</p>
        </div>
        <p v-if="!memory.conversations.length" class="memory-empty">
          对话后会自动整理摘要
        </p>
      </section></template
    >
  </div>
</template>
