import { cn } from "@/lib/utils";

export function JarvisOrb({
  size = 160,
  state = "idle",
  className,
}: {
  size?: number;
  state?: "idle" | "listening" | "thinking";
  className?: string;
}) {
  return (
    <div
      className={cn("orb", state === "listening" && "orb-listening", className)}
      style={{ width: size, height: size }}
      role="img"
      aria-label={state === "listening" ? "Jarvis is listening" : "Jarvis core"}
    >
      <span className="orb-ring orb-ring-3" />
      <span className="orb-ring orb-ring-2" />
      <span className="orb-ring orb-ring-1" />
      {state === "thinking" ? (
        <span className="absolute inset-[18%] animate-spin rounded-full border border-transparent border-t-cyan/80 border-r-violet/60" />
      ) : null}
    </div>
  );
}

export function OrbBackdrop({ className }: { className?: string }) {
  return (
    <div className={cn("pointer-events-none absolute inset-0 overflow-hidden", className)} aria-hidden>
      <div className="absolute left-1/2 top-1/2 h-[420px] w-[420px] -translate-x-1/2 -translate-y-1/2 rounded-full bg-cyan/[0.06] blur-3xl" />
      <div className="absolute left-1/2 top-1/2 h-[240px] w-[240px] -translate-x-1/2 -translate-y-1/2 rounded-full bg-violet/[0.08] blur-2xl" />
    </div>
  );
}
