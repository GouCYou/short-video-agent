import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { describe, it, expect } from "vitest";
import ContentDocument from "../components/ContentDocument.vue";
import StatusBadge from "../components/StatusBadge.vue";
import { content } from "./fixtures";
import { contentText, normalizeContent, safeUrl, active } from "../lib/format";
describe("structured script document", () => {
  it("renders every field read-only and treats model output as plain text", () => {
    const value = structuredClone(content);
    value.opening = "<img src=x onerror=alert(1)>";
    const wrapper = mount(ContentDocument, {
      props: { modelValue: value },
      global: { plugins: [ElementPlus] },
    });
    expect(wrapper.text()).toContain(value.opening);
    expect(wrapper.find("img").exists()).toBe(false);
    expect(wrapper.text()).toContain("2 个片段 · 50 秒");
    expect(wrapper.text()).toContain(value.segments[1]!.subtitle);
    expect(wrapper.find("textarea").exists()).toBe(false);
    wrapper.unmount();
  });
  it("supports a scoped edit while preserving other script fields", async () => {
    const value = structuredClone(content),
      original = structuredClone(value);
    const wrapper = mount(ContentDocument, {
      props: { modelValue: value, editable: true },
      global: { plugins: [ElementPlus] },
    });
    const field = wrapper.get('textarea[aria-label="片段 1 口播"]');
    await field.setValue("调整后的第一段口播");
    expect(value.segments[0]!.narration).toBe("调整后的第一段口播");
    expect(value.segments[1]).toEqual(original.segments[1]);
    expect(value.opening).toBe(original.opening);
    const add = wrapper
      .findAll("button")
      .find((b) => b.text() === "添加脚本片段")!;
    await add.trigger("click");
    expect(value.segments).toHaveLength(3);
    await wrapper.get('button[aria-label="删除片段 3"]').trigger("click");
    expect(value.segments).toHaveLength(2);
    wrapper.unmount();
  });
  it("serializes all publishable content for clipboard without dropping captions", () => {
    const text = contentText(content);
    expect(text).toContain(content.titles[0]);
    expect(text).toContain("第 2 段 · 20 秒");
    expect(text).toContain(content.caption);
    expect(text).toContain("#生活科普 #知识");
  });
  it("blocks unsafe source URLs while allowing source links", () => {
    expect(safeUrl("javascript:alert(1)")).toBeUndefined();
    expect(safeUrl("data:text/html,test")).toBeUndefined();
    expect(safeUrl("file:///etc/passwd")).toBeUndefined();
    expect(safeUrl("https://example.com/article")).toBe(
      "https://example.com/article",
    );
  });
  it("handles partial legacy content and distinguishes polling statuses", () => {
    expect(normalizeContent({} as never).segments).toEqual([]);
    expect(active("WAITING_SELECTION")).toBe(false);
    expect(active("QUEUED")).toBe(true);
    expect(active("RUNNING")).toBe(true);
    const wrapper = mount(StatusBadge, { props: { status: "FAILED" } });
    expect(wrapper.text()).toContain("生成失败");
  });
});
