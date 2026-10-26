import { ref } from "vue";
import type { Conversation } from "../lib/conversation-types";
import { conversationApi } from "../lib/conversation-api";
import { errorMessage } from "../lib/api";
const toolsVisible = ref(true),
  historyVisible = ref(false),
  history = ref<Conversation[]>([]),
  historyError = ref("");
let epoch = 0;
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
    toolsVisible,
    historyVisible,
    history,
    historyError,
    reloadHistory,
    toggleTools: () => (toolsVisible.value = !toolsVisible.value),
    toggleHistory: () => (historyVisible.value = !historyVisible.value),
  };
}
