"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import {
  CalendarDays,
  ChevronLeft,
  ChevronRight,
  Clock,
  MapPin,
  Sparkles,
} from "lucide-react";
import type { EventItem, Task } from "@/lib/types";
import { formatDateTime, formatTime } from "@/lib/format";
import { cn } from "@/lib/utils";

const START_HOUR = 7;
const END_HOUR = 22;
const HOUR_PX = 58;
const ALL_DAY_PX = 40;

function dayKey(iso: string, timeZone: string): string {
  return new Intl.DateTimeFormat("en-CA", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    timeZone,
  }).format(new Date(iso));
}

function hourMinute(iso: string, timeZone: string): { hour: number; minute: number } {
  const parts = new Intl.DateTimeFormat("en-GB", {
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
    timeZone,
  }).formatToParts(new Date(iso));
  return {
    hour: Number(parts.find((p) => p.type === "hour")?.value ?? 0),
    minute: Number(parts.find((p) => p.type === "minute")?.value ?? 0),
  };
}

const TYPE_STYLE: Record<EventItem["type"], string> = {
  lecture: "border-cyan/35 bg-cyan/[0.1] text-cyan",
  exam: "border-amber/40 bg-amber/[0.12] text-amber",
  meeting: "border-violet/40 bg-violet/[0.12] text-violet-300",
  deadline: "border-rose/40 bg-rose/[0.12] text-rose",
  reminder: "border-lime/30 bg-lime/[0.08] text-lime",
  other: "border-white/15 bg-white/[0.05] text-ink-dim",
};

export function WeekView({
  events,
  tasks,
  timezone,
  todayKey,
}: {
  events: EventItem[];
  tasks: Task[];
  timezone: string;
  todayKey: string;
}) {
  const [offset, setOffset] = useState(0);

  const week = useMemo(() => {
    const anchor = new Date(`${todayKey}T12:00:00Z`);
    const mondayIndex = (anchor.getUTCDay() + 6) % 7;
    const start = new Date(anchor);
    start.setUTCDate(anchor.getUTCDate() - mondayIndex + offset * 7);
    return Array.from({ length: 7 }, (_, index) => {
      const date = new Date(start);
      date.setUTCDate(start.getUTCDate() + index);
      return {
        date,
        key: date.toISOString().slice(0, 10),
        weekday: new Intl.DateTimeFormat("en-GB", { weekday: "short", timeZone: "UTC" }).format(date),
        day: new Intl.DateTimeFormat("en-GB", { day: "numeric", timeZone: "UTC" }).format(date),
        month: new Intl.DateTimeFormat("en-GB", { month: "short", timeZone: "UTC" }).format(date),
      };
    });
  }, [offset, todayKey]);

  const weekLabel = `${week[0].day} ${week[0].month} — ${week[6].day} ${week[6].month}`;
  const eventsByDay = useMemo(() => {
    const map = new Map<string, EventItem[]>();
    for (const event of events) {
      const key = dayKey(event.startsAt, timezone);
      const list = map.get(key) ?? [];
      list.push(event);
      map.set(key, list);
    }
    return map;
  }, [events, timezone]);

  const tasksByDay = useMemo(() => {
    const map = new Map<string, Task[]>();
    for (const task of tasks) {
      if (!task.dueAt || task.status === "completed" || task.status === "cancelled") continue;
      const key = dayKey(task.dueAt, timezone);
      const list = map.get(key) ?? [];
      list.push(task);
      map.set(key, list);
    }
    return map;
  }, [tasks, timezone]);

  const nowParts = hourMinute(new Date().toISOString(), timezone);
  const showNowLine = week.some((day) => day.key === todayKey);

  const upcoming = useMemo(() => {
    const inWeek = events.filter((event) => week.some((day) => day.key === dayKey(event.startsAt, timezone)));
    return [...inWeek].sort((a, b) => a.startsAt.localeCompare(b.startsAt));
  }, [events, timezone, week]);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="eyebrow">Chrono</p>
          <h1 className="display mt-1 text-xl font-semibold text-ink">Schedule</h1>
          <p className="mt-1 text-xs text-ink-faint">
            {timezone} · confirmed classes, meetings, exams and deadlines.
          </p>
        </div>
        <div className="flex items-center gap-1.5">
          <button onClick={() => setOffset((o) => o - 1)} className="btn-ghost h-8 w-8 p-0" aria-label="Previous week">
            <ChevronLeft className="h-4 w-4" />
          </button>
          <span className="mono min-w-[150px] text-center text-[10px] uppercase tracking-[0.14em] text-ink-dim">
            {weekLabel}
          </span>
          <button onClick={() => setOffset((o) => o + 1)} className="btn-ghost h-8 w-8 p-0" aria-label="Next week">
            <ChevronRight className="h-4 w-4" />
          </button>
          <button onClick={() => setOffset(0)} className="btn-ghost h-8 px-2.5 text-[10px]">Today</button>
        </div>
      </div>

      <div className="panel overflow-hidden">
        <div className="overflow-x-auto scroll-area">
          <div className={cn("min-w-[900px]")}>
            <div className="grid" style={{ gridTemplateColumns: "56px repeat(7, minmax(0,1fr))" }}>
              <div className="border-b border-r border-white/[0.06]" />
              {week.map((day) => {
                const isToday = day.key === todayKey;
                const deadlines = tasksByDay.get(day.key) ?? [];
                return (
                  <div
                    key={day.key}
                    className={cn(
                      "border-b border-r border-white/[0.06] px-2 py-2 text-center",
                      isToday && "bg-cyan/[0.045]",
                    )}
                  >
                    <p className={cn("mono text-[8px] uppercase tracking-[0.16em]", isToday ? "text-cyan" : "text-ink-faint")}>
                      {day.weekday}
                    </p>
                    <p className={cn("display text-lg font-semibold leading-tight", isToday ? "text-ink" : "text-ink-dim")}>
                      {day.day}
                    </p>
                    <p className="mono text-[8px] uppercase text-ink-faint">
                      {deadlines.length ? `${deadlines.length} due` : day.month}
                    </p>
                  </div>
                );
              })}
            </div>

            <div className="grid" style={{ gridTemplateColumns: "56px repeat(7, minmax(0,1fr))" }}>
              <div className="relative border-r border-white/[0.06]" style={{ height: (END_HOUR - START_HOUR) * HOUR_PX + ALL_DAY_PX }}>
                <div style={{ height: ALL_DAY_PX }} className="border-b border-white/[0.06]" />
                {Array.from({ length: END_HOUR - START_HOUR }, (_, index) => (
                  <div
                    key={index}
                    className="absolute right-1.5 -translate-y-1/2 text-right"
                    style={{ top: ALL_DAY_PX + index * HOUR_PX }}
                  >
                    <span className="mono text-[8px] text-ink-faint">{String(START_HOUR + index).padStart(2, "0")}:00</span>
                  </div>
                ))}
              </div>

              {week.map((day) => {
                const dayEvents = (eventsByDay.get(day.key) ?? []).filter((event) => !event.allDay);
                const allDayEvents = (eventsByDay.get(day.key) ?? []).filter((event) => event.allDay);
                const deadlines = tasksByDay.get(day.key) ?? [];
                const isToday = day.key === todayKey;
                return (
                  <div
                    key={day.key}
                    className={cn(
                      "relative border-r border-white/[0.06]",
                      isToday && "bg-cyan/[0.02]",
                    )}
                    style={{ height: (END_HOUR - START_HOUR) * HOUR_PX + ALL_DAY_PX }}
                  >
                    <div
                      className="flex flex-wrap gap-0.5 overflow-hidden border-b border-white/[0.06] p-1"
                      style={{ height: ALL_DAY_PX }}
                    >
                      {[...allDayEvents.map((e) => ({ label: e.title, key: e.id })), ...deadlines.map((t) => ({ label: t.title.replace(/^.*?—\s*/, ""), key: t.id }))]
                        .slice(0, 2)
                        .map((item) => (
                          <span key={item.key} className="truncate rounded border border-rose/25 bg-rose/[0.09] px-1 py-0.5 text-[7px] text-rose/90">
                            {item.label}
                          </span>
                        ))}
                      {allDayEvents.length + deadlines.length > 2 ? (
                        <span className="mono text-[7px] text-ink-faint">+{allDayEvents.length + deadlines.length - 2}</span>
                      ) : null}
                    </div>

                    {Array.from({ length: END_HOUR - START_HOUR }, (_, index) => (
                      <div
                        key={index}
                        className="absolute left-0 right-0 border-t border-white/[0.035]"
                        style={{ top: ALL_DAY_PX + index * HOUR_PX }}
                      />
                    ))}

                    {isToday && nowParts.hour >= START_HOUR && nowParts.hour < END_HOUR ? (
                      <div
                        className="absolute left-0 right-0 z-20 flex items-center"
                        style={{ top: ALL_DAY_PX + (nowParts.hour - START_HOUR) * HOUR_PX + (nowParts.minute / 60) * HOUR_PX }}
                      >
                        <span className="h-1.5 w-1.5 rounded-full bg-rose shadow-[0_0_8px_rgba(251,113,133,0.8)]" />
                        <span className="h-px flex-1 bg-rose/50" />
                      </div>
                    ) : null}

                    {dayEvents.map((event) => {
                      const { hour, minute } = hourMinute(event.startsAt, timezone);
                      const start = hour + minute / 60;
                      const durationHours = event.endsAt
                        ? (new Date(event.endsAt).getTime() - new Date(event.startsAt).getTime()) / 3_600_000
                        : 1;
                      const top = ALL_DAY_PX + Math.max(0, (start - START_HOUR) * HOUR_PX);
                      const height = Math.max(26, durationHours * HOUR_PX - 3);
                      if (start < START_HOUR || start >= END_HOUR) return null;
                      return (
                        <div
                          key={event.id}
                          title={`${event.title} · ${formatTime(event.startsAt, timezone)}${event.venue ? ` · ${event.venue}` : ""}`}
                          className={cn(
                            "absolute left-1 right-1 z-10 overflow-hidden rounded-md border px-1.5 py-1",
                            TYPE_STYLE[event.type],
                            event.status === "cancelled" && "opacity-50",
                          )}
                          style={{ top, height }}
                        >
                          <p className={cn("truncate text-[9px] font-medium leading-tight", event.status === "cancelled" && "line-through")}>
                            {event.title}
                          </p>
                          <p className="mono mt-0.5 truncate text-[7px] opacity-70">
                            {formatTime(event.startsAt, timezone)}
                            {event.venue ? ` · ${event.venue}` : ""}
                          </p>
                        </div>
                      );
                    })}
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)]">
        <div className="panel p-0">
          <div className="flex items-center justify-between border-b border-white/[0.06] px-4 py-3">
            <div>
              <p className="eyebrow">Agenda</p>
              <p className="mt-0.5 text-xs text-ink">This week</p>
            </div>
            <span className="chip">{upcoming.length} events</span>
          </div>
          <div className="divide-y divide-white/[0.04]">
            {upcoming.map((event) => (
              <div key={event.id} className="flex items-start gap-3 px-4 py-3">
                <div className="mono w-14 shrink-0 text-[9px] uppercase text-ink-faint">
                  {formatTime(event.startsAt, timezone)}
                </div>
                <div className={cn("mt-1 h-2 w-2 shrink-0 rounded-full", event.status === "cancelled" ? "bg-rose/60" : event.type === "exam" ? "bg-amber" : "bg-cyan")} />
                <div className="min-w-0 flex-1">
                  <p className={cn("text-[11px] font-medium", event.status === "cancelled" ? "text-ink-faint line-through" : "text-ink")}>
                    {event.title}
                  </p>
                  <p className="mt-0.5 text-[9px] text-ink-faint">
                    {formatDateTime(event.startsAt, timezone)}
                    {event.venue ? ` · ${event.venue}` : ""}
                    {event.status === "cancelled" ? " · cancelled" : ""}
                  </p>
                </div>
                {event.courseCode ? <span className="chip px-1.5 py-0 text-[8px]">{event.courseCode}</span> : null}
              </div>
            ))}
            {upcoming.length === 0 ? (
              <div className="px-4 py-10 text-center text-xs text-ink-faint">No events this week.</div>
            ) : null}
          </div>
        </div>

        <div className="space-y-3">
          <div className="panel p-4">
            <p className="eyebrow">Next fixed points</p>
            <div className="mt-3 space-y-3">
              {events
                .filter((event) => new Date(event.startsAt).getTime() > Date.now() && event.status !== "cancelled")
                .sort((a, b) => a.startsAt.localeCompare(b.startsAt))
                .slice(0, 3)
                .map((event) => (
                  <div key={event.id} className="flex items-start gap-2.5">
                    <span className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-lg border border-cyan/15 bg-cyan/[0.05] text-cyan">
                      <CalendarDays className="h-3.5 w-3.5" />
                    </span>
                    <div className="min-w-0">
                      <p className="truncate text-[11px] text-ink">{event.title}</p>
                      <p className="text-[9px] text-ink-faint">
                        {formatDateTime(event.startsAt, timezone)}
                        {event.venue ? ` · ${event.venue}` : ""}
                      </p>
                    </div>
                  </div>
                ))}
            </div>
          </div>

          <div className="panel p-4">
            <p className="eyebrow">Deadline load</p>
            <div className="mt-3 space-y-2">
              {tasks
                .filter((task) => task.dueAt && !["completed", "cancelled"].includes(task.status))
                .sort((a, b) => (a.dueAt ?? "").localeCompare(b.dueAt ?? ""))
                .slice(0, 4)
                .map((task) => (
                  <Link key={task.id} href={`/tasks?task=${encodeURIComponent(task.id)}`} className="flex items-center gap-2 rounded-lg px-1 py-1.5 hover:bg-white/[0.03]">
                    <Clock className="h-3 w-3 shrink-0 text-amber/70" />
                    <span className="min-w-0 flex-1 truncate text-[10px] text-ink-dim">{task.title}</span>
                    <span className="mono shrink-0 text-[8px] uppercase text-ink-faint">{task.courseCode ?? "personal"}</span>
                  </Link>
                ))}
            </div>
            <div className="divider my-3" />
            <Link href="/ask?q=What%20should%20I%20prepare%20for%20tomorrow%3F" className="mono flex items-center gap-1.5 text-[9px] uppercase tracking-[0.12em] text-cyan/80 hover:text-cyan">
              <Sparkles className="h-3 w-3" /> plan tomorrow with Jarvis
            </Link>
          </div>

          <div className="panel p-4">
            <p className="eyebrow">Legend</p>
            <div className="mt-2 grid grid-cols-2 gap-2 text-[9px] text-ink-faint">
              <span className="flex items-center gap-1.5"><span className="h-2 w-2 rounded-sm bg-cyan" /> lecture</span>
              <span className="flex items-center gap-1.5"><span className="h-2 w-2 rounded-sm bg-amber" /> exam</span>
              <span className="flex items-center gap-1.5"><span className="h-2 w-2 rounded-sm bg-violet" /> meeting</span>
              <span className="flex items-center gap-1.5"><span className="h-2 w-2 rounded-sm bg-rose" /> deadline</span>
            </div>
            <p className="mt-2 flex items-start gap-1.5 text-[9px] leading-relaxed text-ink-faint">
              <MapPin className="mt-0.5 h-3 w-3 shrink-0" />
              Event times are shown in {timezone}, with the original timestamps preserved.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}
