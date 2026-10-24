import { NextResponse } from "next/server";
import { checkHealth } from "@/lib/store";

export const dynamic = "force-dynamic";

export async function GET() {
  const health = await checkHealth();
  return NextResponse.json(
    {
      ok: health.ok,
      status: health.ok ? "healthy" : "degraded",
      service: "bonjee-os",
      version: "2.0.0",
      mode: health.mode,
      checks: health.checks,
      counts: health.counts,
      timestamp: new Date().toISOString(),
    },
    { status: health.ok ? 200 : 503 },
  );
}
