export interface ToolSummaryRow {
  label: string;
  value: string;
}
const briefLabels: Record<string, string> = {
  topic: "主题",
  direction: "表达方向",
  audience: "受众",
  platform: "平台",
  duration: "目标时长",
  style: "表达风格",
  productionMode: "制作方式",
  equipment: "设备",
  requirements: "补充要求",
};
const kindLabels: Record<string, string> = {
  TOPIC: "选题",
  DIRECTION: "方向",
  AUDIENCE: "受众",
  STYLE: "风格",
  PRODUCTION: "制作方式",
};
const scopeLabels: Record<string, string> = {
  ALL: "整份作品",
  opening: "开场",
  titles: "标题",
  voiceover: "配音方案",
  shooting: "拍摄方案",
  editing: "剪辑方案",
  duration: "时长",
};
function record(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}
function text(value: unknown): string {
  if (typeof value === "string") return value;
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  if (Array.isArray(value))
    return value.filter((item) => typeof item === "string").join("、");
  return "";
}
export function toolSummary(
  tool: string,
  inputValue: unknown,
  outputValue: unknown,
): ToolSummaryRow[] {
  const input = record(inputValue),
    output = record(outputValue),
    rows: ToolSummaryRow[] = [];
  const add = (label: string, value: unknown) => {
    const result = text(value);
    if (result.trim()) rows.push({ label, value: result });
  };
  if (tool === "update_brief") {
    const brief = Object.keys(record(output.brief)).length
      ? record(output.brief)
      : input;
    for (const [key, label] of Object.entries(briefLabels))
      if (brief[key] != null && text(brief[key]).trim())
        add(label, key === "duration" ? `${text(brief[key])} 秒` : brief[key]);
  } else if (tool === "propose_options") {
    const groups = Array.isArray(output.choices) ? output.choices : [input];
    for (const item of groups) {
      const group = record(item);
      add("选项类型", kindLabels[text(group.kind)]);
      add("内容", group.title);
      if (Array.isArray(group.options))
        add(
          "备选方案",
          group.options
            .map((option) => text(record(option).title))
            .filter(Boolean)
            .join("、"),
        );
    }
  } else if (tool === "search_news") {
    add("搜索内容", input.query);
    if (typeof output.matchCount === "number")
      add("找到新闻", `${output.matchCount} 条`);
    if (Array.isArray(output.notices))
      add("检索状态", output.notices.join("；"));
  } else if (tool === "read_news") {
    add("新闻", output.title);
  } else if (tool === "search_knowledge") {
    add("检索内容", input.query);
    add("知识分类", input.category);
    if (typeof output.matchCount === "number")
      add("找到资料", `${output.matchCount} 个片段`);
    if (Array.isArray(output.sources))
      add(
        "资料名称",
        [
          ...new Set(
            output.sources
              .map((source) => text(record(source).documentName))
              .filter(Boolean),
          ),
        ].join("、"),
      );
  } else if (tool === "save_knowledge") {
    add("资料", output.name || input.name);
    add(
      "保存结果",
      output.duplicate === true
        ? "知识库已存在，未重复保存"
        : output.documentId
          ? "已保存到知识库"
          : "尚未保存",
    );
    add(
      "检索状态",
      output.enabled === false
        ? "已停用"
        : (
            {
              UNINDEXED: "待建立索引",
              INDEXING: "索引中",
              READY: "已可检索",
              FAILED: "索引失败",
            } as Record<string, string>
          )[text(output.status)],
    );
    if (output.indexingAvailable === false && output.status !== "READY")
      add("下一步", "配置向量模型后建立索引");
  } else if (tool === "read_document") {
    add("文档", output.name);
    if (typeof output.totalCharacters === "number")
      add("文档文字", `${output.totalCharacters} 字`);
    if (typeof output.nextOffset === "number")
      add(
        "读取范围",
        `${Number(output.offset || 0) + 1}–${output.nextOffset} 字`,
      );
    if (output.hasMore === true) add("状态", "还有后续内容可读取");
  } else if (tool === "search_history_creations") {
    add("检索内容", input.query);
    if (typeof output.count === "number") add("匹配作品", `${output.count} 份`);
    if (Array.isArray(output.works))
      add(
        "作品",
        output.works
          .map((item) => text(record(item).title))
          .filter(Boolean)
          .join("、"),
      );
  } else if (tool === "read_history_creation") {
    add("作品", output.title);
    add("状态", "已读取历史稿件");
  } else if (tool === "read_user_profile") {
    if (output.enabled === false) add("状态", "自动记忆已关闭");
    if (Array.isArray(output.items))
      add(
        "已保存偏好",
        output.items.map((item) => text(record(item).text)).join("；") ||
          "暂无已保存偏好",
      );
  } else if (tool === "create_production") {
    add("作品", output.title);
    if (typeof output.segments === "number")
      add("分镜数量", `${output.segments} 个`);
    add("制作内容", output.completeSections);
    if (typeof output.sourceCount === "number")
      add("引用资料", `${output.sourceCount} 个片段`);
  } else if (tool === "revise_production") {
    add("作品", output.title);
    const scope = text(output.scope || input.scope);
    add(
      "修改范围",
      scope.startsWith("segment:") ? "指定分镜" : scopeLabels[scope],
    );
    if (output.preservedOtherSections === true) add("其他内容", "已保留");
  } else if (tool === "check_production") {
    for (const [key, label] of [
      ["targetSeconds", "目标时长"],
      ["durationSeconds", "分镜总时长"],
      ["estimatedSpeechSeconds", "预计口播时长"],
    ])
      if (typeof output[key!] === "number") add(label!, `${output[key!]} 秒`);
    if (Array.isArray(output.warnings))
      add("检查提醒", output.warnings.join("\n"));
  }
  return rows;
}
