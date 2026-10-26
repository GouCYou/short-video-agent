import type { Content, Task, User } from "../lib/types";
export const content: Content = {
  titles: ["一个可验证的故事", "备选标题"],
  opening: "你有没有注意过这个细节？",
  positioning: "生活知识科普",
  audienceAnalysis: "对科学感兴趣的大学生",
  outline: ["问题引入", "解释原理", "结尾互动"],
  segments: [
    {
      duration: 30,
      visual: "镜头展示实际场景",
      narration: "今天我们来观察这个现象。",
      subtitle: "从观察开始",
    },
    {
      duration: 20,
      visual: "动画解释",
      narration: "背后的原理值得理解。",
      subtitle: "理解原理",
    },
  ],
  ending: "分享你的发现吧。",
  caption: "一起来探索身边的知识。",
  tags: ["生活科普", "知识"],
  qualityNotes: ["资料不足时请人工核实事实。"],
};
export const user: User = {
  id: "user-one",
  username: "creator",
  nickname: "创作者",
  role: "USER",
  enabled: true,
  createdAt: "2026-10-22T15:00:00Z",
};
export function makeTask(overrides: Partial<Task> = {}): Task {
  return {
    id: "task-one",
    title: "生活中的科学",
    topic: "为大学生讲解生活中常见的科学现象",
    audience: "大学生",
    platform: "抖音",
    style: "自然亲切",
    duration: 60,
    requirements: "",
    mode: "GUIDED",
    category: "",
    ragEnabled: true,
    status: "COMPLETED",
    stage: "COMPLETED",
    error: "",
    createdAt: "2026-10-22T15:00:00Z",
    updatedAt: "2026-10-22T15:01:00Z",
    topics: [],
    result: structuredClone(content),
    sources: [],
    events: [],
    versions: [],
    ...overrides,
  };
}
