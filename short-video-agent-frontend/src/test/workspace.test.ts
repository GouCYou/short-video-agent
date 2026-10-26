import { mount, flushPromises } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { describe, it, expect, vi } from "vitest";
import ConversationTurn from "../components/ConversationTurn.vue";
import StudioMenu from "../components/StudioMenu.vue";

describe("continuous creative workspace", () => {
  it("shows timestamps and real processing summaries without chat avatars, and copies and retries the response", async () => {
    const copy = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText: copy },
    });
    const wrapper = mount(ConversationTurn, {
      props: {
        message: {
          id: "answer",
          role: "assistant",
          content: "这是创作建议。",
          choices: [],
          runId: "run",
          createdAt: "2026-10-25T06:00:00Z",
          metadata: { thinkingMs: 2310 },
        },
        events: [
          {
            id: 1,
            runId: "run",
            type: "tool.completed",
            tool: "read_document",
            data: {},
            createdAt: "2026-10-25T06:00:00Z",
          },
        ],
        canRetry: true,
        timestamp: "2026年10月25日 14:00",
      },
      global: { plugins: [ElementPlus] },
    });
    expect(wrapper.find(".el-avatar").exists()).toBe(false);
    expect(wrapper.get("time").text()).toContain("14:00");
    expect(wrapper.get(".process-toggle").text()).toContain("2 秒");
    await wrapper.get(".process-toggle").trigger("click");
    expect(wrapper.get(".process-details").classes()).toContain("is-expanded");
    expect(wrapper.get(".process-details").text()).toContain("阅读上传文档");
    await wrapper.get('[aria-label="复制回复"]').trigger("click");
    expect(copy).toHaveBeenCalledWith("这是创作建议。");
    await wrapper.get('[aria-label="重新生成回复"]').trigger("click");
    expect(wrapper.emitted("retry")).toHaveLength(1);
    wrapper.unmount();
  });
  it("opens a custom menu with pin action and supports Escape", async () => {
    const wrapper = mount(StudioMenu, {
      attachTo: document.body,
      props: {
        label: "操作",
        items: [
          { id: "pin", label: "置顶对话" },
          { id: "delete", label: "删除对话", disabled: true },
        ],
      },
      slots: { default: '<button aria-label="更多操作">更多</button>' },
    });
    await wrapper.get("button").trigger("click");
    await flushPromises();
    const menu = document.querySelector('[role="menu"]')!;
    expect(menu.textContent).toContain("置顶对话");
    const item = menu.querySelector("button")!;
    item.click();
    await flushPromises();
    expect(wrapper.emitted("select")?.[0]).toEqual(["pin"]);
    await wrapper.get("button").trigger("click");
    await flushPromises();
    document
      .querySelector('[role="menu"]')!
      .dispatchEvent(
        new KeyboardEvent("keydown", { key: "Escape", bubbles: true }),
      );
    await flushPromises();
    expect(document.querySelector('[role="menu"]')).toBeNull();
    wrapper.unmount();
  });
});

import SlidingSegment from "../components/SlidingSegment.vue";
it("moves segmented selection with keyboard and exposes one moving thumb", async () => {
  const wrapper = mount(SlidingSegment, {
    props: {
      modelValue: "all",
      label: "作品状态",
      options: [
        { value: "all", label: "全部" },
        { value: "draft", label: "草稿" },
        { value: "final", label: "已定稿" },
      ],
    },
  });
  await wrapper.get("button").trigger("keydown", { key: "ArrowRight" });
  expect(wrapper.emitted("update:modelValue")?.[0]).toEqual(["draft"]);
  await wrapper.setProps({ modelValue: "draft" });
  expect(wrapper.findAll('[aria-pressed="true"]')).toHaveLength(1);
  expect(wrapper.attributes("style")).toContain("--segment-index: 1");
  await wrapper.get("button").trigger("keydown", { key: "End" });
  expect(wrapper.emitted("update:modelValue")?.[1]).toEqual(["final"]);
  expect(wrapper.findAll(".segment-thumb")).toHaveLength(1);
});
it("replaces the old reply body while regeneration streams in the same turn", () => {
  const wrapper = mount(ConversationTurn, {
    props: {
      message: {
        id: "retry",
        role: "assistant",
        content: "旧回答",
        choices: [],
        runId: "old-run",
        createdAt: "2026-10-25T06:00:00Z",
      },
      events: [],
      regenerating: true,
      liveText: "新回答",
    },
    global: { plugins: [ElementPlus] },
  });
  expect(wrapper.text()).not.toContain("旧回答");
  expect(wrapper.text()).toContain("新回答");
  expect(wrapper.find('[aria-label="重新生成回复"]').exists()).toBe(false);
});
