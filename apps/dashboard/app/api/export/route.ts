import { NextResponse } from "next/server";
import { exportJsonl } from "@/lib/store";

export const dynamic = "force-dynamic";

export async function GET() {
  const jsonl = await exportJsonl();
  const stamp = new Date().toISOString().slice(0, 10);
  return new NextResponse(jsonl, {
    status: 200,
    headers: {
      "content-type": "application/x-ndjson; charset=utf-8",
      "content-disposition": `attachment; filename="bonjee-archive-${stamp}.jsonl"`,
      "cache-control": "no-store",
    },
  });
}
