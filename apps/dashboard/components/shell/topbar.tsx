"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { Bell, Menu, Mic, Search, Sparkles } from "lucide-react";
import { findNav } from "./nav";
import { StatusDot } from "@/components/ui/primitives";

function useClock() {
  const [now, setNow] = useState<Date | null>(null);
  useEffect(() => {
    setNow(new Date());
    const id = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(id);
  }, []);
  return now;
}

export function Topbar({
  onMenu,
  onSearch,
  mode,
  attention,
}: {
  onMenu: () => void;
  onSearch: () => void;
  mode: "demo" | "live";
  attention: number;
}) {
  const pathname = usePathname();
  const nav = useMemo(() => findNav(pathname), [pathname]);
  const now = useClock();

  const time = now
    ? new Intl.DateTimeFormat("en-GB", {
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        hour12: false,
      }).format(now)
    : "--:--:--";

  const date = now
    ? new Intl.DateTimeFormat("en-GB", {
        weekday: "short",
        day: "2-digit",
        month: "short",
      }).format(now).toUpperCase()
    : "---";

  return (
    <header className="sticky top-0 z-30 flex h-16 items-center gap-3 border-b border-cyan/10 bg-[#000814]/85 px-4 backdrop-blur-2xl lg:px-7">
      <button
        onClick={onMenu}
        className="rounded-lg border border-white/10 p-2 text-ink-dim hover:text-ink lg:hidden"
        aria-label="Open navigation"
      >
        <Menu className="h-4 w-4" />
      </button>

      <div className="min-w-0">
        <div className="mono flex items-center gap-2 text-[9px] uppercase tracking-[0.22em] text-ink-faint">
          <span>Bonjee OS</span>
          <span className="text-cyan/60">/</span>
          <span className="truncate text-ink-dim">{nav.short}</span>
        </div>
        <h1 className="display mt-0.5 truncate text-[15px] font-semibold text-ink">{nav.label}</h1>
      </div>

      <button
        onClick={onSearch}
        className="group ml-auto hidden h-9 min-w-[240px] max-w-[360px] flex-1 items-center gap-2 rounded-xl border border-white/[0.08] bg-white/[0.025] px-3 text-left text-xs text-ink-faint transition-colors hover:border-cyan/25 hover:text-ink-dim md:flex"
      >
        <Search className="h-3.5 w-3.5" />
        <span className="flex-1">Search or command…</span>
        <span className="kbd">CTRL K</span>
      </button>

      <div className="ml-auto flex items-center gap-1.5 md:ml-2">
        <div className="mono mr-1 hidden flex-col items-end text-right md:flex">
          <span className="text-[11px] tracking-wider text-ink">{time}</span>
          <span className="text-[8px] uppercase tracking-[0.2em] text-ink-faint">{date}</span>
        </div>

        <div className="mr-1 hidden items-center gap-2 rounded-full border border-white/[0.07] bg-white/[0.02] px-3 py-1.5 xl:flex">
          <StatusDot tone={mode === "live" ? "lime" : "amber"} pulse />
          <span className="mono text-[9px] uppercase tracking-[0.18em] text-ink-dim">
            {mode === "live" ? "live archive" : "demo mode"}
          </span>
        </div>

        <Link
          href="/ask?voice=1"
          className="relative grid h-9 w-9 place-items-center rounded-xl border border-white/[0.08] bg-white/[0.02] text-ink-dim transition-colors hover:border-cyan/30 hover:text-cyan"
          aria-label="Talk to Jarvis"
        >
          <Mic className="h-4 w-4" />
          <span className="absolute -right-0.5 -top-0.5 h-2 w-2 rounded-full bg-cyan shadow-glow" />
        </Link>

        <button
          onClick={onSearch}
          className="relative grid h-9 w-9 place-items-center rounded-xl border border-white/[0.08] bg-white/[0.02] text-ink-dim transition-colors hover:border-cyan/30 hover:text-cyan"
          aria-label="Search signals"
        >
          <Bell className="h-4 w-4" />
          {attention > 0 ? (
            <span className="absolute -right-0.5 -top-0.5 grid h-4 min-w-4 place-items-center rounded-full bg-amber px-1 text-[8px] font-bold text-black">
              {attention}
            </span>
          ) : null}
        </button>

        <Link
          href="/ask"
          className="ml-0.5 hidden h-9 items-center gap-2 rounded-xl bg-gradient-to-r from-cyan/90 to-violet/90 px-3 text-xs font-semibold text-slate-950 shadow-glow transition-all hover:brightness-110 sm:flex"
        >
          <Sparkles className="h-3.5 w-3.5" />
          Ask
        </Link>
      </div>
    </header>
  );
}
