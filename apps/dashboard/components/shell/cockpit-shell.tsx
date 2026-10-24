"use client";

import { useCallback, useEffect, useState } from "react";
import { Sidebar } from "./sidebar";
import { Topbar } from "./topbar";
import { CommandPalette } from "./command-palette";

export function CockpitShell({
  children,
  mode,
  counts,
  liveConnections,
}: {
  children: React.ReactNode;
  mode: "demo" | "live";
  counts: { tasks: number; inbox: number; attention: number };
  liveConnections: number;
}) {
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);

  const closePalette = useCallback(() => setPaletteOpen(false), []);
  const closeMobile = useCallback(() => setMobileOpen(false), []);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setPaletteOpen((open) => !open);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  return (
    <>
      <Sidebar
        open={mobileOpen}
        onClose={closeMobile}
        counts={counts}
        mode={mode}
        liveConnections={liveConnections}
      />
      <div className="relative z-10 min-h-screen lg:pl-[268px]">
        <Topbar
          onMenu={() => setMobileOpen(true)}
          onSearch={() => setPaletteOpen(true)}
          mode={mode}
          attention={counts.attention}
        />
        <main className="mx-auto w-full max-w-[1560px] px-4 pb-16 pt-5 sm:px-6 lg:px-8">
          {children}
        </main>
        <footer className="mx-auto flex w-full max-w-[1560px] flex-wrap items-center justify-between gap-2 border-t border-white/[0.05] px-4 py-4 text-[10px] text-ink-faint sm:px-6 lg:px-8">
          <span className="mono uppercase tracking-[0.18em]">
            Bonjee OS · grounded retrieval · no fabricated answers
          </span>
          <span className="mono uppercase tracking-[0.18em]">
            {mode === "live" ? "postgres live" : "synthetic fixtures"} · local reasoning
          </span>
        </footer>
      </div>
      <CommandPalette open={paletteOpen} onClose={closePalette} />
    </>
  );
}
