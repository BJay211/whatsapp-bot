"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { ChevronRight, Cpu, Radio, ShieldCheck, X } from "lucide-react";
import { NAV_GROUPS } from "./nav";
import { cn } from "@/lib/utils";
import { StatusDot } from "@/components/ui/primitives";

export function Sidebar({
  open,
  onClose,
  counts,
  mode,
  liveConnections,
}: {
  open: boolean;
  onClose: () => void;
  counts: { tasks: number; inbox: number; attention: number };
  mode: "demo" | "live";
  liveConnections: number;
}) {
  const pathname = usePathname();

  return (
    <>
      <div
        onClick={onClose}
        className={cn(
          "fixed inset-0 z-40 bg-black/65 backdrop-blur-sm transition-opacity lg:hidden",
          open ? "opacity-100" : "pointer-events-none opacity-0",
        )}
        aria-hidden
      />
      <aside
        className={cn(
          "fixed inset-y-0 left-0 z-50 flex w-[268px] flex-col border-r border-cyan/10 bg-[#000814]/95 backdrop-blur-2xl transition-transform duration-300 lg:translate-x-0",
          open ? "translate-x-0" : "-translate-x-full",
        )}
      >
        <div className="flex h-16 shrink-0 items-center justify-between px-5">
          <Link href="/" className="group flex items-center gap-3" onClick={onClose}>
            <div className="orb h-9 w-9 shrink-0">
              <span className="orb-ring orb-ring-1 opacity-70" />
              <span className="orb-ring orb-ring-2 opacity-60" />
            </div>
            <div className="leading-none">
              <div className="display text-[17px] font-semibold tracking-[0.16em] text-ink">
                BONJEE<span className="text-cyan">.</span>
              </div>
              <div className="mono mt-1 text-[9px] uppercase tracking-[0.3em] text-ink-faint">
                OS // v2.0
              </div>
            </div>
          </Link>
          <button
            onClick={onClose}
            className="rounded-lg p-1.5 text-ink-faint hover:bg-white/5 hover:text-ink lg:hidden"
            aria-label="Close navigation"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="mx-5 mb-4 h-px bg-gradient-to-r from-transparent via-white/10 to-transparent" />

        <nav className="scroll-area flex-1 overflow-y-auto px-3 pb-4">
          {NAV_GROUPS.map((group, gi) => (
            <div key={group.label} className={cn(gi > 0 && "mt-6")}>
              <p className="eyebrow px-3 pb-2">{group.label}</p>
              <div className="space-y-1">
                {group.items.map((item) => {
                  const active = pathname === item.href || (item.href !== "/" && pathname.startsWith(item.href));
                  const badge =
                    item.badgeKey === "tasks"
                      ? counts.tasks
                      : item.badgeKey === "inbox"
                        ? counts.inbox
                        : item.badgeKey === "attention"
                          ? counts.attention
                          : 0;
                  const Icon = item.icon;
                  return (
                    <Link
                      key={item.href}
                      href={item.href}
                      onClick={onClose}
                      className={cn("nav-item group", active && "nav-item-active")}
                    >
                      <span
                        className={cn(
                          "grid h-7 w-7 place-items-center rounded-lg border border-white/[0.06] bg-white/[0.02] transition-colors",
                          active && "border-cyan/30 bg-cyan/10 text-cyan",
                        )}
                      >
                        <Icon className="h-[15px] w-[15px]" strokeWidth={1.8} />
                      </span>
                      <span className="flex-1 truncate">{item.label}</span>
                      {badge > 0 ? (
                        <span
                          className={cn(
                            "mono rounded-md px-1.5 py-0.5 text-[10px]",
                            item.badgeKey === "tasks"
                              ? "bg-amber/15 text-amber"
                              : "bg-white/[0.06] text-ink-faint",
                          )}
                        >
                          {badge}
                        </span>
                      ) : (
                        <ChevronRight className="h-3.5 w-3.5 text-transparent transition-colors group-hover:text-ink-faint" />
                      )}
                    </Link>
                  );
                })}
              </div>
            </div>
          ))}
        </nav>

        <div className="shrink-0 space-y-3 px-4 pb-4">
          <div className="panel hud-corners rounded-xl p-3">
            <div className="flex items-center justify-between">
              <span className="mono text-[9px] uppercase tracking-[0.2em] text-ink-faint">
                System state
              </span>
              <StatusDot tone={mode === "live" ? "lime" : "amber"} pulse />
            </div>
            <div className="mt-2 flex items-center gap-2 text-xs text-ink">
              <Cpu className="h-3.5 w-3.5 text-cyan" />
              <span>{mode === "live" ? "Live archive" : "Demo fixtures"}</span>
            </div>
            <div className="mt-1.5 flex items-center justify-between text-[10px] text-ink-faint">
              <span className="flex items-center gap-1.5">
                <Radio className="h-3 w-3" />
                {liveConnections} / 5 links
              </span>
              <Link href="/settings" className="text-cyan/80 hover:text-cyan">
                Configure
              </Link>
            </div>
          </div>

          <Link
            href="/settings"
            onClick={onClose}
            className="flex items-center gap-3 rounded-xl border border-white/[0.06] bg-white/[0.02] p-2.5 transition-colors hover:border-white/10 hover:bg-white/[0.04]"
          >
            <div className="grid h-8 w-8 place-items-center rounded-lg bg-gradient-to-br from-cyan/30 to-violet/30 text-xs font-semibold text-white">
              B
            </div>
            <div className="min-w-0 flex-1">
              <p className="truncate text-xs font-medium text-ink">Owner</p>
              <p className="mono truncate text-[9px] uppercase tracking-[0.18em] text-ink-faint">
                local operator
              </p>
            </div>
            <ShieldCheck className="h-4 w-4 text-lime/70" />
          </Link>
        </div>
      </aside>
    </>
  );
}
