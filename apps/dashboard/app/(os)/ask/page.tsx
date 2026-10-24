import { getSnapshot } from "@/lib/store";
import { AskConsole } from "@/components/jarvis/ask-console";

export const dynamic = "force-dynamic";

export default async function AskPage({
  searchParams,
}: {
  searchParams: Promise<{ q?: string; voice?: string }>;
}) {
  const params = await searchParams;
  const snapshot = await getSnapshot();
  const initialQuery = typeof params.q === "string" ? params.q.slice(0, 1000) : undefined;
  const autoVoice = params.voice === "1";

  return (
    <AskConsole
      initialQuery={initialQuery}
      autoVoice={autoVoice}
      mode={snapshot.mode}
      archiveSize={snapshot.messages.length}
      timezone={snapshot.user.timezone}
    />
  );
}
