import { createRouter, createWebHistory } from "vue-router";
import { useAuth } from "./stores/auth";
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/login",
      name: "login",
      component: () => import("./views/AuthView.vue"),
      meta: { public: true, title: "欢迎回来" },
    },
    {
      path: "/",
      name: "conversation",
      component: () => import("./views/ConversationView.vue"),
      meta: { title: "对话创作", workspace: true },
    },
    {
      path: "/conversations/:id",
      name: "conversation-detail",
      component: () => import("./views/ConversationView.vue"),
      meta: { title: "对话创作", workspace: true },
    },
    {
      path: "/overview",
      name: "dashboard",
      component: () => import("./views/DashboardView.vue"),
      meta: { title: "创作总览" },
    },
    {
      path: "/tasks",
      name: "tasks",
      component: () => import("./views/TasksView.vue"),
      meta: { title: "我的作品" },
    },
    {
      path: "/tasks/new",
      name: "new-task",
      redirect: "/",
    },
    {
      path: "/tasks/:id",
      name: "task",
      component: () => import("./views/TaskView.vue"),
      meta: { title: "创作工作台" },
    },
    {
      path: "/profile",
      name: "profile",
      component: () => import("./views/ProfileView.vue"),
      meta: { title: "个人设置" },
    },
    {
      path: "/admin",
      name: "admin",
      component: () => import("./views/AdminView.vue"),
      meta: { admin: true, title: "管理概览" },
    },
    {
      path: "/knowledge",
      name: "knowledge",
      component: () => import("./views/KnowledgeView.vue"),
      meta: { title: "知识库" },
    },
    {
      path: "/admin/knowledge",
      redirect: "/knowledge",
    },
    {
      path: "/admin/users",
      name: "users",
      component: () => import("./views/UsersView.vue"),
      meta: { admin: true, title: "用户管理" },
    },
    {
      path: "/:pathMatch(.*)*",
      name: "not-found",
      component: () => import("./views/NotFoundView.vue"),
      meta: { title: "页面未找到" },
    },
  ],
  scrollBehavior: () => ({ top: 0 }),
});
router.beforeEach(async (to) => {
  const auth = useAuth();
  if (!auth.ready) {
    try {
      await auth.load();
    } catch {
      /* login view explains connection error */
    }
  }
  if (!to.meta.public && !auth.loggedIn)
    return { name: "login", query: { redirect: to.fullPath } };
  if (to.meta.admin && !auth.admin) return "/";
  if (to.name === "login" && auth.loggedIn) return "/";
  document.title = `${to.meta.title || "创作工作台"} · 知映`;
});
export default router;
