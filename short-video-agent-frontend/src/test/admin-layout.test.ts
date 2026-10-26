import { mount, flushPromises } from "@vue/test-utils";
import { createPinia } from "pinia";
import { createRouter, createMemoryHistory } from "vue-router";
import ElementPlus from "element-plus";
import { describe, it, expect, vi } from "vitest";
import App from "../App.vue";
import { useAuth } from "../stores/auth";
import { useWorkspace } from "../composables/useWorkspace";
import { conversationApi } from "../lib/conversation-api";
import { user } from "./fixtures";

describe("separate administration experience", () => {
  it("replaces the whole creative shell, then returns to the same user page and sidebar state", async () => {
    const pinia = createPinia(),
      auth = useAuth(pinia),
      workspace = useWorkspace();
    auth.accept({
      token: "admin-layout-session",
      user: { ...user, role: "ADMIN" },
    });
    workspace.historyVisible.value = true;
    workspace.history.value = [];
    const list = vi.spyOn(conversationApi, "list").mockResolvedValue([]);
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/", component: { template: "<div>创作内容</div>" } },
        { path: "/tasks", component: { template: "<div>作品内容</div>" } },
        {
          path: "/admin",
          meta: { admin: true, title: "管理概览" },
          component: { template: "<div>概览内容</div>" },
        },
        {
          path: "/admin/users",
          meta: { admin: true, title: "用户管理" },
          component: { template: "<div>用户管理内容</div>" },
        },
      ],
    });
    await router.push("/tasks");
    const wrapper = mount(App, {
      attachTo: document.body,
      global: { plugins: [pinia, router, ElementPlus] },
    });
    await flushPromises();
    await wrapper.get('[aria-label="账户菜单"]').trigger("click");
    await flushPromises();
    const enter = [...document.querySelectorAll('[role="menuitem"]')].find(
      (e) => e.textContent?.includes("管理面板"),
    ) as HTMLElement;
    expect(enter).toBeTruthy();
    enter.click();
    await flushPromises();
    expect(router.currentRoute.value.path).toBe("/admin");
    expect(wrapper.find(".admin-shell").exists()).toBe(true);
    expect(wrapper.find(".studio-sidebar").exists()).toBe(false);
    expect(wrapper.find(".studio-history").exists()).toBe(false);
    expect(wrapper.find(".workspace-history-panel").exists()).toBe(false);
    expect(wrapper.find(".studio-new").exists()).toBe(false);
    expect(wrapper.find(".workspace-panel").exists()).toBe(false);
    expect(wrapper.findAll(".admin-nav a").map((a) => a.text())).toEqual([
      "管理概览",
      "用户管理",
    ]);
    list.mockClear();
    await router.push("/admin/users");
    await flushPromises();
    expect(list).not.toHaveBeenCalled();
    await wrapper.get(".admin-return").trigger("click");
    await flushPromises();
    expect(router.currentRoute.value.path).toBe("/tasks");
    expect(wrapper.find(".admin-shell").exists()).toBe(false);
    expect(wrapper.get(".studio-shell").classes()).toContain("history-visible");
    expect(
      wrapper.get(".studio-sidebar").find(".studio-history-list").exists(),
    ).toBe(false);
    wrapper.unmount();
    list.mockRestore();
    workspace.historyVisible.value = false;
    auth.clear();
  });
});
