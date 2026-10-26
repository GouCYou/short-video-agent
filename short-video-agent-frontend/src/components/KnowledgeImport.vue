<script setup lang="ts">
import { computed, ref, watch } from "vue";
import {
  PhUploadSimple,
  PhFileText,
  PhX,
  PhCheckCircle,
  PhWarningCircle,
} from "@phosphor-icons/vue";
import { api, errorMessage } from "../lib/api";
import type { KnowledgeImportItem } from "../lib/types";
import AnimatedDisclosure from "./AnimatedDisclosure.vue";
const props = defineProps<{ modelValue: boolean }>(),
  emit = defineEmits<{ "update:modelValue": [value: boolean]; imported: [] }>();
const files = ref<File[]>([]),
  busy = ref(false),
  error = ref(""),
  results = ref<KnowledgeImportItem[]>([]),
  category = ref("通用"),
  chunkSize = ref(700),
  chunkOverlap = ref(100),
  available = ref(false);
const complete = computed(
  () => results.value.filter((r) => !!r.documentId).length,
);
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      files.value = [];
      results.value = [];
      error.value = "";
    }
  },
);
function addFiles(selected: File[]) {
  error.value = "";
  for (const file of selected) {
    if (!/\.(txt|md|csv|docx?|pdf|pptx?|odt|odp|rtf|json)$/i.test(file.name)) {
      error.value = `不支持 ${file.name} 的格式`;
      continue;
    }
    if (file.size > 20 * 1024 * 1024) {
      error.value = `${file.name} 超过 20MB`;
      continue;
    }
    if (
      !files.value.some(
        (f) =>
          f.name === file.name &&
          f.size === file.size &&
          f.lastModified === file.lastModified,
      )
    )
      files.value.push(file);
  }
  if (files.value.length > 20) {
    files.value = files.value.slice(0, 20);
    error.value = "每次最多选择 20 个文件";
  }
}
function select(event: Event) {
  const input = event.target as HTMLInputElement;
  addFiles(Array.from(input.files || []));
  input.value = "";
}
function drop(event: DragEvent) {
  if (!busy.value) addFiles(Array.from(event.dataTransfer?.files || []));
}
async function start() {
  if (!files.value.length) return;
  if (files.value.reduce((n, f) => n + f.size, 0) > 100 * 1024 * 1024) {
    error.value = "总量不能超过 100MB";
    return;
  }
  if (chunkOverlap.value >= chunkSize.value) {
    error.value = "重叠长度须小于分块长度";
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    const body = new FormData();
    files.value.forEach((file) => body.append("files", file));
    body.append("category", category.value || "通用");
    body.append("chunkSize", String(chunkSize.value));
    body.append("chunkOverlap", String(chunkOverlap.value));
    const result = await api.importKnowledge(body);
    results.value = result.items;
    available.value = result.indexingAvailable;
    emit("imported");
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
function retryFailed() {
  const failed = new Set(
    results.value.filter((r) => !r.documentId).map((r) => r.name),
  );
  files.value = files.value.filter(
    (file) =>
      failed.has(file.name) || file.name.toLowerCase().endsWith(".json"),
  );
  results.value = [];
  void start();
}
</script>
<template>
  <el-dialog
    :model-value="modelValue"
    title="批量导入"
    width="min(760px,94vw)"
    :close-on-click-modal="!busy"
    :show-close="!busy"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template v-if="!results.length"
      ><label
        class="knowledge-import-drop"
        :class="{ disabled: busy }"
        @dragover.prevent
        @drop.prevent="drop"
        ><PhUploadSimple :size="32" /><strong>选择或拖入资料</strong
        ><span>Word、PDF、PPT、文本文件或知映知识包</span
        ><input
          type="file"
          multiple
          :disabled="busy"
          accept=".txt,.md,.csv,.doc,.docx,.pdf,.ppt,.pptx,.odt,.odp,.rtf,.json"
          aria-label="导入知识文件"
          @change="select"
      /></label>
      <div v-if="files.length" class="knowledge-import-files">
        <div
          v-for="(file, index) in files"
          :key="file.name + file.size + file.lastModified"
          class="knowledge-import-file"
        >
          <PhFileText :size="21" /><span>{{ file.name }}</span
          ><span class="muted"
            >{{ Math.max(1, Math.round(file.size / 1024)) }} KB</span
          ><button
            class="icon-button"
            :disabled="busy"
            :aria-label="'移除 ' + file.name"
            @click="files.splice(index, 1)"
          >
            <PhX :size="17" />
          </button>
        </div>
      </div>
      <AnimatedDisclosure class="knowledge-import-settings"
        ><template #summary>导入设置</template
        ><el-form label-position="top" @submit.prevent
          ><el-form-item label="分类"
            ><el-input v-model="category" maxlength="100" :disabled="busy"
          /></el-form-item>
          <div class="knowledge-edit-meta">
            <el-form-item label="分块长度"
              ><el-input-number
                v-model="chunkSize"
                :min="200"
                :max="2000"
                :step="100"
                :disabled="busy" /></el-form-item
            ><el-form-item label="重叠长度"
              ><el-input-number
                v-model="chunkOverlap"
                :min="0"
                :max="500"
                :step="50"
                :disabled="busy"
            /></el-form-item></div></el-form></AnimatedDisclosure
    ></template>
    <template v-else
      ><div class="import-result-heading">
        <PhCheckCircle :size="28" />
        <h3>已处理 {{ results.length }} 份资料</h3>
        <span>{{ complete }} 份已保存</span>
      </div>
      <el-alert
        v-if="!available && complete"
        title="资料已保存，配置向量模型后可建立索引。"
        type="info"
        :closable="false"
        class="mb-24"
      />
      <div class="knowledge-import-results">
        <div
          v-for="(item, index) in results"
          :key="index"
          class="knowledge-import-result"
        >
          <PhWarningCircle
            v-if="!item.documentId"
            :size="22"
            class="inline-error"
          /><PhCheckCircle v-else :size="22" />
          <div>
            <strong>{{ item.name }}</strong>
            <p v-if="item.error" class="inline-error">{{ item.error }}</p>
            <p v-else-if="item.indexError" class="inline-error">
              {{ item.indexError }}
            </p>
            <p v-else>
              {{
                item.duplicate
                  ? item.enabled === false
                    ? "已存在，当前已停用"
                    : "已存在，未重复导入"
                  : item.status === "INDEXING"
                    ? "正在建立索引"
                    : item.status === "READY"
                      ? "已可检索"
                      : "已保存，待建立索引"
              }}
            </p>
          </div>
        </div>
      </div></template
    >
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      class="mt-16"
    />
    <template #footer
      ><template v-if="!results.length"
        ><span class="import-count">{{ files.length }} 个文件 · 最多 20 个</span
        ><el-button :disabled="busy" @click="emit('update:modelValue', false)"
          >取消</el-button
        ><el-button
          type="primary"
          :loading="busy"
          :disabled="!files.length"
          @click="start"
          >一键导入</el-button
        ></template
      ><template v-else
        ><el-button
          v-if="results.some((r) => r.status === 'FAILED')"
          :loading="busy"
          @click="retryFailed"
          >重试失败项</el-button
        ><el-button type="primary" @click="emit('update:modelValue', false)"
          >完成</el-button
        ></template
      ></template
    >
  </el-dialog>
</template>
