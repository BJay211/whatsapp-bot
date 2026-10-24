"use client";

import { useEffect, useState } from "react";
import { Activity, Loader2, Smartphone, Wifi, WifiOff } from "lucide-react";
import { CopyButton } from "@/components/ui/copy-button";
import { Panel, PanelHeader, StatusDot } from "@/components/ui/primitives";
import { cn } from "@/lib/utils";

type Health = {
  ok?: boolean;
  status?: string;
  mode?: string;
  checks?: { name: string; ok: boolean; detail: string }[];
};

export function DeviceRuntime({
  mode,
  androidMessages,
  lastAndroidAt,
}: {
  mode: "demo" | "live";
  androidMessages: number;
  lastAndroidAt: string | null;
}) {
  const [origin, setOrigin] = useState<string | null>(null);
  const [health, setHealth] = useState<Health | null>(null);
  const [checking, setChecking] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setOrigin(window.location.origin);
  }, []);

  const runCheck = async () => {
    setChecking(true);
    setError(null);
    try {
      const response = await fetch("/api/health", { cache: "no-store" });
      const body = (await response.json()) as Health;
      setHealth(body);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Health check failed.");
    } finally {
      setChecking(false);
    }
  };

  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Panel className="p-0">
        <PanelHeader
          kicker="Device runtime"
          title="This cockpit from your phone"
          action={<Smartphone className="h-3.5 w-3.5 text-ink-faint" />}
        />
        <div className="space-y-3 p-4">
          <div className="rounded-xl border border-white/[0.06] bg-white/[0.02] p-3">
            <p className="mono text-[8px] uppercase tracking-[0.14em] text-ink-faint">Dashboard origin</p>
            <p className="mono mt-1 truncate text-[11px] text-cyan">{origin ?? "resolving…"}</p>
            {origin ? <CopyButton value={origin} label="Copy URL" className="mt-2" /> : null}
            <p className="mt-2 text-[9.5px] leading-relaxed text-ink-faint">
              The cockpit is responsive. Open this origin on your phone, then add it to your home
              screen if you want a full-screen view. It is not a native app.
            </p>
          </div>

          <div className="rounded-xl border border-white/[0.06] bg-white/[0.02] p-3">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Activity className="h-3.5 w-3.5 text-cyan" />
                <p className="text-[11px] font-medium text-ink">Live health probe</p>
              </div>
              <button
                onClick={runCheck}
                disabled={checking}
                className="mono rounded-lg border border-cyan/25 bg-cyan/[0.06] px-2.5 py-1 text-[9px] uppercase tracking-[0.1em] text-cyan transition-colors hover:bg-cyan/[0.12] disabled:opacity-60"
              >
                {checking ? <Loader2 className="h-3 w-3 animate-spin" /> : "Run"}
              </button>
            </div>
            {health ? (
              <div className="mt-2 space-y-1.5">
                <div className="flex items-center gap-2">
                  <StatusDot tone={health.ok ? "lime" : "amber"} pulse />
                  <span className="mono text-[9px] uppercase tracking-[0.12em] text-ink-dim">
                    {health.status ?? "unknown"} · {health.mode ?? mode}
                  </span>
                </div>
                {health.checks?.map((check) => (
                  <div key={check.name} className="flex items-start gap-2">
                    <span className={cn("mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full", check.ok ? "bg-lime" : "bg-amber")} />
                    <div className="min-w-0">
                      <p className="mono text-[8.5px] uppercase tracking-[0.1em] text-ink-faint">{check.name}</p>
                      <p className="truncate text-[9px] text-ink-faint">{check.detail}</p>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <p className="mt-2 text-[9.5px] leading-relaxed text-ink-faint">
                Probe the route handler this page uses. It reports the data mode and configured links;
                it cannot test your phone.
              </p>
            )}
            {error ? <p className="mt-2 text-[9.5px] text-rose">{error}</p> : null}
          </div>

          <div className="flex items-start gap-2 rounded-xl border border-amber/15 bg-amber/[0.04] p-3">
            {androidMessages > 0 ? (
              <Wifi className="mt-0.5 h-3.5 w-3.5 shrink-0 text-amber" />
            ) : (
              <WifiOff className="mt-0.5 h-3.5 w-3.5 shrink-0 text-amber" />
            )}
            <div>
              <p className="mono text-[8px] uppercase tracking-[0.12em] text-amber/80">
                {mode === "demo" ? "Synthetic device data" : "Archive device signals"}
              </p>
              <p className="mt-1 text-[10px] leading-relaxed text-amber/80">
                {mode === "demo"
                  ? `${androidMessages} demo notification${androidMessages === 1 ? "" : "s"} are fixtures. No phone has delivered to this instance.`
                  : `${androidMessages} Android notification${androidMessages === 1 ? "" : "s"} in the archive${
                      lastAndroidAt ? `; newest ${new Date(lastAndroidAt).toLocaleString()}` : ""
                    }. Device identity and delivery acknowledgment are not stored.`}
              </p>
            </div>
          </div>
        </div>
      </Panel>

      <Panel className="p-0">
        <PanelHeader kicker="Architecture" title="Where device data actually goes" action={<Wifi className="h-3.5 w-3.5 text-ink-faint" />} />
        <div className="p-4">
          <div className="space-y-1.5">
            {[
              { step: "01", label: "Android notification listener", detail: "Captures authorized notifications into a durable SQLite queue." },
              { step: "02", label: "n8n webhook", detail: "POST /webhook/notifications-ingest. The app does not talk to the dashboard directly." },
              { step: "03", label: "PostgreSQL archive", detail: "n8n normalizes and inserts the notification. Originals stay immutable." },
              { step: "04", label: "Bonjee OS", detail: "This cockpit reads the archive and links signals to missions and evidence." },
            ].map((row) => (
              <div key={row.step} className="flex gap-3 rounded-lg border border-white/[0.05] px-3 py-2.5">
                <span className="mono shrink-0 text-[9px] text-cyan/70">{row.step}</span>
                <div>
                  <p className="text-[10.5px] font-medium text-ink-dim">{row.label}</p>
                  <p className="mt-0.5 text-[9px] leading-relaxed text-ink-faint">{row.detail}</p>
                </div>
              </div>
            ))}
          </div>
          <p className="mt-3 rounded-lg border border-rose/15 bg-rose/[0.04] p-2.5 text-[9.5px] leading-relaxed text-rose/80">
            Consequence: if n8n is offline or the workflow is inactive, the phone queues notifications
            locally. They will not appear here until delivery succeeds.
          </p>
        </div>
      </Panel>
    </div>
  );
}
