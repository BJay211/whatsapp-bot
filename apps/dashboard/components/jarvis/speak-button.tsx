"use client";

import { Volume2, VolumeX } from "lucide-react";
import { cn } from "@/lib/utils";

export function SpeakButton({
  enabled,
  supported,
  speaking,
  onToggle,
  onStop,
  className,
}: {
  enabled: boolean;
  supported: boolean;
  speaking: boolean;
  onToggle: () => void;
  onStop: () => void;
  className?: string;
}) {
  if (!supported) return null;
  return (
    <button
      type="button"
      onClick={speaking ? onStop : onToggle}
      title={enabled ? "Voice output is on" : "Voice output is off"}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-lg border px-2 py-1 text-[10px] transition-colors",
        enabled
          ? "border-cyan/25 bg-cyan/10 text-cyan"
          : "border-white/10 bg-white/[0.03] text-ink-faint hover:text-ink-dim",
        className,
      )}
    >
      {enabled ? <Volume2 className={cn("h-3 w-3", speaking && "animate-pulse")} /> : <VolumeX className="h-3 w-3" />}
      {speaking ? "Speaking" : enabled ? "Voice on" : "Voice off"}
    </button>
  );
}
