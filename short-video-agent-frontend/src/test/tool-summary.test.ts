import { describe, it, expect } from "vitest";
import { toolSummary } from "../lib/tool-summary";
describe("readable actual tool summaries", () => {
  it("uses Chinese fields and omits internal prompts, credentials and ids", () => {
    const rows = toolSummary(
      "update_brief",
      { systemPrompt: "never-display", apiKey: "secret" },
      {
        brief: {
          topic: "校园生活",
          duration: 60,
          audience: "大学生",
          systemPrompt: "internal",
        },
      },
    );
    expect(rows).toEqual([
      { label: "主题", value: "校园生活" },
      { label: "受众", value: "大学生" },
      { label: "目标时长", value: "60 秒" },
    ]);
    expect(JSON.stringify(rows)).not.toContain("never-display");
    expect(JSON.stringify(rows)).not.toContain("secret");
  });
  it("shows actual evidence counts and names without claiming fact verification", () => {
    const rows = toolSummary(
      "search_knowledge",
      { query: "拍摄图书馆", category: "创作方法" },
      {
        matchCount: 2,
        sources: [
          { documentName: "实拍指南", chunkId: "private-one" },
          { documentName: "实拍指南", chunkId: "private-two" },
        ],
        factVerified: false,
      },
    );
    expect(rows).toContainEqual({ label: "找到资料", value: "2 个片段" });
    expect(rows).toContainEqual({ label: "资料名称", value: "实拍指南" });
    expect(JSON.stringify(rows)).not.toMatch(/private-one|factVerified|已验证/);
  });
  it("renders actual creation and scoped revision outcomes without exposing UUIDs", () => {
    expect(
      toolSummary(
        "create_production",
        {},
        {
          title: "图书馆的一天",
          segments: 4,
          sourceCount: 1,
          completeSections: ["拍摄方案", "配音方案", "剪辑方案"],
        },
      ),
    ).toContainEqual({ label: "分镜数量", value: "4 个" });
    const rows = toolSummary(
      "revise_production",
      { scope: "segment:secret-uuid", instruction: "internal-expanded-prompt" },
      { title: "图书馆的一天", preservedOtherSections: true },
    );
    expect(rows).toContainEqual({ label: "修改范围", value: "指定分镜" });
    expect(rows).toContainEqual({ label: "其他内容", value: "已保留" });
    expect(JSON.stringify(rows)).not.toMatch(
      /secret-uuid|internal-expanded-prompt/,
    );
  });
});

it("shows a real knowledge save receipt and distinguishes pending indexing", () => {
  const rows = toolSummary(
    "save_knowledge",
    { text: "不要回显整份正文" },
    {
      documentId: "private-id",
      name: "咖啡拍摄指南",
      status: "UNINDEXED",
      indexingAvailable: false,
    },
  );
  expect(rows).toContainEqual({ label: "保存结果", value: "已保存到知识库" });
  expect(rows).toContainEqual({ label: "检索状态", value: "待建立索引" });
  expect(rows).toContainEqual({
    label: "下一步",
    value: "配置向量模型后建立索引",
  });
  expect(JSON.stringify(rows)).not.toMatch(/private-id|UNINDEXED|不要回显/);
});

it("keeps a disabled duplicate distinct from a searchable document", () => {
  expect(
    toolSummary(
      "save_knowledge",
      {},
      {
        documentId: "id",
        name: "资料",
        duplicate: true,
        enabled: false,
        status: "READY",
      },
    ),
  ).toContainEqual({ label: "检索状态", value: "已停用" });
});
