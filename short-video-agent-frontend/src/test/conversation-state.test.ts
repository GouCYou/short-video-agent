import { defineComponent } from "vue";
import { mount, flushPromises, type VueWrapper } from "@vue/test-utils";
import { beforeEach, afterEach, describe, it, expect, vi } from "vitest";
import { useConversation } from "../composables/useConversation";
import { useWorkspace } from "../composables/useWorkspace";
import { conversationApi, streamConversation } from "../lib/conversation-api";
import type { Conversation, RunEvent } from "../lib/conversation-types";
import { conversation, event } from "./conversation-fixtures";
vi.mock("../lib/conversation-api", async (importOriginal) => {
  const original =
    await importOriginal<typeof import("../lib/conversation-api")>();
  return {
    ...original,
    conversationApi: {
      ...original.conversationApi,
      create: vi.fn(),
      get: vi.fn(),
      send: vi.fn(),
      events: vi.fn(),
      cancel: vi.fn(),
    },
    streamConversation: vi.fn(),
  };
});
let wrapper: VueWrapper | undefined,
  state: ReturnType<typeof useConversation>,
  delivered: ((event: RunEvent) => void) | undefined;
function setup() {
  wrapper = mount(
    defineComponent({
      setup() {
        state = useConversation();
        return () => null;
      },
    }),
  );
  return state;
}
beforeEach(() => {
  vi.clearAllMocks();
  sessionStorage.clear();
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
  vi.mocked(conversationApi.get).mockResolvedValue(conversation());
  vi.mocked(conversationApi.events).mockResolvedValue([]);
  vi.mocked(streamConversation).mockImplementation(
    (_id, _after, signal, onEvent) => {
      delivered = onEvent;
      return new Promise((resolve) =>
        signal.addEventListener("abort", () => resolve(), { once: true }),
      );
    },
  );
});
afterEach(() => {
  wrapper?.unmount();
  wrapper = undefined;
  vi.useRealTimers();
});
describe("conversation state consistency", () => {
  it("creates only on first send and assigns new idempotency keys after successful repeated text", async () => {
    setup();
    await state.load();
    expect(conversationApi.create).not.toHaveBeenCalled();
    vi.mocked(conversationApi.create).mockResolvedValue(conversation());
    vi.mocked(conversationApi.send).mockResolvedValue({
      conversation: conversation({ status: "RUNNING", activeRunId: "run-one" }),
      runId: "run-one",
    });
    const created = vi.fn();
    await state.send("校园生活", undefined, created);
    expect(created).toHaveBeenCalledWith("chat-one");
    expect(conversationApi.create).toHaveBeenCalledTimes(1);
    expect(conversationApi.send).toHaveBeenCalledWith(
      "chat-one",
      { content: "校园生活" },
      expect.any(String),
    );
    const key = vi.mocked(conversationApi.send).mock.calls[0]![2];
    state.accept(conversation());
    await state.send("校园生活");
    expect(vi.mocked(conversationApi.send).mock.calls[1]![2]).not.toBe(key);
  });
  it("deduplicates persisted delta events and ignores callbacks after route changes", async () => {
    setup();
    vi.mocked(conversationApi.get).mockResolvedValue(
      conversation({ status: "RUNNING", activeRunId: "run-one" }),
    );
    await state.load("chat-one");
    const oldCallback = delivered!;
    oldCallback(event(1, "message.delta", "你"));
    oldCallback(event(1, "message.delta", "你"));
    oldCallback(event(2, "message.delta", "好"));
    expect(state.delta.value).toBe("你好");
    expect(sessionStorage.getItem("zhiying-event:chat-one")).toBe("2");
    const oldSignal = vi.mocked(streamConversation).mock.calls[0]![2];
    vi.mocked(conversationApi.get).mockResolvedValue(
      conversation({ id: "chat-two" }),
    );
    await state.load("chat-two");
    expect(oldSignal.aborted).toBe(true);
    oldCallback(event(3, "message.delta", "不应出现"));
    expect(state.liveEvents.value).toEqual([]);
    expect(state.conversation.value?.id).toBe("chat-two");
  });
  it("does not apply a cancellation response to a newly opened conversation", async () => {
    setup();
    vi.mocked(conversationApi.get).mockResolvedValue(
      conversation({ status: "RUNNING", activeRunId: "run-one" }),
    );
    await state.load("chat-one");
    let resolveCancel!: (value: Conversation) => void;
    vi.mocked(conversationApi.cancel).mockReturnValue(
      new Promise((resolve) => {
        resolveCancel = resolve;
      }),
    );
    const pending = state.cancel();
    vi.mocked(conversationApi.get).mockResolvedValue(
      conversation({ id: "chat-two", title: "新页面" }),
    );
    await state.load("chat-two");
    resolveCancel(conversation({ status: "IDLE" }));
    await pending;
    expect(state.conversation.value?.id).toBe("chat-two");
    expect(state.conversation.value?.title).toBe("新页面");
  });
  it("rejects stale polling snapshots arriving after a successful cancellation", async () => {
    setup();
    vi.mocked(conversationApi.get).mockResolvedValue(
      conversation({ status: "RUNNING", activeRunId: "run-one" }),
    );
    await state.load("chat-one");
    let resolveOld!: (value: Conversation) => void;
    vi.mocked(conversationApi.get).mockReturnValueOnce(
      new Promise((resolve) => {
        resolveOld = resolve;
      }),
    );
    const pending = state.refresh();
    vi.mocked(conversationApi.cancel).mockResolvedValue(
      conversation({ status: "IDLE" }),
    );
    await state.cancel();
    resolveOld(conversation({ status: "RUNNING", activeRunId: "run-one" }));
    await pending;
    expect(state.running.value).toBe(false);
    expect(state.conversation.value?.activeRunId).toBeNull();
  });
  it("reconciles detail and events every two seconds on stream errors, and cleans timers on unmount", async () => {
    setup();
    vi.mocked(streamConversation).mockRejectedValue(new Error("连接中断"));
    await state.load("chat-one");
    await flushPromises();
    expect(state.streamError.value).toContain("连接中断");
    await vi.advanceTimersByTimeAsync(2000);
    expect(conversationApi.get).toHaveBeenCalledTimes(2);
    expect(conversationApi.events).toHaveBeenCalledWith("chat-one", 0);
    wrapper!.unmount();
    wrapper = undefined;
    const calls = vi.mocked(conversationApi.get).mock.calls.length;
    await vi.advanceTimersByTimeAsync(10000);
    expect(conversationApi.get).toHaveBeenCalledTimes(calls);
  });
  it("resets an in-flight send lock when navigating to another conversation", async () => {
    setup();
    await state.load("chat-one");
    let resolveSend!: (value: {
      conversation: Conversation;
      runId: string;
    }) => void;
    vi.mocked(conversationApi.send).mockReturnValue(
      new Promise((resolve) => {
        resolveSend = resolve;
      }),
    );
    const pending = state.send("正在提交");
    expect(state.sending.value).toBe(true);
    vi.mocked(conversationApi.get).mockResolvedValue(
      conversation({ id: "chat-two" }),
    );
    await state.load("chat-two");
    expect(state.sending.value).toBe(false);
    resolveSend({
      conversation: conversation({ status: "RUNNING", activeRunId: "old-run" }),
      runId: "old-run",
    });
    await pending;
    expect(state.conversation.value?.id).toBe("chat-two");
  });
  it("keeps a server run in history after leaving and resumes persisted output on reopen", async () => {
    const workspace = useWorkspace();
    workspace.history.value = [];
    const active = conversation({ status: "RUNNING", activeRunId: "run-one" });
    vi.mocked(conversationApi.get).mockResolvedValue(active);
    setup();
    await state.load("chat-one");
    expect(workspace.history.value[0]?.status).toBe("RUNNING");
    const signal = vi.mocked(streamConversation).mock.calls.at(-1)![2];
    wrapper!.unmount(); wrapper = undefined;
    expect(signal.aborted).toBe(true);
    expect(conversationApi.cancel).not.toHaveBeenCalled();
    expect(workspace.history.value[0]?.activeRunId).toBe("run-one");
    setup();
    vi.mocked(conversationApi.get).mockResolvedValue(conversation({
      ...active,
      events: [event(1, "message.delta", "离开后生成的内容")],
    }));
    await state.load("chat-one");
    expect(state.delta.value).toBe("离开后生成的内容");
    state.accept(conversation());
    expect(workspace.history.value[0]?.status).toBe("WAITING_INPUT");
    expect(workspace.history.value[0]?.activeRunId).toBeNull();
  });
});

it("clears a recovered connection warning even when the idle stream has no new events", async () => {
  vi.mocked(streamConversation).mockRejectedValueOnce(new Error("连接中断"));
  setup();
  await state.load("chat-one");
  await flushPromises();
  expect(state.streamError.value).toContain("连接中断");
  vi.mocked(streamConversation).mockImplementation(
    (_id, _after, signal, _onEvent, onConnected) => {
      onConnected?.();
      return new Promise((resolve) =>
        signal.addEventListener("abort", () => resolve(), { once: true }),
      );
    },
  );
  await vi.advanceTimersByTimeAsync(3000);
  await flushPromises();
  expect(state.streamError.value).toBe("");
});
