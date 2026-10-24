import { getSnapshot } from "@/lib/store";
import { CockpitShell } from "@/components/shell/cockpit-shell";

export const dynamic = "force-dynamic";

export default async function OsLayout({ children }: { children: React.ReactNode }) {
  const snapshot = await getSnapshot();
  const openTasks = snapshot.tasks.filter((t) =>
    ["detected", "needs_confirmation", "confirmed", "in_progress", "overdue"].includes(t.status),
  ).length;
  const attention = snapshot.tasks.filter(
    (t) => t.priority === "urgent" || t.priority === "high",
  ).length;
  const liveConnections = snapshot.connections.filter(
    (c) => c.status === "connected" || c.status === "demo",
  ).length;

  return (
    <CockpitShell
      mode={snapshot.mode}
      counts={{ tasks: openTasks, inbox: snapshot.messages.length, attention }}
      liveConnections={liveConnections}
    >
      {children}
    </CockpitShell>
  );
}
