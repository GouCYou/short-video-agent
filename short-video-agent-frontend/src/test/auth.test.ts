import { mount, flushPromises } from "@vue/test-utils";
import { createPinia } from "pinia";
import { createRouter, createMemoryHistory } from "vue-router";
import ElementPlus from "element-plus";
import { beforeEach, describe, it, expect, vi } from "vitest";
import { api, TOKEN_KEY } from "../lib/api";
import AuthView from "../views/AuthView.vue";
import ProfileView from "../views/ProfileView.vue";
import { useAuth } from "../stores/auth";
import { user } from "./fixtures";
vi.mock("../lib/api", async (importOriginal) => {
  const original = await importOriginal<typeof import("../lib/api")>();
  return {
    ...original,
    api: {
      ...original.api,
      login: vi.fn(),
      register: vi.fn(),
      password: vi.fn(),
    },
  };
});
async function setup(component = AuthView, path = "/login") {
  const pinia = createPinia(),
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/login", component: AuthView },
        { path: "/", component: { template: "<div>工作台</div>" } },
        { path: "/profile", component: ProfileView },
      ],
    });
  await router.push(path);
  await router.isReady();
  const wrapper = mount(component, {
    global: { plugins: [pinia, router, ElementPlus] },
  });
  return { wrapper, router, pinia };
}
beforeEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
});
describe("account workflows", () => {
  it("validates empty credentials, submits real API credentials and stores session", async () => {
    const { wrapper, router } = await setup();
    expect(
      wrapper.get('button[type="submit"]').attributes("disabled"),
    ).toBeDefined();
    await wrapper.get('input[autocomplete="username"]').setValue("creator");
    await wrapper
      .get('input[autocomplete="current-password"]')
      .setValue("password123");
    vi.mocked(api.login).mockResolvedValue({ token: "opaque-session", user });
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(api.login).toHaveBeenCalledWith("creator", "password123");
    expect(localStorage.getItem(TOKEN_KEY)).toBe("opaque-session");
    expect(router.currentRoute.value.path).toBe("/");
    wrapper.unmount();
  });
  it("keeps failed credentials on screen with actionable service errors", async () => {
    const { wrapper } = await setup();
    await wrapper.get('input[autocomplete="username"]').setValue("creator");
    await wrapper
      .get('input[autocomplete="current-password"]')
      .setValue("password123");
    vi.mocked(api.login).mockRejectedValue(new Error("生成服务连接不可用"));
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(wrapper.text()).toContain("生成服务连接不可用");
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    wrapper.unmount();
  });
  it("requires a nickname for registration and passes it to backend", async () => {
    const { wrapper } = await setup();
    await wrapper.get(".auth-toggle button").trigger("click");
    await wrapper.get('input[autocomplete="username"]').setValue("newcreator");
    await wrapper
      .get('input[autocomplete="new-password"]')
      .setValue("password123");
    expect(
      wrapper.get('button[type="submit"]').attributes("disabled"),
    ).toBeDefined();
    await wrapper.get('input[autocomplete="nickname"]').setValue("新创作者");
    vi.mocked(api.register).mockResolvedValue({
      token: "register-token",
      user,
    });
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(api.register).toHaveBeenCalledWith(
      "newcreator",
      "password123",
      "新创作者",
    );
    wrapper.unmount();
  });
  it("clears local session and returns to login after changing password", async () => {
    const { wrapper, router, pinia } = await setup(ProfileView, "/profile");
    const auth = useAuth(pinia);
    auth.accept({ token: "old-token", user });
    await wrapper
      .get('input[autocomplete="current-password"]')
      .setValue("oldpassword");
    const fields = wrapper.findAll('input[autocomplete="new-password"]');
    await fields[0]!.setValue("newpassword");
    await fields[1]!.setValue("newpassword");
    vi.mocked(api.password).mockResolvedValue({} as never);
    await wrapper.findAll("form").at(-1)!.trigger("submit");
    await flushPromises();
    expect(api.password).toHaveBeenCalledWith("oldpassword", "newpassword");
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    expect(router.currentRoute.value.path).toBe("/login");
    wrapper.unmount();
  });
});

it("accepts the configured six-character administrator password during sign-in", async () => {
  const { wrapper, router } = await setup();
  await wrapper.get('input[autocomplete="username"]').setValue("admin");
  await wrapper
    .get('input[autocomplete="current-password"]')
    .setValue("123456");
  expect(
    wrapper.get('button[type="submit"]').attributes("disabled"),
  ).toBeUndefined();
  vi.mocked(api.login).mockResolvedValue({
    token: "admin-login-session",
    user: { ...user, role: "ADMIN" },
  });
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  expect(api.login).toHaveBeenCalledWith("admin", "123456");
  expect(router.currentRoute.value.path).toBe("/");
  wrapper.unmount();
});
