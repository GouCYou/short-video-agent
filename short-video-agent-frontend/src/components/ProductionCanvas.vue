<script setup lang="ts">
import { computed, ref } from "vue";
import { PhArrowUpRight, PhPlus, PhTrash } from "@phosphor-icons/vue";
import type { ProductionPackage } from "../lib/conversation-types";
import InlineText from "./InlineText.vue";
import SlidingSegment from "./SlidingSegment.vue";
import AnimatedDisclosure from "./AnimatedDisclosure.vue";
const props = withDefaults(
  defineProps<{ editable?: boolean; initialTab?: string }>(),
  { editable: false, initialTab: "script" },
);
const content = defineModel<ProductionPackage>({ required: true });
const emit = defineEmits<{ revise: [scope: string] }>();
const section = ref(
  props.initialTab === "plan" ? "publish" : ["shots", "shooting", "voice", "editing", "publish"].includes(props.initialTab)
    ? props.initialTab
    : "script",
);
const list = (values: string[]) => values.join("\n");
const patchList = (target: string[], text: string) =>
  target.splice(0, target.length, ...text.split("\n").filter((v) => v.trim()));
const total = computed(() =>
  content.value.segments.reduce((sum, s) => sum + Number(s.duration), 0),
);
function addShot() {
  content.value.segments.push({
    id: crypto.randomUUID(),
    duration: 5,
    visual: "补充画面",
    narration: "补充口播",
    subtitle: "",
    shotSize: "中景",
    camera: "固定机位",
    action: "自然表达",
    voiceDirection: "自然口播",
    editing: "按语意剪辑",
    material: "自摄素材",
  });
}
</script>
<template>
  <article class="production-canvas">
    <SlidingSegment
      v-model="section"
      label="作品内容"
      :options="[
        { value: 'script', label: '脚本' },
        { value: 'shots', label: '分镜' },
        { value: 'shooting', label: '拍摄' },
        { value: 'voice', label: '配音' },
        { value: 'editing', label: '剪辑' },
        { value: 'publish', label: '发布' },
      ]"
    />
    <div class="canvas-paper" :class="{ editable }">
      <header class="canvas-document-heading">
        <InlineText
          v-model="content.title"
          label="作品标题"
          :editable="editable"
          :multiline="false"
        /><InlineText
          v-model="content.summary"
          class="canvas-summary"
          label="内容摘要"
          :editable="editable"
        />
        <div class="canvas-meta">
          <span>{{ total }} 秒</span
          ><span>{{ content.segments.length }} 个镜头</span>
        </div>
      </header>
      <template v-if="section === 'script'">
        <section class="canvas-paragraph">
          <header>
            <h3>开场</h3>
            <button v-if="editable" @click="emit('revise', 'opening')">
              让 Agent 调整<PhArrowUpRight :size="16" />
            </button>
          </header>
          <InlineText
            v-model="content.opening"
            label="开场"
            :editable="editable"
          />
        </section>
        <section
          v-for="(shot, i) in content.segments"
          :key="shot.id"
          class="canvas-paragraph"
        >
          <header>
            <h3>
              <span class="canvas-shot-no">{{
                String(i + 1).padStart(2, "0")
              }}</span
              >{{ shot.shotSize }}
            </h3>
            <span>{{ shot.duration }} 秒</span
            ><button v-if="editable" @click="emit('revise', `镜头 ${i + 1}`)">
              调整此段<PhArrowUpRight :size="16" />
            </button>
          </header>
          <InlineText
            v-model="shot.narration"
            :label="`镜头 ${i + 1} 口播`"
            :editable="editable"
          />
          <p class="canvas-visual-note">{{ shot.visual }}</p>
        </section>
        <section class="canvas-paragraph">
          <h3>结尾</h3>
          <InlineText
            v-model="content.ending"
            label="结尾"
            :editable="editable"
          />
        </section>
      </template>
      <template v-else-if="section === 'shots'">
        <section
          v-for="(shot, i) in content.segments"
          :key="shot.id"
          class="canvas-storyboard"
        >
          <header>
            <span class="canvas-shot-no">{{
              String(i + 1).padStart(2, "0")
            }}</span
            ><span>{{ shot.duration }} 秒 · {{ shot.shotSize }}</span
            ><button v-if="editable" @click="emit('revise', `镜头 ${i + 1}`)">
              让 Agent 调整<PhArrowUpRight :size="16" />
            </button>
          </header>
          <div class="canvas-shot-body">
            <h3>画面</h3>
            <InlineText
              v-model="shot.visual"
              :label="`镜头 ${i + 1} 画面`"
              :editable="editable"
            />
            <h3>口播</h3>
            <InlineText
              v-model="shot.narration"
              :label="`镜头 ${i + 1} 口播`"
              :editable="editable"
            />
          </div>
          <AnimatedDisclosure
            ><template #summary>拍摄与剪辑细节</template>
            <div class="canvas-shot-details">
              <label>运镜</label
              ><InlineText
                v-model="shot.camera"
                label="运镜"
                :editable="editable"
              /><label>动作</label
              ><InlineText
                v-model="shot.action"
                label="动作"
                :editable="editable"
              /><label>字幕</label
              ><InlineText
                v-model="shot.subtitle"
                label="字幕"
                :editable="editable"
              /><label>剪辑</label
              ><InlineText
                v-model="shot.editing"
                label="剪辑"
                :editable="editable"
              />
              <div v-if="editable" class="canvas-shot-settings">
                <label
                  >时长
                  <input
                    v-model.number="shot.duration"
                    type="number"
                    min="1"
                    max="600"
                    :aria-label="`镜头 ${i + 1} 时长`"
                  />
                  秒</label
                ><button
                  v-if="content.segments.length > 1"
                  @click="content.segments.splice(i, 1)"
                >
                  <PhTrash :size="17" />删除镜头
                </button>
              </div>
            </div></AnimatedDisclosure
          >
        </section>
        <button
          v-if="editable && content.segments.length < 30"
          class="canvas-add-shot"
          @click="addShot"
        >
          <PhPlus :size="18" />添加镜头
        </button>
      </template>
      <template v-else-if="section === 'shooting'">
        <section class="canvas-paragraph">
          <header>
            <h3>拍摄方式</h3>
            <button v-if="editable" @click="emit('revise', 'shooting')">
              让 Agent 调整<PhArrowUpRight :size="16" />
            </button>
          </header>
          <InlineText
            v-model="content.shootingPlan.approach"
            label="拍摄方式"
            :editable="editable"
          />
        </section>
        <section
          v-for="key in [
            'locations',
            'equipment',
            'props',
            'schedule',
          ] as const"
          :key="key"
          class="canvas-paragraph"
        >
          <h3>
            {{
              {
                locations: "拍摄地点",
                equipment: "设备",
                props: "道具",
                schedule: "拍摄清单",
              }[key]
            }}
          </h3>
          <InlineText
            :model-value="list(content.shootingPlan[key])"
            @update:model-value="patchList(content.shootingPlan[key], $event)"
            :label="key"
            :editable="editable"
          />
        </section>
      </template>
      <template v-else-if="section === 'voice'">
        <section class="canvas-paragraph">
          <header>
            <h3>配音方式</h3>
            <button v-if="editable" @click="emit('revise', 'voiceover')">
              让 Agent 调整<PhArrowUpRight :size="16" />
            </button>
          </header>
          <InlineText
            v-model="content.voiceoverPlan.approach"
            label="配音方式"
            :editable="editable"
          />
        </section>
        <section class="canvas-paragraph">
          <h3>完整口播稿</h3>
          <InlineText
            v-model="content.voiceoverPlan.script"
            label="完整口播稿"
            :editable="editable"
          />
        </section>
        <section class="canvas-paragraph">
          <h3>语气与节奏</h3>
          <InlineText
            v-model="content.voiceoverPlan.tone"
            label="语气"
            :editable="editable"
          /><InlineText
            v-model="content.voiceoverPlan.pace"
            label="节奏"
            :editable="editable"
          /><InlineText
            :model-value="list(content.voiceoverPlan.recordingTips)"
            @update:model-value="
              patchList(content.voiceoverPlan.recordingTips, $event)
            "
            label="录音建议"
            :editable="editable"
          />
        </section>
      </template>
      <template v-else-if="section === 'editing'">
        <section class="canvas-paragraph">
          <header>
            <h3>剪辑方式</h3>
            <button v-if="editable" @click="emit('revise', 'editing')">
              让 Agent 调整<PhArrowUpRight :size="16" />
            </button>
          </header>
          <InlineText
            v-model="content.editingPlan.approach"
            label="剪辑方式"
            :editable="editable"
          />
        </section>
        <section class="canvas-paragraph">
          <h3>剪辑顺序</h3>
          <InlineText
            :model-value="list(content.editingPlan.steps)"
            @update:model-value="patchList(content.editingPlan.steps, $event)"
            label="剪辑顺序"
            :editable="editable"
          />
        </section>
        <section
          v-for="key in ['rhythm', 'music', 'subtitleStyle'] as const"
          :key="key"
          class="canvas-paragraph"
        >
          <h3>
            {{
              { rhythm: "节奏", music: "音乐", subtitleStyle: "字幕样式" }[key]
            }}
          </h3>
          <InlineText
            v-model="content.editingPlan[key]"
            :label="key"
            :editable="editable"
          />
        </section>
      </template>
      <template v-else>
        <section class="canvas-paragraph">
          <h3>发布文案</h3>
          <InlineText
            v-model="content.caption"
            label="发布文案"
            :editable="editable"
          />
        </section>
        <section class="canvas-paragraph">
          <h3>备选标题</h3>
          <InlineText
            :model-value="list(content.titles)"
            @update:model-value="patchList(content.titles, $event)"
            label="备选标题"
            :editable="editable"
          />
        </section>
        <section class="canvas-paragraph">
          <h3>话题</h3>
          <InlineText
            :model-value="content.tags.join(' ')"
            @update:model-value="
              content.tags = $event.split(/\s+/).filter(Boolean)
            "
            label="话题"
            :editable="editable"
          />
        </section>
        <section class="canvas-paragraph">
          <h3>内容定位</h3>
          <InlineText
            v-model="content.positioning"
            label="内容定位"
            :editable="editable"
          /><InlineText
            v-model="content.audienceAnalysis"
            label="受众分析"
            :editable="editable"
          />
        </section>
      </template>
    </div>
  </article>
</template>
