import { mount, flushPromises } from "@vue/test-utils";
import { createPinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import ElementPlus from "element-plus";
import { it, expect, vi } from "vitest";
import App from "../App.vue";
import { useWorkspace } from "../composables/useWorkspace";
import { useAuth } from "../stores/auth";
import { conversationApi } from "../lib/conversation-api";
import { user } from "./fixtures";
it("shows and hides tool and history columns independently while keeping restore controls available", async () => {
  const pinia = createPinia(),
    auth = useAuth(pinia),
    workspace = useWorkspace();
  auth.accept({ token: "panel-test", user });
  workspace.toolsVisible.value = true;
  workspace.historyVisible.value = false;
  const list = vi.spyOn(conversationApi, "list").mockResolvedValue([]);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/", component: { template: "<div>创作页</div>" } },
      { path: "/tasks", component: { template: "<div>作品页</div>" } },
    ],
  });
  await router.push("/");
  const wrapper = mount(App, {
    global: { plugins: [pinia, router, ElementPlus] },
  });
  await flushPromises();
  expect(
    wrapper.find(".studio-sidebar .workspace-history-panel").exists(),
  ).toBe(false);
  await wrapper.get('header [aria-label="显示对话列表"]').trigger("click");
  await flushPromises();
  expect(
    wrapper.get(".workspace-history-panel").attributes("aria-hidden"),
  ).toBe("false");
  expect(workspace.historyVisible.value).toBe(true);
  expect(workspace.toolsVisible.value).toBe(true);
  await wrapper.get('header [aria-label="隐藏工具栏"]').trigger("click");
  await flushPromises();
  expect(wrapper.get(".studio-shell").classes()).toContain("tools-hidden");
  expect(wrapper.get(".studio-shell").classes()).toContain("history-visible");
  expect(wrapper.get(".studio-sidebar").attributes("inert")).toBeDefined();
  await router.push("/tasks");
  await flushPromises();
  expect(workspace.historyVisible.value).toBe(true);
  expect(workspace.toolsVisible.value).toBe(false);
  await wrapper.get('header [aria-label="显示工具栏"]').trigger("click");
  await wrapper
    .get('.studio-page-header [aria-label="隐藏对话列表"]')
    .trigger("click");
  expect(workspace.toolsVisible.value).toBe(true);
  expect(workspace.historyVisible.value).toBe(false);
  expect(wrapper.text()).not.toContain("历史对话");
  expect(wrapper.find('[placeholder="搜索对话"]').exists()).toBe(false);
  wrapper.unmount();
  list.mockRestore();
  auth.clear();
});
