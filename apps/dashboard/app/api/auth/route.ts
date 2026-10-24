import { NextResponse } from "next/server";
import { AUTH_COOKIE, expectedToken, isValidPassphrase } from "@/lib/auth";

export const dynamic = "force-dynamic";

export async function POST(request: Request) {
  let body: { passphrase?: unknown };
  try {
    body = (await request.json()) as typeof body;
  } catch {
    return NextResponse.json({ ok: false, error: "Invalid JSON body." }, { status: 400 });
  }

  const passphrase = typeof body.passphrase === "string" ? body.passphrase : "";
  if (!(await isValidPassphrase(passphrase))) {
    await new Promise((resolve) => setTimeout(resolve, 350));
    return NextResponse.json({ ok: false, error: "Incorrect passphrase." }, { status: 401 });
  }

  const token = await expectedToken();
  if (!token) return NextResponse.json({ ok: true });

  const response = NextResponse.json({ ok: true });
  response.cookies.set(AUTH_COOKIE, token, {
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge: 60 * 60 * 24 * 30,
  });
  return response;
}

export async function DELETE() {
  const response = NextResponse.json({ ok: true });
  response.cookies.set(AUTH_COOKIE, "", { path: "/", maxAge: 0 });
  return response;
}
