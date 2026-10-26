<script setup lang="ts">
import AnimatedDisclosure from "./AnimatedDisclosure.vue";
import { computed } from "vue";
import { Plus, Delete } from "@element-plus/icons-vue";
import type { Content } from "../lib/types";
const props = withDefaults(defineProps<{ editable?: boolean }>(), {
  editable: false,
});
const content = defineModel<Content>({ required: true });
const outline = computed({
  get: () => content.value.outline.join("\n"),
  set: (v: string) =>
    (content.value.outline = v
      .split("\n")
      .map((s) => s.trim())
      .filter(Boolean)),
});
const tags = computed({
  get: () => content.value.tags.join("，"),
  set: (v: string) =>
    (content.value.tags = v
      .split(/[,，\n]/)
      .map((s) => s.trim().replace(/^#/, ""))
      .filter(Boolean)),
});
const titles = computed({
  get: () => content.value.titles.join("\n"),
  set: (v: string) =>
    (content.value.titles = v.split("\n").filter((s) => s.trim())),
});
</script>
<template>
  <article class="content-document">
    <section class="document-section">
      <div class="document-section-heading">
        <span class="section-number">01</span>
        <h2>标题与内容策划</h2>
      </div>
      <template v-if="props.editable"
        ><el-form label-position="top"
          ><el-form-item label="备选标题（每行一个）"
            ><el-input
              v-model="titles"
              type="textarea"
              :rows="3"
              maxlength="3000" /></el-form-item
          ><el-form-item label="内容定位"
            ><el-input
              v-model="content.positioning"
              type="textarea"
              :rows="2"
              maxlength="5000" /></el-form-item
          ><el-form-item label="受众分析"
            ><el-input
              v-model="content.audienceAnalysis"
              type="textarea"
              :rows="2"
              maxlength="5000" /></el-form-item
          ><el-form-item label="内容结构（每行一项）"
            ><el-input
              v-model="outline"
              type="textarea"
              :rows="3"
              maxlength="5000" /></el-form-item></el-form></template
      ><template v-else
        ><div class="title-options">
          <div v-for="(title, index) in content.titles" :key="index">
            <span>{{ String(index + 1).padStart(2, "0") }}</span
            ><strong>{{ title }}</strong>
          </div>
        </div>
        <div class="strategy-grid">
          <div>
            <span class="micro-label">内容定位</span>
            <p>{{ content.positioning }}</p>
          </div>
          <div>
            <span class="micro-label">受众分析</span>
            <p>{{ content.audienceAnalysis }}</p>
          </div>
        </div>
        <div v-if="content.outline.length" class="outline">
          <span class="micro-label">内容结构</span>
          <ol>
            <li v-for="(line, i) in content.outline" :key="i">{{ line }}</li>
          </ol>
        </div></template
      >
    </section>
    <section class="document-section">
      <div class="document-section-heading">
        <span class="section-number">02</span>
        <h2>开场</h2>
      </div>
      <el-input
        v-if="editable"
        v-model="content.opening"
        aria-label="开场口播"
        type="textarea"
        :rows="3"
        maxlength="5000"
      />
      <blockquote v-else class="opening-quote">
        {{ content.opening }}
      </blockquote>
    </section>
    <section class="document-section">
      <div class="document-section-heading">
        <span class="section-number">03</span>
        <h2>分段脚本</h2>
        <span class="small-label"
          >{{ content.segments.length }} 个片段 ·
          {{
            content.segments.reduce((n, s) => n + Number(s.duration || 0), 0)
          }}
          秒</span
        >
      </div>
      <div
        v-for="(segment, index) in content.segments"
        :key="index"
        class="segment-card"
      >
        <div class="segment-heading">
          <span class="segment-index"
            >片段 {{ String(index + 1).padStart(2, "0") }}</span
          ><el-input-number
            v-if="editable"
            v-model="segment.duration"
            :min="1"
            :max="600"
            size="small"
            :aria-label="`片段 ${index + 1} 时长（秒）`"
          /><span v-else class="segment-time">{{ segment.duration }}s</span
          ><el-button
            v-if="editable"
            text
            type="danger"
            :icon="Delete"
            :aria-label="`删除片段 ${index + 1}`"
            @click="content.segments.splice(index, 1)"
          />
        </div>
        <div class="segment-fields">
          <div class="visual-field">
            <span class="micro-label">画面建议</span
            ><el-input
              v-if="editable"
              v-model="segment.visual"
              :aria-label="`片段 ${index + 1} 画面`"
              type="textarea"
              :rows="3"
              maxlength="5000"
            />
            <p v-else>{{ segment.visual }}</p>
          </div>
          <div class="narration-field">
            <span class="micro-label">口播文案</span
            ><el-input
              v-if="editable"
              v-model="segment.narration"
              :aria-label="`片段 ${index + 1} 口播`"
              type="textarea"
              :rows="3"
              maxlength="10000"
            />
            <p v-else>{{ segment.narration }}</p>
            <span class="micro-label subtitle-label">字幕建议</span
            ><el-input
              v-if="editable"
              v-model="segment.subtitle"
              :aria-label="`片段 ${index + 1} 字幕`"
              type="textarea"
              :rows="2"
              maxlength="5000"
            />
            <p v-else class="subtitle-text">{{ segment.subtitle }}</p>
          </div>
        </div>
      </div>
      <el-button
        v-if="editable"
        :icon="Plus"
        :disabled="content.segments.length >= 30"
        @click="
          content.segments.push({
            duration: 10,
            visual: '',
            narration: '',
            subtitle: '',
          })
        "
        >添加脚本片段</el-button
      >
    </section>
    <section class="document-section">
      <div class="document-section-heading">
        <span class="section-number">04</span>
        <h2>结尾与发布文案</h2>
      </div>
      <span class="micro-label">结尾互动</span
      ><el-input
        v-if="editable"
        v-model="content.ending"
        aria-label="结尾互动"
        type="textarea"
        :rows="3"
        maxlength="5000"
      />
      <p v-else class="body-copy">{{ content.ending }}</p>
      <span class="micro-label block-top">发布文案</span
      ><el-input
        v-if="editable"
        v-model="content.caption"
        aria-label="发布文案"
        type="textarea"
        :rows="3"
        maxlength="10000"
      />
      <p v-else class="body-copy">{{ content.caption }}</p>
      <el-form v-if="editable" label-position="top"
        ><el-form-item label="话题标签（逗号分隔）"
          ><el-input v-model="tags" maxlength="2000" /></el-form-item
      ></el-form>
      <div v-else class="tags">
        <span v-for="(tag, i) in content.tags" :key="i"
          ># {{ tag.replace(/^#/, "") }}</span
        >
      </div>
    </section>
    <AnimatedDisclosure
      v-if="content.qualityNotes.length"
      class="quality-notes"
    >
      <template #summary>检查结果</template>
      <p v-for="(note, i) in content.qualityNotes" :key="i">{{ note }}</p>
    </AnimatedDisclosure>
  </article>
</template>
