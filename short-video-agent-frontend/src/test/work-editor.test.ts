import { mount, flushPromises } from "@vue/test-utils";
import { createRouter, createMemoryHistory } from "vue-router";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import { it, expect, vi } from "vitest";
import ProductionCanvas from "../components/ProductionCanvas.vue";
import NewsSourceCard from "../components/NewsSourceCard.vue";
import WorkView from "../views/WorkView.vue";
import TasksView from "../views/TasksView.vue";
import { production, conversation } from "./conversation-fixtures";
import { conversationApi } from "../lib/conversation-api";
import { api } from "../lib/api";
vi.mock("../lib/conversation-api", async (importOriginal) => {
  const original =
    await importOriginal<typeof import("../lib/conversation-api")>();
  return {
    ...original,
    conversationApi: {
      ...original.conversationApi,
      get: vi.fn(),
      list: vi.fn(),
      events: vi.fn(),
      save: vi.fn(),
    },
    streamConversation: vi.fn(
      (_id, _after, signal) =>
        new Promise<void>((resolve) =>
          signal.addEventListener("abort", () => resolve(), { once: true }),
        ),
    ),
  };
});
function fixture() {
  return conversation({
    status: "IDLE",
    artifact: {
      id: "artifact-one",
      title: production.title,
      content: JSON.parse(JSON.stringify(production)),
      versionNumber: 1,
      status: "DRAFT",
      sources: [],
      createdAt: "2026-10-26T05:00:00Z",
      updatedAt: "2026-10-26T05:00:00Z",
    },
  });
}
it("edits a selected paragraph in place without turning the document into a form", async () => {
  const value = JSON.parse(JSON.stringify(production));
  const before = JSON.parse(JSON.stringify(value));
  const wrapper = mount(ProductionCanvas, {
    props: { modelValue: value, editable: true },
  });
  expect(wrapper.find("textarea").exists()).toBe(false);
  await wrapper.get('[aria-label="修改镜头 1 口播"]').trigger("click");
  expect(wrapper.findAll("textarea")).toHaveLength(1);
  await wrapper.get("textarea").setValue("新的口播内容");
  await wrapper.get("textarea").trigger("blur");
  expect(value.segments[0].narration).toBe("新的口播内容");
  expect(value.segments[0].id).toBe(before.segments[0].id);
  expect(value.shootingPlan).toEqual(before.shootingPlan);
  expect(wrapper.find("textarea").exists()).toBe(false);
  wrapper.unmount();
});
it("links a work row to read-only detail before entering edit mode", async () => {
  vi.mocked(conversationApi.list).mockResolvedValue([fixture()]);
  const tasks = vi.spyOn(api, "tasks").mockResolvedValue([]);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/tasks", component: { template: "<div/>" } },
      { path: "/works/:id", component: { template: "<div/>" } },
    ],
  });
  await router.push("/tasks");
  const wrapper = mount(TasksView, {
    global: { plugins: [router, createPinia(), ElementPlus] },
  });
  await flushPromises();
  expect(wrapper.findAll(".work-row")).toHaveLength(1);
  expect(wrapper.find(".work-card").exists()).toBe(false);
  await wrapper.get(".work-row-main").trigger("click");
  await flushPromises();
  expect(router.currentRoute.value.path).toBe("/works/chat-one");
  expect(router.currentRoute.value.query.edit).toBeUndefined();
  wrapper.unmount();
  tasks.mockRestore();
});
it("shows only the original article link in the compact workspace source card", () => {
  const wrapper = mount(NewsSourceCard, {
    props: {
      compact: true,
      source: {
        documentId: "web-one",
        documentName: "真实新闻标题",
        chunkId: "web-one",
        text: "发布时间：2026-10-26T07:20:00Z\n原文内部文本不应展开",
        sourceUrl: "https://news.cctv.com/story.shtml",
        score: 0,
      },
    },
  });
  expect(wrapper.get("a").attributes("href")).toBe(
    "https://news.cctv.com/story.shtml",
  );
  expect(wrapper.text()).not.toContain("原文内部文本");
  expect(wrapper.find(".news-published").exists()).toBe(false);
  wrapper.unmount();
});
it("requires the modify action and saves an inline change as a new artifact version", async () => {
  vi.mocked(conversationApi.get).mockResolvedValue(fixture());
  vi.mocked(conversationApi.events).mockResolvedValue([]);
  vi.mocked(conversationApi.save).mockImplementation(async (_id, content) => {
    const value = fixture();
    value.artifact!.content = JSON.parse(JSON.stringify(content));
    value.artifact!.versionNumber = 2;
    return value;
  });
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/works/:id", name: "work", component: WorkView },
      { path: "/tasks", component: { template: "<div/>" } },
    ],
  });
  await router.push("/works/chat-one");
  const wrapper = mount(WorkView, {
    global: { plugins: [router, createPinia(), ElementPlus] },
  });
  await flushPromises();
  expect(wrapper.classes()).not.toContain("is-editing");
  expect(wrapper.get(".work-agent-panel").attributes("inert")).toBeDefined();
  await wrapper
    .findAll(".work-detail-actions button")
    .find((b) => b.text() === "修改")!
    .trigger("click");
  await flushPromises();
  expect(wrapper.classes()).toContain("is-editing");
  await wrapper.get('[aria-label="修改开场"]').trigger("click");
  await wrapper.get(".production-canvas textarea").setValue("改好的开场");
  await wrapper.get(".production-canvas textarea").trigger("blur");
  await wrapper
    .findAll(".work-detail-actions button")
    .find((b) => b.text() === "保存")!
    .trigger("click");
  await flushPromises();
  expect(conversationApi.save).toHaveBeenCalledWith(
    "chat-one",
    expect.objectContaining({ opening: "改好的开场" }),
    1,
  );
  expect(wrapper.get(".work-detail-version").text()).toContain("V2");
  wrapper.unmount();
});
