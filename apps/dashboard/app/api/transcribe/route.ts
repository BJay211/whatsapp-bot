import { NextResponse } from "next/server";

export const dynamic = "force-dynamic";
export const maxDuration = 60;

const MAX_BYTES = 12 * 1024 * 1024;

/**
 * Optional server-side transcription fallback for browsers without the Web
 * Speech API. The primary voice path is on-device browser speech recognition;
 * this endpoint only works when a compatible transcription key is configured.
 */
export async function POST(request: Request) {
  const apiKey = process.env.JARVIS_STT_API_KEY || process.env.OPENAI_API_KEY;
  if (!apiKey) {
    return NextResponse.json(
      {
        ok: false,
        error:
          "Server transcription is not configured. Use the microphone in a Chromium-based browser, or set JARVIS_STT_API_KEY.",
      },
      { status: 501 },
    );
  }

  const form = await request.formData();
  const file = form.get("audio");
  if (!(file instanceof File)) {
    return NextResponse.json({ ok: false, error: "Attach an 'audio' file." }, { status: 400 });
  }
  if (file.size > MAX_BYTES) {
    return NextResponse.json({ ok: false, error: "Audio exceeds the 12 MB limit." }, { status: 413 });
  }
  if (!file.type.startsWith("audio/") && file.type !== "video/webm") {
    return NextResponse.json({ ok: false, error: "Unsupported audio content type." }, { status: 415 });
  }

  const base = (process.env.JARVIS_STT_BASE_URL || "https://api.openai.com/v1").replace(/\/$/, "");
  const model = process.env.JARVIS_STT_MODEL || "whisper-1";
  const upstream = new FormData();
  upstream.append("file", file, file.name || "voice.webm");
  upstream.append("model", model);
  upstream.append("response_format", "json");

  try {
    const response = await fetch(`${base}/audio/transcriptions`, {
      method: "POST",
      headers: { authorization: `Bearer ${apiKey}` },
      body: upstream,
    });
    if (!response.ok) {
      return NextResponse.json({ ok: false, error: "Transcription provider rejected the request." }, { status: 502 });
    }
    const payload = (await response.json()) as { text?: string };
    if (!payload.text) {
      return NextResponse.json({ ok: false, error: "No transcript returned." }, { status: 502 });
    }
    return NextResponse.json({ ok: true, text: payload.text });
  } catch {
    return NextResponse.json({ ok: false, error: "Transcription provider unreachable." }, { status: 502 });
  }
}
