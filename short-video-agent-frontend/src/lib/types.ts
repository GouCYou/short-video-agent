export type Role = "USER" | "ADMIN";
export interface User {
  id: string;
  username: string;
  email?: string;
  nickname: string;
  role: Role;
  enabled: boolean;
  createdAt: string;
}
export type TaskStatus =
  "QUEUED" | "RUNNING" | "WAITING_SELECTION" | "COMPLETED" | "FAILED";
export interface Topic {
  title: string;
  angle: string;
  reason: string;
}
export interface Segment {
  duration: number;
  visual: string;
  narration: string;
  subtitle: string;
}
export interface Content {
  titles: string[];
  opening: string;
  positioning: string;
  audienceAnalysis: string;
  outline: string[];
  segments: Segment[];
  ending: string;
  caption: string;
  tags: string[];
  qualityNotes: string[];
}
export interface Source {
  documentId: string;
  documentName: string;
  chunkId: string;
  text: string;
  score: number;
  sourceUrl: string;
}
export interface TaskEvent {
  stage: string;
  message: string;
  createdAt: string;
  durationMs?: number;
}
export interface Version {
  id: string;
  versionNumber: number;
  kind: string;
  instruction: string;
  content: Content;
  sources: Source[];
  createdAt: string;
}
export interface Task {
  id: string;
  title: string;
  topic: string;
  audience: string;
  platform: string;
  style: string;
  duration: number;
  requirements: string;
  mode: "GUIDED" | "QUICK";
  category: string;
  ragEnabled: boolean;
  status: TaskStatus;
  stage: string;
  error: string;
  createdAt: string;
  updatedAt: string;
  topics: Topic[];
  result: Content | null;
  sources: Source[];
  events: TaskEvent[];
  versions: Version[];
}
export interface TaskInput {
  title: string;
  topic: string;
  audience: string;
  platform: string;
  style: string;
  duration: number;
  requirements: string;
  mode: "GUIDED" | "QUICK";
  category: string;
  ragEnabled: boolean;
}
export interface KnowledgeDocument {
  chunkSize?: number;
  chunkOverlap?: number;
  originType?: string;
  originId?: string;
  duplicate?: boolean;
  isDefault?: boolean;
  text?: string;
  id: string;
  name: string;
  category: string;
  sourceUrl: string;
  enabled: boolean;
  status: "UNINDEXED" | "INDEXING" | "READY" | "FAILED";
  chunkCount: number;
  error: string;
  createdAt: string;
  chunks?: { id: string; text: string }[];
}
export interface Dashboard {
  totalTasks: number;
  completedTasks: number;
  runningTasks: number;
  failedTasks: number;
  recentTasks: Task[];
}
export interface AdminStats {
  users: number;
  tasks: number;
  documents: number;
  failedTasks: number;
  completedTasks: number;
  averageDurationMs: number;
}
export interface Health {
  ai: Record<string, unknown>;
  redis: string;
  database: string;
}

export interface KnowledgeImportItem {
  enabled?: boolean;
  documentId?: string;
  name: string;
  status: string;
  duplicate?: boolean;
  error?: string;
  indexError?: string;
}
export interface ChunkPreview {
  chunkCount: number;
  characters: number;
  chunks: { text: string; characters: number }[];
  truncated: boolean;
}
