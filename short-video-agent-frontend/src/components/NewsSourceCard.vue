<script setup lang="ts">
import { computed } from "vue";
import { PhArrowUpRight } from "@phosphor-icons/vue";
import type { Source } from "../lib/types";
import { safeUrl } from "../lib/format";
const props = defineProps<{ source: Source; compact?: boolean }>();
function field(name: string) {
  return (
    props.source.text
      .split("\n")
      .find((line) => line.startsWith(name + "："))
      ?.slice(name.length + 1) || ""
  );
}
const news = computed(() => props.source.documentId.startsWith("web-"));
const published = computed(() => {
  const value = field("发布时间");
  if (!value || value === "未确认" || Number.isNaN(Date.parse(value)))
    return "发布时间未确认";
  return new Date(value).toLocaleString("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
});
</script>
<template>
  <article class="news-source-card">
    <a
      v-if="safeUrl(source.sourceUrl)"
      class="news-source-title"
      :href="safeUrl(source.sourceUrl)"
      target="_blank"
      rel="noopener noreferrer"
      ><strong>{{ source.documentName }}</strong
      ><PhArrowUpRight :size="18"
    /></a>
    <strong v-else class="news-source-title">{{ source.documentName }}</strong>
    <div v-if="news && !compact" class="news-source-meta">
      <span>{{ field("发布来源") }}</span
      ><span>{{
        field("证据范围") === "网页正文" ? "已读取正文片段" : "仅获取标题或摘要"
      }}</span>
    </div>
    <time v-if="news && !compact" class="news-published">{{
      published === "发布时间未确认" ? published : "发布于 " + published
    }}</time>
  </article>
</template>
