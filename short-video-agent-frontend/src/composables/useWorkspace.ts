import { ref } from "vue";
import type { Conversation } from "../lib/conversation-types";
import { conversationApi } from "../lib/conversation-api";
import { errorMessage } from "../lib/api";
const HISTORY_PREFERENCE = "zhiying-history-visible";
function initialHistoryVisibility() {
  try {
    return localStorage.getItem(HISTORY_PREFERENCE) !== "false";
  } catch {
    return true;
  }
}
const toolsExpanded = ref(false),
  historyVisible = ref(initialHistoryVisibility()),
  history = ref<Conversation[]>([]),
  historyError = ref("");
let epoch = 0;
function trackConversation(value: Conversation) {
  // Prevent an older list response from replacing a freshly accepted run state.
  ++epoch;
  const index = history.value.findIndex((item) => item.id === value.id);
  if (index < 0) history.value.unshift(value);
  else history.value[index] = { ...history.value[index], ...value };
}
function toggleHistory() {
  historyVisible.value = !historyVisible.value;
  try {
    localStorage.setItem(HISTORY_PREFERENCE, String(historyVisible.value));
  } catch {
    // Keep the current interaction usable when browser storage is unavailable.
  }
}
async function reloadHistory() {
  const current = ++epoch;
  try {
    const list = await conversationApi.list();
    if (current === epoch) {
      history.value = list;
      historyError.value = "";
    }
  } catch (e) {
    if (current === epoch) historyError.value = errorMessage(e);
  }
}
export function useWorkspace() {
  return {
    toolsExpanded,
    historyVisible,
    history,
    historyError,
    reloadHistory,
    trackConversation,
    toggleTools: () => (toolsExpanded.value = !toolsExpanded.value),
    toggleHistory,
  };
}
