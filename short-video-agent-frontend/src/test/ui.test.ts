import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { mount, flushPromises } from "@vue/test-utils";
import { createRouter, createMemoryHistory } from "vue-router";
import ElementPlus, { ElAvatar } from "element-plus";
import { UserFilled } from "@element-plus/icons-vue";
import { createPinia } from "pinia";
import App from "../App.vue";
import ProfileView from "../views/ProfileView.vue";
import UsersView from "../views/UsersView.vue";
import { useAuth } from "../stores/auth";
import { user } from "./fixtures";
import { describe, it, expect, vi } from "vitest";
import { parse } from "@vue/compiler-sfc";
import DashboardView from "../views/DashboardView.vue";
import { api } from "../lib/api";
import { conversationApi } from "../lib/conversation-api";
vi.mock("../lib/api", async (importOriginal) => {
  const original = await importOriginal<typeof import("../lib/api")>();
  return {
    ...original,
    api: { ...original.api, dashboard: vi.fn(), users: vi.fn() },
  };
});
describe("concise workspace presentation", () => {
  it("uses Element Plus icon avatars across account surfaces without name initials", async () => {
    const pinia = createPinia();
    const auth = useAuth(pinia);
    const account = { ...user, nickname: "头像测试" };
    auth.accept({ token: "avatar-test", user: account });
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: "/", component: { template: "<div />" } }],
    });
    await router.push("/");
    const global = { plugins: [router, pinia, ElementPlus] };
    vi.spyOn(conversationApi, "list").mockResolvedValue([]);
    const shell = mount(App, { global });
    expect(shell.findAllComponents(ElAvatar)).toHaveLength(1);
    for (const avatar of shell.findAllComponents(ElAvatar)) {
      expect(avatar.props("icon")).toBe(UserFilled);
      expect(avatar.find("svg").exists()).toBe(true);
      expect(avatar.text()).toBe("");
    }
    expect(shell.get(".studio-account-button .sidebar-label").text()).toBe(
      "头像测试",
    );
    shell.unmount();
    const profile = mount(ProfileView, { global });
    const profileAvatar = profile.getComponent(ElAvatar);
    expect(profileAvatar.props("icon")).toBe(UserFilled);
    expect(profileAvatar.props("size")).toBe(64);
    expect(profileAvatar.text()).toBe("");
    expect(profile.get(".profile-header h2").text()).toBe("头像测试");
    profile.unmount();
    vi.mocked(api.users).mockResolvedValue([account]);
    const users = mount(UsersView, { global });
    await flushPromises();
    expect(users.getComponent(ElAvatar).props("icon")).toBe(UserFilled);
    expect(users.getComponent(ElAvatar).text()).toBe("");
    expect(users.text()).toContain("头像测试");
    users.unmount();
    auth.clear();
  });

  it("renders real summary values and useful category actions without decorative copy", async () => {
    vi.mocked(api.dashboard).mockResolvedValue({
      totalTasks: 7,
      completedTasks: 4,
      runningTasks: 2,
      failedTasks: 1,
      recentTasks: [],
    });
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/", component: DashboardView },
        { path: "/tasks/new", component: { template: "<div />" } },
      ],
    });
    await router.push("/");
    const wrapper = mount(DashboardView, {
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();
    expect(
      wrapper.findAll(".stat-card strong").map((node) => node.text()),
    ).toEqual(["7", "4", "2", "1"]);
    expect(wrapper.text()).not.toMatch(
      /CREATIVE|STORYBOARD|从一句灵感|值得留下|每一份灵感/,
    );
    expect(wrapper.find(".hero-card").exists()).toBe(false);
    expect(wrapper.findAll(".starter-card .el-icon").length).toBe(6);
    await wrapper.findAll(".starter-card")[0]!.trigger("click");
    await flushPromises();
    expect(router.currentRoute.value.path).toBe("/tasks/new");
    expect(router.currentRoute.value.query.topic).toContain("生活中的常见误区");
    wrapper.unmount();
  });
  it("removes auxiliary slogans from component sources instead of hiding them", () => {
    const folders = ["src/views", "src/components"];
    const files = [
      "src/App.vue",
      ...folders.flatMap((folder) =>
        readdirSync(folder)
          .filter((name) => name.endsWith(".vue"))
          .map((name) => join(folder, name)),
      ),
    ];
    for (const file of files) {
      const template =
        parse(readFileSync(file, "utf8")).descriptor.template?.content || "";
      expect(template, file).not.toMatch(
        /class="(?:eyebrow|brand-en|sidebar-note|page-footer|auth-footnote|quiet-note|creation-aside)"/,
      );
      expect(template, file).not.toMatch(
        /ZHIYING STUDIO|START WITH AN IDEA|WELCOME BACK|让资料成为灵感的底气/,
      );
    }
  });
  it("keeps every declared text size at least 14 pixels", () => {
    const css = readFileSync("src/style.css", "utf8");
    const sizes = [...css.matchAll(/font-size:\s*(\d+(?:\.\d+)?)px/g)].map(
      (match) => Number(match[1]),
    );
    expect(sizes.length).toBeGreaterThan(20);
    expect(Math.min(...sizes)).toBeGreaterThanOrEqual(14);
    expect(css).toContain("font-size: 16px");
  });
});
