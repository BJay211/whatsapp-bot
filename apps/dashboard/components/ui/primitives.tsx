import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

export function Panel({
  className,
  children,
  glow = false,
  corners = false,
  ...rest
}: React.HTMLAttributes<HTMLDivElement> & { glow?: boolean; corners?: boolean }) {
  return (
    <div
      className={cn("panel", glow && "panel-glow", corners && "hud-corners", className)}
      {...rest}
    >
      {children}
    </div>
  );
}

export function PanelHeader({
  kicker,
  title,
  action,
  className,
}: {
  kicker?: string;
  title: string;
  action?: ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("flex items-start justify-between gap-3 px-5 pt-4", className)}>
      <div>
        {kicker ? <p className="eyebrow">{kicker}</p> : null}
        <h2 className="display mt-1 text-[15px] font-semibold text-ink">{title}</h2>
      </div>
      {action}
    </div>
  );
}

export function StatusDot({
  tone = "cyan",
  pulse = false,
  className,
}: {
  tone?: "cyan" | "lime" | "amber" | "rose" | "slate" | "violet";
  pulse?: boolean;
  className?: string;
}) {
  const toneMap = {
    cyan: "bg-cyan",
    lime: "bg-lime",
    amber: "bg-amber",
    rose: "bg-rose",
    slate: "bg-slate-500",
    violet: "bg-violet",
  } as const;
  return (
    <span className={cn("relative inline-flex h-1.5 w-1.5 rounded-full", toneMap[tone], className)}>
      {pulse ? (
        <span className={cn("absolute inline-flex h-full w-full animate-ping rounded-full opacity-60", toneMap[tone])} />
      ) : null}
    </span>
  );
}

export function Progress({
  value,
  tone = "cyan",
  className,
}: {
  value: number;
  tone?: "cyan" | "violet" | "amber" | "lime" | "rose";
  className?: string;
}) {
  const toneMap = {
    cyan: "from-cyan to-cyan/50",
    violet: "from-violet to-violet/50",
    amber: "from-amber to-amber/50",
    lime: "from-lime to-lime/50",
    rose: "from-rose to-rose/50",
  } as const;
  return (
    <div className={cn("h-1.5 w-full overflow-hidden rounded-full bg-white/[0.06]", className)}>
      <div
        className={cn("h-full rounded-full bg-gradient-to-r transition-all duration-500", toneMap[tone])}
        style={{ width: `${Math.min(100, Math.max(0, value))}%` }}
      />
    </div>
  );
}

export function EmptyState({
  title,
  body,
  action,
}: {
  title: string;
  body: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 px-6 py-14 text-center">
      <div className="mb-1 grid h-10 w-10 place-items-center rounded-full border border-cyan/20 bg-cyan/5">
        <span className="h-2 w-2 rounded-full bg-cyan/70 shadow-glow" />
      </div>
      <p className="text-sm font-medium text-ink">{title}</p>
      <p className="max-w-sm text-xs leading-relaxed text-ink-faint">{body}</p>
      {action}
    </div>
  );
}
