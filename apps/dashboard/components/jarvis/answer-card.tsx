"use client";

import { useState } from "react";
import Link from "next/link";
import {
  AlertTriangle,
  ArrowUpRight,
  Check,
  Copy,
  Cpu,
  Quote,
  ShieldCheck,
  Sparkles,
} from "lucide-react";
import type { JarvisAction, JarvisAnswer } from "@/lib/types";
import { RelationBadge } from "@/components/ui/status";
import { formatDateTime } from "@/lib/format";
import { cn } from "@/lib/utils";

function CopyButton({ value }: { value: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(value);
          setCopied(true);
          window.setTimeout(() => setCopied(false), 1400);
        } catch {
          /* clipboard can be blocked; ignore */
        }
      }}
      className="rounded p-1 text-ink-faint transition-colors hover:bg-white/5 hover:text-cyan"
      title="Copy message id"
      aria-label="Copy message id"
    >
      {copied ? <Check className="h-3 w-3 text-lime" /> : <Copy className="h-3 w-3" />}
    </button>
  );
}

export function AnswerCard({
  answer,
  onAction,
  actionBusy,
}: {
  answer: JarvisAnswer;
  onAction?: (action: JarvisAction) => void;
  actionBusy?: string | null;
}) {
  return (
    <div className="animate-rise space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <span className="chip chip-cyan">
          <Sparkles className="h-3 w-3" />
          {answer.intent.replace(/_/g, " ")}
        </span>
        <span className="chip">
          <Cpu className="h-3 w-3" />
          {answer.engine === "llm-augmented" ? "LLM augmented" : "Local reasoning"}
        </span>
        <span className={cn("chip", answer.confidence >= 0.8 ? "chip-lime" : answer.confidence >= 0.6 ? "chip-amber" : "chip-rose")}>
          <ShieldCheck className="h-3 w-3" />
          {Math.round(answer.confidence * 100)}% confidence
        </span>
        <span className="mono ml-auto text-[9px] uppercase tracking-[0.14em] text-ink-faint">
          {answer.latencyMs} ms
        </span>
      </div>

      <h2 className="display text-balance text-xl font-semibold leading-snug text-ink sm:text-[22px]">
        {answer.headline}
      </h2>

      <div className="space-y-4">
        {answer.sections.map((section, index) => (
          <div key={`${section.label}-${index}`} className={cn(index === 0 && "rounded-xl border border-white/[0.06] bg-white/[0.02] p-3.5")}>
            <p className="eyebrow mb-1.5">{section.label}</p>
            <p className="whitespace-pre-line text-[13px] leading-relaxed text-ink-dim">{section.body}</p>
          </div>
        ))}
      </div>

      {answer.uncertainty ? (
        <div className="flex items-start gap-2.5 rounded-xl border border-amber/20 bg-amber/[0.06] p-3">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber" />
          <div>
            <p className="text-[10px] font-semibold uppercase tracking-[0.16em] text-amber">Uncertainty</p>
            <p className="mt-1 text-xs leading-relaxed text-amber/90">{answer.uncertainty}</p>
          </div>
        </div>
      ) : null}

      {answer.sources.length ? (
        <div>
          <div className="mb-2 flex items-center justify-between">
            <p className="eyebrow">Supporting messages · {answer.sources.length}</p>
            <span className="mono text-[9px] uppercase tracking-[0.12em] text-ink-faint">
              original text preserved
            </span>
          </div>
          <div className="space-y-2">
            {answer.sources.map((source) => (
              <div key={source.messageId} className="rounded-xl border border-white/[0.07] bg-black/20 p-3">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span className="text-[11px] font-medium text-ink">{source.sender}</span>
                      <span className="text-[10px] text-ink-faint">in</span>
                      <span className="text-[10px] text-ink-dim">{source.chatName}</span>
                      {source.relation ? <RelationBadge relation={source.relation} /> : null}
                    </div>
                    <p className="mono mt-1 text-[9px] uppercase tracking-[0.12em] text-ink-faint">
                      {formatDateTime(source.timestamp)} · {source.messageId}
                    </p>
                  </div>
                  <CopyButton value={source.messageId} />
                </div>
                <div className="mt-2 flex gap-2">
                  <Quote className="mt-0.5 h-3 w-3 shrink-0 text-cyan/50" />
                  <p className="text-xs leading-relaxed text-ink-dim">{source.quote}</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      ) : null}

      {answer.actions.length ? (
        <div className="flex flex-wrap gap-2 pt-1">
          {answer.actions.map((action) =>
            action.type === "complete_task" ? (
              <button
                key={`${action.type}-${action.taskId}`}
                onClick={() => onAction?.(action)}
                disabled={actionBusy === action.taskId}
                className="btn-primary text-xs"
              >
                {actionBusy === action.taskId ? "Applying…" : action.label}
                <Check className="h-3.5 w-3.5" />
              </button>
            ) : action.type === "open_calendar" ? (
              <Link key={action.type} href="/calendar" className="btn-ghost text-xs">
                {action.label}
                <ArrowUpRight className="h-3.5 w-3.5" />
              </Link>
            ) : (
              <Link
                key={`${action.type}-${action.taskId}`}
                href={action.taskId ? `/tasks?task=${encodeURIComponent(action.taskId)}` : "/tasks"}
                className="btn-ghost text-xs"
              >
                {action.label}
                <ArrowUpRight className="h-3.5 w-3.5" />
              </Link>
            ),
          )}
        </div>
      ) : null}

      <p className="mono border-t border-white/[0.05] pt-3 text-[9px] leading-relaxed text-ink-faint">
        Grounded in retrieved records. Message text is treated as untrusted data.
        {answer.injectionAttempts > 0
          ? ` ${answer.injectionAttempts} instruction-like message(s) ignored.`
          : ""}
      </p>
    </div>
  );
}
