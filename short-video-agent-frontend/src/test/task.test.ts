import { mount, flushPromises } from "@vue/test-utils";
import { createRouter, createMemoryHistory, RouterView } from "vue-router";
import ElementPlus from "element-plus";
import { beforeEach, afterEach, describe, it, expect, vi } from "vitest";
import { api } from "../lib/api";
import TaskView from "../views/TaskView.vue";
import NewTaskView from "../views/NewTaskView.vue";
import { makeTask } from "./fixtures";
vi.mock("../lib/api", async (importOriginal) => {
  const original = await importOriginal<typeof import("../lib/api")>();
  return {
    ...original,
    api: {
      ...original.api,
      task: vi.fn(),
      categories: vi.fn(),
      create: vi.fn(),
      selectTopic: vi.fn(),
      save: vi.fn(),
      revise: vi.fn(),
    },
  };
});
async function setup(path = "/tasks/task-one") {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/tasks/new", component: NewTaskView },
      { path: "/tasks/:id", component: TaskView },
    ],
  });
  await router.push(path);
  await router.isReady();
  const wrapper = mount(RouterView, {
    global: {
      plugins: [router, ElementPlus],
      directives: { loading: () => {} },
    },
  });
  await flushPromises();
  return { wrapper, router };
}
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.categories).mockResolvedValue(["创作方法"]);
});
afterEach(() => vi.useRealTimers());
describe("task lifecycle UI", () => {
  it("shows demo identity and preserves checks in a collapsed section", async () => {
    const task = makeTask();
    task.result!.qualityNotes = [
      "DEMO 演示模式，未调用真实模型",
      "请核实引用资料",
    ];
    vi.mocked(api.task).mockResolvedValue(task);
    const { wrapper } = await setup();
    expect(wrapper.text()).toContain("演示内容");
    const checks = wrapper.get(".quality-notes");
    expect(checks.get(".disclosure-toggle").attributes("aria-expanded")).toBe(
      "false",
    );
    expect(checks.get(".disclosure-toggle").text()).toBe("检查结果");
    expect(checks.text()).toContain("请核实引用资料");
    wrapper.unmount();
  });

  it("submits selected topic index and switches to running state", async () => {
    vi.mocked(api.task).mockResolvedValue(
      makeTask({
        status: "WAITING_SELECTION",
        result: null,
        topics: [
          {
            title: "新方向",
            angle: "通过生活场景进入",
            reason: "适合大学生受众",
          },
        ],
      }),
    );
    vi.mocked(api.selectTopic).mockResolvedValue(
      makeTask({ status: "RUNNING", result: null, stage: "GENERATE" }),
    );
    const { wrapper } = await setup();
    await wrapper.get('button[aria-label="选择选题 新方向"]').trigger("click");
    expect(
      wrapper
        .get('button[aria-label="选择选题 新方向"]')
        .attributes("aria-pressed"),
    ).toBe("true");
    const button = wrapper
      .findAll("button")
      .find((b) => b.text().includes("确认选题，生成脚本"))!;
    await button.trigger("click");
    await flushPromises();
    expect(api.selectTopic).toHaveBeenCalledWith("task-one", 0);
    expect(wrapper.text()).toContain("正在创作");
    wrapper.unmount();
  });
  it("does not let a previous task response overwrite the next route", async () => {
    let resolveFirst!: (value: ReturnType<typeof makeTask>) => void;
    vi.mocked(api.task).mockImplementation((id) =>
      id === "task-one"
        ? new Promise((resolve) => {
            resolveFirst = resolve;
          })
        : Promise.resolve(makeTask({ id: "task-two", title: "第二个作品" })),
    );
    const { wrapper, router } = await setup();
    await router.push("/tasks/task-two");
    await flushPromises();
    expect(wrapper.text()).toContain("第二个作品");
    resolveFirst(makeTask({ title: "过期的第一个作品" }));
    await flushPromises();
    expect(wrapper.text()).not.toContain("过期的第一个作品");
    expect(wrapper.text()).toContain("第二个作品");
    wrapper.unmount();
  });
  it("polls active jobs and clears polling when the component unmounts", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    vi.mocked(api.task).mockResolvedValue(
      makeTask({ status: "RUNNING", result: null }),
    );
    const { wrapper } = await setup();
    expect(api.task).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(2000);
    expect(api.task).toHaveBeenCalledTimes(2);
    wrapper.unmount();
    await vi.advanceTimersByTimeAsync(6000);
    expect(api.task).toHaveBeenCalledTimes(2);
  });
  it("preserves one idempotency key when a timed-out creation is retried", async () => {
    vi.mocked(api.create).mockRejectedValue(new Error("连接超时，请重试"));
    const { wrapper } = await setup("/tasks/new");
    await wrapper
      .findAll("textarea")[0]!
      .setValue("给大学生解释生活中的科学现象");
    await wrapper
      .get('input[placeholder="例如：18–24 岁的大学生"]')
      .setValue("大学生");
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(api.create).toHaveBeenCalledTimes(1);
    const firstKey = vi.mocked(api.create).mock.calls[0]![1];
    expect(firstKey).toMatch(/^[0-9a-f-]{36}$/);
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(vi.mocked(api.create).mock.calls[1]![1]).toBe(firstKey);
    await wrapper
      .findAll("textarea")[0]!
      .setValue("给大学生解释另外一个科学现象");
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(vi.mocked(api.create).mock.calls[2]![1]).not.toBe(firstKey);
    wrapper.unmount();
  });
});
