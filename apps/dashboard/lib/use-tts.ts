"use client";

import { useCallback, useEffect, useRef, useState } from "react";

export function useTts() {
  const [supported, setSupported] = useState(false);
  const [speaking, setSpeaking] = useState(false);
  const [enabled, setEnabled] = useState(true);
  const utteranceRef = useRef<SpeechSynthesisUtterance | null>(null);

  useEffect(() => {
    setSupported(typeof window !== "undefined" && "speechSynthesis" in window);
    return () => {
      if (typeof window !== "undefined" && "speechSynthesis" in window) {
        window.speechSynthesis.cancel();
      }
    };
  }, []);

  const stop = useCallback(() => {
    if (typeof window !== "undefined" && "speechSynthesis" in window) {
      window.speechSynthesis.cancel();
    }
    setSpeaking(false);
  }, []);

  const speak = useCallback(
    (text: string) => {
      if (!supported || !enabled || !text.trim()) return;
      window.speechSynthesis.cancel();
      const utterance = new SpeechSynthesisUtterance(text.slice(0, 2400));
      utterance.rate = 1.02;
      utterance.pitch = 1.02;
      utterance.volume = 0.95;
      const voices = window.speechSynthesis.getVoices();
      const preferred = voices.find((v) => /en-GB|en-NG|en-ZA/i.test(v.lang)) ?? voices.find((v) => /^en/i.test(v.lang));
      if (preferred) utterance.voice = preferred;
      utterance.onstart = () => setSpeaking(true);
      utterance.onend = () => setSpeaking(false);
      utterance.onerror = () => setSpeaking(false);
      utteranceRef.current = utterance;
      window.speechSynthesis.speak(utterance);
    },
    [enabled, supported],
  );

  return { supported, enabled, setEnabled, speaking, speak, stop };
}
