import { cn } from "@/lib/utils";
import type { Priority, TaskStatus, WorkflowModule } from "@/lib/types";
import { AlertTriangle, CheckCircle2, CircleDot, Clock, Loader2 } from "lucide-react";

const STATUS_STYLE: Record<TaskStatus, { label: string; className: string; icon: typeof Clock }> = {
  detected: { label: "Detected", className: "border-cyan/25 bg-cyan/10 text-cyan", icon: CircleDot },
  needs_confirmation: { label: "Needs confirmation", className: "border-amber/25 bg-amber/10 text-amber", icon: AlertTriangle },
  confirmed: { label: "Confirmed", className: "border-lime/25 bg-lime/10 text-lime", icon: CheckCircle2 },
  in_progress: { label: "In progress", className: "border-violet/25 bg-violet/10 text-violet-300", icon: Loader2 },
  completed: { label: "Completed", className: "border-white/10 bg-white/5 text-ink-faint", icon: CheckCircle2 },
  cancelled: { label: "Cancelled", className: "border-rose/25 bg-rose/10 text-rose", icon: CircleDot },
  overdue: { label: "Overdue", className: "border-rose/30 bg-rose/10 text-rose", icon: AlertTriangle },
};

export function TaskStatusBadge({ status, className }: { status: TaskStatus; className?: string }) {
  const style = STATUS_STYLE[status] ?? STATUS_STYLE.detected;
  const Icon = style.icon;
  return (
    <span className={cn("inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[9px] font-medium uppercase tracking-[0.08em]", style.className, className)}>
      <Icon className="h-2.5 w-2.5" />
      {style.label}
    </span>
  );
}

const PRIORITY_STYLE: Record<Priority, string> = {
  low: "text-ink-faint",
  normal: "text-cyan/80",
  high: "text-amber",
  urgent: "text-rose",
};

export function PriorityBadge({ priority }: { priority: Priority }) {
  return (
    <span className={cn("mono text-[9px] font-semibold uppercase tracking-[0.14em]", PRIORITY_STYLE[priority])}>
      {priority}
    </span>
  );
}

export function SourcePill({ source }: { source: string }) {
  const map: Record<string, { label: string; className: string }> = {
    whatsapp: { label: "WhatsApp", className: "border-lime/20 bg-lime/10 text-lime" },
    android: { label: "Android", className: "border-cyan/20 bg-cyan/10 text-cyan" },
    calendar: { label: "Calendar", className: "border-violet/20 bg-violet/10 text-violet-300" },
    system: { label: "System", className: "border-white/10 bg-white/5 text-ink-faint" },
  };
  const style = map[source] ?? map.system;
  return (
    <span className={cn("mono rounded-md border px-1.5 py-0.5 text-[8px] uppercase tracking-[0.12em]", style.className)}>
      {style.label}
    </span>
  );
}

export function WorkflowStatusBadge({ status, className }: { status: WorkflowModule["status"]; className?: string }) {
  const map: Record<WorkflowModule["status"], { label: string; className: string }> = {
    active: { label: "Active", className: "border-lime/25 bg-lime/10 text-lime" },
    ready: { label: "Ready", className: "border-cyan/25 bg-cyan/10 text-cyan" },
    needs_setup: { label: "Setup needed", className: "border-amber/25 bg-amber/10 text-amber" },
    planned: { label: "Planned", className: "border-white/10 bg-white/5 text-ink-faint" },
  };
  const style = map[status];
  return (
    <span className={cn("mono rounded-md border px-1.5 py-0.5 text-[8px] uppercase tracking-[0.12em]", style.className, className)}>
      {style.label}
    </span>
  );
}

export function RelationBadge({ relation }: { relation: string }) {
  const map: Record<string, string> = {
    UPDATES: "border-amber/25 bg-amber/10 text-amber",
    SUPERSEDES: "border-rose/25 bg-rose/10 text-rose",
    CONFIRMS: "border-lime/25 bg-lime/10 text-lime",
    CLARIFIES: "border-cyan/25 bg-cyan/10 text-cyan",
    CONTRADICTS: "border-rose/25 bg-rose/10 text-rose",
    CANCELS: "border-rose/25 bg-rose/10 text-rose",
    REPLIES_TO: "border-violet/25 bg-violet/10 text-violet-300",
    REFERENCES: "border-white/10 bg-white/5 text-ink-faint",
    RELATES_TO: "border-white/10 bg-white/5 text-ink-faint",
  };
  return (
    <span className={cn("mono rounded border px-1.5 py-0.5 text-[8px] uppercase tracking-[0.08em]", map[relation] ?? map.REFERENCES)}>
      {relation.replace(/_/g, " ")}
    </span>
  );
}
