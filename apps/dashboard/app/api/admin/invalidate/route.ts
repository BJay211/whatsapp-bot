import { NextResponse } from "next/server";
import { invalidateSnapshot } from "@/lib/store";

export const dynamic = "force-dynamic";

export async function POST() {
  invalidateSnapshot();
  return NextResponse.json({ ok: true, message: "Cache invalidated" });
}

export async function GET() {
  invalidateSnapshot();
  return NextResponse.json({ ok: true, message: "Cache invalidated" });
}
