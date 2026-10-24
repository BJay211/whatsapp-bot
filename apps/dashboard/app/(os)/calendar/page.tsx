import { getSnapshot } from "@/lib/store";
import { WeekView } from "@/components/calendar/week-view";

export const dynamic = "force-dynamic";

export default async function CalendarPage() {
  const snapshot = await getSnapshot();
  const timezone = snapshot.user.timezone;
  const todayKey = new Intl.DateTimeFormat("en-CA", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    timeZone: timezone,
  }).format(new Date());

  return (
    <WeekView
      events={snapshot.events}
      tasks={snapshot.tasks}
      timezone={timezone}
      todayKey={todayKey}
    />
  );
}
