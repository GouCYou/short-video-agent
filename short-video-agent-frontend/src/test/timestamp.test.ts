import { it, expect } from "vitest";
import { chatTimestamp, dateTime } from "../lib/format";
it("omits the year for this calendar year and shows it for older messages", () => {
  const now = new Date(2026, 9, 26, 12, 0),
    current = new Date(2026, 9, 26, 9, 5).toISOString(),
    previous = new Date(2025, 11, 31, 23, 59).toISOString();
  expect(chatTimestamp(current, now)).toBe("10月26日 09:05");
  expect(chatTimestamp(previous, now)).toBe("2025年12月31日 23:59");
  expect(dateTime(current, now)).not.toContain("2026");
  expect(dateTime(previous, now)).toContain("2025");
  expect(
    chatTimestamp(new Date(2024, 9, 26, 9, 5).toISOString(), now),
  ).toContain("2024年");
  expect(chatTimestamp(current, new Date(2027, 0, 1))).toContain("2026年");
  expect(chatTimestamp("invalid", now)).toBeUndefined();
});
