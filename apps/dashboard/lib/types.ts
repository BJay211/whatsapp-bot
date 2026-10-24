export type DataMode = "demo" | "live";

export type Priority = "low" | "normal" | "high" | "urgent";

export type TaskStatus =
  | "detected"
  | "needs_confirmation"
  | "confirmed"
  | "in_progress"
  | "completed"
  | "cancelled"
  | "overdue";

export type RelationType =
  | "REFERENCES"
  | "REPLIES_TO"
  | "CLARIFIES"
  | "UPDATES"
  | "SUPERSEDES"
  | "CONTRADICTS"
  | "CONFIRMS"
  | "CANCELS"
  | "RELATES_TO";

export type SourceKey = "whatsapp" | "android" | "calendar" | "system";

export interface Course {
  id: string;
  code: string;
  title: string;
  lecturer?: string;
  color: string;
}

export interface MessageRelation {
  type: RelationType;
  targetId: string;
  confidence: number;
}

export interface Message {
  id: string;
  externalId: string;
  source: SourceKey;
  chatId: string;
  chatName: string;
  isGroup: boolean;
  senderId: string;
  senderName: string;
  type: "text" | "image" | "document" | "audio" | "notification";
  content: string;
  caption?: string;
  timestamp: string;
  isForwarded?: boolean;
  mentionedMe?: boolean;
  hasMedia?: boolean;
  courseCode?: string;
  topics: string[];
  relations: MessageRelation[];
  suspicious?: boolean;
  processed: boolean;
}

export interface Requirement {
  text: string;
  done: boolean;
}

export interface TaskRevision {
  at: string;
  field: "due_at" | "status" | "venue";
  from: string | null;
  to: string | null;
  reason: string;
  evidenceId?: string;
}

export interface Task {
  id: string;
  kind: "assignment" | "exam" | "quiz" | "lecture" | "meeting" | "event" | "task" | "reminder";
  title: string;
  description: string;
  courseCode?: string;
  courseName?: string;
  sourceChat: string;
  issuer: string;
  requirements: Requirement[];
  dueAt: string | null;
  dueCertain: boolean;
  startsAt?: string | null;
  venue?: string | null;
  priority: Priority;
  status: TaskStatus;
  confidence: number;
  evidenceIds: string[];
  revisions: TaskRevision[];
  missingInfo: string[];
  createdAt: string;
  updatedAt: string;
}

export interface EventItem {
  id: string;
  title: string;
  type: "lecture" | "exam" | "meeting" | "deadline" | "reminder" | "other";
  courseCode?: string;
  startsAt: string;
  endsAt?: string;
  allDay?: boolean;
  venue?: string;
  status: "scheduled" | "confirmed" | "cancelled" | "moved" | "completed";
}

export interface WorkflowModule {
  id: string;
  index: string;
  name: string;
  purpose: string;
  category: "ingestion" | "intelligence" | "retrieval" | "automation" | "ops" | "device";
  status: "active" | "ready" | "needs_setup" | "planned";
  file?: string;
  lastRun: string | null;
  successRate: number | null;
  requires: string[];
}

export interface Connection {
  id: string;
  name: string;
  kind: "database" | "automation" | "whatsapp" | "ai" | "device";
  status: "connected" | "degraded" | "unconfigured" | "demo";
  detail: string;
  endpoint?: string;
  lastSeen?: string | null;
}

export interface UserPreferences {
  displayName: string;
  timezone: string;
  locale: string;
  llmProvider: string;
  llmModel: string;
  embeddingModel: string;
  voiceEnabled: boolean;
  autoCreateTasks: boolean;
  reminderLeadMinutes: number;
  briefingTime: string;
  retentionDays: number;
}

export interface ActivityPoint {
  label: string;
  messages: number;
  tasks: number;
}

export interface Brief {
  generatedAt: string;
  headline: string;
  points: string[];
  changed: string[];
  needsAttention: string[];
}

export interface Snapshot {
  mode: DataMode;
  modeReason: string;
  user: UserPreferences;
  courses: Course[];
  messages: Message[];
  tasks: Task[];
  events: EventItem[];
  workflows: WorkflowModule[];
  connections: Connection[];
  activity: ActivityPoint[];
  brief: Brief;
  generatedAt: string;
}

export interface EvidenceSource {
  messageId: string;
  chatName: string;
  sender: string;
  timestamp: string;
  quote: string;
  relation?: string;
}

export interface JarvisAction {
  type: "complete_task" | "open_task" | "open_calendar" | "create_reminder";
  label: string;
  taskId?: string;
  requiresConfirmation: boolean;
}

export interface JarvisAnswer {
  question: string;
  headline: string;
  sections: { label: string; body: string }[];
  sources: EvidenceSource[];
  uncertainty: string | null;
  actions: JarvisAction[];
  mode: DataMode;
  engine: "local-reasoning" | "llm-augmented";
  latencyMs: number;
  injectionAttempts: number;
  intent: string;
  confidence: number;
}

export interface AskRequest {
  message: string;
  timezone?: string;
}
