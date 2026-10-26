<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  Plus,
  Search,
  UploadFilled,
  Document,
  Refresh,
  Delete,
  EditPen,
  View,
  Link,
  Download,
} from "@element-plus/icons-vue";
import { api, errorMessage } from "../lib/api";
import type { KnowledgeDocument, Source, ChunkPreview } from "../lib/types";
import { safeUrl } from "../lib/format";
import LoadError from "../components/LoadError.vue";
import SlidingSegment from "../components/SlidingSegment.vue";
import KnowledgeImport from "../components/KnowledgeImport.vue";
import AnimatedDisclosure from "../components/AnimatedDisclosure.vue";
const route = useRoute();
const importOpen = ref(false),
  chunkPreview = ref<ChunkPreview | null>(null),
  previewing = ref(false);
async function exportLibrary() {
  try {
    const payload = await api.exportKnowledge(),
      url = URL.createObjectURL(
        new Blob([JSON.stringify(payload, null, 2)], {
          type: "application/json",
        }),
      ),
      a = document.createElement("a");
    a.href = url;
    a.download = "知映知识库.json";
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (e) {
    ElMessage.error(errorMessage(e));
  }
}
async function previewChunks() {
  previewing.value = true;
  try {
    chunkPreview.value = await api.previewKnowledge({
      text: editForm.text,
      chunkSize: editForm.chunkSize,
      chunkOverlap: editForm.chunkOverlap,
    });
  } catch (e) {
    ElMessage.error(errorMessage(e));
  } finally {
    previewing.value = false;
  }
}
const documents = ref<KnowledgeDocument[]>([]),
  busy = ref(true),
  error = ref(""),
  filter = ref(""),
  categoryFilter = ref(""),
  tab = ref("documents");
const health = ref<{
    embeddingConfigured?: boolean;
    vectorStore?: string;
    mode?: string;
  }>({}),
  healthError = ref("");
const canIndex = computed(
  () =>
    health.value.embeddingConfigured &&
    health.value.vectorStore === "ok" &&
    health.value.mode === "real",
);
const uploadOpen = ref(false),
  editOpen = ref(false),
  drawer = ref(false),
  selected = ref<KnowledgeDocument | null>(null),
  actionBusy = ref(false),
  uploadType = ref("file"),
  uploadFile = ref<File | null>(null);
const form = reactive({
    name: "",
    text: "",
    category: "创作方法",
    sourceUrl: "",
  }),
  editForm = reactive({
    chunkSize: 700,
    chunkOverlap: 100,
    name: "",
    text: "",
    enabled: true,
    category: "",
    sourceUrl: "",
  });
const query = ref(""),
  searchCategory = ref(""),
  searching = ref(false),
  searched = ref(false),
  searchResults = ref<Source[]>([]),
  searchError = ref("");
const categories = computed(() => [
  ...new Set(documents.value.map((d) => d.category).filter(Boolean)),
]);
const filtered = computed(() =>
  documents.value.filter(
    (d) =>
      (!filter.value ||
        d.name.toLowerCase().includes(filter.value.toLowerCase())) &&
      (!categoryFilter.value || d.category === categoryFilter.value),
  ),
);
const pending = computed(
  () =>
    documents.value.filter(
      (d) => d.status === "UNINDEXED" || d.status === "FAILED",
    ).length,
);
let timer: ReturnType<typeof setTimeout> | undefined,
  disposed = false;
async function load(quiet = false) {
  if (!quiet) busy.value = true;
  error.value = "";
  const results = await Promise.allSettled([
    api.documents(),
    api.knowledgeHealth(),
  ]);
  if (results[0].status === "fulfilled") documents.value = results[0].value;
  else error.value = errorMessage(results[0].reason);
  if (results[1].status === "fulfilled") {
    health.value = results[1].value;
    healthError.value = "";
  } else {
    health.value = {};
    healthError.value = errorMessage(results[1].reason);
  }
  busy.value = false;
  clearTimeout(timer);
  if (!disposed && documents.value.some((d) => d.status === "INDEXING"))
    timer = setTimeout(() => load(true), 3000);
}
async function openLinkedDocument() {
  const id = route.query.document;
  if (typeof id === "string" && /^[a-z0-9-]{36}$/i.test(id))
    await preview({ id } as KnowledgeDocument);
}
onMounted(async () => {
  await load();
  await openLinkedDocument();
});
watch(() => route.query.document, openLinkedDocument);
onUnmounted(() => {
  disposed = true;
  clearTimeout(timer);
});
function add() {
  Object.assign(form, {
    name: "",
    text: "",
    category: "创作方法",
    sourceUrl: "",
  });
  uploadFile.value = null;
  uploadOpen.value = true;
}
function chooseFile(event: Event) {
  const input = event.target as HTMLInputElement,
    file = input.files?.[0];
  if (!file) return;
  if (file.size > 20 * 1024 * 1024) {
    ElMessage.warning("文件不能超过 20 MB");
    input.value = "";
    return;
  }
  if (!/\.(txt|md|csv|docx?|pdf|pptx?|odt|odp|rtf)$/i.test(file.name)) {
    ElMessage.warning("不支持此文件格式");
    input.value = "";
    return;
  }
  uploadFile.value = file;
}
async function upload() {
  if (!form.category.trim()) return ElMessage.warning("请填写资料分类");
  if (form.sourceUrl && !safeUrl(form.sourceUrl))
    return ElMessage.warning("来源地址应为 http 或 https 链接");
  if (uploadType.value === "file" && !uploadFile.value)
    return ElMessage.warning("请选择文件");
  if (uploadType.value === "text" && (!form.name.trim() || !form.text.trim()))
    return ElMessage.warning("请填写名称和正文");
  actionBusy.value = true;
  try {
    if (uploadType.value === "file") {
      const body = new FormData();
      body.append("file", uploadFile.value!);
      body.append("category", form.category.trim());
      body.append("sourceUrl", form.sourceUrl.trim());
      await api.upload(body);
    } else
      await api.addText({
        ...form,
        name: form.name.trim(),
        category: form.category.trim(),
      });
    uploadOpen.value = false;
    ElMessage.success("资料已保存");
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e));
  } finally {
    actionBusy.value = false;
  }
}
async function preview(doc: KnowledgeDocument) {
  drawer.value = true;
  selected.value = null;
  try {
    selected.value = await api.document(doc.id);
  } catch (e) {
    drawer.value = false;
    ElMessage.error(errorMessage(e));
  }
}
async function edit(doc: KnowledgeDocument) {
  chunkPreview.value = null;
  try {
    selected.value = await api.document(doc.id);
    Object.assign(editForm, {
      ...selected.value,
      text: selected.value.text || "",
      chunkSize: selected.value.chunkSize || 700,
      chunkOverlap: selected.value.chunkOverlap ?? 100,
    });
    editOpen.value = true;
  } catch (e) {
    ElMessage.error(errorMessage(e));
  }
}
async function save() {
  if (
    !selected.value ||
    !editForm.name.trim() ||
    !editForm.text.trim() ||
    !editForm.category.trim()
  )
    return ElMessage.warning("请填写名称、分类和正文");
  if (editForm.sourceUrl && !safeUrl(editForm.sourceUrl))
    return ElMessage.warning("来源地址应为 http 或 https 链接");
  actionBusy.value = true;
  try {
    await api.updateDocument(selected.value.id, {
      chunkSize: editForm.chunkSize,
      chunkOverlap: editForm.chunkOverlap,
      name: editForm.name.trim(),
      text: editForm.text,
      enabled: editForm.enabled,
      category: editForm.category.trim(),
      sourceUrl: editForm.sourceUrl.trim(),
    });
    editOpen.value = false;
    ElMessage.success("资料已保存");
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e));
  } finally {
    actionBusy.value = false;
  }
}
async function toggle(doc: KnowledgeDocument) {
  try {
    await api.updateDocument(doc.id, {
      enabled: !doc.enabled,
      category: doc.category,
      sourceUrl: doc.sourceUrl || "",
    });
    await load(true);
  } catch (e) {
    ElMessage.error(errorMessage(e));
  }
}
async function reindex(doc: KnowledgeDocument) {
  try {
    await api.reindex(doc.id);
    ElMessage.success("索引任务已提交");
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e));
  }
}
async function indexAll() {
  actionBusy.value = true;
  try {
    const result = await api.indexKnowledge();
    ElMessage.success(`已提交 ${result.queued} 份资料`);
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e));
  } finally {
    actionBusy.value = false;
  }
}
async function restore() {
  try {
    await ElMessageBox.confirm(
      "恢复预置资料的原始内容，你添加的资料会保留。",
      "恢复默认知识库",
      { confirmButtonText: "恢复默认", cancelButtonText: "取消" },
    );
    actionBusy.value = true;
    await api.restoreKnowledge();
    ElMessage.success("默认资料已恢复");
    await load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorMessage(e));
  } finally {
    actionBusy.value = false;
  }
}
async function remove(doc: KnowledgeDocument) {
  try {
    await ElMessageBox.confirm(`删除「${doc.name}」及其索引？`, "删除资料", {
      confirmButtonText: "删除",
      cancelButtonText: "保留",
      type: "warning",
    });
    await api.deleteDocument(doc.id);
    await load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errorMessage(e));
  }
}
async function search() {
  if (!query.value.trim()) return;
  searching.value = true;
  searched.value = true;
  searchError.value = "";
  try {
    searchResults.value = await api.search(
      query.value.trim(),
      searchCategory.value,
    );
  } catch (e) {
    searchError.value = errorMessage(e);
    searchResults.value = [];
  } finally {
    searching.value = false;
  }
}
const statusLabel = (status: string) =>
  ({
    UNINDEXED: "待建立索引",
    INDEXING: "索引中",
    READY: "已就绪",
    FAILED: "索引失败",
  })[status] || status;
</script>
<template>
  <div class="knowledge-page">
    <div class="page-heading">
      <h1>知识库</h1>
      <div class="knowledge-actions">
        <el-button :icon="Download" @click="exportLibrary"
          >导出知识包</el-button
        >
        <el-button :icon="UploadFilled" @click="importOpen = true"
          >批量导入</el-button
        >
        <el-button
          :icon="Refresh"
          :disabled="
            actionBusy || documents.some((d) => d.status === 'INDEXING')
          "
          @click="restore"
          >恢复默认</el-button
        >
        <el-button
          :disabled="!canIndex || !pending"
          :loading="actionBusy"
          @click="indexAll"
          >建立索引</el-button
        >
        <el-button type="primary" :icon="Plus" @click="add">添加资料</el-button>
      </div>
    </div>
    <el-alert
      v-if="!busy && !canIndex"
      :title="
        healthError ||
        (!health.embeddingConfigured
          ? '向量模型尚未配置，可先编辑资料，配置后建立索引。'
          : health.mode !== 'real'
            ? '当前为演示模式，切换真实模式后可建立检索索引。'
            : '向量服务暂不可用。')
      "
      type="warning"
      show-icon
      :closable="false"
      class="mb-24"
    />
    <div class="knowledge-toolbar">
      <SlidingSegment
        v-model="tab"
        label="知识库视图"
        :options="[
          { value: 'documents', label: '资料管理' },
          { value: 'search', label: '检索测试' },
        ]"
      /><span
        >{{ documents.length }} 份资料 ·
        {{ documents.filter((d) => d.status === "READY" && d.enabled).length }}
        份可检索</span
      >
    </div>
    <Transition name="view" mode="out-in">
      <section v-if="tab === 'documents'" key="documents">
        <div class="toolbar knowledge-filters">
          <el-input
            v-model="filter"
            placeholder="搜索资料"
            clearable
            :prefix-icon="Search"
            class="search-input"
          /><el-select
            v-model="categoryFilter"
            placeholder="全部分类"
            clearable
            class="status-select"
            ><el-option
              v-for="c in categories"
              :key="c"
              :label="c"
              :value="c" /></el-select
          ><el-button
            :icon="Refresh"
            :loading="busy"
            aria-label="刷新知识库"
            @click="load()"
          />
        </div>
        <LoadError v-if="error" :message="error" @retry="load()" />
        <div v-else class="panel knowledge-table" v-loading="busy">
          <el-table v-if="filtered.length" :data="filtered" class="clean-table">
            <el-table-column label="资料" min-width="260"
              ><template #default="{ row }"
                ><div class="document-cell">
                  <span class="document-icon"
                    ><el-icon><Document /></el-icon
                  ></span>
                  <div>
                    <button class="text-link" @click="preview(row)">
                      {{ row.name }}</button
                    ><el-tag
                      v-if="row.isDefault"
                      effect="plain"
                      class="default-tag"
                      >默认</el-tag
                    >
                    <p v-if="row.error" class="inline-error">{{ row.error }}</p>
                  </div>
                </div></template
              ></el-table-column
            >
            <el-table-column prop="category" label="分类" min-width="120" />
            <el-table-column label="索引" min-width="130"
              ><template #default="{ row }"
                ><el-tag
                  :type="
                    row.status === 'READY'
                      ? 'success'
                      : row.status === 'FAILED'
                        ? 'danger'
                        : 'info'
                  "
                  effect="plain"
                  >{{ statusLabel(row.status) }}</el-tag
                ></template
              ></el-table-column
            >
            <el-table-column prop="chunkCount" label="片段" width="80" />
            <el-table-column label="启用" width="84"
              ><template #default="{ row }"
                ><el-switch
                  :model-value="row.enabled"
                  :disabled="row.status === 'INDEXING'"
                  :aria-label="`${row.enabled ? '停用' : '启用'} ${row.name}`"
                  @change="toggle(row)" /></template
            ></el-table-column>
            <el-table-column label="操作" width="190" align="right"
              ><template #default="{ row }"
                ><el-button
                  text
                  circle
                  :icon="View"
                  :aria-label="`预览 ${row.name}`"
                  @click="preview(row)" /><el-button
                  text
                  circle
                  :icon="EditPen"
                  :aria-label="`编辑 ${row.name}`"
                  :disabled="row.status === 'INDEXING'"
                  @click="edit(row)" /><el-button
                  text
                  circle
                  :icon="Refresh"
                  :aria-label="`重建 ${row.name} 索引`"
                  :disabled="!canIndex || row.status === 'INDEXING'"
                  @click="reindex(row)" /><el-button
                  text
                  circle
                  type="danger"
                  :icon="Delete"
                  :aria-label="`删除 ${row.name}`"
                  :disabled="row.status === 'INDEXING'"
                  @click="remove(row)" /></template
            ></el-table-column>
          </el-table>
          <div v-else class="empty-state">
            <span class="empty-art"
              ><el-icon><Document /></el-icon
            ></span>
            <h3>
              {{ filter || categoryFilter ? "未找到匹配资料" : "暂无资料" }}
            </h3>
            <el-button plain @click="add">添加资料</el-button>
          </div>
        </div>
      </section>
      <section v-else key="search" class="panel search-panel">
        <form class="search-test-form" @submit.prevent="search">
          <el-input
            v-model="query"
            :prefix-icon="Search"
            placeholder="输入检索内容"
            maxlength="2000"
            clearable
          /><el-select v-model="searchCategory" clearable placeholder="全部分类"
            ><el-option
              v-for="c in categories"
              :key="c"
              :value="c"
              :label="c" /></el-select
          ><el-button
            native-type="submit"
            type="primary"
            :loading="searching"
            :disabled="!canIndex || !query.trim()"
            >检索</el-button
          >
        </form>
        <el-alert
          v-if="searchError"
          :title="searchError"
          type="error"
          show-icon
          :closable="false"
        />
        <div
          v-for="(source, i) in searchResults"
          :key="source.chunkId"
          class="source-card"
        >
          <span class="source-index">{{ String(i + 1).padStart(2, "0") }}</span>
          <div>
            <h3>{{ source.documentName }}</h3>
            <p>{{ source.text }}</p>
            <span class="muted">相关度 {{ source.score.toFixed(3) }}</span
            ><a
              v-if="safeUrl(source.sourceUrl)"
              :href="safeUrl(source.sourceUrl)"
              target="_blank"
              rel="noopener noreferrer"
              class="source-link"
              >查看来源</a
            >
          </div>
        </div>
        <div
          v-if="searched && !searchResults.length && !searchError && !searching"
          class="empty-state"
        >
          <h3>未找到相关片段</h3>
        </div>
        <div v-else-if="!searched" class="empty-state compact">
          <span class="empty-art"
            ><el-icon><Search /></el-icon
          ></span>
        </div>
      </section>
    </Transition>
    <KnowledgeImport v-model="importOpen" @imported="load()" />
    <el-dialog
      v-model="uploadOpen"
      title="添加资料"
      width="min(680px,94vw)"
      :close-on-click-modal="!actionBusy"
    >
      <SlidingSegment
        v-model="uploadType"
        label="添加方式"
        :options="[
          { label: '上传文件', value: 'file' },
          { label: '粘贴文本', value: 'text' },
        ]"
        class="mb-24"
      />
      <el-form label-position="top" @submit.prevent
        ><el-form-item v-if="uploadType === 'file'" label="知识文件" required
          ><label class="file-drop"
            ><el-icon><UploadFilled /></el-icon
            ><strong>{{ uploadFile?.name || "选择文件" }}</strong
            ><span>Word、PDF、PPT 或文本文件，最大 20 MB</span
            ><input
              type="file"
              accept=".txt,.md,.csv,.docx,.doc,.pdf,.pptx,.ppt,.odt,.odp,.rtf"
              @change="chooseFile" /></label
        ></el-form-item>
        <template v-else
          ><el-form-item label="资料名称" required
            ><el-input v-model="form.name" maxlength="200" /></el-form-item
          ><el-form-item label="资料正文" required
            ><el-input
              v-model="form.text"
              type="textarea"
              :rows="8"
              maxlength="200000" /></el-form-item
        ></template>
        <el-form-item label="分类" required
          ><el-select
            v-model="form.category"
            filterable
            allow-create
            default-first-option
            ><el-option
              v-for="c in [
                ...new Set(['创作方法', '风格案例', '领域资料', ...categories]),
              ]"
              :key="c"
              :label="c"
              :value="c" /></el-select
        ></el-form-item>
        <el-form-item label="来源地址"
          ><el-input
            v-model="form.sourceUrl"
            :prefix-icon="Link"
            placeholder="https://..."
            maxlength="2000"
        /></el-form-item> </el-form
      ><template #footer
        ><el-button :disabled="actionBusy" @click="uploadOpen = false"
          >取消</el-button
        ><el-button type="primary" :loading="actionBusy" @click="upload"
          >保存资料</el-button
        ></template
      >
    </el-dialog>
    <el-dialog
      v-model="editOpen"
      title="编辑资料"
      width="min(820px,94vw)"
      :close-on-click-modal="!actionBusy"
      ><el-form label-position="top" @submit.prevent
        ><el-form-item label="资料名称" required
          ><el-input v-model="editForm.name" maxlength="200" /></el-form-item
        ><el-form-item label="正文" required
          ><el-input
            v-model="editForm.text"
            type="textarea"
            :rows="11"
            maxlength="200000"
        /></el-form-item>
        <div class="knowledge-edit-meta">
          <el-form-item label="分类" required
            ><el-input
              v-model="editForm.category"
              maxlength="100" /></el-form-item
          ><el-form-item label="来源地址"
            ><el-input v-model="editForm.sourceUrl" maxlength="2000"
          /></el-form-item>
        </div>
        <AnimatedDisclosure class="knowledge-chunk-settings"
          ><template #summary>分块设置与预览</template>
          <div class="knowledge-edit-meta">
            <el-form-item label="分块长度"
              ><el-input-number
                v-model="editForm.chunkSize"
                :min="200"
                :max="2000"
                :step="100" /></el-form-item
            ><el-form-item label="重叠长度"
              ><el-input-number
                v-model="editForm.chunkOverlap"
                :min="0"
                :max="500"
                :step="50"
            /></el-form-item>
          </div>
          <el-button
            :loading="previewing"
            :disabled="
              !editForm.text.trim() ||
              editForm.chunkOverlap >= editForm.chunkSize
            "
            @click="previewChunks"
            >预览分块</el-button
          >
          <div v-if="chunkPreview" class="chunk-preview">
            <p>
              {{ chunkPreview.characters }} 字 ·
              {{ chunkPreview.chunkCount }} 个片段
            </p>
            <div
              v-for="(chunk, index) in chunkPreview.chunks"
              :key="index"
              class="chunk-card"
            >
              <span class="muted"
                >{{ index + 1 }} · {{ chunk.characters }} 字</span
              >
              <p>{{ chunk.text }}</p>
            </div>
            <p v-if="chunkPreview.truncated" class="muted">显示前 20 个片段</p>
          </div></AnimatedDisclosure
        >
        <el-form-item label="参与检索"
          ><el-switch
            v-model="editForm.enabled"
            aria-label="允许检索此资料" /></el-form-item></el-form
      ><template #footer
        ><el-button :disabled="actionBusy" @click="editOpen = false"
          >取消</el-button
        ><el-button type="primary" :loading="actionBusy" @click="save"
          >保存</el-button
        ></template
      ></el-dialog
    >
    <el-drawer v-model="drawer" title="资料内容" size="min(760px,95vw)"
      ><div v-if="selected">
        <h2>{{ selected.name }}</h2>
        <p class="muted">
          {{ selected.category }} · {{ statusLabel(selected.status) }}
          <span v-if="selected.originType?.startsWith('CHAT')">
            · 来自对话</span
          >
        </p>
        <a
          v-if="safeUrl(selected.sourceUrl)"
          :href="safeUrl(selected.sourceUrl)"
          target="_blank"
          rel="noopener noreferrer"
          >原始来源</a
        ><el-alert
          v-if="selected.error"
          :title="selected.error"
          type="error"
          :closable="false"
          class="mb-24"
        />
        <div class="knowledge-raw">{{ selected.text }}</div>
        <template v-if="selected.chunks?.length"
          ><h3>检索片段</h3>
          <div
            v-for="(chunk, i) in selected.chunks"
            :key="chunk.id"
            class="chunk-card"
          >
            <span class="muted">{{ i + 1 }}</span>
            <p>{{ chunk.text }}</p>
          </div></template
        >
      </div></el-drawer
    >
  </div>
</template>
