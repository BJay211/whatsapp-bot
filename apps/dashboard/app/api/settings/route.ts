import { NextResponse } from "next/server";
import { getSnapshot, updatePreferences } from "@/lib/store";
import type { UserPreferences } from "@/lib/types";

export const dynamic = "force-dynamic";

const NUMERIC_BOUNDS: Record<string, [number, number]> = {
  reminderLeadMinutes: [0, 20_160],
  retentionDays: [0, 3650],
};

export async function GET() {
  const snapshot = await getSnapshot();
  return NextResponse.json({
    ok: true,
    mode: snapshot.mode,
    modeReason: snapshot.modeReason,
    user: snapshot.user,
    connections: snapshot.connections,
    authEnabled: Boolean(process.env.DASHBOARD_PASSPHRASE),
  });
}

export async function PATCH(request: Request) {
  let body: Record<string, unknown>;
  try {
    body = (await request.json()) as Record<string, unknown>;
  } catch {
    return NextResponse.json({ ok: false, error: "Invalid JSON body." }, { status: 400 });
  }

  const patch: Partial<UserPreferences> = {};
  const stringFields: (keyof UserPreferences)[] = [
    "displayName",
    "timezone",
    "locale",
    "llmProvider",
    "llmModel",
    "embeddingModel",
    "briefingTime",
  ];

  for (const field of stringFields) {
    if (typeof body[field] === "string") {
      (patch as Record<string, unknown>)[field] = (body[field] as string).slice(0, 120);
    }
  }
  if (typeof body.voiceEnabled === "boolean") patch.voiceEnabled = body.voiceEnabled;
  if (typeof body.autoCreateTasks === "boolean") patch.autoCreateTasks = body.autoCreateTasks;

  for (const [field, [min, max]] of Object.entries(NUMERIC_BOUNDS)) {
    if (typeof body[field] === "number" && Number.isFinite(body[field] as number)) {
      (patch as Record<string, unknown>)[field] = Math.min(max, Math.max(min, body[field] as number));
    }
  }

  if (typeof patch.timezone === "string") {
    try {
      new Intl.DateTimeFormat("en-GB", { timeZone: patch.timezone }).format();
    } catch {
      return NextResponse.json({ ok: false, error: "Invalid IANA timezone." }, { status: 400 });
    }
  }

  const user = await updatePreferences(patch);
  return NextResponse.json({ ok: true, user });
}
