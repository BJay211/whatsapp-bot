import { NextResponse } from "next/server";
import { purgeData } from "@/lib/store";

export const dynamic = "force-dynamic";

export async function POST(request: Request) {
  let body: { scope?: unknown; chatId?: unknown; confirm?: unknown };
  try {
    body = (await request.json()) as typeof body;
  } catch {
    return NextResponse.json({ ok: false, error: "Invalid JSON body." }, { status: 400 });
  }

  if (body.confirm !== "DELETE") {
    return NextResponse.json(
      { ok: false, error: "Confirmation required. Send confirm: \"DELETE\"." },
      { status: 400 },
    );
  }

  const scope = body.scope === "chat" ? "chat" : body.scope === "all" ? "all" : null;
  const chatId = typeof body.chatId === "string" ? body.chatId : undefined;

  if (!scope) return NextResponse.json({ ok: false, error: "scope must be 'all' or 'chat'." }, { status: 400 });
  if (scope === "chat" && !chatId) {
    return NextResponse.json({ ok: false, error: "chatId is required for scope 'chat'." }, { status: 400 });
  }

  try {
    const result = await purgeData(scope, chatId);
    return NextResponse.json({
      ok: true,
      removed: result.removed,
      note: "Derived rows are removed by database cascades; an audit record is written for the action.",
    });
  } catch (error) {
    return NextResponse.json(
      { ok: false, error: error instanceof Error ? error.message : "Deletion failed." },
      { status: 500 },
    );
  }
}
