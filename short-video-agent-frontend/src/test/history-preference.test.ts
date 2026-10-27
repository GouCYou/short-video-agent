import { afterEach, expect, it, vi } from "vitest";

afterEach(() => {
  localStorage.removeItem("zhiying-history-visible");
  vi.restoreAllMocks();
});

it("defaults history to open and restores both manual choices after a fresh app load", async () => {
  localStorage.removeItem("zhiying-history-visible");
  vi.resetModules();
  let workspace = (await import("../composables/useWorkspace")).useWorkspace();
  expect(workspace.historyVisible.value).toBe(true);
  workspace.toggleHistory();
  expect(localStorage.getItem("zhiying-history-visible")).toBe("false");
  vi.resetModules();
  workspace = (await import("../composables/useWorkspace")).useWorkspace();
  expect(workspace.historyVisible.value).toBe(false);
  workspace.toggleHistory();
  expect(localStorage.getItem("zhiying-history-visible")).toBe("true");
  vi.resetModules();
  workspace = (await import("../composables/useWorkspace")).useWorkspace();
  expect(workspace.historyVisible.value).toBe(true);
});

it("still opens and closes history when browser storage is unavailable", async () => {
  vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
    throw new Error("unavailable");
  });
  vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
    throw new Error("unavailable");
  });
  vi.resetModules();
  const workspace = (
    await import("../composables/useWorkspace")
  ).useWorkspace();
  expect(workspace.historyVisible.value).toBe(true);
  expect(() => workspace.toggleHistory()).not.toThrow();
  expect(workspace.historyVisible.value).toBe(false);
});
