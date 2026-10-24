import { NextResponse } from "next/server";
import { resetDemo } from "@/lib/store";

export const dynamic = "force-dynamic";

/**
 * Restores synthetic fixtures. Deliberately refused whenever a database is
 * configured, even if it is currently unreachable, so this can never look like
 * a data operation on a real archive.
 */
export async function POST() {
  if (process.env.DATABASE_URL) {
    return NextResponse.json(
      { ok: false, error: "Demo reset is disabled while DATABASE_URL is configured." },
      { status: 409 },
    );
  }
  await resetDemo();
  return NextResponse.json({ ok: true, note: "Synthetic fixtures restored." });
}
