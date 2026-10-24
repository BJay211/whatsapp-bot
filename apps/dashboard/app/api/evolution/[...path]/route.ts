import { NextResponse } from "next/server";
import { env } from "@/lib/env";

export const dynamic = "force-dynamic";

async function handler(req: Request, { params }: { params: Promise<{ path: string[] }> }) {
  const base = env.evolutionApiUrl;
  const key = env.evolutionApiKey;
  if (!base || !key) {
    return NextResponse.json(
      { ok: false, error: "EVOLUTION_API_URL or EVOLUTION_API_KEY not set" },
      { status: 500 }
    );
  }
  const resolvedParams = await params;
  const path = resolvedParams.path.join("/");
  const url = new URL(`${base}/${path}`);
  const incoming = new URL(req.url);
  incoming.searchParams.forEach((v, k) => url.searchParams.set(k, v));
  
  const headers: Record<string, string> = { apikey: key };
  const ct = req.headers.get("content-type");
  if (ct) headers["content-type"] = ct;
  
  const res = await fetch(url.toString(), {
    method: req.method,
    headers,
    body: req.method === "GET" || req.method === "HEAD" ? undefined : await req.text(),
    cache: "no-store",
  });
  
  const text = await res.text();
  return new NextResponse(text, {
    status: res.status,
    headers: { "content-type": res.headers.get("content-type") || "application/json" },
  });
}

export { handler as GET, handler as POST, handler as PUT, handler as PATCH, handler as DELETE };
