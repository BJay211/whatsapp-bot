"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  ArrowUp,
  BrainCircuit,
  Database,
  Eraser,
  Fingerprint,
  Loader2,
  Lock,
  Mic,
  RefreshCw,
  ShieldCheck,
  Sparkles,
  Volume2,
} from "lucide-react";
import Image from "next/image";
import type { JarvisAction, JarvisAnswer } from "@/lib/types";
import { AnswerCard } from "./answer-card";
import { JarvisOrb } from "./orb";
import { MicButton } from "./mic-button";
import { SpeakButton } from "./speak-button";
import { useTts } from "@/lib/use-tts";
import { cn } from "@/lib/utils";

type Turn =
  | { kind: "user"; id: string; text: string }
  | { kind: "answer"; id: string; answer: JarvisAnswer }
  | { kind: "note"; id: string; text: string; tone: "ok" | "error" };

const STARTERS = [
  "What is my next deadline?",
  "Which deadlines changed?",
  "What do I need for CSC 401?",
  "Summarise what I missed today",
  "What should I prepare for tomorrow?",
  "Show me the original assignment announcement",
];

let seq = 0;
const nextId = () => `turn_${Date.now()}_${seq++}`;

export function AskConsole({
  initialQuery,
  autoVoice = false,
  mode,
  archiveSize,
  timezone,
}: {
  initialQuery?: string;
  autoVoice?: boolean;
  mode: "demo" | "live";
  archiveSize: number;
  timezone: string;
}) {
  const router = useRouter();
  const [turns, setTurns] = useState<Turn[]>([]);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [orbState, setOrbState] = useState<"idle" | "listening" | "thinking">("idle");
  const [busyAction, setBusyAction] = useState<string | null>(null);
  const tts = useTts();
  const scrollRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const started = useRef(false);
  const voiceTurn = useRef(false);

  const speakAnswer = useCallback(
    (answer: JarvisAnswer) => {
      const script = [answer.headline, ...answer.sections.map((section) => `${section.label}. ${section.body}`)]
        .join(". ")
        .replace(/\n+/g, ". ")
        .replace(/[•✓○]/g, " ");
      tts.speak(script);
    },
    [tts],
  );

  const send = useCallback(
    async (question: string, fromVoice = false) => {
      const text = question.trim();
      if (!text || loading) return;
      setError(null);
      setTurns((prev) => [...prev, { kind: "user", id: nextId(), text }]);
      setInput("");
      setLoading(true);
      setOrbState("thinking");
      voiceTurn.current = fromVoice || autoVoice;

      try {
        const response = await fetch("/api/jarvis", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ message: text, timezone }),
        });
        const payload = (await response.json()) as { ok: boolean; answer?: JarvisAnswer; error?: string };
        if (!response.ok || !payload.ok || !payload.answer) {
          throw new Error(payload.error ?? "Jarvis could not answer that.");
        }
        setTurns((prev) => [...prev, { kind: "answer", id: nextId(), answer: payload.answer! }]);
        if (voiceTurn.current) speakAnswer(payload.answer);
      } catch (err) {
        setError(err instanceof Error ? err.message : "Jarvis could not answer that.");
        setTurns((prev) => [
          ...prev,
          {
            kind: "note",
            id: nextId(),
            text: err instanceof Error ? err.message : "Jarvis could not answer that.",
            tone: "error",
          },
        ]);
      } finally {
        setLoading(false);
        setOrbState("idle");
        inputRef.current?.focus();
      }
    },
    [autoVoice, loading, speakAnswer, timezone],
  );

  useEffect(() => {
    if (!initialQuery || started.current) return;
    started.current = true;
    void send(initialQuery, autoVoice);
  }, [initialQuery, autoVoice, send]);

  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [turns, loading]);

  const handleAction = async (action: JarvisAction) => {
    if (action.type !== "complete_task" || !action.taskId) return;
    if (!window.confirm(`${action.label}?\n\nThis changes the task status and writes an audit entry.`)) return;
    setBusyAction(action.taskId);
    try {
      const response = await fetch("/api/tasks", {
        method: "PATCH",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ id: action.taskId, status: "completed" }),
      });
      const payload = (await response.json()) as { ok: boolean; error?: string };
      if (!response.ok || !payload.ok) throw new Error(payload.error ?? "Could not update the task.");
      setTurns((prev) => [
        ...prev,
        {
          kind: "note",
          id: nextId(),
          text: "Task marked completed. The revision history and audit log were updated.",
          tone: "ok",
        },
      ]);
      router.refresh();
    } catch (err) {
      setTurns((prev) => [
        ...prev,
        { kind: "note", id: nextId(), text: err instanceof Error ? err.message : "Update failed.", tone: "error" },
      ]);
    } finally {
      setBusyAction(null);
    }
  };

  const newSession = () => {
    tts.stop();
    setTurns([]);
    setError(null);
    setInput("");
    started.current = true;
    inputRef.current?.focus();
  };

  const empty = turns.length === 0;

  return (
    <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_320px]">
      <div className="panel flex min-h-[calc(100vh-190px)] flex-col overflow-hidden">
        <div className="flex items-center justify-between gap-3 border-b border-white/[0.06] px-4 py-3 sm:px-5">
          <div className="flex items-center gap-2.5">
            <span className="relative flex h-2 w-2">
              <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-cyan opacity-50" />
              <span className="relative inline-flex h-2 w-2 rounded-full bg-cyan" />
            </span>
            <div>
              <p className="mono text-[9px] uppercase tracking-[0.22em] text-ink-faint">Neural link</p>
              <p className="text-xs font-medium text-ink">Jarvis is online</p>
            </div>
          </div>
          <div className="flex items-center gap-1.5">
            <SpeakButton
              enabled={tts.enabled}
              supported={tts.supported}
              speaking={tts.speaking}
              onToggle={() => tts.setEnabled(!tts.enabled)}
              onStop={tts.stop}
            />
            <button onClick={newSession} className="btn-ghost px-2.5 py-1 text-[10px]" title="Start a new session">
              <Eraser className="h-3 w-3" />
              New
            </button>
          </div>
        </div>

        <div ref={scrollRef} className="scroll-area flex-1 overflow-y-auto px-4 py-5 sm:px-6">
          {empty ? (
            <div className="mx-auto flex max-w-2xl flex-col items-center pt-4 text-center sm:pt-10">
              <div className="animate-float">
                <JarvisOrb size={148} state={orbState} />
              </div>
              <p className="eyebrow mt-6">Grounded personal intelligence</p>
              <h2 className="display mt-2 text-balance text-2xl font-semibold text-ink sm:text-3xl">
                What do you want to <span className="neon-text">know</span>?
              </h2>
              <p className="mt-2 max-w-md text-sm leading-relaxed text-ink-faint">
                Ask about your messages, deadlines, classes or what changed. I answer from retrieved
                evidence and show you the original source — never from thin air.
              </p>

              <div className="mt-6 grid w-full gap-2 sm:grid-cols-2">
                {STARTERS.map((starter) => (
                  <button
                    key={starter}
                    onClick={() => void send(starter)}
                    className="group flex items-center justify-between gap-2 rounded-xl border border-white/[0.07] bg-white/[0.02] px-3 py-2.5 text-left text-xs text-ink-dim transition-all hover:border-cyan/30 hover:bg-cyan/[0.04] hover:text-ink"
                  >
                    <span>{starter}</span>
                    <ArrowUp className="h-3.5 w-3.5 rotate-45 text-ink-faint transition-colors group-hover:text-cyan" />
                  </button>
                ))}
              </div>
            </div>
          ) : (
            <div className="mx-auto max-w-3xl space-y-6">
              {turns.map((turn) =>
                turn.kind === "user" ? (
                  <div key={turn.id} className="flex justify-end">
                    <div className="max-w-[85%] rounded-2xl rounded-br-md border border-cyan/20 bg-cyan/[0.07] px-4 py-2.5 text-sm text-ink">
                      {turn.text}
                    </div>
                  </div>
                ) : turn.kind === "answer" ? (
                  <div key={turn.id} className="panel px-4 py-4 sm:px-5">
                    <AnswerCard answer={turn.answer} onAction={handleAction} actionBusy={busyAction} />
                  </div>
                ) : (
                  <div
                    key={turn.id}
                    className={cn(
                      "rounded-xl border px-3 py-2 text-xs",
                      turn.tone === "ok"
                        ? "border-lime/20 bg-lime/[0.06] text-lime"
                        : "border-rose/20 bg-rose/[0.06] text-rose",
                    )}
                  >
                    {turn.text}
                  </div>
                ),
              )}

              {loading ? (
                <div className="panel flex items-center gap-3 px-4 py-3.5">
                  <JarvisOrb size={30} state="thinking" className="shrink-0" />
                  <div className="min-w-0 flex-1">
                    <p className="text-xs font-medium text-ink">Retrieving evidence…</p>
                    <p className="mono mt-0.5 text-[9px] uppercase tracking-[0.14em] text-ink-faint">
                      full-text · structured records · relationships
                    </p>
                  </div>
                  <Loader2 className="h-4 w-4 animate-spin text-cyan/60" />
                </div>
              ) : null}
            </div>
          )}
        </div>

        <div className="border-t border-white/[0.06] p-3 sm:p-4">
          {error ? <p className="mb-2 text-[11px] text-rose">{error}</p> : null}
          <form
            onSubmit={(event) => {
              event.preventDefault();
              void send(input);
            }}
            className="flex items-center gap-2 rounded-2xl border border-white/[0.09] bg-black/30 p-1.5 pl-3 transition-all focus-within:border-cyan/40 focus-within:shadow-glow"
          >
            <Sparkles className="h-4 w-4 shrink-0 text-cyan/70" />
            <input
              ref={inputRef}
              value={input}
              onChange={(event) => setInput(event.target.value)}
              placeholder="Ask about a deadline, a course, a change…"
              className="h-10 min-w-0 flex-1 bg-transparent text-sm text-ink outline-none placeholder:text-ink-faint"
              aria-label="Message Jarvis"
            />
            <MicButton
              onTranscript={(text) => void send(text, true)}
              onInterim={setInput}
              onListeningChange={(listening) => setOrbState(listening ? "listening" : "idle")}
              size="sm"
            />
            <button
              type="submit"
              disabled={!input.trim() || loading}
              className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-gradient-to-br from-cyan to-violet text-slate-950 shadow-glow transition-all hover:brightness-110 disabled:opacity-40"
              aria-label="Send"
            >
              <ArrowUp className="h-4 w-4" />
            </button>
          </form>
          <div className="mt-2 flex items-center justify-between gap-2">
            <p className="mono flex items-center gap-1.5 text-[9px] uppercase tracking-[0.12em] text-ink-faint">
              <Mic className="h-2.5 w-2.5" />
              Voice uses browser speech recognition
            </p>
            <p className="mono hidden text-[9px] uppercase tracking-[0.12em] text-ink-faint sm:block">
              Enter to send · data never leaves your stack
            </p>
          </div>
        </div>
      </div>

      <aside className="space-y-3">
        <div className="panel p-4">
          <p className="eyebrow">Grounding protocol</p>
          <div className="mt-3 space-y-3">
            {[
              { icon: Database, title: "Retrieval first", body: "Structured records, full-text and relationships before generation." },
              { icon: Fingerprint, title: "Source attribution", body: "Every claim links back to an original message id and timestamp." },
              { icon: ShieldCheck, title: "Untrusted input", body: "Message text can never override security rules or tools." },
              { icon: Lock, title: "No open tools", body: "No SQL, shell or arbitrary actions from model output." },
            ].map((item) => {
              const Icon = item.icon;
              return (
                <div key={item.title} className="flex gap-2.5">
                  <span className="mt-0.5 grid h-6 w-6 shrink-0 place-items-center rounded-md border border-white/[0.07] bg-white/[0.03] text-cyan">
                    <Icon className="h-3 w-3" />
                  </span>
                  <div>
                    <p className="text-[11px] font-medium text-ink">{item.title}</p>
                    <p className="mt-0.5 text-[10px] leading-relaxed text-ink-faint">{item.body}</p>
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        <div className="panel p-4">
          <div className="flex items-center justify-between">
            <p className="eyebrow">Context</p>
            <span className={cn("chip", mode === "live" ? "chip-lime" : "chip-amber")}>
              {mode === "live" ? "live" : "demo"}
            </span>
          </div>
          <dl className="mt-3 space-y-2 text-[11px]">
            <div className="flex items-center justify-between">
              <dt className="text-ink-faint">Archive</dt>
              <dd className="mono text-ink-dim">{archiveSize} messages</dd>
            </div>
            <div className="flex items-center justify-between">
              <dt className="text-ink-faint">Timezone</dt>
              <dd className="mono text-ink-dim">{timezone}</dd>
            </div>
            <div className="flex items-center justify-between">
              <dt className="text-ink-faint">Engine</dt>
              <dd className="mono text-ink-dim">local + optional LLM</dd>
            </div>
          </dl>
          <div className="divider my-3" />
          <p className="text-[10px] leading-relaxed text-ink-faint">
            The model never receives the whole archive — only a bounded set of retrieved evidence for
            the current question.
          </p>
        </div>

        <div className="panel p-4">
          <p className="eyebrow">Voice</p>
          <div className="mt-2 flex items-center justify-between gap-2">
            <p className="text-[11px] text-ink-dim">Spoken answers</p>
            <button
              onClick={() => tts.setEnabled(!tts.enabled)}
              className={cn(
                "relative h-5 w-9 rounded-full border transition-colors",
                tts.enabled ? "border-cyan/40 bg-cyan/20" : "border-white/10 bg-white/5",
              )}
              aria-label="Toggle spoken answers"
              aria-pressed={tts.enabled}
            >
              <span
                className={cn(
                  "absolute top-0.5 h-3.5 w-3.5 rounded-full bg-ink transition-transform",
                  tts.enabled ? "translate-x-[18px] bg-cyan" : "translate-x-0.5 bg-ink-faint",
                )}
              />
            </button>
          </div>
          <p className="mt-2 flex items-start gap-1.5 text-[10px] leading-relaxed text-ink-faint">
            <Volume2 className="mt-0.5 h-3 w-3 shrink-0" />
            {tts.enabled
              ? "Answers to voice questions are read aloud using the system voice."
              : "Answers stay silent. Enable to hear responses to voice questions."}
          </p>
        </div>

        <Link href="/automations" className="panel panel-interactive flex items-center gap-3 p-3.5">
          <span className="grid h-8 w-8 place-items-center rounded-lg border border-violet/20 bg-violet/10 text-violet-300">
            <BrainCircuit className="h-4 w-4" />
          </span>
          <div className="min-w-0 flex-1">
            <p className="text-[11px] font-medium text-ink">See the machinery</p>
            <p className="text-[10px] text-ink-faint">17 workflow modules, honest status</p>
          </div>
          <RefreshCw className="h-3.5 w-3.5 text-ink-faint" />
        </Link>

        <p className="px-1 text-[9px] leading-relaxed text-ink-faint">
          <Image src="/icon.svg" alt="" width={10} height={10} className="mr-1 inline opacity-60" />
          Voice input requires microphone permission. Audio is handled by the browser&apos;s speech
          service; the cockpit does not store recordings.
        </p>
      </aside>
    </div>
  );
}
