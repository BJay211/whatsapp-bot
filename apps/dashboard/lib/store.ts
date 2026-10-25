import { Pool } from "pg";
import { env, hostOf } from "./env";
import {
  buildBrief,
  buildDemoSnapshot,
  DEMO_USER,
  getDemoSnapshot,
  resetDemoSnapshot,
} from "./demo-data";
import type {
  ActivityPoint,
  Connection,
  Course,
  EventItem,
  Message,
  Requirement,
  Snapshot,
  Task,
  TaskRevision,
  UserPreferences,
} from "./types";

// A small helper type used internally when mapping revisions.
type RevisionLike = {
  at: string;
  field: "due_at" | "status" | "venue";
  from: string | null;
  to: string | null;
  reason: string;
  evidenceId?: string;
};

const COURSE_COLORS = ["#00e5ff", "#7c4dff", "#ff6f00", "#00e676", "#ff1744", "#ffc107"];
const CACHE_TTL_MS = 12_000;

let pool: Pool | null = null;
let cache: { at: number; snapshot: Snapshot } | null = null;
let inflight: Promise<Snapshot> | null = null;

function hasDatabase(): boolean {
  return Boolean(process.env.DATABASE_URL);
}

function getPool(): Pool {
  if (!pool) {
    const connectionString = process.env.DATABASE_URL!;
    const needsSsl =
      /sslmode=require/i.test(connectionString) ||
      process.env.DATABASE_SSL === "require" ||
      /render\.com/i.test(connectionString) ||
      (process.env.NODE_ENV === "production" && !/localhost|127\.0\.0\.1/.test(connectionString));
    pool = new Pool({
      connectionString,
      max: 5,
      idleTimeoutMillis: 20_000,
      connectionTimeoutMillis: 5_000,
      ssl: needsSsl && !/localhost|127\.0\.0\.1/.test(connectionString)
        ? { rejectUnauthorized: false }
        : undefined,
    });
    pool.on("error", () => {
      /* surfaced through health checks, never thrown into a request */
    });
  }
  return pool;
}

export function dataSourceLabel(): { mode: "demo" | "live"; reason: string } {
  if (!hasDatabase()) {
    return {
      mode: "demo",
      reason: "Synthetic fixtures (DATABASE_URL is not configured).",
    };
  }
  return { mode: "live", reason: "PostgreSQL archive." };
}

function demoCacheable(): Snapshot {
  const snapshot = getDemoSnapshot();
  snapshot.user = { ...DEMO_USER, ...snapshot.user };
  return snapshot;
}

export async function getSnapshot(): Promise<Snapshot> {
  if (!hasDatabase()) {
    if (!cache || cache.snapshot.mode !== "demo") {
      cache = { at: Date.now(), snapshot: demoCacheable() };
    }
    return cache.snapshot;
  }

  if (cache && cache.snapshot.mode === "live" && Date.now() - cache.at < CACHE_TTL_MS) {
    return cache.snapshot;
  }
  if (inflight) return inflight;

  inflight = loadLiveSnapshot()
    .then((snapshot) => {
      cache = { at: Date.now(), snapshot };
      return snapshot;
    })
    .catch((error: unknown) => {
      const fallback = demoCacheable();
      fallback.mode = "demo";
      fallback.modeReason = `PostgreSQL unavailable — using synthetic fixtures. (${errorMessage(error)})`;
      fallback.connections = fallback.connections.map((c) =>
        c.kind === "database"
          ? { ...c, status: "degraded", detail: fallback.modeReason }
          : c,
      );
      cache = { at: Date.now(), snapshot: fallback };
      return fallback;
    })
    .finally(() => {
      inflight = null;
    });

  return inflight;
}

export function invalidateSnapshot(): void {
  cache = null;
}

function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message.slice(0, 240);
  return String(error).slice(0, 240);
}

async function loadLiveSnapshot(): Promise<Snapshot> {
  const db = getPool();
  const base = buildDemoSnapshot();
  const now = Date.now();

  const [prefsRes, coursesRes, tasksRes, evidenceRes, revisionsRes, messagesRes, relationsRes, eventsRes, countsRes] =
    await Promise.allSettled([
      db.query(
        `SELECT display_name, timezone, locale, llm_provider, llm_model, embedding_model,
                auto_create_tasks, reminder_lead_minutes, briefing_time, retention_days
           FROM user_preferences WHERE user_id = 'owner' LIMIT 1`,
      ).catch(() => ({ rows: [] })),
      db.query(`SELECT code, title, lecturer FROM courses ORDER BY code LIMIT 100`).catch(() => ({ rows: [] })),
      db.query(
        `SELECT t.id, t.kind, t.title, t.description, t.course_code, t.due_at, t.due_is_uncertain,
                t.starts_at, t.venue, t.priority, t.status, t.confidence, t.requirements,
                t.missing_info, t.issuer_name, t.created_at, t.updated_at, c.name AS chat_name
           FROM tasks t LEFT JOIN chats c ON c.id = t.chat_id
          WHERE t.status <> 'cancelled'
          ORDER BY t.due_at NULLS LAST LIMIT 100`,
      ).catch(() => ({ rows: [] })),
      db.query(
        `SELECT te.task_id, m.external_id, te.role, te.quote, m.content, m.sender_name,
                m.chat_name, m."timestamp"
           FROM task_evidence te JOIN messages m ON m.id = te.message_id
          ORDER BY m."timestamp"`,
      ).catch(() => ({ rows: [] })),
      db.query(
        `SELECT tr.task_id, tr.changed_fields, tr.old_values, tr.new_values, tr.reason,
                tr.created_at, m.external_id AS evidence_external_id
           FROM task_revisions tr LEFT JOIN messages m ON m.id = tr.message_id
          ORDER BY tr.created_at`,
      ).catch(() => ({ rows: [] })),
      db.query(
        `SELECT external_id, source, chat_id, chat_name, is_group, sender_id, sender_name,
                message_type, content, caption, "timestamp", is_forwarded, mentioned_me,
                has_media
           FROM messages
          ORDER BY "timestamp" DESC LIMIT 120`,
      ).catch(() => ({ rows: [] })),
      db.query(
        `SELECT fm.external_id AS from_external, tm.external_id AS to_external,
                r.relation, r.confidence
           FROM message_references r
           JOIN messages fm ON fm.id = r.from_message_id
           LEFT JOIN messages tm ON tm.id = r.to_message_id`,
      ).catch(() => ({ rows: [] })),
      db.query(
        `SELECT e.id, e.title, e.event_type, e.starts_at, e.ends_at, e.all_day, e.venue,
                e.status, e.course_id AS course_code
           FROM events e
          ORDER BY e.starts_at LIMIT 60`,
      ).catch(() => ({ rows: [] })),
      db.query(
        `SELECT date_trunc('day', "timestamp") AS day, count(*)::int AS count
           FROM messages WHERE "timestamp" > now() - interval '7 days'
          GROUP BY 1 ORDER BY 1`,
      ).catch(() => ({ rows: [] })),
    ]).then((results) =>
      results.map((r) => (r.status === "fulfilled" ? r.value : { rows: [] })),
    );

  const prefsRow = prefsRes.rows[0];
  const user: UserPreferences = prefsRow
    ? {
        displayName: prefsRow.display_name ?? "Owner",
        timezone: prefsRow.timezone ?? "Africa/Lagos",
        locale: prefsRow.locale ?? "en-NG",
        llmProvider: prefsRow.llm_provider ?? "openai",
        llmModel: prefsRow.llm_model ?? "gpt-4o-mini",
        embeddingModel: prefsRow.embedding_model ?? "text-embedding-3-small",
        voiceEnabled: true,
        autoCreateTasks: Boolean(prefsRow.auto_create_tasks),
        reminderLeadMinutes: Number(prefsRow.reminder_lead_minutes ?? 1440),
        briefingTime: String(prefsRow.briefing_time ?? "07:00").slice(0, 5),
        retentionDays: Number(prefsRow.retention_days ?? 0),
      }
    : DEMO_USER;

  const courses: Course[] = coursesRes.rows.map((row, i) => ({
    id: `course_${row.code}`,
    code: row.code,
    title: row.title ?? row.code,
    lecturer: row.lecturer ?? undefined,
    color: COURSE_COLORS[i % COURSE_COLORS.length],
  }));

  const evidenceByTask = new Map<string, { ids: string[]; rows: any[] }>();
  for (const row of evidenceRes.rows) {
    if (!row.external_id) continue;
    const entry = evidenceByTask.get(row.task_id) ?? { ids: [], rows: [] };
    entry.ids.push(row.external_id);
    entry.rows.push(row);
    evidenceByTask.set(row.task_id, entry);
  }

  const revisionsByTask = new Map<string, TaskRevision[]>();
  for (const row of revisionsRes.rows) {
    const changed: string[] = Array.isArray(row.changed_fields) ? row.changed_fields : [];
    const field = changed.includes("due_at")
      ? "due_at"
      : changed.includes("venue")
        ? "venue"
        : "status";
    const oldValue = row.old_values?.[field] ?? row.old_values?.due_at ?? null;
    const newValue = row.new_values?.[field] ?? row.new_values?.due_at ?? null;
    const revision: TaskRevision = {
      at: toIso(row.created_at) ?? new Date().toISOString(),
      field,
      from: oldValue ? String(oldValue) : null,
      to: newValue ? String(newValue) : null,
      reason: row.reason ?? "Recorded change",
      evidenceId: row.evidence_external_id ?? undefined,
    };
    const list = revisionsByTask.get(row.task_id) ?? [];
    list.push(revision);
    revisionsByTask.set(row.task_id, list);
  }

  const tasks: Task[] = tasksRes.rows.map((row) => {
    const evidence = evidenceByTask.get(row.id);
    return {
      id: row.id,
      kind: row.kind ?? "task",
      title: row.title,
      description: row.description ?? "",
      courseCode: row.course_code ?? undefined,
      courseName: courses.find((c) => c.code === row.course_code)?.title,
      sourceChat: row.chat_name ?? "Unknown chat",
      issuer: row.issuer_name ?? "Unknown",
      requirements: normalizeRequirements(row.requirements),
      dueAt: toIso(row.due_at),
      dueCertain: !row.due_is_uncertain,
      startsAt: toIso(row.starts_at),
      venue: row.venue ?? null,
      priority: row.priority ?? "normal",
      status: row.status ?? "detected",
      confidence: Number(row.confidence ?? 0.5),
      evidenceIds: evidence?.ids ?? [],
      revisions: revisionsByTask.get(row.id) ?? [],
      missingInfo: Array.isArray(row.missing_info)
        ? row.missing_info.map((x: unknown) => String(x))
        : [],
      createdAt: toIso(row.created_at) ?? new Date().toISOString(),
      updatedAt: toIso(row.updated_at) ?? new Date().toISOString(),
    };
  });

  const relationsByMessage = new Map<string, Message["relations"]>();
  for (const row of relationsRes.rows) {
    if (!row.from_external) continue;
    const list = relationsByMessage.get(row.from_external) ?? [];
    list.push({
      type: row.relation,
      targetId: row.to_external ?? "unknown",
      confidence: Number(row.confidence ?? 0.5),
    });
    relationsByMessage.set(row.from_external, list);
  }

  const messages: Message[] = messagesRes.rows.map((row) => {
    const content = String(row.content ?? row.caption ?? "");
    return {
      id: row.external_id,
      externalId: row.external_id,
      source: row.source === "android" ? "android" : row.source === "calendar" ? "calendar" : "whatsapp",
      chatId: row.chat_id ?? "",
      chatName: row.chat_name ?? row.chat_id ?? "Unknown",
      isGroup: Boolean(row.is_group),
      senderId: row.sender_id ?? "",
      senderName: row.sender_name ?? "Unknown",
      type: normalizeMessageType(row.message_type),
      content,
      caption: row.caption ?? undefined,
      timestamp: toIso(row.timestamp) ?? new Date().toISOString(),
      isForwarded: Boolean(row.is_forwarded),
      mentionedMe: Boolean(row.mentioned_me),
      hasMedia: Boolean(row.has_media),
      courseCode: detectCourse(content, courses),
      topics: inferTopics(content),
      relations: relationsByMessage.get(row.external_id) ?? [],
      suspicious: /ignore (all|previous) instructions|forward the database password/i.test(content),
      processed: true,
    };
  });

  const events: EventItem[] = eventsRes.rows.map((row) => ({
    id: String(row.id),
    title: row.title,
    type: row.event_type ?? "other",
    courseCode: row.course_code ?? undefined,
    startsAt: toIso(row.starts_at) ?? new Date().toISOString(),
    endsAt: toIso(row.ends_at) ?? undefined,
    allDay: Boolean(row.all_day),
    venue: row.venue ?? undefined,
    status: row.status ?? "scheduled",
  }));

  const activity = buildActivity(messages, tasks);
  const brief = buildBrief(tasks, messages, events);
  const connections = buildConnections(true);

  return {
    mode: "live",
    modeReason: "Connected to PostgreSQL. Original messages are never overwritten by generated knowledge.",
    user,
    courses,
    messages,
    tasks,
    events,
    workflows: base.workflows,
    connections,
    activity,
    brief,
    generatedAt: new Date(now).toISOString(),
  };
}

function buildConnections(live: boolean): Connection[] {
  return [
    {
      id: "conn_db",
      name: "Supabase Postgres",
      kind: "database",
      status: live ? "connected" : "demo",
      detail: live
        ? `Live archive via ${hostOf(env.supabaseUrl)} (read/write).`
        : "Synthetic fixtures. Set DATABASE_URL to the Supabase connection string.",
      endpoint: env.supabaseUrl,
      lastSeen: new Date().toISOString(),
    },
    {
      id: "conn_n8n",
      name: "n8n orchestration",
      kind: "automation",
      status: process.env.N8N_BASE_URL ? "connected" : "unconfigured",
      detail: process.env.N8N_BASE_URL
        ? `Configured at ${process.env.N8N_BASE_URL}`
        : "Set N8N_BASE_URL to enable workflow runs.",
      endpoint: process.env.N8N_BASE_URL,
      lastSeen: null,
    },
    {
      id: "conn_evolution",
      name: "Evolution API",
      kind: "whatsapp",
      status: env.evolutionApiUrl ? "connected" : "unconfigured",
      detail: env.evolutionApiUrl
        ? `Configured at ${hostOf(env.evolutionApiUrl)} for instance "${env.evolutionInstance}".`
        : "Set EVOLUTION_API_URL after deploying the WhatsApp bridge.",
      endpoint: env.evolutionApiUrl,
      lastSeen: null,
    },
    {
      id: "conn_ai",
      name: "Reasoning model",
      kind: "ai",
      status: process.env.OPENAI_API_KEY || process.env.JARVIS_LLM_API_KEY || process.env.JARVIS_LLM_BASE_URL
        ? "connected"
        : "degraded",
      detail: process.env.OPENAI_API_KEY || process.env.JARVIS_LLM_API_KEY || process.env.JARVIS_LLM_BASE_URL
        ? "Model endpoint detected."
        : "No model key set — deterministic grounded retrieval is active.",
      lastSeen: null,
    },
    {
      id: "conn_android",
      name: "Android companion",
      kind: "device",
      status: "unconfigured",
      detail: "Sideload the notifier APK to stream authorized notifications.",
      lastSeen: null,
    },
  ];
}

function buildActivity(messages: Message[], tasks: Task[]): ActivityPoint[] {
  const points: ActivityPoint[] = [];
  const now = new Date();
  for (let i = 6; i >= 0; i--) {
    const day = new Date(now.getTime() - i * 86_400_000);
    const label = new Intl.DateTimeFormat("en-GB", { weekday: "short" }).format(day);
    const key = day.toISOString().slice(0, 10);
    points.push({
      label,
      messages: messages.filter((m) => m.timestamp.slice(0, 10) === key).length,
      tasks: tasks.filter((t) => t.createdAt.slice(0, 10) === key).length,
    });
  }
  return points;
}

function normalizeRequirements(value: unknown): Requirement[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((item): Requirement | null => {
      if (typeof item === "string") return { text: item, done: false };
      if (item && typeof item === "object") {
        const obj = item as Record<string, unknown>;
        if (typeof obj.text === "string") return { text: obj.text, done: Boolean(obj.done) };
      }
      return null;
    })
    .filter((x): x is Requirement => x !== null);
}

function normalizeMessageType(value: unknown): Message["type"] {
  const type = String(value ?? "text");
  if (["image", "document", "audio", "notification"].includes(type)) return type as Message["type"];
  return "text";
}

function inferTopics(content: string): string[] {
  const topics: string[] = [];
  const map: Record<string, RegExp> = {
    assignment: /assign|submit|submission|report|project|lab/i,
    deadline: /deadline|due|submit|extension|moved/i,
    exam: /exam|test|quiz/i,
    meeting: /meet|sync|call|link/i,
    cancellation: /cancel|postpon|no lecture/i,
    security: /password|otp|ignore.*instruction|urgent.*admin/i,
    personal: /call me|mum|family/i,
  };
  for (const [topic, re] of Object.entries(map)) if (re.test(content)) topics.push(topic);
  return topics;
}

function detectCourse(content: string, courses: Course[]): string | undefined {
  const match = content.match(/\b([A-Z]{2,4})\s?-?\s?(\d{3})\b/);
  if (match) {
    const code = `${match[1]} ${match[2]}`;
    if (courses.some((c) => c.code === code)) return code;
    return code;
  }
  return courses.find((c) => content.toLowerCase().includes(c.title.toLowerCase()))?.code;
}

function toIso(value: unknown): string | null {
  if (!value) return null;
  if (value instanceof Date) return value.toISOString();
  const date = new Date(String(value));
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

export async function getTask(id: string): Promise<Task | null> {
  const snapshot = await getSnapshot();
  return snapshot.tasks.find((t) => t.id === id) ?? null;
}

export async function updateTaskStatus(
  id: string,
  status: Task["status"],
): Promise<{ ok: boolean; task?: Task; error?: string }> {
  const snapshot = await getSnapshot();
  const task = snapshot.tasks.find((t) => t.id === id);
  if (!task) return { ok: false, error: "Task not found" };

  const now = new Date().toISOString();
  const previous = task.status;

  if (snapshot.mode === "live" && hasDatabase()) {
    try {
      const db = getPool();
      await db.query(`UPDATE tasks SET status = $1, updated_at = now() WHERE id = $2::uuid`, [status, id]);
      await db.query(
        `INSERT INTO audit_logs (actor, action, target_type, target_id, detail)
         VALUES ('owner', 'task.status_changed', 'task', $1, $2::jsonb)`,
        [id, JSON.stringify({ from: previous, to: status })],
      );
    } catch (error) {
      return { ok: false, error: errorMessage(error) };
    }
    invalidateSnapshot();
  }

  task.status = status;
  task.updatedAt = now;
  task.revisions.push({
    at: now,
    field: "status",
    from: previous,
    to: status,
    reason: "Changed by the owner from the cockpit.",
  });
  return { ok: true, task };
}

export async function toggleRequirement(
  id: string,
  index: number,
): Promise<{ ok: boolean; task?: Task; error?: string }> {
  const snapshot = await getSnapshot();
  const task = snapshot.tasks.find((t) => t.id === id);
  if (!task) return { ok: false, error: "Task not found" };
  if (index < 0 || index >= task.requirements.length) return { ok: false, error: "Requirement not found" };

  const next = task.requirements.map((r, i) => (i === index ? { ...r, done: !r.done } : r));
  if (snapshot.mode === "live" && hasDatabase()) {
    try {
      await getPool().query(`UPDATE tasks SET requirements = $1::jsonb, updated_at = now() WHERE id = $2::uuid`, [
        JSON.stringify(next),
        id,
      ]);
    } catch (error) {
      return { ok: false, error: errorMessage(error) };
    }
    invalidateSnapshot();
  }
  task.requirements = next;
  task.updatedAt = new Date().toISOString();
  return { ok: true, task };
}

export async function updatePreferences(
  patch: Partial<UserPreferences>,
): Promise<UserPreferences> {
  const snapshot = await getSnapshot();
  const next: UserPreferences = { ...snapshot.user, ...patch };
  if (snapshot.mode === "live" && hasDatabase()) {
    await getPool().query(
      `UPDATE user_preferences SET
         display_name = $1, timezone = $2, locale = $3, llm_provider = $4, llm_model = $5,
         embedding_model = $6, auto_create_tasks = $7, reminder_lead_minutes = $8,
         briefing_time = $9, retention_days = $10, updated_at = now()
       WHERE user_id = 'owner'`,
      [
        next.displayName,
        next.timezone,
        next.locale,
        next.llmProvider,
        next.llmModel,
        next.embeddingModel,
        next.autoCreateTasks,
        next.reminderLeadMinutes,
        next.briefingTime,
        next.retentionDays,
      ],
    );
    invalidateSnapshot();
  }
  snapshot.user = next;
  return next;
}

export async function exportJsonl(): Promise<string> {
  const snapshot = await getSnapshot();
  return snapshot.messages
    .slice()
    .sort((a, b) => a.timestamp.localeCompare(b.timestamp))
    .map((message) =>
      JSON.stringify({
        external_id: message.externalId,
        source: message.source,
        chat_id: message.chatId,
        chat_name: message.chatName,
        sender_id: message.senderId,
        sender_name: message.senderName,
        message_type: message.type,
        content: message.content,
        timestamp: message.timestamp,
        is_forwarded: Boolean(message.isForwarded),
        raw_payload: null,
      }),
    )
    .join("\n");
}

export async function purgeData(scope: "all" | "chat", chatId?: string): Promise<{ removed: number }> {
  const snapshot = await getSnapshot();
  let removed = 0;

  if (snapshot.mode === "live" && hasDatabase()) {
    const db = getPool();
    if (scope === "all") {
      const res = await db.query(`DELETE FROM messages RETURNING id`);
      removed = res.rowCount ?? 0;
    } else if (chatId) {
      const res = await db.query(`DELETE FROM messages WHERE chat_id = $1 RETURNING id`, [chatId]);
      removed = res.rowCount ?? 0;
    }
    await db.query(
      `INSERT INTO audit_logs (actor, action, target_type, target_id, detail)
       VALUES ('owner', 'data.deleted', $1, $2, $3::jsonb)`,
      [scope, chatId ?? "all", JSON.stringify({ removed })],
    );
    invalidateSnapshot();
    return { removed };
  }

  if (scope === "all") {
    removed = snapshot.messages.length;
    const fresh = resetDemoSnapshot();
    fresh.user = { ...DEMO_USER };
    fresh.messages = [];
    fresh.tasks = [];
    fresh.events = [];
    fresh.brief = buildBrief([], [], []);
    cache = { at: Date.now(), snapshot: fresh };
  } else if (chatId) {
    removed = snapshot.messages.filter((m) => m.chatId === chatId).length;
    snapshot.messages = snapshot.messages.filter((m) => m.chatId !== chatId);
    snapshot.tasks = snapshot.tasks.filter((t) => t.sourceChat !== chatId);
  }
  return { removed };
}

export async function checkHealth(): Promise<{
  ok: boolean;
  mode: "demo" | "live";
  checks: { name: string; ok: boolean; detail: string }[];
  counts: { messages: number; tasks: number; events: number };
}> {
  const snapshot = await getSnapshot();
  const checks = [
    { name: "cockpit", ok: true, detail: "API responding" },
    {
      name: "postgres",
      ok: snapshot.mode === "live",
      detail:
        snapshot.mode === "live"
          ? "SELECT 1 succeeded"
          : hasDatabase()
            ? "DATABASE_URL set but unreachable"
            : "DATABASE_URL not set (demo mode)",
    },
    {
      name: "reasoning",
      ok: true,
      detail: "Deterministic local retrieval active",
    },
    {
      name: "n8n",
      ok: Boolean(process.env.N8N_BASE_URL),
      detail: process.env.N8N_BASE_URL ? "configured" : "not configured",
    },
  ];

  if (hasDatabase()) {
    try {
      await getPool().query("SELECT 1");
      checks[1] = { name: "postgres", ok: true, detail: "SELECT 1 succeeded" };
    } catch (error) {
      checks[1] = { name: "postgres", ok: false, detail: errorMessage(error) };
    }
  }

  return {
    ok: checks.every((c) => c.ok || c.name === "n8n" || c.name === "postgres"),
    mode: snapshot.mode,
    checks,
    counts: {
      messages: snapshot.messages.length,
      tasks: snapshot.tasks.length,
      events: snapshot.events.length,
    },
  };
}

export async function resetDemo(): Promise<Snapshot> {
  const snapshot = resetDemoSnapshot();
  snapshot.user = { ...DEMO_USER };
  cache = { at: Date.now(), snapshot };
  return snapshot;
}
