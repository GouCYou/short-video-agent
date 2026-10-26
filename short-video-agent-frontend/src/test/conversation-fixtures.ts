import type {
  ChoiceGroup,
  Conversation,
  ProductionPackage,
  RunEvent,
} from "../lib/conversation-types";
export function choiceGroup(kind: ChoiceGroup["kind"] = "TOPIC"): ChoiceGroup {
  return {
    id: `group-${kind}`,
    kind,
    title: `选择${kind === "TOPIC" ? "选题" : kind === "AUDIENCE" ? "受众" : kind === "DIRECTION" ? "方向" : kind === "STYLE" ? "风格" : "制作方式"}`,
    options: [
      {
        id: `${kind}-a`,
        label: "A",
        title: "第一个方案",
        description: "这个方向的说明",
        value: "one",
      },
      {
        id: `${kind}-b`,
        label: "B",
        title: "第二个方案",
        description: "另一个方向",
        value: "two",
      },
      {
        id: `${kind}-c`,
        label: "C",
        title: "第三个方案",
        description: "第三种选择",
        value: "three",
      },
    ],
    selectedOptionId: null,
    customValue: null,
    status: "OPEN",
  };
}
export const production: ProductionPackage = {
  title: "图书馆里的大学生活",
  summary: "记录一次校园图书馆学习体验",
  positioning: "校园生活记录",
  audienceAnalysis: "刚入学的大学生",
  titles: ["图书馆的一天", "校园学习时刻"],
  opening: "今天带你看看学校的图书馆。",
  segments: [
    {
      id: "shot-stable-one",
      duration: 30,
      visual: "图书馆入口",
      narration: "早晨走进图书馆。",
      subtitle: "图书馆的一天",
      shotSize: "全景",
      camera: "缓慢推进",
      action: "人物走入画面",
      voiceDirection: "轻松自然",
      editing: "开场淡入",
      material: "图书馆外景",
    },
  ],
  ending: "你最喜欢哪个学习角落？",
  caption: "校园里的安静时刻",
  tags: ["校园", "大学生活"],
  shootingPlan: {
    approach: "手机实拍",
    locations: ["学校图书馆"],
    equipment: ["手机", "小三脚架"],
    props: ["笔记本"],
    schedule: ["提前确认开放时间", "早晨拍摄外景"],
  },
  voiceoverPlan: {
    approach: "后期录制口播",
    tone: "自然亲切",
    pace: "每分钟220字",
    recordingTips: ["关闭空调", "录制环境底噪"],
    script: "今天带你看看学校的图书馆。早晨走进图书馆。你最喜欢哪个学习角落？",
  },
  editingPlan: {
    approach: "叙事剪辑",
    rhythm: "平缓舒适",
    music: "授权轻音乐",
    soundEffects: ["翻书声"],
    subtitleStyle: "白色字幕",
    steps: ["整理素材", "录制配音", "匹配画面", "添加字幕"],
  },
  qualityNotes: ["发布前确认人物出镜授权"],
};
export function event(
  id: number,
  type: RunEvent["type"] = "message.delta",
  text = "一句回复",
  runId = "run-one",
): RunEvent {
  return {
    id,
    runId,
    type,
    data: { text },
    createdAt: "2026-10-25T10:00:00+08:00",
  };
}
export function conversation(
  overrides: Partial<Conversation> = {},
): Conversation {
  return {
    id: "chat-one",
    title: "校园生活",
    status: "WAITING_INPUT",
    brief: {
      topic: "校园生活",
      direction: "",
      audience: "",
      platform: "抖音",
      duration: 60,
      style: "自然亲切",
      productionMode: "",
      equipment: "",
      requirements: "",
    },
    createdAt: "2026-10-25T10:00:00+08:00",
    updatedAt: "2026-10-25T10:00:00+08:00",
    activeRunId: null,
    messages: [],
    events: [],
    artifact: null,
    versions: [],
    ...overrides,
  };
}
