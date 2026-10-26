<script setup lang="ts">
import AnimatedDisclosure from "./AnimatedDisclosure.vue";
import { ref, watch } from "vue";
import { Plus, Delete } from "@element-plus/icons-vue";
import type { ProductionPackage } from "../lib/conversation-types";
import ProductionField from "./ProductionField.vue";
import ProductionList from "./ProductionList.vue";
const props = withDefaults(
  defineProps<{ editable?: boolean; compact?: boolean; initialTab?: string }>(),
  {
    editable: false,
    compact: false,
    initialTab: "timeline",
  },
);
const content = defineModel<ProductionPackage>({ required: true });
const tab = ref(props.initialTab);
watch(
  () => props.initialTab,
  (value) => {
    tab.value = value;
  },
);
function addSegment() {
  if (content.value.segments.length < 30)
    content.value.segments.push({
      id: crypto.randomUUID(),
      duration: 10,
      visual: "",
      narration: "",
      subtitle: "",
      shotSize: "",
      camera: "",
      action: "",
      voiceDirection: "",
      editing: "",
      material: "",
    });
}
</script>
<template>
  <article class="production-document" :class="{ compact }">
    <el-tabs v-model="tab"
      ><el-tab-pane label="分镜" name="timeline"
        ><div v-if="!editable" class="production-summary">
          <h3>{{ content.title }}</h3>
          <p>{{ content.summary }}</p>
          <div class="production-timing">
            <span>{{ content.segments.length }} 个镜头</span
            ><span
              >{{
                content.segments.reduce((n, s) => n + Number(s.duration), 0)
              }}
              秒</span
            >
          </div>
        </div>
        <template v-if="editable"
          ><ProductionField
            v-model="content.title"
            label="作品标题"
            :editable="editable"
            :rows="1"
            :max="2000" /><ProductionField
            v-model="content.summary"
            label="内容摘要"
            :editable="editable" /></template
        ><ProductionField
          v-model="content.opening"
          label="开场"
          :editable="editable" />
        <div class="production-timeline">
          <AnimatedDisclosure
            v-for="(segment, index) in content.segments"
            :key="segment.id"
            class="production-shot"
            :open="editable || !compact"
          >
            <template #summary>
              <span class="shot-number">{{
                String(index + 1).padStart(2, "0")
              }}</span
              ><span
                >{{ segment.shotSize || "镜头"
                }}<span class="shot-duration"
                  >{{ segment.duration }} 秒</span
                ></span
              >
            </template>
            <div class="shot-content">
              <div v-if="editable" class="shot-controls">
                <label>时长（秒）</label
                ><el-input-number
                  v-model="segment.duration"
                  :min="1"
                  :max="600"
                  :aria-label="`镜头 ${index + 1} 时长`"
                /><el-button
                  :icon="Delete"
                  type="danger"
                  text
                  :aria-label="`删除镜头 ${index + 1}`"
                  @click="content.segments.splice(index, 1)"
                />
              </div>
              <div class="shot-field-grid">
                <ProductionField
                  v-model="segment.visual"
                  label="画面"
                  :editable="editable"
                /><ProductionField
                  v-model="segment.narration"
                  label="口播"
                  :editable="editable"
                /><ProductionField
                  v-model="segment.shotSize"
                  label="景别"
                  :editable="editable"
                  :rows="1"
                /><ProductionField
                  v-model="segment.camera"
                  label="镜头运动"
                  :editable="editable"
                  :rows="2"
                /><ProductionField
                  v-model="segment.action"
                  label="人物与动作"
                  :editable="editable"
                  :rows="2"
                /><ProductionField
                  v-model="segment.voiceDirection"
                  label="配音指导"
                  :editable="editable"
                  :rows="2"
                /><ProductionField
                  v-model="segment.subtitle"
                  label="字幕"
                  :editable="editable"
                  :rows="2"
                /><ProductionField
                  v-model="segment.editing"
                  label="剪辑处理"
                  :editable="editable"
                  :rows="2"
                /><ProductionField
                  v-model="segment.material"
                  label="素材准备"
                  :editable="editable"
                  :rows="2"
                />
              </div>
            </div>
          </AnimatedDisclosure>
        </div>
        <el-button
          v-if="editable"
          :icon="Plus"
          :disabled="content.segments.length >= 30"
          @click="addSegment"
          >添加镜头</el-button
        ><ProductionField
          v-model="content.ending"
          label="结尾"
          :editable="editable" /></el-tab-pane
      ><el-tab-pane label="策划" name="plan"
        ><ProductionField
          v-model="content.positioning"
          label="内容定位"
          :editable="editable" /><ProductionField
          v-model="content.audienceAnalysis"
          label="受众分析"
          :editable="editable" /><ProductionList
          v-model="content.titles"
          label="备选标题"
          :editable="editable" /></el-tab-pane
      ><el-tab-pane label="拍摄" name="shooting"
        ><ProductionField
          v-model="content.shootingPlan.approach"
          label="拍摄方式"
          :editable="editable" /><ProductionList
          v-model="content.shootingPlan.locations"
          label="拍摄地点"
          :editable="editable" /><ProductionList
          v-model="content.shootingPlan.equipment"
          label="设备"
          :editable="editable" /><ProductionList
          v-model="content.shootingPlan.props"
          label="道具"
          :editable="editable" /><ProductionList
          v-model="content.shootingPlan.schedule"
          label="拍摄安排"
          :editable="editable" /></el-tab-pane
      ><el-tab-pane label="配音" name="voice"
        ><ProductionField
          v-model="content.voiceoverPlan.approach"
          label="配音方式"
          :editable="editable" /><ProductionField
          v-model="content.voiceoverPlan.tone"
          label="语气"
          :editable="editable"
          :rows="2" /><ProductionField
          v-model="content.voiceoverPlan.pace"
          label="语速与停顿"
          :editable="editable"
          :rows="2" /><ProductionList
          v-model="content.voiceoverPlan.recordingTips"
          label="录音要点"
          :editable="editable" /><ProductionField
          v-model="content.voiceoverPlan.script"
          label="完整配音稿"
          :editable="editable"
          :rows="8" /></el-tab-pane
      ><el-tab-pane label="剪辑" name="editing"
        ><ProductionField
          v-model="content.editingPlan.approach"
          label="剪辑方式"
          :editable="editable" /><ProductionField
          v-model="content.editingPlan.rhythm"
          label="节奏"
          :editable="editable" /><ProductionField
          v-model="content.editingPlan.music"
          label="配乐"
          :editable="editable" /><ProductionList
          v-model="content.editingPlan.soundEffects"
          label="音效"
          :editable="editable" /><ProductionField
          v-model="content.editingPlan.subtitleStyle"
          label="字幕样式"
          :editable="editable" /><ProductionList
          v-model="content.editingPlan.steps"
          label="剪辑步骤"
          :editable="editable" /></el-tab-pane
      ><el-tab-pane label="发布" name="publish"
        ><ProductionField
          v-model="content.caption"
          label="发布文案"
          :editable="editable"
          :rows="5" /><ProductionList
          v-model="content.tags"
          label="话题标签"
          :editable="editable" /></el-tab-pane
    ></el-tabs>
    <AnimatedDisclosure
      v-if="content.qualityNotes?.length"
      class="quality-notes"
    >
      <template #summary>检查结果</template>
      <p v-for="(note, i) in content.qualityNotes" :key="i">{{ note }}</p>
    </AnimatedDisclosure>
  </article>
</template>
