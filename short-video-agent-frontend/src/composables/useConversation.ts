import { computed, onUnmounted, ref } from "vue";
import { conversationApi, streamConversation } from "../lib/conversation-api";
import { errorMessage } from "../lib/api";
import type {
  Conversation,
  RunEvent,
  Selection,
} from "../lib/conversation-types";
export function useConversation() {
  const conversation = ref<Conversation | null>(null),
    loading = ref(false),
    sending = ref(false),
    error = ref(""),
    streamError = ref(""),
    pendingText = ref(""),
    liveEvents = ref<RunEvent[]>([]);
  let generation = 0,
    disposed = false,
    streamAbort: AbortController | undefined,
    pollTimer: ReturnType<typeof setTimeout> | undefined,
    reconnectTimer: ReturnType<typeof setTimeout> | undefined,
    sendKey = "",
    sendPayload = "",
    snapshotRevision = 0,
    refreshSequence = 0,
    resumeCursor = 0;
  const cancelling = ref(false);
  const running = computed(
    () =>
      conversation.value?.status === "RUNNING" ||
      !!conversation.value?.activeRunId,
  );
  const delta = computed(() => {
    const runId = conversation.value?.activeRunId;
    if (!runId) return "";
    return liveEvents.value
      .filter((e) => e.runId === runId && e.type === "message.delta")
      .map((e) => String(e.data.text || ""))
      .join("");
  });
  const lastEvent = computed(() =>
    liveEvents.value.reduce((n, e) => Math.max(n, e.id), 0),
  );
  function ingest(events: RunEvent[]) {
    const merged = new Map(liveEvents.value.map((e) => [e.id, e]));
    for (const event of events) merged.set(event.id, event);
    liveEvents.value = [...merged.values()].sort((a, b) => a.id - b.id);
    const id = conversation.value?.id;
    if (id)
      sessionStorage.setItem(
        `zhiying-event:${id}`,
        String(Math.max(lastEvent.value, resumeCursor)),
      );
  }
  function apply(value: Conversation) {
    conversation.value = value;
    ingest(value.events || []);
  }
  function accept(value: Conversation) {
    ++snapshotRevision;
    apply(value);
  }
  function stop() {
    ++generation;
    streamAbort?.abort();
    streamAbort = undefined;
    clearTimeout(pollTimer);
    clearTimeout(reconnectTimer);
  }
  async function refresh(epoch = generation) {
    const id = conversation.value?.id;
    if (!id || disposed) return;
    const revision = snapshotRevision,
      sequence = ++refreshSequence;
    try {
      const [value, events] = await Promise.all([
        conversationApi.get(id),
        conversationApi.events(id, lastEvent.value),
      ]);
      if (
        disposed ||
        epoch !== generation ||
        id !== conversation.value?.id ||
        revision !== snapshotRevision ||
        sequence !== refreshSequence
      )
        return;
      apply(value);
      ingest(events);
      error.value = "";
      if (!running.value) pendingText.value = "";
    } catch (e) {
      if (epoch === generation) streamError.value = errorMessage(e);
    }
  }
  function schedulePoll(epoch: number) {
    clearTimeout(pollTimer);
    pollTimer = setTimeout(async () => {
      if (disposed || epoch !== generation) return;
      await refresh(epoch);
      if (epoch === generation) schedulePoll(epoch);
    }, 2000);
  }
  async function connect(epoch: number) {
    const id = conversation.value?.id;
    if (!id || disposed || epoch !== generation) return;
    streamAbort?.abort();
    const controller = new AbortController();
    streamAbort = controller;
    try {
      await streamConversation(
        id,
        Math.max(lastEvent.value, resumeCursor),
        controller.signal,
        (event) => {
          if (disposed || epoch !== generation || event.id <= lastEvent.value)
            return;
          streamError.value = "";
          ingest([event]);
          if (
            event.type === "brief.updated" &&
            conversation.value &&
            event.data.brief
          )
            conversation.value.brief = event.data
              .brief as Conversation["brief"];
          if (
            [
              "artifact.updated",
              "run.completed",
              "run.waiting_input",
              "run.failed",
              "run.cancelled",
            ].includes(event.type)
          )
            void refresh(epoch);
        },
        () => {
          if (!disposed && epoch === generation) streamError.value = "";
        },
      );
      if (!controller.signal.aborted && epoch === generation)
        reconnectTimer = setTimeout(() => connect(epoch), 1200);
    } catch (e) {
      if (controller.signal.aborted || epoch !== generation) return;
      streamError.value = errorMessage(e);
      reconnectTimer = setTimeout(() => connect(epoch), 3000);
    }
  }
  function attach() {
    const epoch = generation;
    schedulePoll(epoch);
    void connect(epoch);
  }
  async function load(id?: string) {
    stop();
    const epoch = generation;
    conversation.value = null;
    liveEvents.value = [];
    sending.value = false;
    cancelling.value = false;
    resumeCursor = id
      ? Number(sessionStorage.getItem(`zhiying-event:${id}`) || 0)
      : 0;
    pendingText.value = "";
    error.value = "";
    streamError.value = "";
    if (!id) {
      loading.value = false;
      return;
    }
    loading.value = true;
    try {
      const value = await conversationApi.get(id);
      if (disposed || epoch !== generation) return;
      accept(value);
      attach();
    } catch (e) {
      if (epoch === generation) error.value = errorMessage(e);
    } finally {
      if (epoch === generation) loading.value = false;
    }
  }
  async function send(
    content: string,
    choice?: Selection,
    onCreated?: (id: string) => void,
    attachmentIds: string[] = [],
  ) {
    if (
      sending.value ||
      running.value ||
      (!content.trim() && !choice && !attachmentIds.length)
    )
      return false;
    sending.value = true;
    error.value = "";
    const epoch = generation;
    pendingText.value = content.trim();
    try {
      if (!conversation.value) {
        const created = await conversationApi.create();
        if (epoch !== generation || disposed) return false;
        accept(created);
        onCreated?.(created.id);
        attach();
      }
      const body = {
        ...(content.trim() ? { content: content.trim() } : {}),
        ...(choice ? { choice } : {}),
        ...(attachmentIds.length ? { attachmentIds } : {}),
      };
      const signature = JSON.stringify({ id: conversation.value!.id, ...body });
      if (signature !== sendPayload) {
        sendKey = crypto.randomUUID();
        sendPayload = signature;
      }
      const result = await conversationApi.send(
        conversation.value!.id,
        body,
        sendKey,
      );
      if (epoch !== generation || disposed) return false;
      accept(result.conversation);
      sendPayload = "";
      sendKey = "";
      pendingText.value = "";
      streamError.value = "";
      return true;
    } catch (e) {
      if (epoch === generation) error.value = errorMessage(e);
      return false;
    } finally {
      if (epoch === generation) {
        sending.value = false;
        pendingText.value = "";
      }
    }
  }
  async function cancel() {
    const value = conversation.value,
      epoch = generation;
    if (!value?.activeRunId || cancelling.value) return;
    cancelling.value = true;
    try {
      const updated = await conversationApi.cancel(value.id, value.activeRunId);
      if (
        disposed ||
        epoch !== generation ||
        conversation.value?.id !== value.id
      )
        return;
      accept(updated);
    } catch (e) {
      if (epoch === generation) error.value = errorMessage(e);
    } finally {
      if (epoch === generation) cancelling.value = false;
    }
  }
  async function ensure(onCreated: (id: string) => void) {
    if (conversation.value) return conversation.value.id;
    const epoch = generation;
    const created = await conversationApi.create();
    if (epoch !== generation || disposed) throw new Error("对话已切换");
    accept(created);
    onCreated(created.id);
    attach();
    return created.id;
  }
  async function retry(messageId: string) {
    if (!conversation.value || sending.value || running.value) return;
    sending.value = true;
    const id = conversation.value.id,
      epoch = generation;
    try {
      const updated = await conversationApi.retry(
        id,
        messageId,
        crypto.randomUUID(),
      );
      if (epoch === generation && !disposed) accept(updated.conversation);
    } catch (e) {
      if (epoch === generation) error.value = errorMessage(e);
    } finally {
      if (epoch === generation) sending.value = false;
    }
  }
  onUnmounted(() => {
    disposed = true;
    stop();
  });
  return {
    conversation,
    loading,
    sending,
    cancelling,
    running,
    error,
    streamError,
    pendingText,
    liveEvents,
    delta,
    load,
    send,
    cancel,
    refresh,
    accept,
    stop,
    ensure,
    retry,
  };
}
