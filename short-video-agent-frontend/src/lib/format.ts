import type { Content, TaskStatus } from "./types";
export const statusLabels: Record<TaskStatus, string> = {
  QUEUED: "等待生成",
  RUNNING: "正在创作",
  WAITING_SELECTION: "待选题",
  COMPLETED: "已完成",
  FAILED: "生成失败",
};
export const stageLabels: Record<string, string> = {
  QUEUED: "准备开始",
  PLAN: "构思选题",
  ANALYZE: "分析需求",
  RETRIEVE: "检索资料",
  TOPICS: "生成选题",
  GENERATE: "生成脚本",
  SCRIPT: "撰写脚本",
  VALIDATE: "检查内容",
  REVISE: "优化内容",
  COMPLETED: "创作完成",
  FAILED: "运行失败",
  WAITING_SELECTION: "等待选题",
  QUALITY: "检查内容",
};
export const active = (status: string) =>
  status === "QUEUED" || status === "RUNNING";
export function dateTime(value?: string, now = new Date()) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? "—"
    : new Intl.DateTimeFormat("zh-CN", {
        year: date.getFullYear() !== now.getFullYear() ? "numeric" : undefined,
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        hour12: false,
      }).format(date);
}
export function durationText(milliseconds: number): string {
  const seconds = Math.floor(
    Math.max(0, Number.isFinite(milliseconds) ? milliseconds : 0) / 1000,
  );
  if (seconds < 1) return "不到 1 秒";
  const hours = Math.floor(seconds / 3600),
    minutes = Math.floor((seconds % 3600) / 60),
    remainder = seconds % 60;
  return [
    hours ? `${hours} 小时` : "",
    minutes ? `${minutes} 分` : "",
    remainder ? `${remainder} 秒` : "",
  ]
    .filter(Boolean)
    .join(" ");
}
export function chatTimestamp(
  value: string,
  now = new Date(),
): string | undefined {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return undefined;
  return new Intl.DateTimeFormat("zh-CN", {
    year: date.getFullYear() !== now.getFullYear() ? "numeric" : undefined,
    month: "long",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(date);
}
export function contentText(c: Content): string {
  return [
    c.titles.join("\n"),
    `内容定位：${c.positioning}`,
    `受众分析：${c.audienceAnalysis}`,
    "开场",
    c.opening,
    ...c.segments.map(
      (s, i) =>
        `第 ${i + 1} 段 · ${s.duration} 秒\n画面：${s.visual}\n口播：${s.narration}\n字幕：${s.subtitle}`,
    ),
    "结尾",
    c.ending,
    "发布文案",
    c.caption,
    c.tags.map((t) => `#${t.replace(/^#/, "")}`).join(" "),
  ].join("\n\n");
}
export function normalizeContent(c: Content): Content {
  return {
    titles: c.titles || [],
    opening: c.opening || "",
    positioning: c.positioning || "",
    audienceAnalysis: c.audienceAnalysis || "",
    outline: c.outline || [],
    segments: c.segments || [],
    ending: c.ending || "",
    caption: c.caption || "",
    tags: c.tags || [],
    qualityNotes: c.qualityNotes || [],
  };
}
export function safeUrl(value?: string): string | undefined {
  if (!value) return;
  try {
    const url = new URL(value);
    if (url.protocol === "https:" || url.protocol === "http:") return url.href;
  } catch {
    /* source can be descriptive */
  }
}

export function validateContent(content: Content): string | undefined {
  const required: [string, string, number][] = [
    ["开场口播", content.opening, 5000],
    ["内容定位", content.positioning, 5000],
    ["受众分析", content.audienceAnalysis, 5000],
    ["结尾互动", content.ending, 5000],
    ["发布文案", content.caption, 10000],
  ];
  for (const [label, value, max] of required) {
    if (!value?.trim()) return `请填写${label}。`;
    if (value.length > max) return `${label}不能超过 ${max} 字。`;
  }
  for (const [label, items, min, max] of [
    ["备选标题", content.titles, 1, 10],
    ["内容结构", content.outline, 1, 30],
    ["话题标签", content.tags, 0, 20],
  ] as const) {
    if (items.length < min || items.length > max)
      return `${label}需保留 ${min}–${max} 项。`;
    if (items.some((item) => !item.trim() || item.length > 2000))
      return `${label}每项需为 1–2000 字的有效文本。`;
  }
  if (content.segments.length < 1 || content.segments.length > 30)
    return "请保留 1–30 个脚本片段。";
  for (const [i, segment] of content.segments.entries()) {
    if (
      !Number.isFinite(segment.duration) ||
      segment.duration <= 0 ||
      segment.duration > 600
    )
      return `片段 ${i + 1} 时长需在 0–600 秒之间。`;
    if (!segment.visual.trim() || !segment.narration.trim())
      return `请填写片段 ${i + 1} 的画面建议与口播文案。`;
    if (
      segment.visual.length > 5000 ||
      segment.narration.length > 10000 ||
      segment.subtitle.length > 5000
    )
      return `片段 ${i + 1} 文本长度超出限制。`;
  }
}
