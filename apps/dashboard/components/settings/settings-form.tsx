"use client";

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import {
  AlertTriangle,
  CheckCircle2,
  Database,
  Download,
  Gauge,
  Loader2,
  Lock,
  RotateCcw,
  Save,
  ShieldCheck,
  Sparkles,
  Trash2,
  Volume2,
  Workflow,
} from "lucide-react";
import type { Connection, DataMode, UserPreferences } from "@/lib/types";
import { useTts } from "@/lib/use-tts";
import { StatusDot } from "@/components/ui/primitives";
import { cn } from "@/lib/utils";

const CONNECTION_ICON: Record<Connection["kind"], typeof Database> = {
  database: Database,
  automation: Workflow,
  whatsapp: AlertTriangle,
  ai: Sparkles,
  device: Gauge,
};

export function SettingsForm({
  user,
  mode,
  modeReason,
  connections,
  authEnabled,
}: {
  user: UserPreferences;
  mode: DataMode;
  modeReason: string;
  connections: Connection[];
  authEnabled: boolean;
}) {
  const router = useRouter();
  const [form, setForm] = useState<UserPreferences>(user);
  const [saveState, setSaveState] = useState<"idle" | "saving" | "saved" | "error">("idle");
  const [error, setError] = useState<string | null>(null);

  const [confirmText, setConfirmText] = useState("");
  const [purgeState, setPurgeState] = useState<"idle" | "working" | "done" | "error">("idle");
  const [purgeMessage, setPurgeMessage] = useState<string | null>(null);
  const [resetState, setResetState] = useState<"idle" | "working" | "done" | "error">("idle");

  const tts = useTts();
  const dirty = useMemo(() => JSON.stringify(form) !== JSON.stringify(user), [form, user]);

  const set = <K extends keyof UserPreferences>(key: K, value: UserPreferences[K]) => {
    setForm((prev) => ({ ...prev, [key]: value }));
    setSaveState("idle");
    setError(null);
  };

  const save = async () => {
    setSaveState("saving");
    setError(null);
    try {
      const response = await fetch("/api/settings", {
        method: "PATCH",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          ...form,
          reminderLeadMinutes: Number(form.reminderLeadMinutes),
          retentionDays: Number(form.retentionDays),
        }),
      });
      const body = (await response.json()) as { ok?: boolean; error?: string; user?: UserPreferences };
      if (!response.ok || !body.ok) throw new Error(body.error ?? "Could not save settings.");
      if (body.user) setForm(body.user);
      setSaveState("saved");
      router.refresh();
    } catch (cause) {
      setSaveState("error");
      setError(cause instanceof Error ? cause.message : "Could not save settings.");
    }
  };

  const purgeAll = async () => {
    if (confirmText !== "DELETE") return;
    setPurgeState("working");
    setPurgeMessage(null);
    try {
      const response = await fetch("/api/retention", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ scope: "all", confirm: "DELETE" }),
      });
      const body = (await response.json()) as { ok?: boolean; removed?: number; error?: string };
      if (!response.ok || !body.ok) throw new Error(body.error ?? "Deletion failed.");
      setPurgeState("done");
      setPurgeMessage(
        `${body.removed ?? 0} archived message${body.removed === 1 ? "" : "s"} removed. Derived rows are removed by cascade in live mode.`,
      );
      setConfirmText("");
      router.refresh();
    } catch (cause) {
      setPurgeState("error");
      setPurgeMessage(cause instanceof Error ? cause.message : "Deletion failed.");
    }
  };

  const resetDemo = async () => {
    setResetState("working");
    try {
      const response = await fetch("/api/demo/reset", { method: "POST" });
      if (!response.ok) {
        const body = (await response.json()) as { error?: string };
        throw new Error(body.error ?? "Reset failed.");
      }
      setResetState("done");
      router.refresh();
    } catch {
      setResetState("error");
    }
  };

  const lock = async () => {
    await fetch("/api/auth", { method: "DELETE" });
    router.push("/login");
    router.refresh();
  };

  return (
    <div className="space-y-4">
      <div className="panel panel-glow hud-corners flex flex-wrap items-center justify-between gap-3 p-4">
        <div>
          <p className="eyebrow">Settings</p>
          <h1 className="display mt-1 text-xl font-semibold text-ink">Tune the cockpit.</h1>
          <p className="mt-1 text-[10.5px] text-ink-faint">
            Secrets are read from environment variables on the server and are never shown here.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {saveState === "saved" ? (
            <span className="mono flex items-center gap-1.5 text-[9px] uppercase tracking-[0.12em] text-lime">
              <CheckCircle2 className="h-3 w-3" /> saved
            </span>
          ) : null}
          {saveState === "error" ? (
            <span className="mono flex items-center gap-1.5 text-[9px] uppercase tracking-[0.12em] text-rose">
              <AlertTriangle className="h-3 w-3" /> {error ?? "save failed"}
            </span>
          ) : null}
          <button
            onClick={save}
            disabled={saveState === "saving" || !dirty}
            className="btn-primary py-2 text-xs disabled:opacity-50"
          >
            {saveState === "saving" ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Save className="h-3.5 w-3.5" />}
            {dirty ? "Save changes" : "Saved"}
          </button>
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Section kicker="Identity" title="Profile and locale">
          <Field label="Display name">
            <input className="field" value={form.displayName} onChange={(event) => set("displayName", event.target.value)} maxLength={120} />
          </Field>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Timezone (IANA)">
              <input className="field" value={form.timezone} onChange={(event) => set("timezone", event.target.value)} placeholder="Africa/Lagos" />
            </Field>
            <Field label="Locale">
              <input className="field" value={form.locale} onChange={(event) => set("locale", event.target.value)} placeholder="en-NG" />
            </Field>
          </div>
          <p className="text-[9px] leading-relaxed text-ink-faint">
            Timezone affects countdowns, calendar grouping and briefing dates. An invalid timezone is
            rejected by the API.
          </p>
        </Section>

        <Section kicker="Intelligence" title="Models and retrieval">
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="LLM provider">
              <input className="field" list="provider-options" value={form.llmProvider} onChange={(event) => set("llmProvider", event.target.value)} />
              <datalist id="provider-options">
                <option value="OpenAI-compatible" />
                <option value="openai" />
                <option value="ollama" />
                <option value="custom" />
              </datalist>
            </Field>
            <Field label="LLM model">
              <input className="field" list="model-options" value={form.llmModel} onChange={(event) => set("llmModel", event.target.value)} />
              <datalist id="model-options">
                <option value="gpt-4o-mini" />
                <option value="gpt-4.1-mini" />
                <option value="llama3.1" />
                <option value="qwen2.5" />
              </datalist>
            </Field>
          </div>
          <Field label="Embedding model">
            <input className="field" value={form.embeddingModel} onChange={(event) => set("embeddingModel", event.target.value)} />
          </Field>
          <div className="rounded-xl border border-cyan/15 bg-cyan/[0.04] p-3">
            <p className="mono flex items-center gap-1.5 text-[8px] uppercase tracking-[0.12em] text-cyan">
              <Sparkles className="h-3 w-3" /> Current mode
            </p>
            <p className="mt-1 text-[10px] leading-relaxed text-ink-faint">
              Deterministic retrieval and grounded answers always run. LLM augmentation is used only
              when a key is configured; the model name here is advisory metadata. Embedding search is
              not active until the pgvector pipeline is implemented.
            </p>
          </div>
        </Section>

        <Section kicker="Voice and capture" title="Hands-free behaviour">
          <ToggleRow
            label="Voice output"
            detail="Allow spoken answers in Ask Jarvis on this device."
            checked={form.voiceEnabled}
            onChange={(value) => set("voiceEnabled", value)}
          />
          <ToggleRow
            label="Auto-create tasks"
            detail="When enabled, high-confidence commitments can be written without manual confirmation. Off by default."
            checked={form.autoCreateTasks}
            onChange={(value) => set("autoCreateTasks", value)}
          />
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={() => tts.speak("Good evening. All cockpit systems are operational. Every answer is tied to a stored source.")}
              disabled={!tts.supported}
              className="btn-ghost py-2 text-[10px]"
            >
              <Volume2 className="h-3.5 w-3.5" /> Test voice
            </button>
            <span className="mono text-[8.5px] uppercase tracking-[0.1em] text-ink-faint">
              {tts.supported ? "Web Speech API available" : "not supported in this browser"}
            </span>
          </div>
        </Section>

        <Section kicker="Timing" title="Reminders and retention">
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Reminder lead (minutes)">
              <input
                type="number"
                min={0}
                max={20160}
                className="field"
                value={form.reminderLeadMinutes}
                onChange={(event) => set("reminderLeadMinutes", Number(event.target.value))}
              />
            </Field>
            <Field label="Briefing time">
              <input type="time" className="field" value={form.briefingTime} onChange={(event) => set("briefingTime", event.target.value)} />
            </Field>
          </div>
          <Field label="Retention (days)">
            <input
              type="number"
              min={0}
              max={3650}
              className="field"
              value={form.retentionDays}
              onChange={(event) => set("retentionDays", Number(event.target.value))}
            />
          </Field>
          <p className="text-[9px] leading-relaxed text-ink-faint">
            Zero means keep until deleted. Retention is stored as a preference; the scheduled purge
            job is not wired yet, so do not treat this value as an enforced policy.
          </p>
        </Section>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Section
          kicker="Runtime"
          title="Connections"
          action={
            <span className={cn("chip", mode === "live" ? "chip-lime" : "chip-amber")}>
              <StatusDot tone={mode === "live" ? "lime" : "amber"} pulse />
              {mode === "live" ? "live" : "demo"}
            </span>
          }
        >
          <p className="-mt-1 text-[9.5px] leading-relaxed text-ink-faint">{modeReason}</p>
          <div className="space-y-1.5">
            {connections.map((connection) => {
              const Icon = CONNECTION_ICON[connection.kind];
              const ok = connection.status === "connected" || connection.status === "demo";
              return (
                <div key={connection.id} className="flex items-start gap-2.5 rounded-xl border border-white/[0.05] bg-white/[0.015] p-2.5">
                  <Icon className="mt-0.5 h-3.5 w-3.5 shrink-0 text-ink-faint" />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center justify-between gap-2">
                      <p className="truncate text-[10.5px] text-ink-dim">{connection.name}</p>
                      <span className={cn("mono shrink-0 text-[8px] uppercase tracking-[0.1em]", ok ? "text-lime/80" : "text-amber/80")}>
                        {connection.status}
                      </span>
                    </div>
                    <p className="mt-0.5 text-[9px] leading-relaxed text-ink-faint">{connection.detail}</p>
                  </div>
                </div>
              );
            })}
          </div>
          <div className="rounded-lg border border-white/[0.05] bg-black/20 p-2.5">
            <p className="mono text-[8px] uppercase tracking-[0.12em] text-ink-faint">Server environment keys</p>
            <p className="mono mt-1 text-[8.5px] leading-relaxed text-cyan/70">
              DATABASE_URL · N8N_BASE_URL · EVOLUTION_API_URL · OPENAI_API_KEY · JARVIS_LLM_BASE_URL ·
              DASHBOARD_PASSPHRASE · JARVIS_STT_API_KEY
            </p>
            <p className="mt-1 text-[8.5px] leading-relaxed text-ink-faint">
              Values are never returned by the API. A key existing does not mean the upstream service
              is reachable; verify through health checks and real traffic.
            </p>
          </div>
        </Section>

        <Section kicker="Safety" title="Access and data control" action={<ShieldCheck className="h-3.5 w-3.5 text-lime/70" />}>
          <div className="rounded-xl border border-white/[0.06] bg-white/[0.02] p-3">
            <div className="flex items-center justify-between gap-2">
              <div>
                <p className="text-[10.5px] font-medium text-ink-dim">Cockpit passphrase</p>
                <p className="mt-0.5 text-[9px] text-ink-faint">
                  {authEnabled
                    ? "Enabled. All pages and APIs require a signed session cookie."
                    : "Disabled. Anyone who can reach this URL can read the archive. Set DASHBOARD_PASSPHRASE before exposing it."}
                </p>
              </div>
              <span className={cn("chip shrink-0", authEnabled ? "chip-lime" : "chip-amber")}>
                {authEnabled ? "locked" : "open"}
              </span>
            </div>
            {authEnabled ? (
              <button onClick={lock} className="btn-ghost mt-2 w-full justify-center py-1.5 text-[10px]">
                <Lock className="h-3 w-3" /> Lock and sign out
              </button>
            ) : null}
          </div>

          <div className="rounded-xl border border-white/[0.06] bg-white/[0.02] p-3">
            <div className="flex items-center justify-between gap-2">
              <div>
                <p className="text-[10.5px] font-medium text-ink-dim">Export archive</p>
                <p className="mt-0.5 text-[9px] text-ink-faint">One JSON object per line, original messages only.</p>
              </div>
              <Link href="/api/export" className="btn-ghost shrink-0 py-1.5 text-[10px]" prefetch={false}>
                <Download className="h-3 w-3" /> JSONL
              </Link>
            </div>
          </div>

          <div className="rounded-xl border border-rose/20 bg-rose/[0.04] p-3">
            <p className="mono flex items-center gap-1.5 text-[8px] uppercase tracking-[0.12em] text-rose">
              <Trash2 className="h-3 w-3" /> Delete all archive data
            </p>
            <p className="mt-1 text-[9px] leading-relaxed text-rose/80">
              Removes messages and cascades to derived tasks, evidence and events in live mode. This is
              irreversible. Type DELETE to confirm.
            </p>
            <div className="mt-2 flex gap-2">
              <input
                value={confirmText}
                onChange={(event) => setConfirmText(event.target.value)}
                placeholder="DELETE"
                className="field h-8 flex-1 py-0 text-[11px]"
                aria-label="Type DELETE to confirm"
              />
              <button
                onClick={purgeAll}
                disabled={confirmText !== "DELETE" || purgeState === "working"}
                className="btn-danger px-3 py-1.5 text-[10px]"
              >
                {purgeState === "working" ? <Loader2 className="h-3 w-3 animate-spin" /> : "Purge"}
              </button>
            </div>
            {purgeMessage ? (
              <p className={cn("mt-1.5 text-[9px]", purgeState === "error" ? "text-rose" : "text-lime/80")}>{purgeMessage}</p>
            ) : null}
          </div>

          {mode === "demo" ? (
            <div className="flex items-center justify-between gap-2 rounded-xl border border-white/[0.06] bg-white/[0.02] p-3">
              <div>
                <p className="text-[10.5px] font-medium text-ink-dim">Restore demo fixtures</p>
                <p className="mt-0.5 text-[9px] text-ink-faint">Only available when no DATABASE_URL is configured.</p>
              </div>
              <button onClick={resetDemo} disabled={resetState === "working"} className="btn-ghost shrink-0 py-1.5 text-[10px]">
                {resetState === "working" ? <Loader2 className="h-3 w-3 animate-spin" /> : <RotateCcw className="h-3 w-3" />}
                {resetState === "done" ? "Reset" : "Restore"}
              </button>
            </div>
          ) : null}
        </Section>
      </div>

      <div className="panel flex flex-wrap items-center justify-between gap-2 p-4">
        <p className="text-[9.5px] leading-relaxed text-ink-faint">
          Need the full deployment steps? Read <code className="mono text-cyan/80">docs/ENVIRONMENT.md</code> and{" "}
          <code className="mono text-cyan/80">docs/SETUP_RENDER.md</code>.
        </p>
        <Link href="/automations" className="mono flex items-center gap-1 text-[9px] uppercase tracking-[0.12em] text-cyan/80 hover:text-cyan">
          Workflow inventory <Workflow className="h-3 w-3" />
        </Link>
      </div>
    </div>
  );
}

function Section({
  kicker,
  title,
  action,
  children,
}: {
  kicker: string;
  title: string;
  action?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <section className="panel p-4">
      <div className="mb-3 flex items-start justify-between gap-2">
        <div>
          <p className="eyebrow">{kicker}</p>
          <h2 className="display mt-0.5 text-sm font-semibold text-ink">{title}</h2>
        </div>
        {action}
      </div>
      <div className="space-y-3">{children}</div>
    </section>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="label">{label}</span>
      {children}
    </label>
  );
}

function ToggleRow({
  label,
  detail,
  checked,
  onChange,
}: {
  label: string;
  detail: string;
  checked: boolean;
  onChange: (value: boolean) => void;
}) {
  return (
    <div className="flex items-start justify-between gap-3 rounded-xl border border-white/[0.05] bg-white/[0.015] p-3">
      <div>
        <p className="text-[10.5px] font-medium text-ink-dim">{label}</p>
        <p className="mt-0.5 text-[9px] leading-relaxed text-ink-faint">{detail}</p>
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        onClick={() => onChange(!checked)}
        className={cn(
          "relative mt-0.5 h-5 w-9 shrink-0 rounded-full border transition-colors",
          checked ? "border-cyan/40 bg-cyan/30" : "border-white/10 bg-white/[0.06]",
        )}
      >
        <span
          className={cn(
            "absolute top-0.5 h-3.5 w-3.5 rounded-full transition-all",
            checked ? "left-[18px] bg-cyan shadow-glow" : "left-0.5 bg-slate-400",
          )}
        />
      </button>
    </div>
  );
}
