"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { ArrowRight, CornerDownLeft, Search, Sparkles, X } from "lucide-react";
import { NAV_ITEMS } from "./nav";
import { cn } from "@/lib/utils";

const PROMPTS = [
  "What is my next deadline?",
  "Which deadlines changed?",
  "What do I need for CSC 401?",
  "Summarise what I missed today",
  "What should I prepare for tomorrow?",
  "Show me the original assignment announcement",
];

export function CommandPalette({
  open,
  onClose,
}: {
  open: boolean;
  onClose: () => void;
}) {
  const router = useRouter();
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  const results = useMemo(() => {
    const q = query.trim().toLowerCase();
    const nav = NAV_ITEMS.filter(
      (item) => !q || item.label.toLowerCase().includes(q) || item.description.toLowerCase().includes(q),
    ).map((item) => ({
      id: `nav:${item.href}`,
      label: item.label,
      hint: item.description,
      icon: item.icon,
      run: () => router.push(item.href),
    }));

    const prompts = PROMPTS.filter((p) => !q || p.toLowerCase().includes(q)).map((p) => ({
      id: `ask:${p}`,
      label: p,
      hint: "Ask Jarvis",
      icon: Sparkles,
      run: () => router.push(`/ask?q=${encodeURIComponent(p)}`),
    }));

    return [...prompts, ...nav].slice(0, 10);
  }, [query, router]);

  useEffect(() => {
    if (!open) return;
    setQuery("");
    setActive(0);
    const id = window.setTimeout(() => inputRef.current?.focus(), 30);
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
      if (event.key === "ArrowDown") {
        event.preventDefault();
        setActive((a) => Math.min(a + 1, results.length - 1));
      }
      if (event.key === "ArrowUp") {
        event.preventDefault();
        setActive((a) => Math.max(a - 1, 0));
      }
      if (event.key === "Enter") {
        event.preventDefault();
        results[active]?.run();
        onClose();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => {
      window.clearTimeout(id);
      window.removeEventListener("keydown", onKey);
    };
  }, [open, onClose, results, active]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-[90] flex items-start justify-center px-4 pt-[12vh]">
      <div className="absolute inset-0 bg-black/70 backdrop-blur-md" onClick={onClose} aria-hidden />
      <div className="panel panel-glow hud-corners relative w-full max-w-xl overflow-hidden animate-rise">
        <div className="flex items-center gap-3 border-b border-white/[0.07] px-4">
          <Search className="h-4 w-4 text-cyan" />
          <input
            ref={inputRef}
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              setActive(0);
            }}
            placeholder="Jump to a module or ask a question…"
            className="h-14 flex-1 bg-transparent text-sm text-ink outline-none placeholder:text-ink-faint"
          />
          <button onClick={onClose} className="rounded-md p-1 text-ink-faint hover:text-ink" aria-label="Close">
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="max-h-[52vh] overflow-y-auto p-2 scroll-area">
          {results.length === 0 ? (
            <p className="px-3 py-8 text-center text-xs text-ink-faint">No commands match.</p>
          ) : (
            results.map((result, index) => {
              const Icon = result.icon;
              return (
                <button
                  key={result.id}
                  onMouseEnter={() => setActive(index)}
                  onClick={() => {
                    result.run();
                    onClose();
                  }}
                  className={cn(
                    "flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left transition-colors",
                    index === active ? "bg-cyan/[0.08] text-ink" : "text-ink-dim hover:bg-white/[0.03]",
                  )}
                >
                  <span className="grid h-7 w-7 place-items-center rounded-lg border border-white/[0.07] bg-white/[0.02]">
                    <Icon className="h-3.5 w-3.5" />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-xs font-medium">{result.label}</span>
                    <span className="mono block truncate text-[9px] uppercase tracking-[0.14em] text-ink-faint">
                      {result.hint}
                    </span>
                  </span>
                  {index === active ? <CornerDownLeft className="h-3.5 w-3.5 text-cyan/70" /> : null}
                </button>
              );
            })
          )}
        </div>

        <div className="flex items-center justify-between border-t border-white/[0.07] px-4 py-2.5">
          <span className="mono flex items-center gap-2 text-[9px] uppercase tracking-[0.16em] text-ink-faint">
            <ArrowRight className="h-3 w-3" /> {results.length} results
          </span>
          <span className="mono flex items-center gap-1.5 text-[9px] text-ink-faint">
            <span className="kbd">↑</span><span className="kbd">↓</span> navigate
            <span className="kbd">↵</span> open
          </span>
        </div>
      </div>
    </div>
  );
}
