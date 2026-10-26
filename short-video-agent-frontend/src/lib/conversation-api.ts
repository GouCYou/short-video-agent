import { http, TOKEN_KEY } from "./api";
import type {
  Conversation,
  ProductionPackage,
  RunEvent,
  Selection,
  Attachment,
  UserMemory,
} from "./conversation-types";
const data = async <T>(promise: Promise<{ data: T }>) => (await promise).data;
export const conversationApi = {
  list: () => data(http.get<Conversation[]>("/conversations")),
  create: () => data(http.post<Conversation>("/conversations", {})),
  get: (id: string) => data(http.get<Conversation>(`/conversations/${id}`)),
  rename: (id: string, title: string) =>
    data(http.patch<Conversation>(`/conversations/${id}`, { title })),
  pin: (id: string, pinned: boolean) =>
    data(http.patch<Conversation>(`/conversations/${id}`, { pinned })),
  renameWork: (id: string, title: string) =>
    data(http.patch<Conversation>(`/conversations/${id}/artifact`, { title })),
  deleteWork: (id: string) => http.delete(`/conversations/${id}/artifact`),
  upload: (id: string, file: File) => {
    const form = new FormData();
    form.append("file", file);
    return data(
      http.post<Attachment>(`/conversations/${id}/attachments`, form, {
        timeout: 120000,
      }),
    );
  },
  removeFile: (id: string, fileId: string) =>
    http.delete(`/conversations/${id}/attachments/${fileId}`),
  retry: (id: string, messageId: string, key: string) =>
    data(
      http.post<{ conversation: Conversation; runId: string }>(
        `/conversations/${id}/messages/${messageId}/retry`,
        {},
        { headers: { "Idempotency-Key": key } },
      ),
    ),
  memory: () => data(http.get<UserMemory>("/memory")),
  updateMemory: (value: Partial<UserMemory>) =>
    data(http.put<UserMemory>("/memory", value)),
  clearMemory: () => http.delete("/memory"),
  remove: (id: string) => http.delete(`/conversations/${id}`),
  send: (
    id: string,
    body: { content?: string; choice?: Selection; attachmentIds?: string[] },
    key: string,
  ) =>
    data(
      http.post<{ conversation: Conversation; runId: string }>(
        `/conversations/${id}/messages`,
        body,
        { headers: { "Idempotency-Key": key } },
      ),
    ),
  events: (id: string, after: number) =>
    data(
      http.get<RunEvent[]>(`/conversations/${id}/events`, {
        params: { after },
      }),
    ),
  cancel: (id: string, runId: string) =>
    data(http.post<Conversation>(`/conversations/${id}/runs/${runId}/cancel`)),
  save: (id: string, content: ProductionPackage, baseVersion: number) =>
    data(
      http.put<Conversation>(`/conversations/${id}/artifact`, {
        content,
        baseVersion,
      }),
    ),
  restore: (id: string, versionId: string) =>
    data(
      http.post<Conversation>(
        `/conversations/${id}/artifact/restore/${versionId}`,
      ),
    ),
  finalize: (id: string, baseVersion: number) =>
    data(
      http.post<Conversation>(`/conversations/${id}/artifact/finalize`, {
        baseVersion,
      }),
    ),
  export: (id: string, format: "markdown" | "txt") =>
    data(
      http.get<Blob>(`/conversations/${id}/artifact/export`, {
        params: { format },
        responseType: "blob",
      }),
    ),
};
export function createSseParser(onEvent: (event: RunEvent) => void) {
  let buffer = "";
  return {
    feed(chunk: string) {
      buffer += chunk;
      let match: RegExpMatchArray | null;
      while ((match = buffer.match(/\r?\n\r?\n/))) {
        const end = match.index!;
        const block = buffer.slice(0, end);
        buffer = buffer.slice(end + match[0].length);
        const lines = block.split(/\r?\n/),
          json = lines
            .filter((line) => line.startsWith("data:"))
            .map((line) => line.slice(5).trimStart())
            .join("\n");
        if (!json) continue;
        try {
          const event = JSON.parse(json) as RunEvent;
          if (Number.isFinite(event.id) && event.type) onEvent(event);
        } catch {
          /* Ignore heartbeat / incomplete transport records; persisted polling reconciles. */
        }
      }
    },
    end() {
      if (buffer.trim()) {
        this.feed("\n\n");
      }
    },
  };
}
export async function streamConversation(
  id: string,
  after: number,
  signal: AbortSignal,
  onEvent: (event: RunEvent) => void,
  onConnected?: () => void,
) {
  const token = localStorage.getItem(TOKEN_KEY),
    base = (import.meta.env.VITE_API_BASE_URL || "/api").replace(/\/$/, "");
  const response = await fetch(
    `${base}/conversations/${encodeURIComponent(id)}/stream?after=${after}`,
    {
      headers: {
        Accept: "text/event-stream",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      signal,
      cache: "no-store",
    },
  );
  if (!response.ok) {
    let message = `实时连接暂不可用（${response.status}）`;
    try {
      const body = await response.json();
      if (typeof body.message === "string") message = body.message;
    } catch {
      /* no JSON body */
    }
    if (response.status === 401) {
      localStorage.removeItem(TOKEN_KEY);
      window.dispatchEvent(new Event("session-expired"));
    }
    throw new Error(message);
  }
  if (!response.body) throw new Error("浏览器未提供流式响应");
  onConnected?.();
  const reader = response.body.getReader(),
    decoder = new TextDecoder(),
    parser = createSseParser(onEvent);
  try {
    while (!signal.aborted) {
      const { done, value } = await reader.read();
      if (done) break;
      parser.feed(decoder.decode(value, { stream: true }));
    }
    parser.feed(decoder.decode());
    parser.end();
  } finally {
    if (signal.aborted) await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}
export const toolLabels: Record<string, string> = {
  update_brief: "整理创作需求",
  propose_options: "提供创作选项",
  search_knowledge: "检索知识资料",
  create_production: "生成制作方案",
  revise_production: "修改制作方案",
  check_production: "检查制作方案",
  read_document: "阅读上传文档",
  save_knowledge: "保存知识资料",
  search_history_creations: "查找历史作品",
  read_history_creation: "阅读历史作品",
  read_user_profile: "读取创作偏好",
};
export function packageText(c: ProductionPackage) {
  return [
    `# ${c.title}`,
    c.summary,
    `## 内容定位\n${c.positioning}\n\n${c.audienceAnalysis}`,
    `## 标题\n${c.titles.join("\n")}`,
    `## 开场\n${c.opening}`,
    `## 分镜脚本\n${c.segments.map((s, i) => `${i + 1}. ${s.duration} 秒 · ${s.shotSize}\n画面：${s.visual}\n镜头：${s.camera}\n动作：${s.action}\n口播：${s.narration}\n配音：${s.voiceDirection}\n字幕：${s.subtitle}\n剪辑：${s.editing}\n素材：${s.material}`).join("\n\n")}`,
    `## 结尾\n${c.ending}`,
    `## 拍摄方案\n${c.shootingPlan.approach}\n地点：${c.shootingPlan.locations.join("、")}\n设备：${c.shootingPlan.equipment.join("、")}\n道具：${c.shootingPlan.props.join("、")}\n${c.shootingPlan.schedule.join("\n")}`,
    `## 配音方案\n${c.voiceoverPlan.approach}\n语气：${c.voiceoverPlan.tone}\n节奏：${c.voiceoverPlan.pace}\n${c.voiceoverPlan.recordingTips.join("\n")}\n\n${c.voiceoverPlan.script}`,
    `## 剪辑方案\n${c.editingPlan.approach}\n节奏：${c.editingPlan.rhythm}\n音乐：${c.editingPlan.music}\n音效：${c.editingPlan.soundEffects.join("、")}\n字幕：${c.editingPlan.subtitleStyle}\n${c.editingPlan.steps.join("\n")}`,
    `## 发布文案\n${c.caption}\n${c.tags.map((t) => "#" + t.replace(/^#/, "")).join(" ")}`,
  ].join("\n\n");
}
