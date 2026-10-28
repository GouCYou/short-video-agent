import { afterEach, expect, it, vi } from "vitest";
import { randomUuid } from "../lib/uuid";
afterEach(() => vi.unstubAllGlobals());
it("uses native UUID when supported, preserving the crypto receiver", () => {
  const crypto = {randomUUID() { expect(this).toBe(crypto); return "native-uuid"; }};
  vi.stubGlobal("crypto", crypto);
  expect(randomUuid()).toBe("native-uuid");
});
it("generates valid UUID v4 on LAN HTTP without randomUUID", () => {
  vi.stubGlobal("crypto", {getRandomValues(bytes: Uint8Array) { bytes.fill(255); return bytes; }});
  expect(randomUuid()).toBe("ffffffff-ffff-4fff-bfff-ffffffffffff");
});
it("keeps distinct request identifiers with only getRandomValues available", () => {
  const getRandomValues = globalThis.crypto.getRandomValues.bind(globalThis.crypto);
  vi.stubGlobal("crypto", {getRandomValues});
  const ids = Array.from({length:1000}, () => randomUuid());
  expect(new Set(ids).size).toBe(1000);
  expect(ids.every(id=>/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(id))).toBe(true);
});
it("reports unsupported browsers clearly instead of a TypeError", () => {
  vi.stubGlobal("crypto", undefined);
  expect(randomUuid).toThrow("当前浏览器不支持生成消息编号");
});
