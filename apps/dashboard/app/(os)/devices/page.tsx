import Link from "next/link";
import {
  AlertTriangle,
  ArrowUpRight,
  BellRing,
  CheckCircle2,
  CircleDashed,
  Copy,
  KeyRound,
  ShieldCheck,
  Smartphone,
  Terminal,
  XCircle,
} from "lucide-react";
import { getSnapshot } from "@/lib/store";
import { DeviceRuntime } from "@/components/devices/device-runtime";
import { CopyButton } from "@/components/ui/copy-button";
import { Panel, PanelHeader, StatusDot } from "@/components/ui/primitives";
import { cn } from "@/lib/utils";

export const dynamic = "force-dynamic";

const N8N_DEFAULT = "https://bonjee-n8n.onrender.com";

export default async function DevicesPage() {
  const snapshot = await getSnapshot();
  const androidMessages = snapshot.messages.filter((message) => message.source === "android");
  const lastAndroidAt =
    androidMessages
      .map((message) => message.timestamp)
      .sort((a, b) => b.localeCompare(a))[0] ?? null;
  const androidLink = snapshot.connections.find((connection) => connection.kind === "device");
  const n8nBase = process.env.N8N_BASE_URL?.replace(/\/$/, "") ?? N8N_DEFAULT;
  const n8nConfigured = Boolean(process.env.N8N_BASE_URL);

  return (
    <div className="space-y-4">
      <Panel glow corners className="relative overflow-hidden p-5 sm:p-6">
        <div className="relative flex flex-wrap items-start justify-between gap-4">
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <span className={cn("chip", androidLink?.status === "connected" ? "chip-lime" : "chip-amber")}>
                <StatusDot tone={androidLink?.status === "connected" ? "lime" : "amber"} pulse />
                {androidLink?.status === "connected" ? "Device linked" : "No verified device"}
              </span>
              <span className="mono text-[9px] uppercase tracking-[0.18em] text-ink-faint">
                Android companion · {androidLink?.status ?? "unconfigured"}
              </span>
            </div>
            <h1 className="display mt-3 text-2xl font-semibold text-ink">Your phone is the sensor.</h1>
            <p className="mt-1.5 max-w-3xl text-xs leading-relaxed text-ink-dim">
              Bonjee Notifier captures notifications you explicitly allow, queues them through dead
              zones, and posts them to your n8n webhook. This cockpit only reads the resulting archive.
            </p>
          </div>
          <div className="rounded-xl border border-white/[0.07] bg-black/25 p-3">
            <p className="mono text-[8px] uppercase tracking-[0.14em] text-ink-faint">APK build</p>
            <p className="mt-1 text-[11px] text-ink">Not built in this environment</p>
            <p className="mt-0.5 text-[9px] text-ink-faint">No JDK or Android SDK detected.</p>
          </div>
        </div>
      </Panel>

      <DeviceRuntime mode={snapshot.mode} androidMessages={androidMessages.length} lastAndroidAt={lastAndroidAt} />

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)]">
        <Panel className="p-0">
          <PanelHeader kicker="Companion" title="Bonjee Notifier" action={<Smartphone className="h-3.5 w-3.5 text-ink-faint" />} />
          <div className="space-y-3 p-4">
            <div className="grid grid-cols-2 gap-2">
              <Fact label="Package" value="com.bonjee.notifier" />
              <Fact label="Source" value="apps/android-notifier" />
              <Fact label="Min SDK" value="21" />
              <Fact label="Target SDK" value="34" />
              <Fact label="Transport" value="HTTPS → n8n" />
              <Fact label="Queue" value="SQLite, retry 30s" />
            </div>

            <div className="rounded-xl border border-white/[0.06] bg-black/25 p-3">
              <div className="flex items-center justify-between gap-2">
                <p className="mono text-[8px] uppercase tracking-[0.14em] text-ink-faint">n8n base URL</p>
                <span className={cn("mono text-[8px] uppercase tracking-[0.1em]", n8nConfigured ? "text-lime" : "text-amber")}>
                  {n8nConfigured ? "from env" : "Android default"}
                </span>
              </div>
              <div className="mt-1 flex items-center gap-2">
                <code className="mono min-w-0 flex-1 truncate text-[11px] text-cyan">{n8nBase}</code>
                <CopyButton value={n8nBase} className="shrink-0" />
              </div>
              <div className="mt-2 grid gap-1.5">
                {[
                  ["Notification path", "/webhook/notifications-ingest"],
                  ["Ask path", "/webhook/personal-ai-ask"],
                  ["Health path", "/webhook/health"],
                ].map(([label, path]) => (
                  <div key={label} className="flex items-center justify-between gap-2">
                    <span className="mono text-[8.5px] uppercase tracking-[0.1em] text-ink-faint">{label}</span>
                    <code className="mono truncate text-[9px] text-ink-dim">{path}</code>
                    <CopyButton value={`${n8nBase}${path}`} label="URL" className="shrink-0 border-0 bg-transparent px-1 py-0.5" />
                  </div>
                ))}
              </div>
            </div>

            <p className="text-[9.5px] leading-relaxed text-ink-faint">
              Defaults are compiled from <code className="mono text-cyan/80">app/build.gradle</code> and can be
              changed inside the app at runtime. Setting <code className="mono text-cyan/80">N8N_BASE_URL</code> affects
              this cockpit's status display, not the already-installed APK.
            </p>
          </div>
        </Panel>

        <Panel className="p-0">
          <PanelHeader kicker="Bring-up" title="Install and verify" action={<Terminal className="h-3.5 w-3.5 text-ink-faint" />} />
          <div className="space-y-2 p-4">
            <Step index="01" title="Build the APK" done={false}>
              <CodeBlock value={"cd apps\\android-notifier\npowershell -ExecutionPolicy Bypass -File setup-toolchain.ps1\npowershell -ExecutionPolicy Bypass -File build-apk.ps1"} />
              <p>Output: <code className="mono text-[9px] text-cyan/80">app\build\outputs\apk\debug\app-debug.apk</code></p>
            </Step>
            <Step index="02" title="Sideload on the phone" done={false}>
              Copy the APK to the device, allow unknown-app installs for the file manager, then open
              Bonjee Notifier.
            </Step>
            <Step index="03" title="Configure the webhook" done={n8nConfigured}>
              Enter the n8n base URL and keep the three paths. Optionally add an auth header that your
              n8n webhook validates. Tap <strong className="text-ink-dim">Test connection</strong>.
            </Step>
            <Step index="04" title="Grant notification access" done={false}>
              Settings → Apps → Special app access → Notification access → enable Bonjee Notifier.
              Capture is impossible without this permission.
            </Step>
            <Step index="05" title="Start capture" done={false}>
              Tap <strong className="text-ink-dim">Start capture service</strong>. A persistent notification is the
              honest indicator. Trigger a test notification and confirm it reaches n8n Executions.
            </Step>
          </div>
        </Panel>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Panel className="p-0">
          <PanelHeader kicker="Capability truth" title="What is actually built" action={<BellRing className="h-3.5 w-3.5 text-ink-faint" />} />
          <div className="space-y-1.5 p-4">
            {[
              [true, "Notification capture", "Kotlin NotificationListenerService. Implemented."],
              [true, "Durable offline queue", "SQLite queue with automatic retry and manual drain."],
              [true, "Ask your assistant", "AskActivity posts to /webhook/personal-ai-ask when n8n is reachable."],
              [true, "Per-app blocklist", "Ignored packages are never captured."],
              [false, "Push reminders", "No reminder receiver or Android alarm scheduling. Planned only."],
              [false, "Calendar sync", "No device calendar provider integration. Planned only."],
              [false, "Compose UI", "The notifier uses XML views, not Jetpack Compose."],
            ].map(([ok, label, detail]) => (
              <div key={String(label)} className="flex items-start gap-2.5 rounded-lg border border-white/[0.05] px-3 py-2">
                {ok ? (
                  <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0 text-lime/80" />
                ) : (
                  <XCircle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-ink-faint" />
                )}
                <div>
                  <p className={cn("text-[10.5px] font-medium", ok ? "text-ink-dim" : "text-ink-faint")}>{label}</p>
                  <p className="mt-0.5 text-[9px] leading-relaxed text-ink-faint">{detail}</p>
                </div>
              </div>
            ))}
          </div>
        </Panel>

        <Panel className="p-0">
          <PanelHeader kicker="Security" title="Scope and boundaries" action={<ShieldCheck className="h-3.5 w-3.5 text-lime/70" />} />
          <div className="space-y-2 p-4">
            {[
              {
                icon: KeyRound,
                title: "No secrets on the phone",
                body: "The APK contains no database URL, WhatsApp key or LLM key. It stores only the base URL, paths and an optional header token in private SharedPreferences.",
              },
              {
                icon: ShieldCheck,
                title: "Notification access is broad",
                body: "The OS hands over notifications from every app unless you blocklist it. Add banking, password managers and private messaging apps to the ignore list.",
              },
              {
                icon: AlertTriangle,
                title: "TLS is required",
                body: "Use an https:// n8n URL. Plain HTTP exposes notification content and any auth header to the network.",
              },
              {
                icon: CircleDashed,
                title: "Replay protection is not built",
                body: "The queue retries delivery; the archive must deduplicate on a stable notification identifier. Verify this before trusting counts.",
              },
            ].map((item) => {
              const Icon = item.icon;
              return (
                <div key={item.title} className="flex items-start gap-2.5 rounded-xl border border-white/[0.05] bg-white/[0.015] p-3">
                  <Icon className="mt-0.5 h-3.5 w-3.5 shrink-0 text-cyan/70" />
                  <div>
                    <p className="text-[10.5px] font-medium text-ink-dim">{item.title}</p>
                    <p className="mt-0.5 text-[9px] leading-relaxed text-ink-faint">{item.body}</p>
                  </div>
                </div>
              );
            })}
            <div className="flex items-center gap-2 rounded-lg border border-white/[0.05] px-2.5 py-2">
              <span className="mono flex-1 truncate text-[8.5px] text-ink-faint">apps/android-notifier/README.md</span>
              <CopyButton value="apps/android-notifier/README.md" className="border-0 bg-transparent px-1 py-0.5" />
            </div>
          </div>
        </Panel>
      </div>

      <Panel className="flex flex-wrap items-center justify-between gap-3 p-4">
        <div className="flex items-center gap-2.5">
          <Copy className="h-4 w-4 text-ink-faint" />
          <div>
            <p className="text-[11px] font-medium text-ink">Experimental Expo client</p>
            <p className="mt-0.5 text-[9.5px] text-ink-faint">
              <code className="mono">apps/bonjee-mobile</code> is an incomplete scaffold. It is not
              part of the supported path and was not built or tested.
            </p>
          </div>
        </div>
        <div className="flex gap-2">
          <Link href="/automations" className="btn-ghost text-[10px]">
            Workflow status <ArrowUpRight className="h-3 w-3" />
          </Link>
          <Link href="/settings" className="btn-ghost text-[10px]">
            Security settings <ArrowUpRight className="h-3 w-3" />
          </Link>
        </div>
      </Panel>
    </div>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-white/[0.05] bg-white/[0.02] px-2.5 py-2">
      <p className="mono text-[8px] uppercase tracking-[0.12em] text-ink-faint">{label}</p>
      <p className="mono mt-0.5 truncate text-[10px] text-ink-dim">{value}</p>
    </div>
  );
}

function Step({
  index,
  title,
  done,
  children,
}: {
  index: string;
  title: string;
  done: boolean;
  children: React.ReactNode;
}) {
  return (
    <div className="rounded-xl border border-white/[0.05] bg-white/[0.015] p-3">
      <div className="flex items-center gap-2">
        <span className={cn("mono grid h-5 w-5 place-items-center rounded-md border text-[8.5px]", done ? "border-lime/25 bg-lime/10 text-lime" : "border-cyan/20 bg-cyan/[0.06] text-cyan")}>
          {done ? <CheckCircle2 className="h-3 w-3" /> : index}
        </span>
        <p className="text-[10.5px] font-medium text-ink-dim">{title}</p>
      </div>
      <div className="mt-1.5 space-y-1 pl-7 text-[9px] leading-relaxed text-ink-faint">{children}</div>
    </div>
  );
}

function CodeBlock({ value }: { value: string }) {
  return (
    <div className="relative rounded-lg border border-white/[0.06] bg-black/40 p-2 pr-10">
      <pre className="mono overflow-x-auto whitespace-pre text-[8.5px] leading-relaxed text-cyan/80">{value}</pre>
      <CopyButton value={value} className="absolute right-1 top-1 border-0 bg-transparent px-1 py-0.5" />
    </div>
  );
}
