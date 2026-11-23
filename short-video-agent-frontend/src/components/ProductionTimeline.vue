<script setup lang="ts">
import { computed, ref, watch } from "vue";
import {
  PhCaretLeft,
  PhCaretRight,
  PhFilmStrip,
  PhTextT,
  PhMicrophone,
  PhPencilSimple,
} from "@phosphor-icons/vue";
import InlineText from "./InlineText.vue";
import type {
  ProductionPackage,
  ProductionSegment,
} from "../lib/conversation-types";

defineProps<{ editable: boolean }>();
const draft = defineModel<ProductionPackage>({ required: true });
const position = ref(0);
const shots = computed(() => {
  let end = 0;
  return draft.value.segments.map((segment, index) => {
    const start = end;
    end += Math.max(0, segment.duration);
    return {
      segment,
      index,
      start,
      end,
      number: String(index + 1).padStart(2, "0"),
      title:
        segment.visual.trim().split(/[。！？\n]/u)[0] || `镜头 ${index + 1}`,
    };
  });
});
const total = computed(() => shots.value.at(-1)?.end ?? 0);
const progress = computed(() =>
  total.value ? position.value / total.value : 0,
);
const active = computed(
  () =>
    shots.value.find((shot) => position.value < shot.end) ?? shots.value.at(-1),
);
const ticks = computed(() =>
  Array.from({ length: 5 }, (_, index) => (total.value * index) / 4),
);
const columns = computed(() =>
  shots.value
    .map(({ segment }) => `minmax(0, ${Math.max(0.001, segment.duration)}fr)`)
    .join(" "),
);
const tracks = [
  { label: "分镜", icon: PhFilmStrip, field: "title" },
  { label: "字幕", icon: PhTextT, field: "subtitle" },
  { label: "口播", icon: PhMicrophone, field: "narration" },
] as const;
const fields = [
  { key: "visual", label: "画面", max: 5000 },
  { key: "subtitle", label: "字幕", max: 5000 },
  { key: "narration", label: "口播", max: 10000 },
  { key: "editing", label: "剪辑提示", max: 2000 },
] as const;

watch(total, (value) => {
  position.value = Math.min(position.value, value);
});
function time(seconds: number) {
  const rounded = Math.floor(seconds + 1e-6);
  return [Math.floor(rounded / 60), rounded % 60]
    .map((part) => String(part).padStart(2, "0"))
    .join(":");
}
function select(index: number) {
  const shot = shots.value[index];
  if (shot) position.value = shot.start;
}
function updateSegment(index: number, patch: Partial<ProductionSegment>) {
  draft.value = {
    ...draft.value,
    segments: draft.value.segments.map((segment, i) =>
      i === index ? { ...segment, ...patch } : segment,
    ),
  };
}
function updateDuration(index: number, event: Event) {
  const input = event.target as HTMLInputElement;
  const duration = input.valueAsNumber;
  const shot = shots.value[index];
  if (!shot) return;
  if (!Number.isFinite(duration) || duration <= 0 || duration > 600) {
    input.value = String(shot.segment.duration);
    return;
  }
  if (duration === shot.segment.duration) return;
  position.value = shot.start;
  updateSegment(index, { duration });
}
</script>

<template>
  <div v-if="active" class="production-timeline">
    <div class="timeline-workspace">
      <section class="timeline-pane timeline-shots" aria-label="分镜">
        <header>
          <h2>分镜</h2>
          <span>{{ shots.length }} 个镜头</span>
        </header>
        <div class="timeline-shot-list">
          <button
            v-for="shot in shots"
            :key="shot.segment.id"
            type="button"
            :aria-pressed="active.index === shot.index"
            @click="select(shot.index)"
          >
            <span class="shot-number">{{ shot.number }}</span>
            <span class="shot-list-copy">
              <strong>{{ shot.title }}</strong>
              <span>{{ time(shot.start) }} – {{ time(shot.end) }}</span>
            </span>
          </button>
        </div>
      </section>

      <section class="timeline-pane timeline-preview" aria-label="分镜预览">
        <header>
          <h2>分镜预览</h2>
          <span>文字预览</span>
        </header>
        <div class="timeline-stage">
          <div class="timeline-portrait">
            <span>画面说明</span>
            <p>{{ active.segment.visual }}</p>
            <strong v-if="active.segment.subtitle">{{
              active.segment.subtitle
            }}</strong>
          </div>
        </div>
        <div class="timeline-transport">
          <button
            type="button"
            aria-label="上一镜头"
            title="上一镜头"
            :disabled="active.index === 0"
            @click="select(active.index - 1)"
          >
            <PhCaretLeft :size="16" />
          </button>
          <output>{{ time(position) }} / {{ time(total) }}</output>
          <input
            v-model.number="position"
            type="range"
            min="0"
            :max="total"
            step="any"
            aria-label="预览进度"
          />
          <button
            type="button"
            aria-label="下一镜头"
            title="下一镜头"
            :disabled="active.index === shots.length - 1"
            @click="select(active.index + 1)"
          >
            <PhCaretRight :size="16" />
          </button>
        </div>
      </section>

      <section class="timeline-pane timeline-inspector" aria-label="镜头详情">
        <header>
          <h2>
            镜头 <span class="shot-number">{{ active.number }}</span>
          </h2>
          <span>{{ time(active.start) }} – {{ time(active.end) }}</span>
        </header>
        <div :key="active.segment.id" class="timeline-inspector-body">
          <div
            v-for="field in fields"
            :key="field.key"
            class="timeline-property"
          >
            <span class="timeline-field-label">{{ field.label }}</span>
            <InlineText
              :label="field.label"
              :editable="editable"
              :maxlength="field.max"
              :model-value="active.segment[field.key]"
              @update:model-value="
                updateSegment(active.index, { [field.key]: $event })
              "
            />
            <PhPencilSimple
              v-if="editable"
              class="timeline-edit-icon"
              :size="15"
              aria-hidden="true"
            />
          </div>
          <label class="timeline-duration"
            >时长
            <input
              type="number"
              min="0"
              max="600"
              step="any"
              :value="active.segment.duration"
              :disabled="!editable"
              aria-label="镜头时长（秒）"
              @change="updateDuration(active.index, $event)"
            />
            秒
          </label>
        </div>
      </section>
    </div>

    <section class="timeline-tracks" aria-label="时间线轨道">
      <div class="timeline-labels" aria-hidden="true">
        <div></div>
        <span v-for="track in tracks" :key="track.label"
          ><component :is="track.icon" :size="17" />{{ track.label }}</span
        >
      </div>
      <div class="timeline-track-scroll">
        <div
          class="timeline-track-content"
          :style="{
            '--shot-columns': columns,
            '--playhead': progress * 100 + '%',
          }"
        >
          <div class="timeline-ruler">
            <span
              v-for="(tick, index) in ticks"
              :key="index"
              :style="{ left: index * 25 + '%' }"
              >{{ time(tick) }}</span
            >
            <input
              v-model.number="position"
              type="range"
              min="0"
              :max="total"
              step="any"
              aria-label="时间线播放头"
            />
          </div>
          <div
            v-for="track in tracks"
            :key="track.label"
            class="timeline-track-row"
          >
            <button
              v-for="shot in shots"
              :key="shot.segment.id"
              type="button"
              class="timeline-clip"
              :class="{ selected: active.index === shot.index }"
              :aria-label="track.label + ' · 镜头 ' + shot.number"
              :aria-pressed="active.index === shot.index"
              :title="
                track.field === 'title' ? shot.title : shot.segment[track.field]
              "
              @click="select(shot.index)"
            >
              {{
                (track.field === "title"
                  ? shot.number + " " + shot.title
                  : shot.segment[track.field]) || "—"
              }}
            </button>
          </div>
          <div
            class="timeline-playhead"
            aria-hidden="true"
            :class="{
              'near-start': progress < 0.06,
              'near-end': progress > 0.94,
            }"
          >
            <span>{{ time(position) }}</span>
          </div>
        </div>
      </div>
    </section>
  </div>
  <p v-else class="timeline-empty">暂无分镜</p>
</template>

<style scoped>
.production-timeline {
  display: grid;
  gap: 16px;
  color: var(--ink);
  min-width: 0;
}
.timeline-workspace {
  display: grid;
  grid-template-columns: minmax(150px, 1fr) minmax(235px, 1.7fr) minmax(
      245px,
      1.23fr
    );
  gap: 12px;
}
.timeline-pane {
  min-width: 0;
  min-height: 0;
  border: 1px solid var(--line);
  border-radius: 13px;
  background: #fffefa;
  overflow: hidden;
}
.timeline-pane > header {
  min-height: 45px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px;
  background: #f3f5ed;
  font-size: 13px;
  color: var(--muted);
}
.timeline-pane h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
  color: var(--ink);
  display: flex;
  align-items: center;
  gap: 8px;
}
.timeline-shot-list,
.timeline-inspector-body {
  max-height: clamp(255px, 44vh, 510px);
  overflow: auto;
  padding: 10px;
}
.timeline-shot-list {
  display: grid;
  gap: 8px;
}
.timeline-shot-list button {
  min-width: 0;
  width: 100%;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 9px;
  text-align: left;
  border: 1px solid var(--line);
  border-radius: 9px;
  color: inherit;
  background: transparent;
}
.timeline-shot-list button:hover,
.timeline-shot-list button[aria-pressed="true"] {
  background: #e9efde;
  border-color: #dce5d0;
}
.shot-number {
  flex: none;
  padding: 3px 6px;
  border-radius: 6px;
  background: #e5edd9;
  color: #43543a;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.shot-list-copy {
  min-width: 0;
  display: grid;
  gap: 3px;
}
.shot-list-copy strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
}
.shot-list-copy > span {
  color: var(--muted);
  font-size: 12px;
}
.timeline-stage {
  display: grid;
  place-items: center;
  padding: 10px;
}
.timeline-portrait {
  width: min(100%, clamp(140px, 24vh, 270px));
  aspect-ratio: 9 / 16;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  background: #eaf0df;
  border-radius: 11px;
  overflow: auto;
  overflow-wrap: anywhere;
}
.timeline-portrait > span {
  font-size: 12px;
  color: #43543a;
  font-weight: 650;
}
.timeline-portrait p {
  margin: 0;
  line-height: 1.55;
  font-size: 14px;
  white-space: pre-wrap;
}
.timeline-portrait strong {
  margin-top: auto;
  padding: 7px 9px;
  border-radius: 6px;
  color: #fffefa;
  background: #43543a;
  text-align: center;
  white-space: pre-wrap;
  font-size: 15px;
}
.timeline-transport {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 10px 10px;
}
.timeline-transport button {
  display: grid;
  place-items: center;
  width: 31px;
  height: 31px;
  flex: none;
  border-radius: 7px;
  border: 1px solid var(--line);
  background: #f3f5ed;
  color: var(--ink);
}
.timeline-transport button:disabled {
  opacity: 0.45;
  cursor: default;
}
.timeline-transport output {
  white-space: nowrap;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.timeline-transport input {
  min-width: 0;
  flex: 1;
  accent-color: #718458;
}
.timeline-inspector > header {
  justify-content: space-between;
}
.timeline-inspector-body {
  padding: 4px 14px 10px;
}
.timeline-property {
  display: grid;
  grid-template-columns: 60px minmax(0, 1fr) 15px;
  align-items: start;
  gap: 9px;
  padding: 11px 0;
  border-bottom: 1px solid var(--line);
  font-size: 14px;
}
.timeline-field-label {
  width: fit-content;
  padding: 3px 6px;
  background: #edf2e5;
  border-radius: 5px;
  color: #43543a;
  font-size: 12px;
  white-space: nowrap;
}
.timeline-property :deep(p),
.timeline-property :deep(.inline-copy-button),
.timeline-property :deep(textarea) {
  line-height: 1.55;
}
.timeline-property :deep(textarea) {
  max-height: 220px;
  overflow: auto;
}
.timeline-property :deep(.inline-copy-button) {
  margin: 0;
  padding: 0 3px;
}
.timeline-edit-icon {
  margin-top: 3px;
  color: var(--muted);
  pointer-events: none;
}
.timeline-duration {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 9px;
  font-size: 13px;
  color: var(--muted);
}
.timeline-duration input {
  width: 76px;
  padding: 4px 6px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fafbf7;
  color: var(--ink);
  font: inherit;
}
.timeline-tracks {
  min-width: 0;
  display: grid;
  grid-template-columns: 78px minmax(0, 1fr);
  gap: 7px;
  border: 1px solid var(--line);
  border-radius: 13px;
  background: #fffefa;
  padding: 8px 12px 12px;
}
.timeline-labels,
.timeline-track-content {
  display: grid;
  grid-template-rows: 35px repeat(3, 38px);
  gap: 5px;
}
.timeline-labels > span {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  border-radius: 6px;
  background: #f1f4eb;
  font-size: 13px;
}
.timeline-track-scroll {
  overflow-x: auto;
  min-width: 0;
}
.timeline-track-content {
  position: relative;
  min-width: 340px;
  margin-inline: 3px;
}
.timeline-ruler {
  border-bottom: 1px solid #c5cfbc;
  font-size: 11px;
  color: var(--muted);
  position: relative;
  font-variant-numeric: tabular-nums;
}
.timeline-ruler span {
  position: absolute;
  top: 3px;
  transform: translateX(-50%);
  pointer-events: none;
}
.timeline-ruler span:first-child {
  transform: none;
}
.timeline-ruler span:nth-child(5) {
  transform: translateX(-100%);
}
.timeline-ruler span::after {
  content: "";
  position: absolute;
  height: 6px;
  width: 1px;
  background: #9da990;
  left: 50%;
  top: 23px;
}
.timeline-ruler span:first-child::after {
  left: 0;
}
.timeline-ruler span:nth-child(5)::after {
  left: 100%;
}
.timeline-ruler input {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  margin: 0;
  opacity: 0;
  cursor: ew-resize;
}
.timeline-ruler:focus-within {
  outline: 2px solid #a2b48d;
  outline-offset: -2px;
}
.timeline-track-row {
  display: grid;
  grid-template-columns: var(--shot-columns);
  gap: 2px;
  min-width: 0;
}
.timeline-clip {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: left;
  padding-inline: 8px;
  color: var(--ink);
  border: 1px solid #e9e8dd;
  background: #f4f1e8;
  border-radius: 6px;
  font-size: 13px;
}
.timeline-track-row:nth-child(3) .timeline-clip {
  background: #eef3e6;
}
.timeline-track-row .timeline-clip.selected {
  background: #e2ebd6;
  border-color: #c4d3b4;
}
.timeline-clip:hover {
  border-color: #9eb48b;
}
.timeline-playhead {
  position: absolute;
  left: var(--playhead);
  inset-block: 25px 0;
  border-left: 1px solid #de603d;
  pointer-events: none;
}
.timeline-playhead span {
  position: absolute;
  top: -11px;
  transform: translateX(-50%);
  border-radius: 8px;
  padding: 2px 5px;
  background: #c54b29;
  color: white;
  font-size: 10px;
  font-variant-numeric: tabular-nums;
}
.timeline-playhead.near-start span {
  transform: none;
}
.timeline-playhead.near-end span {
  transform: translateX(-100%);
}
.timeline-empty {
  margin: 24px auto;
  color: var(--muted);
}
@media (max-width: 830px) {
  .timeline-workspace {
    grid-template-columns: minmax(125px, 1fr) minmax(185px, 1.7fr);
  }
  .timeline-inspector {
    grid-column: 1 / -1;
  }
}
@media (max-width: 520px) {
  .timeline-workspace {
    grid-template-columns: minmax(0, 1fr);
  }
  .timeline-shot-list {
    grid-auto-flow: column;
    grid-auto-columns: 130px;
    overflow: auto;
  }
  .timeline-labels > span {
    font-size: 12px;
  }
  .timeline-tracks {
    grid-template-columns: 58px minmax(0, 1fr);
    padding-inline: 6px;
  }
}
</style>
