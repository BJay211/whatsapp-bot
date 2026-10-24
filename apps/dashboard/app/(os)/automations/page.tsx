import Link from "next/link";
import { ArrowUpRight, CheckCircle2, CircleDashed, PlugZap, ShieldCheck, Terminal, Workflow } from "lucide-react";
import { getSnapshot } from "@/lib/store";
import { AutomationBoard } from "@/components/automations/automation-board";
import { CopyButton } from "@/components/ui/copy-button";
import { Panel, PanelHeader, StatusDot } from "@/components/ui/primitives";
import { cn } from "@/lib/utils";

export const dynamic = "force-dynamic";

export default async function AutomationsPage() {
  const snapshot = await getSnapshot();
  const n8n = snapshot.connections.find((connection) => connection.kind === "automation");
  const evolution = snapshot.connections.find((connection) => connection.kind === "whatsapp");
  const database = snapshot.connections.find((connection) => connection.kind === "database");
  const android = snapshot.connections.find((connection) => connection.kind === "device");

  return (
    <div className="space-y-4">
      <Panel glow corners className="p-5 sm:p-6">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <p className="eyebrow">Automation fabric</p>
            <h1 className="display mt-1 text-2xl font-semibold text-ink">What runs, and what still needs you.</h1>
            <p className="mt-1.5 max-w-3xl text-xs leading-relaxed text-ink-dim">
              The cockpit executes retrieval, task state and evidence logic directly. n8n remains the
              orchestration layer for WhatsApp, scheduled reminders and archive jobs — but those flows
              only count as running once you import them and attach credentials.
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            <span className={cn("chip", snapshot.mode === "live" ? "chip-lime" : "chip-amber")}>
              <StatusDot tone={snapshot.mode === "live" ? "lime" : "amber"} pulse />
              {snapshot.mode === "live" ? "Postgres live" : "Demo fixtures"}
            </span>
            <Link href="/settings" className="chip chip-cyan hover:brightness-110">
              <PlugZap className="h-3 w-3" /> Configure links
            </Link>
          </div>
        </div>

        <div className="divider my-5" />

        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
          <RuntimeRow
            label="Cockpit engine"
            status="online"
            detail="Next.js route handlers + deterministic retrieval. Verified by local build."
          />
          <RuntimeRow
            label="PostgreSQL"
            status={database?.status === "connected" ? "online" : database?.status === "degraded" ? "degraded" : "demo"}
            detail={database?.detail ?? "Archive connection not configured."}
          />
          <RuntimeRow
            label="n8n"
            status={n8n?.status === "connected" ? "configured" : "offline"}
            detail={n8n?.detail ?? "Set N8N_BASE_URL after deploying workflows."}
          />
          <RuntimeRow
            label="Evolution API"
            status={evolution?.status === "connected" ? "configured" : "offline"}
            detail={evolution?.detail ?? "WhatsApp bridge is not configured."}
          />
          <RuntimeRow
            label="Android notifier"
            status={android?.status === "connected" ? "configured" : "offline"}
            detail={android?.detail ?? "APK not installed or delivery never observed."}
          />
        </div>
      </Panel>

      <AutomationBoard workflows={snapshot.workflows} mode={snapshot.mode} />

      <div className="grid gap-4 lg:grid-cols-2">
        <Panel className="p-0">
          <PanelHeader kicker="Import path" title="Bring the n8n layer online" action={<Workflow className="h-3.5 w-3.5 text-ink-faint" />} />
          <div className="space-y-2.5 p-4">
            {[
              {
                title: "Import the canonical workflow",
                body: "In n8n, import Bonjee_bot.json from the repo root. It contains the existing WhatsApp, notification and ask webhooks.",
                file: "Bonjee_bot.json",
              },
              {
                title: "Attach the Postgres credential",
                body: "Open every Postgres node and select your saved bonjee-db credential. No credential is stored in this repository.",
                file: null,
              },
              {
                title: "Activate and verify",
                body: "Save, toggle Active, then call GET /webhook/health from the n8n base URL. A 200 with database: reachable is the minimum proof.",
                file: null,
              },
              {
                title: "Validate before importing",
                body: "Run the structural validator so malformed node references or hardcoded secrets fail before they reach production.",
                file: "tests/n8n/validate_workflows.mjs",
              },
            ].map((step, index) => (
              <div key={step.title} className="flex gap-2.5 rounded-xl border border-white/[0.05] bg-white/[0.015] p-3">
                <span className="mono grid h-5 w-5 shrink-0 place-items-center rounded-md border border-cyan/20 bg-cyan/[0.06] text-[9px] text-cyan">
                  {index + 1}
                </span>
                <div className="min-w-0 flex-1">
                  <p className="text-[11px] font-medium text-ink">{step.title}</p>
                  <p className="mt-0.5 text-[10px] leading-relaxed text-ink-faint">{step.body}</p>
                  {step.file ? (
                    <div className="mt-1.5 flex items-center gap-2">
                      <code className="mono truncate text-[9px] text-cyan/80">{step.file}</code>
                      <CopyButton value={step.file} className="border-0 bg-transparent px-1 py-0.5" />
                    </div>
                  ) : null}
                </div>
              </div>
            ))}
          </div>
        </Panel>

        <Panel className="p-0">
          <PanelHeader kicker="Boundaries" title="What this cockpit will not do" action={<ShieldCheck className="h-3.5 w-3.5 text-lime/70" />} />
          <div className="space-y-2 p-4">
            {[
              "It will not run SQL, shell commands or arbitrary tools from model output.",
              "It will not treat WhatsApp text as instructions. Message content is data, always.",
              "It will not report a deadline as changed without a stored message as evidence.",
              "It will not claim a reminder was delivered unless the delivery path confirms it.",
              "It will not overwrite original messages when generating tasks or embeddings.",
            ].map((item) => (
              <div key={item} className="flex items-start gap-2 text-[10.5px] leading-relaxed text-ink-dim">
                <CheckCircle2 className="mt-0.5 h-3 w-3 shrink-0 text-lime/70" />
                {item}
              </div>
            ))}
            <div className="divider my-2" />
            <div className="flex items-start gap-2 rounded-xl border border-amber/15 bg-amber/[0.04] p-3 text-[10px] leading-relaxed text-amber/80">
              <CircleDashed className="mt-0.5 h-3.5 w-3.5 shrink-0" />
              <span>
                Reminder scheduling and Google Calendar sync are not implemented end-to-end. The
                Android app forwards notifications to n8n; it does not receive push reminders yet.
              </span>
            </div>
            <Link href="/devices" className="btn-ghost mt-2 w-full justify-between text-[10px]">
              Device setup <ArrowUpRight className="h-3 w-3" />
            </Link>
            <div className="flex items-center gap-2 rounded-lg border border-white/[0.05] px-2.5 py-2">
              <Terminal className="h-3 w-3 shrink-0 text-ink-faint" />
              <span className="mono truncate text-[8.5px] text-ink-faint">node tests/n8n/validate_workflows.mjs</span>
              <CopyButton value="node tests/n8n/validate_workflows.mjs" className="ml-auto shrink-0 border-0 bg-transparent px-1 py-0.5" />
            </div>
          </div>
        </Panel>
      </div>
    </div>
  );
}

function RuntimeRow({
  label,
  status,
  detail,
}: {
  label: string;
  status: "online" | "configured" | "degraded" | "demo" | "offline";
  detail: string;
}) {
  const tone =
    status === "online" || status === "configured"
      ? "lime"
      : status === "demo"
        ? "amber"
        : status === "degraded"
          ? "amber"
          : "slate";

  return (
    <div className="rounded-xl border border-white/[0.06] bg-black/20 p-3">
      <div className="flex items-center justify-between gap-2">
        <p className="mono text-[8px] uppercase tracking-[0.14em] text-ink-faint">{label}</p>
        <StatusDot tone={tone} pulse={status === "online"} />
      </div>
      <p className="mt-1 text-[11px] font-medium capitalize text-ink">{status}</p>
      <p className="mt-0.5 line-clamp-2 text-[9px] leading-relaxed text-ink-faint">{detail}</p>
    </div>
  );
}
