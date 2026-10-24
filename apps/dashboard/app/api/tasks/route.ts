import { NextResponse } from "next/server";
import { getSnapshot, toggleRequirement, updateTaskStatus } from "@/lib/store";
import type { TaskStatus } from "@/lib/types";

export const dynamic = "force-dynamic";

const ALLOWED_STATUS: TaskStatus[] = [
  "detected",
  "needs_confirmation",
  "confirmed",
  "in_progress",
  "completed",
  "cancelled",
  "overdue",
];

export async function GET() {
  const snapshot = await getSnapshot();
  return NextResponse.json({
    ok: true,
    mode: snapshot.mode,
    tasks: snapshot.tasks,
  });
}

export async function PATCH(request: Request) {
  let body: { id?: unknown; status?: unknown; requirementIndex?: unknown };
  try {
    body = (await request.json()) as typeof body;
  } catch {
    return NextResponse.json({ ok: false, error: "Invalid JSON body." }, { status: 400 });
  }

  const id = typeof body.id === "string" ? body.id : "";
  if (!id) return NextResponse.json({ ok: false, error: "Task id is required." }, { status: 400 });

  if (typeof body.requirementIndex === "number") {
    const result = await toggleRequirement(id, body.requirementIndex);
    if (!result.ok) return NextResponse.json({ ok: false, error: result.error }, { status: 400 });
    return NextResponse.json({ ok: true, task: result.task });
  }

  const status = body.status as TaskStatus;
  if (!ALLOWED_STATUS.includes(status)) {
    return NextResponse.json({ ok: false, error: "Unsupported task status." }, { status: 400 });
  }

  const result = await updateTaskStatus(id, status);
  if (!result.ok) return NextResponse.json({ ok: false, error: result.error }, { status: 404 });
  return NextResponse.json({ ok: true, task: result.task });
}
