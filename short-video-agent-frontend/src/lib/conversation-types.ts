import type { Source } from "./types";
export interface Brief {
  topic: string;
  direction: string;
  audience: string;
  platform: string;
  duration: number;
  style: string;
  productionMode: string;
  equipment: string;
  requirements: string;
}
export interface ChoiceOption {
  id: string;
  label: "A" | "B" | "C";
  title: string;
  description: string;
  value: string;
}
export interface ChoiceGroup {
  id: string;
  kind: "TOPIC" | "DIRECTION" | "AUDIENCE" | "STYLE" | "PRODUCTION";
  title: string;
  options: ChoiceOption[];
  selectedOptionId: string | null;
  customValue: string | null;
  status: "OPEN" | "SELECTED" | "SUPERSEDED";
}
export interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  choices: ChoiceGroup[];
  runId: string | null;
  createdAt: string;
  attachments?: Attachment[];
  metadata?: { thinkingMs?: number; processingMs?: number; summary?: string };
}
export interface Attachment {
  id: string;
  name: string;
  size: number;
  characters: number;
}
export interface UserMemory {
  enabled: boolean;
  revision: number;
  updatedAt?: string;
  items: { text: string; evidence: string }[];
  conversations: {
    id: string;
    title: string;
    summary: string;
    updatedAt: string;
  }[];
}
export interface RunEvent {
  id: number;
  runId: string;
  type:
    | "run.started"
    | "message.delta"
    | "brief.updated"
    | "tool.started"
    | "tool.completed"
    | "tool.failed"
    | "artifact.updated"
    | "run.waiting_input"
    | "run.completed"
    | "run.failed"
    | "run.cancelled";
  toolCallId?: string;
  tool?: string;
  data: Record<string, unknown>;
  createdAt: string;
}
export interface ProductionSegment {
  id: string;
  duration: number;
  visual: string;
  narration: string;
  subtitle: string;
  shotSize: string;
  camera: string;
  action: string;
  voiceDirection: string;
  editing: string;
  material: string;
}
export interface ProductionPackage {
  title: string;
  summary: string;
  positioning: string;
  audienceAnalysis: string;
  titles: string[];
  opening: string;
  segments: ProductionSegment[];
  ending: string;
  caption: string;
  tags: string[];
  shootingPlan: {
    approach: string;
    locations: string[];
    equipment: string[];
    props: string[];
    schedule: string[];
  };
  voiceoverPlan: {
    approach: string;
    tone: string;
    pace: string;
    recordingTips: string[];
    script: string;
  };
  editingPlan: {
    approach: string;
    rhythm: string;
    music: string;
    soundEffects: string[];
    subtitleStyle: string;
    steps: string[];
  };
  qualityNotes: string[];
}
export interface Artifact {
  id: string;
  title: string;
  content: ProductionPackage;
  versionNumber: number;
  status: "DRAFT" | "FINAL";
  sources: Source[];
  createdAt: string;
  updatedAt: string;
}
export interface ArtifactVersion {
  id: string;
  versionNumber: number;
  kind: "GENERATED" | "REVISED" | "MANUAL" | "RESTORED" | "FINALIZED";
  instruction: string;
  content: ProductionPackage;
  sources: Source[];
  createdAt: string;
}
export interface Conversation {
  id: string;
  title: string;
  status: "IDLE" | "RUNNING" | "WAITING_INPUT" | "FAILED";
  brief: Brief;
  createdAt: string;
  updatedAt: string;
  activeRunId: string | null;
  retryingMessageId?: string | null;
  messages: ChatMessage[];
  events: RunEvent[];
  artifact: Artifact | null;
  versions: ArtifactVersion[];
  pinned?: boolean;
  summary?: string;
}
export interface Selection {
  messageId: string;
  groupId: string;
  optionId?: string;
  customText?: string;
}
