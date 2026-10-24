import { getSnapshot } from "@/lib/store";
import { InboxExplorer } from "@/components/inbox/inbox-explorer";

export const dynamic = "force-dynamic";

export default async function InboxPage({
  searchParams,
}: {
  searchParams: Promise<{ message?: string }>;
}) {
  const params = await searchParams;
  const snapshot = await getSnapshot();
  return (
    <InboxExplorer
      messages={snapshot.messages}
      initialMessageId={params.message}
      mode={snapshot.mode}
    />
  );
}
