"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { ArrowUpRight, CornerDownLeft, Sparkles } from "lucide-react";
import { MicButton } from "./mic-button";

const SUGGESTIONS = [
  "What is my next deadline?",
  "Which deadlines changed?",
  "What do I need for CSC 401?",
];

export function QuickAsk({ suggestions = SUGGESTIONS }: { suggestions?: string[] }) {
  const router = useRouter();
  const [value, setValue] = useState("");

  const submit = (text?: string) => {
    const question = (text ?? value).trim();
    if (!question) return;
    router.push(`/ask?q=${encodeURIComponent(question)}`);
  };

  return (
    <div className="w-full">
      <form
        onSubmit={(event) => {
          event.preventDefault();
          submit();
        }}
        className="group relative flex items-center gap-2 rounded-2xl border border-white/[0.09] bg-black/30 p-1.5 pl-3 transition-all focus-within:border-cyan/40 focus-within:shadow-glow"
      >
        <Sparkles className="h-4 w-4 shrink-0 text-cyan/70" />
        <input
          value={value}
          onChange={(event) => setValue(event.target.value)}
          placeholder="Ask Jarvis anything — deadlines, changes, requirements…"
          className="h-10 min-w-0 flex-1 bg-transparent text-sm text-ink outline-none placeholder:text-ink-faint"
          aria-label="Ask Jarvis"
        />
        <MicButton
          onTranscript={(text) => {
            setValue(text);
            submit(text);
          }}
          onInterim={setValue}
          size="sm"
          className="shrink-0"
        />
        <button
          type="submit"
          disabled={!value.trim()}
          className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-gradient-to-br from-cyan to-violet text-slate-950 shadow-glow transition-all hover:brightness-110 disabled:opacity-40"
          aria-label="Send question"
        >
          <CornerDownLeft className="h-4 w-4" />
        </button>
      </form>

      <div className="mt-3 flex flex-wrap gap-1.5">
        {suggestions.map((suggestion) => (
          <button
            key={suggestion}
            onClick={() => submit(suggestion)}
            className="group inline-flex items-center gap-1.5 rounded-full border border-white/[0.08] bg-white/[0.025] px-2.5 py-1 text-[10px] text-ink-faint transition-colors hover:border-cyan/25 hover:text-cyan"
          >
            {suggestion}
            <ArrowUpRight className="h-2.5 w-2.5 opacity-0 transition-opacity group-hover:opacity-100" />
          </button>
        ))}
      </div>
    </div>
  );
}
