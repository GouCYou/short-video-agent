import { describe, it, expect, vi, afterEach } from "vitest";
import {
  createSseParser,
  streamConversation,
  packageText,
} from "../lib/conversation-api";
import { TOKEN_KEY } from "../lib/api";
import { event, production } from "./conversation-fixtures";
afterEach(() => {
  vi.unstubAllGlobals();
  localStorage.clear();
});
describe("authenticated persisted conversation streams", () => {
  it("parses fragmented CRLF frames and multiline payloads without parsing heartbeat", () => {
    const delivered = vi.fn(),
      parser = createSseParser(delivered),
      value = JSON.stringify(event(4));
    parser.feed(": heartbeat\r");
    parser.feed("\n\r\n");
    parser.feed(`id:4\r\nevent:run-event\r\ndata:${value.slice(0, 15)}`);
    parser.feed(value.slice(15) + "\r");
    parser.feed("\n\r");
    parser.feed("\n");
    expect(delivered).toHaveBeenCalledTimes(1);
    expect(delivered).toHaveBeenCalledWith(event(4));
    parser.feed(`data:${JSON.stringify(event(5))}`);
    parser.end();
    expect(delivered).toHaveBeenCalledTimes(2);
  });
  it("uses bearer authorization and event cursor, never puts credentials in stream URL", async () => {
    localStorage.setItem(TOKEN_KEY, "private-token");
    const delivered = vi.fn(),
      fetchMock = vi.fn().mockResolvedValue({
        ok: true,
        body: new ReadableStream({
          start(controller) {
            controller.enqueue(
              new TextEncoder().encode(
                `id:3\nevent:run-event\ndata:${JSON.stringify(event(3))}\n\n`,
              ),
            );
            controller.close();
          },
        }),
      });
    vi.stubGlobal("fetch", fetchMock);
    await streamConversation(
      "chat-one",
      2,
      new AbortController().signal,
      delivered,
    );
    expect(fetchMock.mock.calls[0]![0]).toBe(
      "/api/conversations/chat-one/stream?after=2",
    );
    expect(fetchMock.mock.calls[0]![1].headers.Authorization).toBe(
      "Bearer private-token",
    );
    expect(delivered).toHaveBeenCalledWith(event(3));
  });
  it("reports actual stream setup errors instead of fake content", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 503,
        json: async () => ({ message: "生成模型暂不可用" }),
      }),
    );
    await expect(
      streamConversation("chat-one", 0, new AbortController().signal, vi.fn()),
    ).rejects.toThrow("生成模型暂不可用");
  });
  it("retains shooting, voiceover, editing, material and distribution in package export text", () => {
    const result = packageText(production);
    for (const value of [
      "手机实拍",
      "后期录制口播",
      "关闭空调",
      "叙事剪辑",
      "授权轻音乐",
      "图书馆外景",
      "校园里的安静时刻",
      "白色字幕",
    ])
      expect(result).toContain(value);
  });
});

it("reports a connected idle stream before any events arrive", async () => {
  const onConnected = vi.fn();
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue({
      ok: true,
      body: new ReadableStream({
        start(controller) {
          controller.close();
        },
      }),
    }),
  );
  await streamConversation(
    "idle-chat",
    0,
    new AbortController().signal,
    vi.fn(),
    onConnected,
  );
  expect(onConnected).toHaveBeenCalledOnce();
});
