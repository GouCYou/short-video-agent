import axios from "axios";
import type {
  AdminStats,
  KnowledgeImportItem,
  ChunkPreview,
  Content,
  Dashboard,
  Health,
  KnowledgeDocument,
  Source,
  Task,
  TaskInput,
  User,
} from "./types";
export const TOKEN_KEY = "zhiying-token";
export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "/api",
  timeout: 30000,
});
http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY);
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});
http.interceptors.response.use(
  (response) => response,
  (error) => {
    const token = localStorage.getItem(TOKEN_KEY);
    if (
      error.response?.status === 401 &&
      token &&
      error.config?.headers?.Authorization === `Bearer ${token}` &&
      !error.config?.url?.includes("/auth/login") &&
      !error.config?.url?.includes("/auth/register")
    ) {
      localStorage.removeItem(TOKEN_KEY);
      window.dispatchEvent(new Event("session-expired"));
    }
    return Promise.reject(error);
  },
);
export function errorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    if (!error.response)
      return "暂时无法连接服务，请检查后端是否启动或网络连接后重试。";
    return typeof error.response.data?.message === "string"
      ? error.response.data.message
      : `请求失败（${error.response.status}），请稍后重试。`;
  }
  return error instanceof Error ? error.message : "操作未成功，请稍后重试。";
}
const data = async <T>(promise: Promise<{ data: T }>) => (await promise).data;
export const api = {
  login: (username: string, password: string) =>
    data(
      http.post<{ token: string; user: User }>("/auth/login", {
        username,
        password,
      }),
    ),
  register: (username: string, password: string, nickname: string, email: string) =>
    data(
      http.post<{ token: string; user: User }>("/auth/register", {
        username,
        password,
        nickname,
        email,
      }),
    ),
  me: () => data(http.get<User>("/auth/me")),
  logout: () => http.post("/auth/logout"),
  updateProfile: (nickname: string) =>
    data(http.put<User>("/auth/me", { nickname })),
  password: (oldPassword: string, newPassword: string) =>
    http.put("/auth/password", { oldPassword, newPassword }),
  dashboard: () => data(http.get<Dashboard>("/dashboard")),
  tasks: (search = "", status = "") =>
    data(http.get<Task[]>("/tasks", { params: { search, status } })),
  task: (id: string) => data(http.get<Task>(`/tasks/${id}`)),
  create: (body: TaskInput, idempotencyKey?: string) =>
    data(
      http.post<Task>("/tasks", body, {
        headers: idempotencyKey
          ? { "Idempotency-Key": idempotencyKey }
          : undefined,
      }),
    ),
  selectTopic: (id: string, topicIndex: number) =>
    data(http.post<Task>(`/tasks/${id}/select-topic`, { topicIndex })),
  retry: (id: string) => data(http.post<Task>(`/tasks/${id}/retry`)),
  revise: (id: string, instruction: string, section: string) =>
    data(http.post<Task>(`/tasks/${id}/revise`, { instruction, section })),
  save: (id: string, content: Content) =>
    data(http.put<Task>(`/tasks/${id}/content`, { content })),
  restore: (id: string, versionId: string) =>
    data(http.post<Task>(`/tasks/${id}/restore/${versionId}`)),
  deleteTask: (id: string) => http.delete(`/tasks/${id}`),
  export: (id: string, format: string) =>
    data(
      http.get<Blob>(`/tasks/${id}/export`, {
        params: { format },
        responseType: "blob",
      }),
    ),
  categories: () => data(http.get<string[]>("/knowledge/categories")),
  documents: () => data(http.get<KnowledgeDocument[]>("/knowledge")),
  upload: (body: FormData) =>
    data(http.post<KnowledgeDocument>("/knowledge", body)),
  addText: (body: {
    name: string;
    text: string;
    category: string;
    sourceUrl: string;
  }) => data(http.post<KnowledgeDocument>("/knowledge/text", body)),
  document: (id: string) =>
    data(http.get<KnowledgeDocument>(`/knowledge/${id}`)),
  updateDocument: (
    id: string,
    body: {
      enabled: boolean;
      category: string;
      sourceUrl: string;
      name?: string;
      text?: string;
      chunkSize?: number;
      chunkOverlap?: number;
    },
  ) => data(http.put<KnowledgeDocument>(`/knowledge/${id}`, body)),
  reindex: (id: string) =>
    data(http.post<KnowledgeDocument>(`/knowledge/${id}/reindex`)),
  deleteDocument: (id: string) => http.delete(`/knowledge/${id}`),
  search: (query: string, category: string, limit = 5) =>
    data(
      http.post<Source[]>("/knowledge/search", {
        query,
        category,
        limit,
      }),
    ),
  importKnowledge: (body: FormData) =>
    data(
      http.post<{ items: KnowledgeImportItem[]; indexingAvailable: boolean }>(
        "/knowledge/import",
        body,
        { timeout: 300000 },
      ),
    ),
  exportKnowledge: () =>
    data(http.get<Record<string, unknown>>("/knowledge/export")),
  previewKnowledge: (body: {
    text: string;
    chunkSize: number;
    chunkOverlap: number;
  }) => data(http.post<ChunkPreview>("/knowledge/preview", body)),
  knowledgeHealth: () =>
    data(
      http.get<{
        embeddingConfigured?: boolean;
        vectorStore?: string;
        mode?: string;
      }>("/knowledge/health"),
    ),
  restoreKnowledge: () =>
    data(http.post<KnowledgeDocument[]>("/knowledge/restore-defaults")),
  indexKnowledge: () =>
    data(http.post<{ queued: number }>("/knowledge/index-all")),
  users: () => data(http.get<User[]>("/admin/users")),
  updateUser: (id: string, enabled: boolean, role: string) =>
    data(http.put<User>(`/admin/users/${id}`, { enabled, role })),
  adminTasks: () => data(http.get<Task[]>("/admin/tasks")),
  stats: () => data(http.get<AdminStats>("/admin/stats")),
  health: () => data(http.get<Health>("/admin/health")),
};
