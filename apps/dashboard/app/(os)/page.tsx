import Link from "next/link";
import {
  ArrowUpRight,
  BellRing,
  CalendarClock,
  ChevronRight,
  GitBranch,
  Inbox,
  ListChecks,
  Radio,
  Sparkles,
  TriangleAlert,
} from "lucide-react";
import { getSnapshot } from "@/lib/store";
import { calendarDaysBetween, formatWhen } from "@/lib/engine";
import { greeting, formatDayLong, timeAgo } from "@/lib/format";
import { Panel, PanelHeader, StatusDot } from "@/components/ui/primitives";
import { JarvisOrb, OrbBackdrop } from "@/components/jarvis/orb";
import { QuickAsk } from "@/components/jarvis/quick-ask";
import {
  ActivityChart,
  AttentionList,
  BriefPoints,
  EventRow,
  MessageRow,
  MiniAgenda,
  StatCard,
  TaskRow,
  Vitals,
} from "@/components/dashboard/widgets";
import { cn } from "@/lib/utils";

export const dynamic = "force-dynamic";

export default async function CommandCenterPage() {
  const snapshot = await getSnapshot();
  const { user, tasks, events, messages, connections, activity, brief } = snapshot;
  const tz = user.timezone;
  const now = new Date();

  const openTasks = tasks
    .filter((t) => ["detected", "needs_confirmation", "confirmed", "in_progress", "overdue"].includes(t.status))
    .sort((a, b) => (a.dueAt ?? "").localeCompare(b.dueAt ?? ""));

  const todayEvents = events
    .filter((e) => calendarDaysBetween(now, new Date(e.startsAt), tz) === 0)
    .sort((a, b) => a.startsAt.localeCompare(b.startsAt));

  const upcomingEvents = events
    .filter((e) => {
      const diff = calendarDaysBetween(now, new Date(e.startsAt), tz);
      return diff >= 1 && diff <= 9;
    })
    .sort((a, b) => a.startsAt.localeCompare(b.startsAt))
    .slice(0, 7);

  const recentMessages = [...messages].sort((a, b) => b.timestamp.localeCompare(a.timestamp)).slice(0, 5);
  const dueSoon = openTasks.filter((t) => {
    if (!t.dueAt) return false;
    const diff = calendarDaysBetween(now, new Date(t.dueAt), tz);
    return diff >= 0 && diff <= 7;
  });
  const changes = tasks.reduce((acc, task) => acc + task.revisions.filter((r) => r.field === "due_at").length, 0);
  const signals24h = messages.filter((m) => now.getTime() - new Date(m.timestamp).getTime() < 86_400_000).length;
  const urgent = openTasks.filter((t) => t.priority === "urgent" || t.priority === "high");
  const attention = urgent.slice(0, 3);
  const liveLinks = connections.filter((c) => c.status === "connected" || c.status === "demo").length;

  return (
    <div className="space-y-4">
      <section className="grid gap-4 xl:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)]">
        <Panel glow corners className="relative overflow-hidden p-5 sm:p-6">
          <OrbBackdrop />
          <div className="relative flex items-start justify-between gap-4">
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-2">
                <span className="chip chip-cyan">
                  <StatusDot tone="cyan" pulse />
                  Jarvis online
                </span>
                <span className="mono text-[9px] uppercase tracking-[0.18em] text-ink-faint">
                  {formatDayLong(now.toISOString(), tz)}
                </span>
              </div>

              <h1 className="display mt-4 text-balance text-2xl font-semibold leading-tight text-ink sm:text-[32px]">
                {greeting(now, tz)}, <span className="neon-text">{user.displayName}</span>.
              </h1>
              <p className="mt-2 max-w-2xl text-sm leading-relaxed text-ink-dim">{brief.headline}</p>

              <div className="mt-4 flex flex-wrap items-center gap-2">
                <span className={cn("chip", snapshot.mode === "live" ? "chip-lime" : "chip-amber")}>
                  <Radio className="h-3 w-3" />
                  {snapshot.mode === "live" ? "Live archive" : "Demo fixtures"}
                </span>
                <span className="chip">
                  <GitBranch className="h-3 w-3" />
                  {changes} change{changes === 1 ? "" : "s"} tracked
                </span>
                <span className="chip">
                  <BellRing className="h-3 w-3" />
                  {signals24h} signal{signals24h === 1 ? "" : "s"} / 24h
                </span>
              </div>

              <div className="mt-6 max-w-2xl">
                <QuickAsk />
              </div>
            </div>

            <div className="relative hidden shrink-0 pt-1 lg:block">
              <JarvisOrb size={176} />
              <p className="mono mt-4 text-center text-[8px] uppercase tracking-[0.24em] text-ink-faint">
                core · stable
              </p>
            </div>
          </div>

          <div className="divider my-5" />
          <div className="relative grid gap-4 sm:grid-cols-[1.3fr_1fr]">
            <div>
              <p className="eyebrow mb-2">Morning brief</p>
              <BriefPoints brief={brief} />
            </div>
            <div>
              <div className="flex items-center justify-between">
                <p className="eyebrow">Needs attention</p>
                <TriangleAlert className="h-3 w-3 text-amber/70" />
              </div>
              <div className="mt-2">
                <AttentionList tasks={attention} />
              </div>
            </div>
          </div>
        </Panel>

        <Panel className="flex flex-col p-0">
          <PanelHeader
            kicker="Chrono · today"
            title="Your day"
            action={
              <Link href="/calendar" className="mono flex items-center gap-1 text-[9px] uppercase tracking-[0.14em] text-ink-faint hover:text-cyan">
                open <ArrowUpRight className="h-3 w-3" />
              </Link>
            }
          />
          <div className="px-4 pt-3">
            <div className="flex items-center justify-between rounded-xl border border-white/[0.06] bg-white/[0.02] px-3 py-2">
              <div>
                <p className="mono text-[9px] uppercase tracking-[0.16em] text-ink-faint">Local time</p>
                <p className="text-sm font-medium text-ink">
                  {new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", timeZone: tz }).format(now)}
                </p>
              </div>
              <div className="text-right">
                <p className="mono text-[9px] uppercase tracking-[0.16em] text-ink-faint">Agenda</p>
                <p className="text-sm font-medium text-ink">{todayEvents.length} item{todayEvents.length === 1 ? "" : "s"}</p>
              </div>
            </div>
          </div>
          <div className="flex-1 px-3 py-3">
            <MiniAgenda events={todayEvents} />
          </div>
          <div className="border-t border-white/[0.05] p-3">
            <div className="flex items-center justify-between text-[10px]">
              <span className="flex items-center gap-1.5 text-ink-faint">
                <CalendarClock className="h-3 w-3" />
                Next: {upcomingEvents[0] ? formatWhen(upcomingEvents[0].startsAt, tz) : "nothing scheduled"}
              </span>
              <Link href="/tasks" className="text-cyan/80 hover:text-cyan">
                Tasks
              </Link>
            </div>
          </div>
        </Panel>
      </section>

      <section className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatCard
          label="Open missions"
          value={openTasks.length}
          detail={`${urgent.length} high priority · ${openTasks.filter((t) => t.status === "needs_confirmation").length} awaiting confirmation`}
          icon={ListChecks}
          tone="cyan"
          href="/tasks"
        />
        <StatCard
          label="Due this week"
          value={dueSoon.length}
          detail={dueSoon[0] ? `Next: ${dueSoon[0].title}` : "No deadlines in the next 7 days"}
          icon={CalendarClock}
          tone="amber"
          href="/tasks"
        />
        <StatCard
          label="Changes tracked"
          value={changes}
          detail="Deadline revisions with preserved originals"
          icon={GitBranch}
          tone="violet"
          href="/memory"
        />
        <StatCard
          label="Signals"
          value={signals24h}
          detail={`${messages.length} archived messages across ${new Set(messages.map((m) => m.chatName)).size} chats`}
          icon={Inbox}
          tone="lime"
          href="/inbox"
        />
      </section>

      <section className="grid gap-4 xl:grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)]">
        <Panel className="p-0">
          <PanelHeader
            kicker="Mission queue"
            title="What actually matters"
            action={
              <Link href="/tasks" className="mono flex items-center gap-1 text-[9px] uppercase tracking-[0.14em] text-ink-faint hover:text-cyan">
                all {openTasks.length} <ChevronRight className="h-3 w-3" />
              </Link>
            }
          />
          <div className="space-y-2 p-4 pt-3">
            {openTasks.slice(0, 4).map((task) => (
              <TaskRow key={task.id} task={task} />
            ))}
            {openTasks.length === 0 ? (
              <div className="rounded-xl border border-lime/15 bg-lime/[0.04] p-6 text-center text-xs text-lime/80">
                No open tasks. Enjoy the quiet.
              </div>
            ) : null}
          </div>
          <div className="flex items-center justify-between border-t border-white/[0.05] px-4 py-2.5">
            <span className="mono text-[9px] uppercase tracking-[0.12em] text-ink-faint">
              every item is source-backed
            </span>
            <Link href="/ask?q=Which%20deadlines%20changed%3F" className="mono flex items-center gap-1 text-[9px] uppercase tracking-[0.12em] text-cyan/80 hover:text-cyan">
              <Sparkles className="h-2.5 w-2.5" /> ask about changes
            </Link>
          </div>
        </Panel>

        <Panel className="p-0">
          <PanelHeader
            kicker="Signal stream"
            title="Latest intelligence"
            action={
              <Link href="/inbox" className="mono flex items-center gap-1 text-[9px] uppercase tracking-[0.14em] text-ink-faint hover:text-cyan">
                inbox <ChevronRight className="h-3 w-3" />
              </Link>
            }
          />
          <div className="space-y-0.5 p-2.5 pt-2">
            {recentMessages.map((message) => (
              <MessageRow key={message.id} message={message} />
            ))}
          </div>
          <div className="flex items-center justify-between border-t border-white/[0.05] px-4 py-2.5">
            <span className="mono text-[9px] uppercase tracking-[0.12em] text-ink-faint">
              {timeAgo(recentMessages[0]?.timestamp)} · latest
            </span>
            <Link href="/ask?q=Summarise%20what%20I%20missed%20today" className="mono flex items-center gap-1 text-[9px] uppercase tracking-[0.12em] text-cyan/80 hover:text-cyan">
              <Sparkles className="h-2.5 w-2.5" /> summarise
            </Link>
          </div>
        </Panel>
      </section>

      <section className="grid gap-4 lg:grid-cols-2 xl:grid-cols-[1.15fr_1fr_1fr]">
        <Panel className="p-0">
          <PanelHeader kicker="Forward horizon" title="Coming up" action={<CalendarClock className="h-3.5 w-3.5 text-ink-faint" />} />
          <div className="px-3 py-3">
            {upcomingEvents.map((event) => (
              <div key={event.id} className="border-b border-white/[0.04] py-1 last:border-0">
                <div className="mono mb-0.5 px-1 text-[8px] uppercase tracking-[0.14em] text-ink-faint">
                  {formatWhen(event.startsAt, tz)}
                </div>
                <EventRow event={event} />
              </div>
            ))}
            {upcomingEvents.length === 0 ? (
              <p className="p-4 text-xs text-ink-faint">Nothing on the horizon in the next 9 days.</p>
            ) : null}
          </div>
        </Panel>

        <Panel className="p-0">
          <PanelHeader
            kicker="System vitals"
            title="Connections"
            action={
              <span className="mono flex items-center gap-1.5 text-[9px] uppercase tracking-[0.12em] text-ink-faint">
                <StatusDot tone={liveLinks > 2 ? "lime" : "amber"} />
                {liveLinks}/5
              </span>
            }
          />
          <div className="px-4 py-3">
            <Vitals connections={connections} />
          </div>
          <div className="border-t border-white/[0.05] px-4 py-2.5">
            <Link href="/settings" className="mono flex items-center justify-between text-[9px] uppercase tracking-[0.12em] text-ink-faint hover:text-cyan">
              configure integrations
              <ArrowUpRight className="h-3 w-3" />
            </Link>
          </div>
        </Panel>

        <Panel className="p-0 lg:col-span-2 xl:col-span-1">
          <PanelHeader
            kicker="Neural activity"
            title="Archive pulse"
            action={<span className="mono text-[9px] uppercase tracking-[0.12em] text-ink-faint">7 days</span>}
          />
          <div className="px-4 pb-3 pt-4">
            <ActivityChart data={activity} />
            <div className="divider my-4" />
            <div className="grid grid-cols-2 gap-3">
              <div>
                <p className="mono text-[8px] uppercase tracking-[0.14em] text-ink-faint">Archived</p>
                <p className="display text-lg font-semibold text-ink">{messages.length}</p>
                <p className="text-[9px] text-ink-faint">original messages</p>
              </div>
              <div>
                <p className="mono text-[8px] uppercase tracking-[0.14em] text-ink-faint">Knowledge</p>
                <p className="display text-lg font-semibold text-ink">{tasks.length}</p>
                <p className="text-[9px] text-ink-faint">structured records</p>
              </div>
            </div>
            <Link href="/memory" className="btn-ghost mt-4 w-full justify-between text-[10px]">
              Explore memory graph
              <ArrowUpRight className="h-3 w-3" />
            </Link>
          </div>
        </Panel>
      </section>
    </div>
  );
}
