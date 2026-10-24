import { getSnapshot } from "@/lib/store";
import { MemoryGraph } from "@/components/memory/memory-graph";

export const dynamic = "force-dynamic";

export default async function MemoryPage() {
  const snapshot = await getSnapshot();
  return (
    <MemoryGraph
      courses={snapshot.courses}
      tasks={snapshot.tasks}
      messages={snapshot.messages}
      timeZone={snapshot.user.timezone}
    />
  );
}
