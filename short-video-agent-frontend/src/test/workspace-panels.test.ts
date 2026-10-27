import { mount, flushPromises } from "@vue/test-utils";
import { createPinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import ElementPlus from "element-plus";
import { it, expect, vi } from "vitest";
import App from "../App.vue";
import WorkspacePanels from "../components/WorkspacePanels.vue";
import { useWorkspace } from "../composables/useWorkspace";
import { useAuth } from "../stores/auth";
import { conversationApi } from "../lib/conversation-api";
import { user } from "./fixtures";
it("expands and collapses navigation while independently showing the history column", async () => {
  const pinia = createPinia(),
    auth = useAuth(pinia),
    workspace = useWorkspace();
  auth.accept({ token: "panel-test", user });
  workspace.toolsExpanded.value = false;
  workspace.historyVisible.value = false;
  const list = vi.spyOn(conversationApi, "list").mockResolvedValue([]);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: "/",
        meta: { workspace: true },
        component: {
          components: { WorkspacePanels },
          template: "<header><WorkspacePanels /></header><div>创作页</div>",
        },
      },
      { path: "/tasks", component: { template: "<div>作品页</div>" } },
      { path: "/knowledge", component: { template: "<div>知识库页</div>" } },
      { path: "/profile", component: { template: "<div>个人设置页</div>" } },
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
  expect(workspace.toolsExpanded.value).toBe(false);
  await wrapper.get('header [aria-label="展开工具栏"]').trigger("click");
  await flushPromises();
  expect(wrapper.get(".studio-shell").classes()).toContain("tools-expanded");
  expect(wrapper.get(".studio-shell").classes()).toContain("history-visible");
  expect(wrapper.get(".studio-sidebar").attributes("inert")).toBeUndefined();
  expect(wrapper.get(".studio-sidebar").classes()).toContain("expanded");
  await router.push("/tasks");
  await flushPromises();
  expect(workspace.historyVisible.value).toBe(true);
  expect(workspace.toolsExpanded.value).toBe(true);
  expect(wrapper.get(".studio-shell").classes()).not.toContain(
    "history-visible",
  );
  expect(wrapper.find(".workspace-history-panel").exists()).toBe(false);
  expect(wrapper.find('[aria-label="隐藏对话列表"]').exists()).toBe(false);
  expect(wrapper.find('[aria-label="显示对话列表"]').exists()).toBe(false);
  for (const path of ["/knowledge", "/profile"]) {
    await router.push(path);
    await flushPromises();
    expect(wrapper.find(".workspace-history-panel").exists()).toBe(false);
    expect(wrapper.find(".history-rail-toggle").exists()).toBe(false);
  }
  await router.push("/");
  await flushPromises();
  expect(wrapper.get(".studio-shell").classes()).toContain("history-visible");
  await wrapper.get('header [aria-label="收缩工具栏"]').trigger("click");
  await wrapper.get('header [aria-label="隐藏对话列表"]').trigger("click");
  expect(workspace.toolsExpanded.value).toBe(false);
  expect(workspace.historyVisible.value).toBe(false);
  expect(wrapper.get(".studio-sidebar").classes()).not.toContain("expanded");
  expect(wrapper.find(".studio-nav").exists()).toBe(true);
  expect(wrapper.text()).not.toContain("历史对话");
  expect(wrapper.find('[placeholder="搜索对话"]').exists()).toBe(false);
  wrapper.unmount();
  list.mockRestore();
  auth.clear();
});

it("shows a running indicator only on the corresponding history row", async () => {
  const { conversation } = await import('./conversation-fixtures');
  const { default: ConversationHistory } = await import('../components/ConversationHistory.vue');
  const pinia = createPinia(), auth = useAuth(pinia), workspace = useWorkspace();
  auth.accept({ token: 'history-running-test', user });
  workspace.historyVisible.value = true;
  const active = conversation({ id: 'active-chat', title: '正在写脚本', status: 'RUNNING', activeRunId: 'run-one' });
  const idle = conversation({ id: 'idle-chat', title: '已完成的创作' });
  const list = vi.spyOn(conversationApi, 'list').mockResolvedValue([active, idle]);
  const router = createRouter({ history: createMemoryHistory(), routes: [{path:'/', component:{template:'<div />'}}] });
  await router.push('/');
  const wrapper = mount(ConversationHistory, {global:{plugins:[pinia, router, ElementPlus]}});
  await flushPromises();
  expect(wrapper.findAll('.history-running')).toHaveLength(1);
  expect(wrapper.get('.history-running').attributes('aria-label')).toBe('正在写脚本 正在生成');
  workspace.trackConversation({...active, status:'IDLE', activeRunId:null});
  await flushPromises();
  expect(wrapper.find('.history-running').exists()).toBe(false);
  wrapper.unmount(); list.mockRestore(); auth.clear(); workspace.history.value=[];
});
