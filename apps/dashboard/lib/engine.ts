import type {
  EvidenceSource,
  JarvisAction,
  JarvisAnswer,
  Message,
  Snapshot,
  Task,
} from "./types";

const DAY = 86_400_000;

const STOPWORDS = new Set(
  `a an the is are was were be been being do does did i me my mine we our you your it its of to in on at for with about from as by and or but if then than that this these those what which who whom when where why how can could should would will shall may might have has had not no yes please tell show give find list any all there here
  something anything everything me? i'm im whats what's what are`
    .split(/\s+/)
    .filter(Boolean),
);

export type Intent =
  | "complete_task"
  | "changes"
  | "requirements"
  | "deadlines"
  | "schedule"
  | "digest"
  | "groups"
  | "original"
  | "search"
  | "overview";

export function tokenize(text: string): string[] {
  return text
    .toLowerCase()
    .replace(/[^a-z0-9\s]/g, " ")
    .split(/\s+/)
    .filter((w) => w.length > 1 && !STOPWORDS.has(w));
}

export function detectCourseCode(text: string): string | null {
  const m = text.match(/\b([A-Za-z]{2,4})\s?-?\s?(\d{3})\b/);
  if (!m) return null;
  return `${m[1].toUpperCase()} ${m[2]}`;
}

export function detectInjection(text: string): boolean {
  const patterns = [
    /ignore (all|any|the) (previous|prior|above) instructions/i,
    /system prompt/i,
    /you are now (in )?(developer|admin|dan) mode/i,
    /reveal (the |your )?(api|secret|password|token|credential)/i,
    /forward (the )?(database|db|api)?\s*(password|secret|keys?)/i,
    /disregard (your|all) (rules|instructions)/i,
    /execute (this )?(sql|shell|command)/i,
  ];
  return patterns.some((re) => re.test(text));
}

export function relativeDue(iso: string | null, now = new Date(), timeZone = "Africa/Lagos"): string {
  if (!iso) return "no confirmed date";
  const target = new Date(iso);
  const days = calendarDaysBetween(now, target, timeZone);
  if (days < 0) return `${Math.abs(days)} day${Math.abs(days) === 1 ? "" : "s"} overdue`;
  if (days === 0) return "today";
  if (days === 1) return "tomorrow";
  return `in ${days} days`;
}

function calendarDayNumber(date: Date, timeZone: string): number {
  const parts = new Intl.DateTimeFormat("en-CA", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    timeZone,
  }).formatToParts(date);
  const get = (t: string) => Number(parts.find((p) => p.type === t)?.value ?? 0);
  return Math.floor(Date.UTC(get("year"), get("month") - 1, get("day")) / DAY);
}

export function calendarDaysBetween(from: Date, to: Date, timeZone: string): number {
  return calendarDayNumber(to, timeZone) - calendarDayNumber(from, timeZone);
}

export function formatWhen(iso: string | null | undefined, timeZone = "Africa/Lagos"): string {
  if (!iso) return "no date";
  return new Intl.DateTimeFormat("en-GB", {
    weekday: "short",
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "numeric",
    minute: "2-digit",
    timeZone,
  }).format(new Date(iso));
}

export function formatDay(iso: string | null | undefined, timeZone = "Africa/Lagos"): string {
  if (!iso) return "no date";
  return new Intl.DateTimeFormat("en-GB", {
    weekday: "long",
    day: "numeric",
    month: "long",
    year: "numeric",
    timeZone,
  }).format(new Date(iso));
}

export function formatTime(iso: string | null | undefined, timeZone = "Africa/Lagos"): string {
  if (!iso) return "";
  return new Intl.DateTimeFormat("en-GB", {
    hour: "numeric",
    minute: "2-digit",
    timeZone,
  }).format(new Date(iso));
}

export function classifyIntent(question: string): Intent {
  const q = question.toLowerCase().trim();

  if (
    /\b(mark|set|flag)\b/.test(q) &&
    /\b(task|assignment|quiz|project|lab|report|it)\b/.test(q) &&
    /\b(complete|completed|done|finished|submit|submitted)\b/.test(q)
  ) {
    return "complete_task";
  }
  if (/\b(change|changed|moved|move|extended|extension|postponed|reschedul|shifted|corrected|correction|update|updated)\b/.test(q)) {
    return "changes";
  }
  if (/\b(requirement|requirements|checklist|what do i need|what's needed|whats needed|prepare for|rubric)\b/.test(q)) {
    return "requirements";
  }
  if (/\b(next deadline|deadline|deadlines|due|due date|when is|when are|how long)\b/.test(q)) {
    return "deadlines";
  }
  if (/\b(today|tomorrow|schedule|calendar|timetable|class|classes|lecture|exam|meeting|agenda)\b/.test(q)) {
    return "schedule";
  }
  if (/\b(summar|catch me up|what did i miss|brief|briefing|since yesterday|digest|overview)\b/.test(q)) {
    return "digest";
  }
  if (/\b(group|groups|class chat|chats)\b/.test(q)) {
    return "groups";
  }
  if (/\b(original|source|show me|who said|message|quote|evidence|proof)\b/.test(q)) {
    return "original";
  }
  return "search";
}

function activeTasks(snapshot: Snapshot): Task[] {
  return snapshot.tasks
    .filter((t) => ["detected", "needs_confirmation", "confirmed", "in_progress", "overdue"].includes(t.status))
    .sort((a, b) => {
      if (!a.dueAt) return 1;
      if (!b.dueAt) return -1;
      return a.dueAt.localeCompare(b.dueAt);
    });
}

function taskScore(task: Task, tokens: string[], course: string | null): number {
  let score = 0;
  const haystack = [
    task.title,
    task.description,
    task.courseCode ?? "",
    task.courseName ?? "",
    task.issuer,
    task.sourceChat,
    ...task.requirements.map((r) => r.text),
  ]
    .join(" ")
    .toLowerCase();

  for (const token of tokens) {
    if (haystack.includes(token)) score += 2;
  }
  if (course && (task.courseCode ?? "").toUpperCase().includes(course)) score += 5;
  if (task.priority === "urgent") score += 1.5;
  if (task.priority === "high") score += 1;
  if (task.status === "needs_confirmation") score -= 0.5;
  return score;
}

function messageScore(message: Message, tokens: string[], course: string | null): number {
  let score = 0;
  const haystack = `${message.content} ${message.chatName} ${message.senderName} ${message.courseCode ?? ""}`.toLowerCase();
  for (const token of tokens) {
    if (haystack.includes(token)) score += 2;
  }
  if (course && (message.courseCode ?? "").toUpperCase().includes(course)) score += 4;
  if (message.relations.some((r) => ["UPDATES", "SUPERSEDES", "CONFIRMS", "CLARIFIES", "CONTRADICTS", "CANCELS"].includes(r.type))) {
    score += 1.5;
  }
  if (message.suspicious) score -= 20;
  const ageDays = Math.max(0, (Date.now() - new Date(message.timestamp).getTime()) / DAY);
  score += Math.max(0, 1.2 - ageDays * 0.1);
  return score;
}

export function retrieveMessages(
  snapshot: Snapshot,
  question: string,
  limit = 6,
  opts: { includeSuspicious?: boolean } = {},
): Message[] {
  const tokens = tokenize(question);
  const course = detectCourseCode(question);
  return snapshot.messages
    .filter((m) => opts.includeSuspicious || !m.suspicious)
    .map((m) => ({ m, score: messageScore(m, tokens, course) }))
    .filter((x) => x.score > 0)
    .sort((a, b) => b.score - a.score)
    .slice(0, limit)
    .map((x) => x.m);
}

export function pickTask(snapshot: Snapshot, question: string): Task | null {
  const tokens = tokenize(question);
  const course = detectCourseCode(question);
  const ranked = activeTasks(snapshot)
    .map((task) => ({ task, score: taskScore(task, tokens, course) }))
    .sort((a, b) => b.score - a.score);
  return ranked[0]?.score > 0 ? ranked[0].task : null;
}

function toSources(messages: Message[], limit = 5): EvidenceSource[] {
  const seen = new Set<string>();
  const out: EvidenceSource[] = [];
  for (const m of messages) {
    if (seen.has(m.id)) continue;
    seen.add(m.id);
    const relation = m.relations[0]?.type;
    out.push({
      messageId: m.id,
      chatName: m.chatName,
      sender: m.senderName,
      timestamp: m.timestamp,
      quote: m.content.length > 260 ? `${m.content.slice(0, 257)}…` : m.content,
      relation,
    });
    if (out.length >= limit) break;
  }
  return out;
}

function sourcesForTask(snapshot: Snapshot, task: Task): Message[] {
  const ids = new Set(task.evidenceIds);
  for (const rev of task.revisions) if (rev.evidenceId) ids.add(rev.evidenceId);
  return snapshot.messages
    .filter((m) => ids.has(m.id))
    .sort((a, b) => a.timestamp.localeCompare(b.timestamp));
}

function injectionCount(snapshot: Snapshot): number {
  return snapshot.messages.filter((m) => m.suspicious || detectInjection(m.content)).length;
}

function uncertaintyFor(task: Task): string | null {
  const notes: string[] = [];
  if (!task.dueCertain) notes.push("The exact date is not fully confirmed by the source messages.");
  if (task.status === "needs_confirmation") notes.push("This item is still waiting for your confirmation.");
  if (task.missingInfo.length) notes.push(...task.missingInfo);
  return notes.length ? notes.join(" ") : null;
}

function actionFor(task: Task): JarvisAction[] {
  return [
    {
      type: "open_task",
      label: `Open ${task.courseCode ?? "task"} details`,
      taskId: task.id,
      requiresConfirmation: false,
    },
  ];
}

function emptyAnswer(
  question: string,
  intent: Intent,
  snapshot: Snapshot,
  startedAt: number,
  partial: Partial<JarvisAnswer> = {},
): JarvisAnswer {
  const injections = injectionCount(snapshot);
  return {
    question,
    headline: "I could not find grounded evidence for that in the archive.",
    sections: [
      {
        label: "Answer",
        body: "I only answer from retrieved messages and structured records. I did not find a matching item, so I am not going to guess.",
      },
    ],
    sources: [],
    uncertainty:
      "No matching source was retrieved. Try a course code, a chat name, or ask about a specific task or date range.",
    actions: [],
    mode: snapshot.mode,
    engine: "local-reasoning",
    latencyMs: Date.now() - startedAt,
    injectionAttempts: injections,
    intent,
    confidence: 0.2,
    ...partial,
  };
}

export function answerQuestion(question: string, snapshot: Snapshot): JarvisAnswer {
  const startedAt = Date.now();
  const intent = classifyIntent(question);
  const tz = snapshot.user.timezone;
  const injections = injectionCount(snapshot);
  const tokens = tokenize(question);
  const course = detectCourseCode(question);
  const active = activeTasks(snapshot);
  const now = new Date();
  const base = {
    question,
    mode: snapshot.mode,
    engine: "local-reasoning" as const,
    injectionAttempts: injections,
    intent,
    latencyMs: 0,
  };

  const injectionNote =
    injections > 0
      ? `${injections} archived message${injections === 1 ? "" : "s"} contain instruction-like text. It was treated as untrusted data, never as a command.`
      : null;

  if (intent === "complete_task") {
    const task = pickTask(snapshot, question);
    if (!task) return emptyAnswer(question, intent, snapshot, startedAt);
    return {
      ...base,
      headline: `Ready to mark “${task.title}” as completed.`,
      sections: [
        {
          label: "Answer",
          body: "Nothing has been changed yet. Confirm below and I will update the task status and log the change as a local action. Destructive or outbound actions always require explicit confirmation.",
        },
        {
          label: "What will happen",
          body: `Status: ${task.status.replace(/_/g, " ")} → completed. Evidence and revision history are preserved.`,
        },
      ],
      sources: toSources(sourcesForTask(snapshot, task)),
      uncertainty: null,
      actions: [
        {
          type: "complete_task",
          label: `Confirm: mark ${task.courseCode ?? "task"} completed`,
          taskId: task.id,
          requiresConfirmation: true,
        },
      ],
      confidence: 0.9,
      latencyMs: Date.now() - startedAt,
    };
  }

  if (intent === "changes") {
    const changed = snapshot.tasks.filter((t) => t.revisions.length > 0);
    const examMessages = snapshot.messages.filter((m) =>
      m.relations.some((r) => ["UPDATES", "SUPERSEDES", "CONTRADICTS", "CANCELS"].includes(r.type)),
    );
    const relevant = [...changed].sort((a, b) => {
      if (!course) return 0;
      return Number(b.courseCode?.includes(course)) - Number(a.courseCode?.includes(course));
    });
    const focus = course ? relevant.filter((t) => t.courseCode?.includes(course)) : relevant;
    const list = (focus.length ? focus : relevant)
      .map((t) => {
        const rev = t.revisions[t.revisions.length - 1];
        return `• ${t.title}: was ${formatWhen(rev.from, tz)}, now ${formatWhen(rev.to, tz)}. ${rev.reason}`;
      })
      .join("\n");

    const evidence = focus.length
      ? sourcesForTask(snapshot, focus[0])
      : [...examMessages, ...retrieveMessages(snapshot, question, 3)];

    const conflict = snapshot.messages.find((m) => m.relations.some((r) => r.type === "CONTRADICTS"));
    const uncertain = [
      focus.some((t) => !t.dueCertain) ? "At least one moved deadline is not independently confirmed by a second source." : null,
      conflict ? "One archived message contradicts another; the newer message was not automatically trusted." : null,
      injectionNote,
    ]
      .filter(Boolean)
      .join(" ");

    return {
      ...base,
      headline: `${(focus.length ? focus : relevant).length} dated item${(focus.length ? focus : relevant).length === 1 ? " has" : "s have"} changed, with the original value preserved.`,
      sections: [
        { label: "Answer", body: list || "No task currently has a recorded revision." },
        {
          label: "How the change was resolved",
          body: "I preserve the original announcement and the correction separately. The current value is only updated when the later message explicitly changes it. Confirmations increase confidence; jokes, questions and unverified claims do not.",
        },
        ...(course ? [{ label: "Scope", body: `Filtered to ${course}. Ask without a course code to see every change.` }] : []),
      ],
      sources: toSources(evidence, 5),
      uncertainty: uncertain || null,
      actions: focus[0]
        ? [
            {
              type: "open_task",
              label: `Open ${focus[0].title}`,
              taskId: focus[0].id,
              requiresConfirmation: false,
            },
          ]
        : [],
      confidence: 0.92,
      latencyMs: Date.now() - startedAt,
    };
  }

  if (intent === "requirements") {
    const task = pickTask(snapshot, question);
    if (!task) return emptyAnswer(question, intent, snapshot, startedAt);
    const reqs = task.requirements.length
      ? task.requirements.map((r) => `${r.done ? "✓" : "○"} ${r.text}`).join("\n")
      : "No explicit requirements were captured. I will not invent them.";
    const done = task.requirements.filter((r) => r.done).length;
    return {
      ...base,
      headline: `${task.title} — ${done}/${task.requirements.length} requirements complete.`,
      sections: [
        { label: "Answer", body: reqs },
        {
          label: "Deadline",
          body: task.dueAt
            ? `${formatDay(task.dueAt, tz)} at ${formatTime(task.dueAt, tz)} (${relativeDue(task.dueAt, now, tz)}).${task.dueCertain ? "" : " This date is not fully certain."}`
            : "No confirmed deadline is recorded.",
        },
        ...(task.missingInfo.length ? [{ label: "Still missing", body: task.missingInfo.join(" ") }] : []),
      ],
      sources: toSources(sourcesForTask(snapshot, task)),
      uncertainty: uncertaintyFor(task) || injectionNote,
      actions: actionFor(task),
      confidence: task.confidence,
      latencyMs: Date.now() - startedAt,
    };
  }

  if (intent === "deadlines") {
    let upcoming = active.filter((t) => t.dueAt && new Date(t.dueAt).getTime() > now.getTime() - DAY);
    if (course) {
      const scoped = upcoming.filter((t) => t.courseCode?.toUpperCase().includes(course));
      if (scoped.length) upcoming = scoped;
    }
    if (!upcoming.length) return emptyAnswer(question, intent, snapshot, startedAt);

    const next = upcoming[0];
    const rest = upcoming.slice(1, 5);
    const changedNote = next.revisions.length
      ? `This deadline moved from ${formatWhen(next.revisions[0].from, tz)} to ${formatWhen(next.revisions[0].to, tz)}. The original date is preserved in the revision history.`
      : null;

    return {
      ...base,
      headline: `${next.title} — ${relativeDue(next.dueAt, now, tz)} (${formatWhen(next.dueAt, tz)}).`,
      sections: [
        {
          label: "Answer",
          body: `The next open deadline is ${next.title}${next.courseCode ? ` for ${next.courseCode}` : ""}. It is due ${formatDay(next.dueAt, tz)} at ${formatTime(next.dueAt, tz)} ${tz}. Status: ${next.status.replace(/_/g, " ")}.`,
        },
        ...(changedNote ? [{ label: "Change history", body: changedNote }] : []),
        {
          label: "Also coming up",
          body:
            rest
              .map(
                (t) =>
                  `• ${t.title} — ${formatWhen(t.dueAt, tz)} (${relativeDue(t.dueAt, now, tz)})${t.dueCertain ? "" : " · date uncertain"}`,
              )
              .join("\n") || "No other open deadlines in the archive.",
        },
      ],
      sources: toSources(sourcesForTask(snapshot, next)),
      uncertainty: uncertaintyFor(next) || injectionNote,
      actions: actionFor(next),
      confidence: next.confidence,
      latencyMs: Date.now() - startedAt,
    };
  }

  if (intent === "schedule") {
    const tomorrow = /tomorrow/i.test(question);
    const offset = tomorrow ? 1 : 0;
    const dayEvents = snapshot.events.filter((e) => calendarDaysBetween(now, new Date(e.startsAt), tz) === offset);
    const dayTasks = active.filter(
      (t) => t.dueAt && calendarDaysBetween(now, new Date(t.dueAt), tz) === offset,
    );
    const scopeLabel = tomorrow ? "Tomorrow" : "Today";

    const lines = [
      ...dayEvents.map(
        (e) =>
          `• ${formatTime(e.startsAt, tz)} — ${e.title}${e.venue ? ` @ ${e.venue}` : ""}${e.status === "cancelled" ? " (cancelled)" : ""} [${e.status}]`,
      ),
      ...dayTasks.map((t) => `• ${t.title} — due ${formatTime(t.dueAt, tz)} (${t.status.replace(/_/g, " ")})`),
    ];

    const retrieved = retrieveMessages(snapshot, question, 4);
    return {
      ...base,
      headline: lines.length
        ? `${scopeLabel}: ${lines.length} item${lines.length === 1 ? "" : "s"}.`
        : `${scopeLabel} is clear — no events or deadlines found.`,
      sections: [
        { label: "Answer", body: lines.join("\n") || "Nothing scheduled in the archive for that day." },
        {
          label: "Cancellations",
          body:
            dayEvents
              .filter((e) => e.status === "cancelled")
              .map((e) => `${e.title} was cancelled.`)
              .join(" ") || "No cancellations recorded for this day.",
        },
      ],
      sources: toSources(retrieved),
      uncertainty: injectionNote,
      actions: [{ type: "open_calendar", label: "Open Chrono", requiresConfirmation: false }],
      confidence: 0.86,
      latencyMs: Date.now() - startedAt,
    };
  }

  if (intent === "digest" || intent === "groups") {
    const recent = [...snapshot.messages]
      .filter((m) => !m.suspicious)
      .sort((a, b) => b.timestamp.localeCompare(a.timestamp));
    const grouped = new Map<string, Message[]>();
    for (const m of recent) {
      if (!grouped.has(m.chatName)) grouped.set(m.chatName, []);
      grouped.get(m.chatName)!.push(m);
    }
    const groupLines = [...grouped.entries()]
      .slice(0, 6)
      .map(([chat, msgs]) => `• ${chat} — ${msgs.length} message${msgs.length === 1 ? "" : "s"}; latest: “${msgs[0].content.slice(0, 90)}${msgs[0].content.length > 90 ? "…" : ""}”`)
      .join("\n");

    const changed = snapshot.tasks.filter((t) => t.revisions.length).map((t) => `• ${t.title} → ${formatWhen(t.dueAt, tz)}`);
    const urgent = active.filter((t) => t.priority === "urgent" || t.priority === "high").slice(0, 3);

    return {
      ...base,
      headline: `Since yesterday: ${recent.length} signals, ${changed.length} deadline change${changed.length === 1 ? "" : "s"}, ${urgent.length} high-priority item${urgent.length === 1 ? "" : "s"}.`,
      sections: [
        { label: "Answer", body: groupLines || "No recent messages." },
        { label: "Changes", body: changed.join("\n") || "No deadline changes detected." },
        {
          label: "Needs attention",
          body: urgent.map((t) => `• ${t.title} — ${relativeDue(t.dueAt, now, tz)}`).join("\n") || "Nothing urgent.",
        },
        ...(injectionNote ? [{ label: "Security", body: injectionNote }] : []),
      ],
      sources: toSources(recent.slice(0, 5)),
      uncertainty: injectionNote,
      actions: [],
      confidence: 0.88,
      latencyMs: Date.now() - startedAt,
    };
  }

  const retrieved = retrieveMessages(snapshot, question, 6, {
    includeSuspicious: /secur|suspi|inject|phish|scam/i.test(question),
  });

  if (!retrieved.length) return emptyAnswer(question, intent, snapshot, startedAt);

  const top = retrieved[0];
  const others = retrieved.slice(1, 4).map((m) => `• ${m.senderName} in ${m.chatName}: “${m.content.slice(0, 110)}${m.content.length > 110 ? "…" : ""}”`);
  const taskMatch = pickTask(snapshot, question);

  return {
    ...base,
    headline: `Found ${retrieved.length} relevant message${retrieved.length === 1 ? "" : "s"} in the archive.`,
    sections: [
      {
        label: "Answer",
        body: `The strongest match is from ${top.senderName} in ${top.chatName} on ${formatWhen(top.timestamp, tz)}: “${top.content.slice(0, 220)}${top.content.length > 220 ? "…" : ""}”`,
      },
      ...(others.length ? [{ label: "Related signals", body: others.join("\n") }] : []),
      ...(taskMatch
        ? [
            {
              label: "Linked structured record",
              body: `${taskMatch.title} — ${taskMatch.dueAt ? `due ${formatWhen(taskMatch.dueAt, tz)}` : "no confirmed date"}. Status: ${taskMatch.status.replace(/_/g, " ")}.`,
            },
          ]
        : []),
    ],
    sources: toSources(retrieved),
    uncertainty:
      [injectionNote, taskMatch && !taskMatch.dueCertain ? "The linked task's date is marked uncertain." : null]
        .filter(Boolean)
        .join(" ") || null,
    actions: taskMatch ? actionFor(taskMatch) : [],
    confidence: 0.68,
    latencyMs: Date.now() - startedAt,
  };
}

/** Local-only, deterministic completion. Called by the authenticated API route. */
export function completeTask(snapshot: Snapshot, taskId: string): { ok: boolean; task?: Task } {
  const task = snapshot.tasks.find((t) => t.id === taskId);
  if (!task) return { ok: false };
  const now = new Date().toISOString();
  const previous = task.status;
  task.status = "completed";
  task.updatedAt = now;
  task.revisions.push({
    at: now,
    field: "status",
    from: previous,
    to: "completed",
    reason: "Marked completed by the owner from the cockpit.",
  });
  return { ok: true, task };
}
