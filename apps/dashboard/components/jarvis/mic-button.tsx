"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Mic, Square, TriangleAlert } from "lucide-react";
import { cn } from "@/lib/utils";

type SpeechResultEvent = {
  resultIndex: number;
  results: {
    length: number;
    [index: number]: { isFinal: boolean; 0: { transcript: string } };
  };
};

type SpeechRecognitionLike = {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  start: () => void;
  stop: () => void;
  abort: () => void;
  onresult: ((event: SpeechResultEvent) => void) | null;
  onerror: ((event: { error?: string }) => void) | null;
  onend: (() => void) | null;
  onstart: (() => void) | null;
};

type RecognitionCtor = new () => SpeechRecognitionLike;

function getRecognition(): RecognitionCtor | null {
  if (typeof window === "undefined") return null;
  const w = window as unknown as {
    SpeechRecognition?: RecognitionCtor;
    webkitSpeechRecognition?: RecognitionCtor;
  };
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null;
}

export function MicButton({
  onTranscript,
  onInterim,
  onListeningChange,
  disabled = false,
  className,
  size = "md",
  autoStart = false,
}: {
  onTranscript: (text: string) => void;
  onInterim?: (text: string) => void;
  onListeningChange?: (listening: boolean) => void;
  disabled?: boolean;
  className?: string;
  size?: "sm" | "md" | "lg";
  autoStart?: boolean;
}) {
  const [listening, setListening] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const recognitionRef = useRef<SpeechRecognitionLike | null>(null);
  const finalRef = useRef("");
  const transcriptRef = useRef(onTranscript);
  const interimRef = useRef(onInterim);
  const autoStarted = useRef(false);

  transcriptRef.current = onTranscript;
  interimRef.current = onInterim;

  const stop = useCallback(() => {
    recognitionRef.current?.stop();
  }, []);

  const start = useCallback(() => {
    setError(null);
    const Ctor = getRecognition();
    if (!Ctor) {
      setError("Voice input needs Chrome, Edge or another browser with the Web Speech API. You can still type.");
      return;
    }
    if (listening) {
      stop();
      return;
    }

    const recognition = new Ctor();
    recognitionRef.current = recognition;
    finalRef.current = "";
    recognition.lang = navigator.language || "en-NG";
    recognition.continuous = false;
    recognition.interimResults = true;

    recognition.onstart = () => setListening(true);
    recognition.onresult = (event) => {
      let interim = "";
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const result = event.results[i];
        const text = result[0]?.transcript ?? "";
        if (result.isFinal) finalRef.current += text;
        else interim += text;
      }
      interimRef.current?.((finalRef.current + interim).trim());
    };
    recognition.onerror = (event) => {
      const code = event.error ?? "unknown";
      const message =
        code === "not-allowed" || code === "service-not-allowed"
          ? "Microphone permission was denied. Allow microphone access in your browser settings."
          : code === "no-speech"
            ? "I did not hear anything. Try again."
            : `Voice input stopped (${code}).`;
      setError(message);
      setListening(false);
    };
    recognition.onend = () => {
      setListening(false);
      recognitionRef.current = null;
      const text = finalRef.current.trim();
      if (text) transcriptRef.current(text);
    };

    try {
      recognition.start();
    } catch {
      setError("Voice input could not start. Check microphone permissions.");
    }
  }, [listening, stop]);

  useEffect(() => {
    if (!autoStart || autoStarted.current) return;
    autoStarted.current = true;
    const timer = window.setTimeout(() => start(), 450);
    return () => window.clearTimeout(timer);
  }, [autoStart, start]);

  useEffect(() => {
    return () => {
      recognitionRef.current?.abort();
    };
  }, []);

  useEffect(() => {
    onListeningChange?.(listening);
  }, [listening, onListeningChange]);

  const dimension = size === "sm" ? "h-9 w-9" : size === "lg" ? "h-14 w-14" : "h-11 w-11";
  const icon = size === "sm" ? "h-4 w-4" : size === "lg" ? "h-6 w-6" : "h-5 w-5";

  return (
    <div className={cn("flex flex-col items-center gap-1.5", className)}>
      <button
        type="button"
        onClick={start}
        disabled={disabled}
        aria-pressed={listening}
        aria-label={listening ? "Stop listening" : "Start voice input"}
        className={cn(
          "relative grid place-items-center rounded-full border transition-all disabled:opacity-40",
          dimension,
          listening
            ? "border-cyan/60 bg-cyan/15 text-cyan shadow-glow"
            : "border-white/10 bg-white/[0.035] text-ink-dim hover:border-cyan/40 hover:text-cyan",
        )}
      >
        {listening ? <Square className={cn(icon, "fill-current")} /> : <Mic className={icon} strokeWidth={1.8} />}
        {listening ? (
          <>
            <span className="absolute inset-0 animate-ping rounded-full border border-cyan/40" />
            <span className="absolute -inset-2 rounded-full border border-cyan/20" />
          </>
        ) : null}
      </button>
      {listening ? (
        <div className="flex h-4 items-end gap-[3px]" aria-hidden>
          {[0, 1, 2, 3, 4].map((i) => (
            <span
              key={i}
              className="wavebar h-4"
              style={{ animationDelay: `${i * 120}ms`, height: `${8 + ((i * 5) % 14)}px` }}
            />
          ))}
        </div>
      ) : null}
      {error ? (
        <div className="flex max-w-[240px] items-start gap-1.5 text-[10px] leading-snug text-amber">
          <TriangleAlert className="mt-0.5 h-3 w-3 shrink-0" />
          <span>{error}</span>
        </div>
      ) : null}
    </div>
  );
}
