<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { PhStackSimple, PhPaperclip } from "@phosphor-icons/vue";
import { conversationApi } from "../lib/conversation-api";
import type { Conversation } from "../lib/conversation-types";
import { errorMessage } from "../lib/api";
const open = defineModel<boolean>({ required: true });
const emit = defineEmits<{ select: [file: File] }>();
const works = ref<Conversation[]>([]), query = ref(""), loading = ref(false), selecting = ref(""), error = ref("");
let revision = 0;
const filtered = computed(() => works.value.filter(w => (w.artifact?.title || w.title).toLowerCase().includes(query.value.trim().toLowerCase())));
watch(open, async value => {
  const current = ++revision;
  if (!value) return;
  loading.value = true; error.value = ""; query.value = "";
  try {
    const values = await conversationApi.list();
    if (current === revision) works.value = values.filter(w => w.artifact);
  } catch(e) { if (current === revision) error.value = errorMessage(e); }
  finally { if (current === revision) loading.value = false; }
});
async function attach(work: Conversation) {
  if (selecting.value) return;
  selecting.value = work.id; error.value = "";
  try {
    const blob = await conversationApi.export(work.id, "markdown");
    const name = (work.artifact?.title || work.title).replace(/[\\/:*?"<>|]/g, "_").slice(0, 120);
    emit("select", new File([blob], name + ".md", { type: "text/markdown" }));
    open.value = false;
  } catch(e) { error.value = errorMessage(e); }
  finally { selecting.value = ""; }
}
</script>
<template>
  <el-dialog v-model="open" title="添加已有作品" width="min(680px, 94vw)" :close-on-click-modal="!selecting">
    <div class="work-attachment-picker">
      <el-input v-model="query" placeholder="搜索作品" clearable :disabled="!!selecting" />
      <p v-if="error" role="alert">{{ error }}</p>
      <div v-if="loading" class="work-picker-empty" role="status">正在读取作品</div>
      <div v-else-if="!filtered.length" class="work-picker-empty">{{ query ? '没有匹配的作品' : '暂无作品' }}</div>
      <div v-else class="work-picker-list">
        <button v-for="work in filtered" :key="work.id" class="work-picker-row" :disabled="!!selecting" @click="attach(work)">
          <PhStackSimple :size="23" /><span>{{ work.artifact?.title || work.title }}</span>
          <span v-if="selecting === work.id" role="status">正在添加</span><PhPaperclip v-else :size="19" />
        </button>
      </div>
    </div>
  </el-dialog>
</template>
