"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  AlertTriangle,
  ArrowUpRight,
  CalendarClock,
  Check,
  CheckCircle2,
  ChevronDown,
  Circle,
  Clock,
  FileText,
  Filter,
  GitBranch,
  History,
  Loader2,
  Quote,
  Search,
  Sparkles,
  X,
} from "lucide-react";
import type { Message, Task, TaskStatus } from "@/lib/types";
import { countdownLabel, formatDateTime, formatDayLong } from "@/lib/format";
import { PriorityBadge, TaskStatusBadge } from "@/components/ui/status";
import { Progress } from "@/components/ui/primitives";
import { cn } from "@/lib/utils";

type FilterKey = "open" | "needs_confirmation" | "completed" | "all";

const FILTERS: { key: FilterKey; label: string }[] = [
  { key: "open", label: "Open" },
  { key: "needs_confirmation", label: "Needs confirmation" },
  { key: "completed", label: "Completed" },
  { key: "all", label: "All" },
];

function TaskCard({ task, onOpen }: { task: Task; onOpen: () => void }) {
  const done = task.requirements.filter((r) => r.done).length;
  const pct = task.requirements.length ? (done / task.requirements.length) * 100 : 0;
  const moved = task.revisions.some((r) => r.field === "due_at");

  return (
    <button
      onClick={onOpen}
      className="panel panel-interactive w-full p-4 text-left"
    >
      <div className="flex items-start justify-between gap-2">
        <div className="flex flex-wrap items-center gap-1.5">
          {task.courseCode ? (
            <span className="mono rounded border border-cyan/20 bg-cyan/[0.07] px-1.5 py-0.5 text-[8px] uppercase tracking-[0.1em] text-cyan">
              {task.courseCode}
            </span>
          ) : (
            <span className="mono rounded border border-white/10 bg-white/[0.04] px-1.5 py-0.5 text-[8px] uppercase tracking-[0.1em] text-ink-faint">
              personal
            </span>
          )}
          <PriorityBadge priority={task.priority} />
          {moved ? (
            <span className="chip chip-amber px-1.5 py-0 text-[8px]">
              <GitBranch className="h-2.5 w-2.5" /> moved
            </span>
          ) : null}
        </div>
        <TaskStatusBadge status={task.status} />
      </div>

      <h3 className="mt-2 text-sm font-medium leading-snug text-ink">{task.title}</h3>
      <p className="mt-1 line-clamp-2 text-[11px] leading-relaxed text-ink-faint">{task.description}</p>

      <div className="mt-3 flex items-center gap-2">
        <Progress value={pct} tone={pct === 100 ? "lime" : task.priority === "urgent" ? "rose" : "cyan"} />
        <span className="mono shrink-0 text-[9px] text-ink-faint">
          {done}/{task.requirements.length}
        </span>
      </div>

      <div className="mt-3 flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-1.5 text-[10px] text-ink-dim">
          <CalendarClock className="h-3 w-3 text-cyan/60" />
          <span>{formatDateTime(task.dueAt)}</span>
        </div>
        <span
          className={cn(
            "mono text-[9px] uppercase tracking-[0.08em]",
            countdownLabel(task.dueAt).includes("overdue") ? "text-rose" : "text-ink-faint",
          )}
        >
          {countdownLabel(task.dueAt)}
        </span>
      </div>

      <div className="mt-2.5 flex items-center justify-between border-t border-white/[0.05] pt-2 text-[9px] text-ink-faint">
        <span className="truncate">{task.issuer} · {task.sourceChat}</span>
        <span className="mono flex shrink-0 items-center gap-1">
          <FileText className="h-2.5 w-2.5" /> {task.evidenceIds.length}
        </span>
      </div>
    </button>
  );
}

function TaskDrawer({
  task,
  messages,
  onClose,
  onUpdate,
  onToggleRequirement,
  busy,
}: {
  task: Task;
  messages: Message[];
  onClose: () => void;
  onUpdate: (status: TaskStatus) => void;
  onToggleRequirement: (index: number) => void;
  busy: boolean;
}) {
  const [showAllEvidence, setShowAllEvidence] = useState(false);
  const evidence = task.evidenceIds
    .map((id) => messages.find((m) => m.id === id))
    .filter((m): m is Message => Boolean(m));
  const shown = showAllEvidence ? evidence : evidence.slice(0, 3);
  const done = task.requirements.filter((r) => r.done).length;
  const pct = task.requirements.length ? (done / task.requirements.length) * 100 : 0;
  const dueRevision = task.revisions.filter((r) => r.field === "due_at").slice(-1)[0];

  return (
    <div className="fixed inset-0 z-[85] flex justify-end">
      <div className="absolute inset-0 bg-black/70 backdrop-blur-sm" onClick={onClose} aria-hidden />
      <aside className="relative flex h-full w-full max-w-[560px] flex-col border-l border-cyan/10 bg-[#000d1a] shadow-2xl animate-rise">
        <div className="flex items-start justify-between gap-3 border-b border-white/[0.07] p-4">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-1.5">
              {task.courseCode ? <span className="chip chip-cyan px-1.5 py-0 text-[8px]">{task.courseCode}</span> : null}
              <PriorityBadge priority={task.priority} />
              <TaskStatusBadge status={task.status} />
            </div>
            <h2 className="mt-2 text-base font-medium leading-snug text-ink">{task.title}</h2>
            <p className="mono mt-1 text-[9px] uppercase tracking-[0.12em] text-ink-faint">
              {task.issuer} · {task.sourceChat} · confidence {Math.round(task.confidence * 100)}%
            </p>
          </div>
          <button onClick={onClose} className="rounded-lg p-1.5 text-ink-faint hover:bg-white/5 hover:text-ink" aria-label="Close">
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="scroll-area flex-1 overflow-y-auto p-4">
          <div className="rounded-xl border border-cyan/15 bg-cyan/[0.04] p-3">
            <div className="flex items-center justify-between">
              <p className="mono flex items-center gap-1.5 text-[9px] uppercase tracking-[0.14em] text-cyan/80">
                <Clock className="h-3 w-3" /> Current deadline
              </p>
              <span className="mono text-[9px] text-ink-faint">{countdownLabel(task.dueAt)}</span>
            </div>
            <p className="mt-1.5 text-sm font-medium text-ink">{formatDayLong(task.dueAt)}</p>
            <p className="mt-0.5 text-[10px] text-ink-faint">
              {task.dueCertain ? "Confirmed by the source messages." : "Date is marked uncertain."}
            </p>
            {dueRevision ? (
              <div className="mt-2 flex items-start gap-2 rounded-lg border border-amber/20 bg-amber/[0.06] p-2">
                <GitBranch className="mt-0.5 h-3 w-3 shrink-0 text-amber" />
                <div>
                  <p className="text-[9px] font-medium uppercase tracking-[0.12em] text-amber">Deadline changed</p>
                  <p className="mt-0.5 text-[10px] leading-relaxed text-amber/85">
                    Originally {formatDateTime(dueRevision.from)}. {dueRevision.reason}
                  </p>
                </div>
              </div>
            ) : null}
          </div>

          <div className="mt-4">
            <p className="eyebrow mb-2">Brief</p>
            <p className="text-[12px] leading-relaxed text-ink-dim">{task.description}</p>
          </div>

          <div className="mt-4">
            <div className="mb-2 flex items-center justify-between">
              <p className="eyebrow">Requirements</p>
              <span className="mono text-[9px] text-ink-faint">{done}/{task.requirements.length}</span>
            </div>
            <Progress value={pct} tone={pct === 100 ? "lime" : "cyan"} className="mb-2.5" />
            <div className="space-y-1.5">
              {task.requirements.map((requirement, index) => (
                <button
                  key={`${requirement.text}-${index}`}
                  onClick={() => onToggleRequirement(index)}
                  disabled={busy}
                  className="flex w-full items-start gap-2.5 rounded-lg border border-white/[0.06] px-2.5 py-2 text-left transition-colors hover:border-cyan/25 hover:bg-cyan/[0.03] disabled:opacity-60"
                >
                  {requirement.done ? (
                    <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0 text-lime" />
                  ) : (
                    <Circle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-ink-faint" />
                  )}
                  <span className={cn("text-[11px] leading-relaxed", requirement.done ? "text-ink-faint line-through" : "text-ink-dim")}>
                    {requirement.text}
                  </span>
                </button>
              ))}
              {task.requirements.length === 0 ? (
                <p className="rounded-lg border border-dashed border-white/10 p-3 text-[10px] text-ink-faint">
                  No explicit requirements were captured. Jarvis will not invent them.
                </p>
              ) : null}
            </div>
          </div>

          {task.missingInfo.length ? (
            <div className="mt-4 rounded-xl border border-amber/20 bg-amber/[0.05] p-3">
              <p className="flex items-center gap-1.5 text-[9px] font-medium uppercase tracking-[0.12em] text-amber">
                <AlertTriangle className="h-3 w-3" /> Open questions
              </p>
              <ul className="mt-1.5 space-y-1">
                {task.missingInfo.map((info) => (
                  <li key={info} className="text-[10px] leading-relaxed text-amber/85">• {info}</li>
                ))}
              </ul>
            </div>
          ) : null}

          <div className="mt-5">
            <div className="mb-2 flex items-center justify-between">
              <p className="eyebrow flex items-center gap-1.5"><History className="h-3 w-3" /> Revision history</p>
              <span className="mono text-[9px] text-ink-faint">{task.revisions.length}</span>
            </div>
            {task.revisions.length ? (
              <div className="space-y-2 border-l border-white/10 pl-3">
                {task.revisions.map((revision, index) => (
                  <div key={`${revision.at}-${index}`} className="relative">
                    <span className="absolute -left-[17px] top-1 h-2 w-2 rounded-full border border-cyan/60 bg-[#000d1a]" />
                    <p className="mono text-[8px] uppercase tracking-[0.1em] text-ink-faint">{formatDateTime(revision.at)}</p>
                    <p className="mt-0.5 text-[10px] text-ink-dim">
                      <span className="capitalize">{revision.field.replace("_", " ")}</span>
                      {revision.from ? `: ${formatDateTime(revision.from)}` : ""}
                      {revision.to ? ` → ${formatDateTime(revision.to)}` : ""}
                    </p>
                    <p className="text-[10px] leading-relaxed text-ink-faint">{revision.reason}</p>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-[10px] text-ink-faint">No changes recorded. Original values remain intact.</p>
            )}
          </div>

          <div className="mt-5">
            <div className="mb-2 flex items-center justify-between">
              <p className="eyebrow flex items-center gap-1.5"><Quote className="h-3 w-3" /> Evidence</p>
              <span className="mono text-[9px] text-ink-faint">{evidence.length} messages</span>
            </div>
            <div className="space-y-2">
              {shown.map((message) => (
                <div key={message.id} className="rounded-lg border border-white/[0.06] bg-black/20 p-2.5">
                  <p className="text-[10px] font-medium text-ink">{message.senderName}</p>
                  <p className="mono text-[8px] uppercase tracking-[0.1em] text-ink-faint">
                    {formatDateTime(message.timestamp)} · {message.chatName}
                  </p>
                  <p className="mt-1.5 text-[10px] leading-relaxed text-ink-dim">{message.content}</p>
                </div>
              ))}
              {evidence.length > 3 ? (
                <button
                  onClick={() => setShowAllEvidence((value) => !value)}
                  className="flex w-full items-center justify-center gap-1 text-[10px] text-cyan/80 hover:text-cyan"
                >
                  <ChevronDown className={cn("h-3 w-3 transition-transform", showAllEvidence && "rotate-180")} />
                  {showAllEvidence ? "Show less" : `Show ${evidence.length - 3} more`}
                </button>
              ) : null}
              {evidence.length === 0 ? (
                <p className="text-[10px] text-ink-faint">No linked messages. This may be a personal task.</p>
              ) : null}
            </div>
          </div>
        </div>

        <div className="border-t border-white/[0.07] p-3">
          <div className="flex flex-wrap gap-1.5">
            {task.status !== "completed" ? (
              <button onClick={() => onUpdate("completed")} disabled={busy} className="btn-primary flex-1 text-[11px]">
                {busy ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Check className="h-3.5 w-3.5" />}
                Mark completed
              </button>
            ) : (
              <button onClick={() => onUpdate("in_progress")} disabled={busy} className="btn-ghost flex-1 text-[11px]">
                Reopen task
              </button>
            )}
            {task.status === "needs_confirmation" ? (
              <button onClick={() => onUpdate("confirmed")} disabled={busy} className="btn-ghost text-[11px]">
                <CheckCircle2 className="h-3.5 w-3.5" /> Confirm
              </button>
            ) : null}
            {task.status !== "in_progress" && task.status !== "completed" ? (
              <button onClick={() => onUpdate("in_progress")} disabled={busy} className="btn-ghost text-[11px]">
                Start
              </button>
            ) : null}
            <Link
              href={`/ask?q=${encodeURIComponent(`Explain the requirements and deadline for ${task.title}`)}`}
              className="btn-ghost text-[11px]"
            >
              <Sparkles className="h-3.5 w-3.5" /> Ask
            </Link>
          </div>
        </div>
      </aside>
    </div>
  );
}

export function TasksBoard({
  tasks: initialTasks,
  messages,
  initialTaskId,
}: {
  tasks: Task[];
  messages: Message[];
  initialTaskId?: string;
}) {
  const router = useRouter();
  const [tasks, setTasks] = useState(initialTasks);
  const [filter, setFilter] = useState<FilterKey>("open");
  const [query, setQuery] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(initialTaskId ?? null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const selected = tasks.find((t) => t.id === selectedId) ?? null;

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return tasks
      .filter((task) => {
        if (filter === "open" && !["detected", "needs_confirmation", "confirmed", "in_progress", "overdue"].includes(task.status)) return false;
        if (filter === "needs_confirmation" && task.status !== "needs_confirmation") return false;
        if (filter === "completed" && task.status !== "completed") return false;
        if (q) {
          const haystack = `${task.title} ${task.description} ${task.courseCode ?? ""} ${task.issuer}`.toLowerCase();
          if (!haystack.includes(q)) return false;
        }
        return true;
      })
      .sort((a, b) => {
        if (a.status === "completed" && b.status !== "completed") return 1;
        if (b.status === "completed" && a.status !== "completed") return -1;
        return (a.dueAt ?? "9999").localeCompare(b.dueAt ?? "9999");
      });
  }, [tasks, filter, query]);

  const persist = async (payload: { id: string; status?: TaskStatus; requirementIndex?: number }) => {
    setBusy(true);
    setError(null);
    try {
      const response = await fetch("/api/tasks", {
        method: "PATCH",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(payload),
      });
      const body = (await response.json()) as { ok: boolean; task?: Task; error?: string };
      if (!response.ok || !body.ok || !body.task) throw new Error(body.error ?? "Update failed.");
      setTasks((prev) => prev.map((task) => (task.id === body.task!.id ? body.task! : task)));
      router.refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Update failed.");
    } finally {
      setBusy(false);
    }
  };

  const counts = {
    open: tasks.filter((t) => ["detected", "needs_confirmation", "confirmed", "in_progress", "overdue"].includes(t.status)).length,
    needs_confirmation: tasks.filter((t) => t.status === "needs_confirmation").length,
    completed: tasks.filter((t) => t.status === "completed").length,
    all: tasks.length,
  };

  const dueThisWeek = tasks.filter((task) => {
    if (!["detected", "needs_confirmation", "confirmed", "in_progress"].includes(task.status) || !task.dueAt) return false;
    const diff = new Date(task.dueAt).getTime() - Date.now();
    return diff >= 0 && diff < 7 * 86_400_000;
  }).length;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="eyebrow">Academic command</p>
          <h1 className="display mt-1 text-xl font-semibold text-ink">Mission queue</h1>
          <p className="mt-1 text-xs text-ink-faint">
            Tasks, deadlines and revisions. Original announcements are never overwritten.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <span className="chip chip-cyan">{counts.open} open</span>
          <span className="chip chip-amber">{dueThisWeek} due this week</span>
          <span className="chip chip-violet">{tasks.reduce((n, t) => n + t.revisions.length, 0)} revisions</span>
        </div>
      </div>

      {error ? (
        <div className="rounded-xl border border-rose/25 bg-rose/[0.07] px-3 py-2 text-[11px] text-rose">{error}</div>
      ) : null}

      <div className="flex flex-wrap items-center gap-2">
        <div className="flex flex-wrap gap-1 rounded-xl border border-white/[0.07] bg-white/[0.02] p-1">
          {FILTERS.map((item) => (
            <button
              key={item.key}
              onClick={() => setFilter(item.key)}
              className={cn(
                "rounded-lg px-2.5 py-1.5 text-[10px] font-medium transition-colors",
                filter === item.key ? "bg-cyan/15 text-cyan" : "text-ink-faint hover:text-ink-dim",
              )}
            >
              {item.label}
              <span className="mono ml-1.5 opacity-60">{counts[item.key]}</span>
            </button>
          ))}
        </div>
        <div className="relative ml-auto min-w-[180px] flex-1 sm:max-w-xs">
          <Search className="absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-ink-faint" />
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Filter tasks…"
            className="field h-9 py-0 pl-8 text-xs"
          />
        </div>
      </div>

      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        {filtered.map((task) => (
          <TaskCard key={task.id} task={task} onOpen={() => setSelectedId(task.id)} />
        ))}
      </div>

      {filtered.length === 0 ? (
        <div className="panel flex flex-col items-center gap-2 px-6 py-16 text-center">
          <CheckCircle2 className="h-6 w-6 text-lime/60" />
          <p className="text-sm text-ink">No tasks in this view.</p>
          <p className="text-[11px] text-ink-faint">
            Try a different filter, or ask Jarvis which assignments are pending.
          </p>
          <Link href="/ask?q=What%20assignments%20do%20I%20have%3F" className="btn-ghost mt-2 text-[11px]">
            <Sparkles className="h-3.5 w-3.5" /> Ask Jarvis
          </Link>
        </div>
      ) : null}

      {selected ? (
        <TaskDrawer
          task={selected}
          messages={messages}
          busy={busy}
          onClose={() => setSelectedId(null)}
          onUpdate={(status) => {
            if (status === "completed" && !window.confirm(`Mark “${selected.title}” completed?`)) return;
            void persist({ id: selected.id, status });
          }}
          onToggleRequirement={(index) => void persist({ id: selected.id, requirementIndex: index })}
        />
      ) : null}
    </div>
  );
}
