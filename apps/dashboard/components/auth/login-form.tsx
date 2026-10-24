"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { AlertTriangle, ArrowRight, Eye, EyeOff, Loader2, LockKeyhole, ShieldCheck } from "lucide-react";
import { JarvisOrb } from "@/components/jarvis/orb";

export function LoginForm({
  authEnabled,
  nextPath,
}: {
  authEnabled: boolean;
  nextPath: string;
}) {
  const router = useRouter();
  const [passphrase, setPassphrase] = useState("");
  const [reveal, setReveal] = useState(false);
  const [state, setState] = useState<"idle" | "working" | "error">("idle");
  const [error, setError] = useState<string | null>(null);

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!passphrase.trim()) {
      setState("error");
      setError("Enter the cockpit passphrase.");
      return;
    }
    setState("working");
    setError(null);
    try {
      const response = await fetch("/api/auth", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ passphrase }),
      });
      const body = (await response.json()) as { ok?: boolean; error?: string };
      if (!response.ok || !body.ok) throw new Error(body.error ?? "Access denied.");
      router.replace(nextPath);
      router.refresh();
    } catch (cause) {
      setState("error");
      setError(cause instanceof Error ? cause.message : "Access denied.");
    }
  };

  return (
    <main className="relative flex min-h-screen items-center justify-center px-4 py-12">
      <div className="pointer-events-none absolute inset-0 overflow-hidden" aria-hidden>
        <div className="absolute left-1/2 top-1/2 h-[520px] w-[520px] -translate-x-1/2 -translate-y-1/2 rounded-full bg-cyan/[0.05] blur-3xl" />
        <div className="absolute bottom-0 right-0 h-[340px] w-[340px] rounded-full bg-violet/[0.08] blur-3xl" />
      </div>

      <div className="relative w-full max-w-md">
        <div className="mb-6 flex flex-col items-center text-center">
          <JarvisOrb size={112} />
          <p className="mono mt-5 text-[9px] uppercase tracking-[0.34em] text-cyan/70">Bonjee OS · access control</p>
          <h1 className="display mt-2 text-2xl font-semibold text-ink">
            {authEnabled ? "Operator verification" : "Cockpit unlocked"}
          </h1>
          <p className="mt-1.5 max-w-sm text-xs leading-relaxed text-ink-faint">
            {authEnabled
              ? "This instance is protected by a single passphrase. A signed session cookie is issued on success and expires after 30 days."
              : "No DASHBOARD_PASSPHRASE is configured. The cockpit is open to anyone who can reach this URL."}
          </p>
        </div>

        <div className="panel panel-glow hud-corners p-5 sm:p-6">
          {!authEnabled ? (
            <div className="space-y-4">
              <div className="flex items-start gap-2.5 rounded-xl border border-amber/20 bg-amber/[0.05] p-3">
                <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-amber" />
                <p className="text-[10px] leading-relaxed text-amber/85">
                  Authentication is disabled. Set <code className="mono">DASHBOARD_PASSPHRASE</code> in the
                  server environment before exposing this instance to a network you do not control.
                </p>
              </div>
              <Link href="/" className="btn-primary w-full justify-between">
                Enter the cockpit <ArrowRight className="h-4 w-4" />
              </Link>
            </div>
          ) : (
            <form onSubmit={submit} className="space-y-4">
              <label className="block">
                <span className="label">Passphrase</span>
                <div className="relative">
                  <LockKeyhole className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-faint" />
                  <input
                    type={reveal ? "text" : "password"}
                    value={passphrase}
                    onChange={(event) => {
                      setPassphrase(event.target.value);
                      setState("idle");
                      setError(null);
                    }}
                    autoFocus
                    autoComplete="current-password"
                    className="field py-3 pl-10 pr-10"
                    placeholder="Enter passphrase"
                  />
                  <button
                    type="button"
                    onClick={() => setReveal((value) => !value)}
                    className="absolute right-2.5 top-1/2 -translate-y-1/2 rounded-md p-1 text-ink-faint transition-colors hover:text-ink"
                    aria-label={reveal ? "Hide passphrase" : "Show passphrase"}
                  >
                    {reveal ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                  </button>
                </div>
              </label>

              {error ? (
                <div className="flex items-start gap-2 rounded-lg border border-rose/25 bg-rose/[0.06] p-2.5 text-[10px] leading-relaxed text-rose">
                  <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                  {error}
                </div>
              ) : null}

              <button type="submit" disabled={state === "working"} className="btn-primary w-full justify-between">
                {state === "working" ? (
                  <span className="flex items-center gap-2">
                    <Loader2 className="h-4 w-4 animate-spin" /> Verifying
                  </span>
                ) : (
                  <span className="flex items-center gap-2">
                    <ShieldCheck className="h-4 w-4" /> Unlock cockpit
                  </span>
                )}
                <ArrowRight className="h-4 w-4" />
              </button>
            </form>
          )}

          <div className="divider my-4" />
          <div className="space-y-1.5">
            <p className="mono text-[8px] uppercase tracking-[0.14em] text-ink-faint">How this works</p>
            <p className="text-[9px] leading-relaxed text-ink-faint">
              This is not a multi-user account system. The passphrase is compared server-side and an
              HMAC token is stored in an httpOnly cookie. Failed attempts are delayed, not locked out.
              There is no password reset email.
            </p>
          </div>
        </div>

        <p className="mono mt-4 text-center text-[8px] uppercase tracking-[0.22em] text-ink-faint">
          local-first · source-backed · no fabricated answers
        </p>
      </div>
    </main>
  );
}
