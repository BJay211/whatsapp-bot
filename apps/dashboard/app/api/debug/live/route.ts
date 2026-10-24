import { NextResponse } from "next/server";
import { getSnapshot, invalidateSnapshot } from "@/lib/store";

export const dynamic = "force-dynamic";

export async function GET() {
  try {
    invalidateSnapshot();
    const snapshot = await getSnapshot();
    return NextResponse.json(
      {
        ok: true,
        mode: snapshot.mode,
        modeReason: snapshot.modeReason,
        counts: {
          messages: snapshot.messages.length,
          tasks: snapshot.tasks.length,
          events: snapshot.events.length,
        },
        connections: snapshot.connections.filter((c) => c.kind === "database"),
        generatedAt: snapshot.generatedAt,
      },
      { status: 200 },
    );
  } catch (error: any) {
    return NextResponse.json(
      { ok: false, error: error?.message || String(error), stack: error?.stack?.slice(0, 500) },
      { status: 500 },
    );
  }
}
