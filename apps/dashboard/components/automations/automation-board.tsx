"use client";

import { useMemo, useState } from "react";
import { Search, Terminal, Workflow } from "lucide-react";
import type { WorkflowModule } from "@/lib/types";
import { WorkflowStatusBadge } from "@/components/ui/status";
import { Progress } from "@/components/ui/primitives";
import { CopyButton } from "@/components/ui/copy-button";
import { timeAgo } from "@/lib/format";
import { cn } from "@/lib/utils";

const CATEGORY_LABEL: Record<WorkflowModule["category"], string> = {
  ingestion: "Ingestion",
  intelligence: "Intelligence",
  retrieval: "Retrieval",
  automation: "Automation",
  ops: "Operations",
  device: "Device",
};

const CATEGORIES: (WorkflowModule["category"] | "all")[] = [
  "all",
  "ingestion",
  "intelligence",
  "retrieval",
  "automation",
  "ops",
  "device",
];

const STATUS_NOTE: Record<WorkflowModule["status"], string> = {
  active: "Executed by the cockpit's local engine. No external service required.",
  ready: "Implementation present. Requires import or credentials before it can run.",
  needs_setup: "Code present. An external service or credential is still unconfigured.",
  planned: "Not implemented yet. Listed so the gap is explicit, not hidden.",
};

export function AutomationBoard({
  workflows,
  mode,
}: {
  workflows: WorkflowModule[];
  mode: "demo" | "live";
}) {
  const [category, setCategory] = useState<(typeof CATEGORIES)[number]>("all");
  const [query, setQuery] = useState("");

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return workflows.filter((workflow) => {
      if (category !== "all" && workflow.category !== category) return false;
      if (!q) return true;
      return `${workflow.name} ${workflow.purpose} ${workflow.file ?? ""} ${workflow.requires.join(" ")}`
        .toLowerCase()
        .includes(q);
    });
  }, [workflows, category, query]);

  const counts = useMemo(
    () =>
      workflows.reduce(
        (acc, workflow) => {
          acc[workflow.status] += 1;
          return acc;
        },
        { active: 0, ready: 0, needs_setup: 0, planned: 0 },
      ),
    [workflows],
  );

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        <CountCard label="Running locally" value={counts.active} tone="text-lime" detail="cockpit engine" />
        <CountCard label="Ready to import" value={counts.ready} tone="text-cyan" detail="needs a target" />
        <CountCard label="Setup needed" value={counts.needs_setup} tone="text-amber" detail="external credentials" />
        <CountCard label="Planned" value={counts.planned} tone="text-ink-faint" detail="documented gaps" />
      </div>

      <div className="panel overflow-hidden p-0">
        <div className="flex flex-wrap items-center gap-2 border-b border-white/[0.06] p-3">
          <div className="relative min-w-[200px] flex-1">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-ink-faint" />
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Search modules, files or requirements…"
              className="field h-9 py-0 pl-9 text-xs"
              aria-label="Search workflow modules"
            />
          </div>
          <div className="flex flex-wrap items-center gap-1">
            {CATEGORIES.map((value) => (
              <button
                key={value}
                onClick={() => setCategory(value)}
                className={cn(
                  "mono rounded-lg border px-2.5 py-1.5 text-[9px] uppercase tracking-[0.1em] transition-colors",
                  category === value
                    ? "border-cyan/30 bg-cyan/10 text-cyan"
                    : "border-white/[0.07] bg-white/[0.02] text-ink-faint hover:text-ink-dim",
                )}
              >
                {value === "all" ? "All" : CATEGORY_LABEL[value]}
              </button>
            ))}
          </div>
        </div>

        <div className="grid gap-3 p-3 lg:grid-cols-2">
          {filtered.map((workflow) => (
            <article
              key={workflow.id}
              className={cn(
                "group flex flex-col rounded-xl border bg-white/[0.015] p-3.5 transition-colors",
                workflow.status === "active"
                  ? "border-lime/15 hover:border-lime/30"
                  : workflow.status === "ready"
                    ? "border-cyan/15 hover:border-cyan/30"
                    : workflow.status === "needs_setup"
                      ? "border-amber/15 hover:border-amber/30"
                      : "border-white/[0.05] hover:border-white/10",
              )}
            >
              <div className="flex items-start justify-between gap-2">
                <div className="flex min-w-0 items-center gap-2">
                  <span className="mono shrink-0 rounded-md border border-white/[0.08] bg-black/30 px-1.5 py-0.5 text-[9px] text-ink-faint">
                    {workflow.index}
                  </span>
                  <div className="min-w-0">
                    <h3 className="truncate text-[12.5px] font-medium text-ink">{workflow.name}</h3>
                    <p className="mono mt-0.5 text-[8px] uppercase tracking-[0.12em] text-ink-faint">
                      {CATEGORY_LABEL[workflow.category]}
                    </p>
                  </div>
                </div>
                <WorkflowStatusBadge status={workflow.status} className="shrink-0" />
              </div>

              <p className="mt-2.5 text-[10.5px] leading-relaxed text-ink-faint">{workflow.purpose}</p>

              <div className="mt-2.5 flex flex-wrap gap-1">
                {workflow.requires.map((requirement) => (
                  <span key={requirement} className="chip px-2 py-0.5 text-[8.5px]">
                    {requirement}
                  </span>
                ))}
              </div>

              <div className="mt-auto pt-3">
                <div className="flex items-center justify-between gap-2 rounded-lg border border-white/[0.05] bg-black/20 px-2.5 py-1.5">
                  <span className="mono flex min-w-0 items-center gap-1.5 text-[8.5px] text-ink-faint">
                    <Terminal className="h-3 w-3 shrink-0" />
                    <span className="truncate">{workflow.file ?? "no file yet"}</span>
                  </span>
                  {workflow.file ? (
                    <CopyButton value={workflow.file} className="shrink-0 border-0 bg-transparent px-1 py-0.5" />
                  ) : null}
                </div>

                <div className="mt-1.5 flex items-center justify-between gap-2">
                  <span className="mono text-[8px] uppercase tracking-[0.1em] text-ink-faint">
                    {workflow.status === "active"
                      ? "cockpit local"
                      : workflow.status === "planned"
                        ? "not built"
                        : workflow.status === "ready"
                          ? "implementation ready"
                          : "awaiting credentials"}
                  </span>
                  <span className="mono text-[8px] uppercase tracking-[0.1em] text-ink-faint">
                    {mode === "demo" && workflow.lastRun
                      ? "fixture activity"
                      : workflow.lastRun
                        ? timeAgo(workflow.lastRun)
                        : "never run"}
                  </span>
                </div>

                {workflow.successRate !== null ? (
                  <div className="mt-2 flex items-center gap-2">
                    <Progress
                      value={workflow.successRate * 100}
                      tone={workflow.successRate > 0.9 ? "lime" : workflow.successRate > 0.6 ? "amber" : "rose"}
                    />
                    <span className="mono shrink-0 text-[8px] text-ink-faint">
                      {Math.round(workflow.successRate * 100)}%
                    </span>
                  </div>
                ) : null}

                <p className="mt-2 text-[9px] leading-relaxed text-ink-faint">{STATUS_NOTE[workflow.status]}</p>
              </div>
            </article>
          ))}
        </div>

        {filtered.length === 0 ? (
          <div className="flex flex-col items-center gap-2 px-4 py-12 text-center">
            <Workflow className="h-5 w-5 text-ink-faint" />
            <p className="text-xs text-ink-faint">No modules match that filter.</p>
          </div>
        ) : null}
      </div>
    </div>
  );
}

function CountCard({ label, value, tone, detail }: { label: string; value: number; tone: string; detail: string }) {
  return (
    <div className="panel hud-corners p-3">
      <p className={cn("display text-2xl font-semibold", tone)}>{value}</p>
      <p className="mono mt-0.5 text-[8px] uppercase tracking-[0.12em] text-ink-faint">{label}</p>
      <p className="mt-1 text-[9px] text-ink-faint">{detail}</p>
    </div>
  );
}
