import { NextResponse } from "next/server";
import { getSnapshot } from "@/lib/store";

export const dynamic = "force-dynamic";

/**
 * Compact read model for the Android companion and external clients.
 * It contains no secrets and never returns raw_payload.
 */
export async function GET() {
  const snapshot = await getSnapshot();
  const openTasks = snapshot.tasks.filter((t) =>
    ["detected", "needs_confirmation", "confirmed", "in_progress", "overdue"].includes(t.status),
  );

  return NextResponse.json({
    ok: true,
    mode: snapshot.mode,
    generatedAt: snapshot.generatedAt,
    user: { displayName: snapshot.user.displayName, timezone: snapshot.user.timezone },
    summary: snapshot.brief,
    stats: {
      openTasks: openTasks.length,
      urgent: openTasks.filter((t) => t.priority === "urgent").length,
      messages: snapshot.messages.length,
      events: snapshot.events.length,
    },
    nextTasks: openTasks
      .sort((a, b) => (a.dueAt ?? "").localeCompare(b.dueAt ?? ""))
      .slice(0, 5)
      .map((t) => ({
        id: t.id,
        title: t.title,
        courseCode: t.courseCode,
        dueAt: t.dueAt,
        status: t.status,
        priority: t.priority,
      })),
    today: snapshot.events
      .filter((event) => event.startsAt.slice(0, 10) === new Date().toISOString().slice(0, 10))
      .map((event) => ({ title: event.title, startsAt: event.startsAt, status: event.status })),
  });
}
