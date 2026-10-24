import { getSnapshot } from "@/lib/store";
import { TasksBoard } from "@/components/tasks/tasks-board";

export const dynamic = "force-dynamic";

export default async function TasksPage({
  searchParams,
}: {
  searchParams: Promise<{ task?: string }>;
}) {
  const params = await searchParams;
  const snapshot = await getSnapshot();
  return (
    <TasksBoard
      tasks={snapshot.tasks}
      messages={snapshot.messages}
      initialTaskId={params.task}
    />
  );
}
