import { NextResponse } from "next/server";
import { checkHealth } from "@/lib/store";
import { env } from "@/lib/env";

export const dynamic = "force-dynamic";

async function ping(url: string, init?: RequestInit, timeoutMs = 3000) {
  const controller = new AbortController();
  const t = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(url, { ...init, signal: controller.signal, cache: "no-store" });
    return { ok: res.ok, status: res.status };
  } catch (e: any) {
    return { ok: false, error: e?.message || "error" };
  } finally {
    clearTimeout(t);
  }
}

export async function GET() {
  const health = await checkHealth();
  const checks = [...(health.checks || [])];

  // Check n8n webhook health
  if (env.n8nBaseUrl) {
    const r = await ping(`${env.n8nBaseUrl}/webhook/health`, { method: "GET" });
    checks.push({
      name: "n8n",
      ok: r.ok,
      detail: r.ok ? `ok ${r.status}` : `fail ${r.status || r.error}`,
    });
  } else {
    checks.push({ name: "n8n", ok: false, detail: "N8N_BASE_URL not set" });
  }

  // Check Evolution API
  if (env.evolutionApiUrl && env.evolutionApiKey) {
    const r = await ping(`${env.evolutionApiUrl}/`, {
      method: "GET",
      headers: { apikey: env.evolutionApiKey },
    });
    checks.push({
      name: "evolution",
      ok: r.ok,
      detail: r.ok ? `ok ${r.status}` : `fail ${r.status || r.error}`,
    });
  } else {
    checks.push({ name: "evolution", ok: false, detail: "EVOLUTION_API_URL/KEY not set" });
  }

  const allOk = health.ok && checks.every((c) => c.ok);
  return NextResponse.json(
    {
      ok: allOk,
      status: allOk ? "healthy" : "degraded",
      service: "bonjee-os",
      version: "2.0.0",
      mode: health.mode,
      checks,
      counts: health.counts,
      timestamp: new Date().toISOString(),
    },
    { status: allOk ? 200 : 503 },
  );
}
