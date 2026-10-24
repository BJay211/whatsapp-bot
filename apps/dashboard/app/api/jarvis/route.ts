import { NextResponse } from "next/server";
import { answerQuestion } from "@/lib/engine";
import { getSnapshot } from "@/lib/store";
import { augmentAnswer } from "@/lib/llm";
import { clientKey, rateLimit } from "@/lib/rate-limit";

export const dynamic = "force-dynamic";
export const maxDuration = 30;

export async function POST(request: Request) {
  const limit = rateLimit(`jarvis:${clientKey(request)}`, 30, 60_000);
  if (!limit.ok) {
    return NextResponse.json(
      { ok: false, error: "Too many questions. Wait a moment and try again." },
      { status: 429 },
    );
  }

  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ ok: false, error: "Invalid JSON body." }, { status: 400 });
  }

  const raw = typeof (body as { message?: unknown })?.message === "string"
    ? (body as { message: string }).message
    : "";
  const message = raw.trim().slice(0, 1000);

  if (message.length < 2) {
    return NextResponse.json(
      { ok: false, error: "Ask a question with at least two characters." },
      { status: 400 },
    );
  }

  const snapshot = await getSnapshot();
  const started = Date.now();
  let answer = answerQuestion(message, snapshot);

  if (answer.intent !== "complete_task") {
    const augmented = await augmentAnswer(message, answer);
    if (augmented) answer = augmented;
  }
  answer.latencyMs = Date.now() - started;

  return NextResponse.json({
    ok: true,
    answer,
    mode: snapshot.mode,
    generatedAt: new Date().toISOString(),
  });
}

export async function GET() {
  return NextResponse.json({
    ok: true,
    endpoint: "POST /api/jarvis",
    accepts: { message: "string (2-1000 chars)" },
    examples: [
      "What is my next deadline?",
      "Which deadlines changed?",
      "What do I need for CSC 401?",
      "Summarise what I missed today",
      "Show me the original assignment announcement",
    ],
    grounded: true,
  });
}
