import Link from "next/link";
import {
  ArrowUpRight,
  CalendarDays,
  CheckCircle2,
  Circle,
  Clock,
  MessageSquare,
  Radio,
  Sparkles,
  TriangleAlert,
  type LucideIcon,
} from "lucide-react";
import type { Connection, EventItem, Message, Task } from "@/lib/types";
import { countdownLabel, formatDateTime, formatTime, timeAgo, truncate } from "@/lib/format";
import { SourcePill, PriorityBadge, TaskStatusBadge } from "@/components/ui/status";
import { Progress, StatusDot } from "@/components/ui/primitives";
import { cn } from "@/lib/utils";

export function StatCard({
  label,
  value,
  detail,
  icon: Icon,
  tone = "cyan",
  href,
}: {
  label: string;
  value: string | number;
  detail: string;
  icon: LucideIcon;
  tone?: "cyan" | "violet" | "amber" | "rose" | "lime";
  href?: string;
}) {
  const toneMap = {
    cyan: "text-cyan border-cyan/20 bg-cyan/[0.07]",
    violet: "text-violet-300 border-violet/20 bg-violet/[0.07]",
    amber: "text-amber border-amber/20 bg-amber/[0.07]",
    rose: "text-rose border-rose/20 bg-rose/[0.07]",
    lime: "text-lime border-lime/20 bg-lime/[0.07]",
  } as const;

  const body = (
    <div className="panel panel-interactive hud-corners h-full p-4">
      <div className="flex items-start justify-between">
        <p className="eyebrow">{label}</p>
        <span className={cn("grid h-7 w-7 place-items-center rounded-lg border", toneMap[tone])}>
          <Icon className="h-3.5 w-3.5" strokeWidth={1.8} />
        </span>
      </div>
      <p className="stat-value mt-3">{value}</p>
      <p className="mt-1 text-[10px] leading-relaxed text-ink-faint">{detail}</p>
    </div>
  );

  return href ? <Link href={href}>{body}</Link> : body;
}

export function TaskRow({ task, dense = false }: { task: Task; dense?: boolean }) {
  const done = task.requirements.filter((r) => r.done).length;
  const pct = task.requirements.length ? (done / task.requirements.length) * 100 : 0;
  const overdue = task.dueAt ? new Date(task.dueAt).getTime() < Date.now() : false;

  return (
    <Link
      href={`/tasks?task=${encodeURIComponent(task.id)}`}
      className="group block rounded-xl border border-white/[0.06] bg-white/[0.015] p-3 transition-all hover:border-cyan/25 hover:bg-cyan/[0.025]"
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-1.5">
            {task.courseCode ? (
              <span className="mono rounded border border-cyan/20 bg-cyan/[0.07] px-1.5 py-0.5 text-[8px] uppercase tracking-[0.1em] text-cyan">
                {task.courseCode}
              </span>
            ) : null}
            <PriorityBadge priority={task.priority} />
          </div>
          <p className="mt-1.5 truncate text-[12.5px] font-medium text-ink group-hover:text-cyan">
            {task.title}
          </p>
          {!dense ? (
            <p className="mono mt-1 text-[9px] uppercase tracking-[0.12em] text-ink-faint">
              {task.issuer} · {task.sourceChat}
            </p>
          ) : null}
        </div>
        <TaskStatusBadge status={task.status} className="shrink-0" />
      </div>

      <div className="mt-2.5 flex items-center gap-3">
        <div className="flex-1">
          <Progress value={pct} tone={overdue ? "rose" : pct === 100 ? "lime" : "cyan"} />
        </div>
        <span className={cn("mono shrink-0 text-[9px]", overdue ? "text-rose" : "text-ink-faint")}>
          {countdownLabel(task.dueAt)}
        </span>
      </div>

      {!dense && task.requirements.length ? (
        <p className="mt-1.5 text-[9px] text-ink-faint">
          {done}/{task.requirements.length} requirements · {task.evidenceIds.length} source messages
        </p>
      ) : null}
    </Link>
  );
}

export function MessageRow({ message }: { message: Message }) {
  return (
    <Link
      href={`/inbox?message=${encodeURIComponent(message.id)}`}
      className="group flex gap-2.5 rounded-xl px-2 py-2.5 transition-colors hover:bg-white/[0.03]"
    >
      <span
        className={cn(
          "mt-1 grid h-7 w-7 shrink-0 place-items-center rounded-lg border",
          message.source === "whatsapp"
            ? "border-lime/20 bg-lime/[0.07] text-lime"
            : "border-cyan/20 bg-cyan/[0.07] text-cyan",
        )}
      >
        {message.source === "whatsapp" ? (
          <MessageSquare className="h-3 w-3" />
        ) : (
          <Radio className="h-3 w-3" />
        )}
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-1.5">
          <p className="truncate text-[11px] font-medium text-ink">{message.senderName}</p>
          <SourcePill source={message.source} />
          <span className="mono ml-auto shrink-0 text-[8px] uppercase tracking-[0.1em] text-ink-faint">
            {timeAgo(message.timestamp)}
          </span>
        </div>
        <p className="mt-0.5 truncate text-[10px] text-ink-faint">{message.chatName}</p>
        <p className="mt-1 line-clamp-2 text-[11px] leading-relaxed text-ink-dim">
          {truncate(message.content, 150)}
        </p>
        {message.relations.length ? (
          <p className="mono mt-1 text-[8px] uppercase tracking-[0.1em] text-violet-300/70">
            {message.relations.map((r) => r.type).join(" · ")}
          </p>
        ) : null}
      </div>
    </Link>
  );
}

export function EventRow({ event }: { event: EventItem }) {
  const cancelled = event.status === "cancelled";
  return (
    <div className="flex items-start gap-3 rounded-lg px-1 py-2">
      <div className="mono w-12 shrink-0 pt-0.5 text-right text-[10px] text-ink-faint">
        {event.allDay ? "ALL" : formatTime(event.startsAt)}
      </div>
      <div
        className={cn(
          "w-0.5 shrink-0 self-stretch rounded-full",
          cancelled ? "bg-rose/50" : event.type === "exam" ? "bg-amber/70" : event.type === "meeting" ? "bg-violet/70" : "bg-cyan/60",
        )}
      />
      <div className="min-w-0 flex-1">
        <p className={cn("truncate text-[11px] font-medium", cancelled ? "text-ink-faint line-through" : "text-ink")}>
          {event.title}
        </p>
        <div className="mt-0.5 flex items-center gap-1.5">
          {event.courseCode ? <span className="mono text-[8px] uppercase tracking-[0.1em] text-ink-faint">{event.courseCode}</span> : null}
          {event.venue ? <span className="truncate text-[9px] text-ink-faint">· {event.venue}</span> : null}
          {cancelled ? <span className="chip chip-rose px-1.5 py-0 text-[8px]">cancelled</span> : null}
          {event.status === "confirmed" ? <CheckCircle2 className="h-2.5 w-2.5 text-lime/70" /> : null}
        </div>
      </div>
    </div>
  );
}

export function Vitals({ connections }: { connections: Connection[] }) {
  return (
    <div className="space-y-2.5">
      {connections.map((connection) => {
        const ok = connection.status === "connected" || connection.status === "demo";
        return (
          <div key={connection.id} className="flex items-start gap-2.5">
            <StatusDot tone={ok ? (connection.status === "demo" ? "amber" : "lime") : connection.status === "degraded" ? "amber" : "slate"} className="mt-1.5" />
            <div className="min-w-0 flex-1">
              <div className="flex items-center justify-between gap-2">
                <p className="truncate text-[11px] text-ink-dim">{connection.name}</p>
                <span className="mono shrink-0 text-[8px] uppercase tracking-[0.1em] text-ink-faint">
                  {connection.status === "unconfigured" ? "offline" : connection.status}
                </span>
              </div>
              <p className="mt-0.5 truncate text-[9px] text-ink-faint">{connection.detail}</p>
            </div>
          </div>
        );
      })}
    </div>
  );
}

export function ActivityChart({ data }: { data: { label: string; messages: number; tasks: number }[] }) {
  const max = Math.max(1, ...data.map((d) => d.messages));
  return (
    <div>
      <div className="flex items-end gap-2" style={{ height: 92 }}>
        {data.map((point) => {
          const height = Math.max(6, (point.messages / max) * 72);
          const taskHeight = Math.max(2, (point.tasks / max) * 72);
          return (
            <div key={point.label} className="group flex flex-1 flex-col items-center justify-end gap-1">
              <div className="relative flex w-full items-end justify-center gap-0.5">
                <div
                  className="w-1/3 rounded-t bg-gradient-to-t from-cyan/20 to-cyan/80 transition-all group-hover:from-cyan/40"
                  style={{ height }}
                />
                <div
                  className="w-1/3 rounded-t bg-gradient-to-t from-violet/20 to-violet/70 transition-all"
                  style={{ height: taskHeight * 2.6 }}
                />
              </div>
              <span className="mono text-[8px] uppercase text-ink-faint">{point.label}</span>
            </div>
          );
        })}
      </div>
      <div className="mt-3 flex items-center gap-4">
        <span className="mono flex items-center gap-1.5 text-[8px] uppercase tracking-[0.1em] text-ink-faint">
          <span className="h-1.5 w-1.5 rounded-full bg-cyan" /> messages
        </span>
        <span className="mono flex items-center gap-1.5 text-[8px] uppercase tracking-[0.1em] text-ink-faint">
          <span className="h-1.5 w-1.5 rounded-full bg-violet" /> extractions
        </span>
        <span className="ml-auto mono text-[8px] uppercase tracking-[0.1em] text-ink-faint">7d</span>
      </div>
    </div>
  );
}

export function BriefPoints({ brief }: { brief: { points: string[]; changed: string[]; needsAttention: string[] } }) {
  return (
    <div className="space-y-2">
      {brief.points.map((point) => (
        <div key={point} className="flex items-start gap-2">
          <span className="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-cyan/70" />
          <p className="text-[11px] leading-relaxed text-ink-dim">{point}</p>
        </div>
      ))}
    </div>
  );
}

export function AttentionList({ tasks }: { tasks: Task[] }) {
  if (!tasks.length) {
    return (
      <div className="flex items-center gap-2 rounded-xl border border-lime/15 bg-lime/[0.04] p-3 text-[11px] text-lime/90">
        <CheckCircle2 className="h-4 w-4" />
        Nothing urgent. The horizon is clear.
      </div>
    );
  }
  return (
    <div className="space-y-1.5">
      {tasks.slice(0, 3).map((task) => (
        <Link
          key={task.id}
          href={`/tasks?task=${encodeURIComponent(task.id)}`}
          className="flex items-center gap-2.5 rounded-lg border border-white/[0.05] px-2.5 py-2 transition-colors hover:border-amber/20 hover:bg-amber/[0.03]"
        >
          <TriangleAlert className={cn("h-3.5 w-3.5 shrink-0", task.priority === "urgent" ? "text-rose" : "text-amber")} />
          <span className="min-w-0 flex-1 truncate text-[11px] text-ink-dim">{task.title}</span>
          <span className="mono shrink-0 text-[9px] text-ink-faint">{countdownLabel(task.dueAt)}</span>
        </Link>
      ))}
    </div>
  );
}

export function MiniAgenda({ events }: { events: EventItem[] }) {
  if (!events.length) {
    return (
      <div className="flex items-center gap-2 py-6 text-[11px] text-ink-faint">
        <CalendarDays className="h-4 w-4" />
        No events left today.
      </div>
    );
  }
  return (
    <div className="space-y-1">
      {events.map((event) => (
        <EventRow key={event.id} event={event} />
      ))}
    </div>
  );
}

export function QuickLink({ href, label, detail }: { href: string; label: string; detail: string }) {
  return (
    <Link href={href} className="group flex items-center gap-2 rounded-lg px-2 py-2 transition-colors hover:bg-white/[0.03]">
      <Circle className="h-2 w-2 text-cyan/60" />
      <div className="min-w-0 flex-1">
        <p className="text-[11px] text-ink-dim group-hover:text-ink">{label}</p>
        <p className="truncate text-[9px] text-ink-faint">{detail}</p>
      </div>
      <ArrowUpRight className="h-3 w-3 text-ink-faint opacity-0 transition-opacity group-hover:opacity-100" />
    </Link>
  );
}

export function HeaderStat({ icon: Icon, label, value }: { icon: LucideIcon; label: string; value: string | number }) {
  return (
    <div className="flex items-center gap-2">
      <Icon className="h-3.5 w-3.5 text-cyan/70" />
      <div>
        <p className="mono text-[8px] uppercase tracking-[0.12em] text-ink-faint">{label}</p>
        <p className="text-[11px] text-ink">{value}</p>
      </div>
    </div>
  );
}

export function SectionEmpty({ icon: Icon = Clock, text }: { icon?: LucideIcon; text: string }) {
  return (
    <div className="flex items-center gap-2 px-2 py-6 text-[11px] text-ink-faint">
      <Icon className="h-3.5 w-3.5" />
      {text}
    </div>
  );
}

export { Sparkles };
