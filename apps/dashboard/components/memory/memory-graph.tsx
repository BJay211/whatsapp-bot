"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import {
  ArrowUpRight,
  BookOpen,
  CheckCircle2,
  Circle,
  GitBranch,
  Link2,
  MessageSquare,
  Search,
  ShieldAlert,
  Sparkles,
  X,
} from "lucide-react";
import type { Course, Message, Task } from "@/lib/types";
import { formatDateTime, timeAgo } from "@/lib/format";
import { countdownLabel } from "@/lib/format";
import { PriorityBadge, RelationBadge, SourcePill, TaskStatusBadge } from "@/components/ui/status";
import { Panel } from "@/components/ui/primitives";
import { cn } from "@/lib/utils";

type Kind = "course" | "task" | "message";
type Focus = "all" | "tasks" | "signals" | "risks";

interface GraphNode {
  id: string;
  kind: Kind;
  label: string;
  sublabel?: string;
  x: number;
  y: number;
  radius: number;
  flagged?: boolean;
  relationCount: number;
}

interface GraphEdge {
  id: string;
  from: string;
  to: string;
  label: string;
  tone: "cyan" | "violet" | "amber" | "rose" | "slate";
}

const CX = 450;
const CY = 330;

function place(index: number, total: number, radius: number, offset = 0) {
  const angle = -Math.PI / 2 + offset + (index / Math.max(1, total)) * Math.PI * 2;
  return { x: CX + Math.cos(angle) * radius, y: CY + Math.sin(angle) * radius };
}

const NODE_TONE: Record<Kind, { fill: string; stroke: string; text: string }> = {
  course: { fill: "#00e5ff", stroke: "#18ffff", text: "#e0f7fa" },
  task: { fill: "#7c4dff", stroke: "#b388ff", text: "#f3e8ff" },
  message: { fill: "#00e676", stroke: "#69f0ae", text: "#f0fdf4" },
};

const EDGE_TONE: Record<GraphEdge["tone"], string> = {
  cyan: "rgba(34,211,238,0.5)",
  violet: "rgba(167,139,250,0.45)",
  amber: "rgba(245,158,11,0.55)",
  rose: "rgba(244,63,94,0.55)",
  slate: "rgba(148,163,184,0.22)",
};

export function MemoryGraph({
  courses,
  tasks,
  messages,
  timeZone,
}: {
  courses: Course[];
  tasks: Task[];
  messages: Message[];
  timeZone: string;
}) {
  const [selectedId, setSelectedId] = useState<string | null>(
    tasks.find((task) => task.revisions.some((revision) => revision.field === "due_at"))?.id ??
      tasks[0]?.id ??
      null,
  );
  const [hoveredId, setHoveredId] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [focus, setFocus] = useState<Focus>("all");

  const graph = useMemo(() => {
    const orderedMessages = [...messages].sort((a, b) => a.timestamp.localeCompare(b.timestamp));
    const nodes: GraphNode[] = [];
    const edges: GraphEdge[] = [];

    courses.forEach((course, index) => {
      const point = place(index, courses.length, 118);
      nodes.push({
        id: `course:${course.code}`,
        kind: "course",
        label: course.code,
        sublabel: course.title,
        ...point,
        radius: 19,
        relationCount: 0,
      });
    });

    tasks.forEach((task, index) => {
      const point = place(index, tasks.length, 208, 0.16);
      nodes.push({
        id: task.id,
        kind: "task",
        label: task.title.replace(/^[A-Z]{2,4}\s\d{3}\s*[—–·-]\s*/, ""),
        sublabel: task.courseCode ?? "Personal",
        ...point,
        radius: 13,
        flagged: task.revisions.some((revision) => revision.field === "due_at"),
        relationCount: 0,
      });
    });

    orderedMessages.forEach((message, index) => {
      const point = place(index, orderedMessages.length, 288, 0.08);
      const related = message.relations.length;
      nodes.push({
        id: message.id,
        kind: "message",
        label: message.senderName,
        sublabel: message.chatName,
        ...point,
        radius: message.suspicious ? 8 : 6.5,
        flagged: Boolean(message.suspicious),
        relationCount: related,
      });
    });

    const nodeIds = new Set(nodes.map((node) => node.id));
    const taskById = new Map(tasks.map((task) => [task.id, task]));
    const messageById = new Map(messages.map((message) => [message.id, message]));
    const relationCounts = new Map<string, number>();
    const bump = (id: string) => relationCounts.set(id, (relationCounts.get(id) ?? 0) + 1);

    for (const task of tasks) {
      const courseId = task.courseCode ? `course:${task.courseCode}` : null;
      if (courseId && nodeIds.has(courseId)) {
        edges.push({
          id: `${task.id}->${courseId}`,
          from: task.id,
          to: courseId,
          label: "BELONGS_TO",
          tone: "slate",
        });
        bump(task.id);
        bump(courseId);
      }
      for (const evidenceId of task.evidenceIds) {
        if (!nodeIds.has(evidenceId)) continue;
        edges.push({
          id: `${task.id}=>${evidenceId}`,
          from: task.id,
          to: evidenceId,
          label: "EVIDENCE",
          tone: "cyan",
        });
        bump(task.id);
        bump(evidenceId);
      }
    }

    for (const message of messages) {
      const courseId = message.courseCode ? `course:${message.courseCode}` : null;
      if (courseId && nodeIds.has(courseId)) {
        edges.push({
          id: `${message.id}->${courseId}`,
          from: message.id,
          to: courseId,
          label: "MENTIONS",
          tone: "slate",
        });
        bump(message.id);
        bump(courseId);
      }
      for (const relation of message.relations) {
        if (!nodeIds.has(relation.targetId)) continue;
        const tone: GraphEdge["tone"] =
          relation.type === "UPDATES" || relation.type === "SUPERSEDES"
            ? "amber"
            : relation.type === "CONTRADICTS" || relation.type === "CANCELS"
              ? "rose"
              : "violet";
        edges.push({
          id: `${message.id}-${relation.type}-${relation.targetId}`,
          from: message.id,
          to: relation.targetId,
          label: relation.type,
          tone,
        });
        bump(message.id);
        bump(relation.targetId);
      }
    }

    for (const node of nodes) node.relationCount = relationCounts.get(node.id) ?? 0;

    return { nodes, edges, taskById, messageById };
  }, [courses, tasks, messages]);

  const nodeMap = useMemo(() => new Map(graph.nodes.map((node) => [node.id, node])), [graph.nodes]);

  const focusMatch = (node: GraphNode): boolean => {
    if (focus === "tasks" && node.kind !== "task") return false;
    if (focus === "signals" && node.kind !== "message") return false;
    if (focus === "risks") {
      if (node.kind === "task") return Boolean(node.flagged);
      if (node.kind === "message") return Boolean(node.flagged);
      return false;
    }
    return true;
  };

  const searchMatch = (node: GraphNode): boolean => {
    const q = query.trim().toLowerCase();
    if (!q) return true;
    return `${node.label} ${node.sublabel ?? ""} ${node.id}`.toLowerCase().includes(q);
  };

  const visible = (node: GraphNode) => focusMatch(node) && searchMatch(node);

  const selected = selectedId ? nodeMap.get(selectedId) ?? null : null;
  const hovered = hoveredId ? nodeMap.get(hoveredId) ?? null : null;
  const relatedIds = useMemo(() => {
    if (!selectedId) return new Set<string>();
    const ids = new Set<string>([selectedId]);
    for (const edge of graph.edges) {
      if (edge.from === selectedId) ids.add(edge.to);
      if (edge.to === selectedId) ids.add(edge.from);
    }
    return ids;
  }, [graph.edges, selectedId]);

  const selectedTask = selectedId ? graph.taskById.get(selectedId) ?? null : null;
  const selectedMessage = selectedId ? graph.messageById.get(selectedId) ?? null : null;
  const selectedCourse = selectedId?.startsWith("course:")
    ? courses.find((course) => `course:${course.code}` === selectedId) ?? null
    : null;

  const revisionTasks = tasks.filter((task) => task.revisions.some((r) => r.field === "due_at"));
  const suspiciousCount = messages.filter((message) => message.suspicious).length;
  const relationTotal = graph.edges.filter((edge) => edge.label !== "MENTIONS" && edge.label !== "BELONGS_TO").length;

  const linkedTasks = selectedTask
    ? tasks.filter((task) => task.id !== selectedTask.id && task.courseCode && task.courseCode === selectedTask.courseCode)
    : selectedCourse
      ? tasks.filter((task) => task.courseCode === selectedCourse.code)
      : [];

  const linkedMessages = selectedTask
    ? selectedTask.evidenceIds
        .map((id) => graph.messageById.get(id))
        .filter((message): message is Message => Boolean(message))
    : selectedCourse
      ? messages.filter((message) => message.courseCode === selectedCourse.code).slice(0, 6)
      : selectedMessage
        ? selectedMessage.relations
            .map((relation) => graph.messageById.get(relation.targetId))
            .filter((message): message is Message => Boolean(message))
        : [];

  const centerNode = hovered ?? selected;
  const labelNode = (node: GraphNode) =>
    node.kind !== "message" || node.flagged || node.relationCount > 0;

  return (
    <div className="space-y-4">
      <Panel glow corners className="relative overflow-hidden p-5 sm:p-6">
        <div
          className="pointer-events-none absolute -right-24 -top-24 h-72 w-72 rounded-full opacity-40 blur-3xl"
          style={{ background: "radial-gradient(circle, rgba(167,139,250,0.4), transparent 65%)" }}
        />
        <div className="relative flex flex-wrap items-start justify-between gap-4">
          <div>
            <p className="eyebrow">Memory graph</p>
            <h1 className="display mt-1 text-2xl font-semibold text-ink">Your knowledge, connected.</h1>
            <p className="mt-1.5 max-w-2xl text-xs leading-relaxed text-ink-dim">
              Every course, commitment and source message in the archive, linked by provenance and
              detected relationships. Click a node to trace its evidence.
            </p>
          </div>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            <GraphStat label="Entities" value={graph.nodes.length} tone="text-cyan" />
            <GraphStat label="Links" value={graph.edges.length} tone="text-violet-300" />
            <GraphStat label="Revisions" value={revisionTasks.length} tone="text-amber" />
            <GraphStat label="Flagged" value={suspiciousCount} tone="text-rose" />
          </div>
        </div>
      </Panel>

      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_360px]">
        <Panel className="overflow-hidden p-0">
          <div className="flex flex-wrap items-center gap-2 border-b border-white/[0.06] p-3">
            <div className="relative min-w-[180px] flex-1">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-ink-faint" />
              <input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Find an entity, course or sender…"
                className="field h-9 py-0 pl-9 text-xs"
                aria-label="Search memory graph"
              />
            </div>
            <div className="flex flex-wrap items-center gap-1">
              {(
                [
                  ["all", "All"],
                  ["tasks", "Missions"],
                  ["signals", "Signals"],
                  ["risks", "Risk"],
                ] as [Focus, string][]
              ).map(([value, label]) => (
                <button
                  key={value}
                  onClick={() => setFocus(value)}
                  className={cn(
                    "mono rounded-lg border px-2.5 py-1.5 text-[9px] uppercase tracking-[0.12em] transition-colors",
                    focus === value
                      ? "border-cyan/30 bg-cyan/10 text-cyan"
                      : "border-white/[0.07] bg-white/[0.02] text-ink-faint hover:text-ink-dim",
                  )}
                >
                  {label}
                </button>
              ))}
            </div>
          </div>

          <div className="relative">
            <svg viewBox="0 0 900 660" className="block w-full" role="img" aria-label="Memory entity graph">
              <defs>
                <radialGradient id="graph-halo" cx="50%" cy="50%" r="50%">
                  <stop offset="0%" stopColor="rgba(34,211,238,0.13)" />
                  <stop offset="55%" stopColor="rgba(139,92,246,0.05)" />
                  <stop offset="100%" stopColor="transparent" />
                </radialGradient>
                <filter id="graph-glow" x="-60%" y="-60%" width="220%" height="220%">
                  <feGaussianBlur stdDeviation="4" result="blur" />
                  <feMerge>
                    <feMergeNode in="blur" />
                    <feMergeNode in="SourceGraphic" />
                  </feMerge>
                </filter>
              </defs>

              <circle cx={CX} cy={CY} r={310} fill="url(#graph-halo)" />
              {[118, 208, 288].map((radius) => (
                <circle
                  key={radius}
                  cx={CX}
                  cy={CY}
                  r={radius}
                  fill="none"
                  stroke="rgba(148,163,184,0.12)"
                  strokeWidth="1"
                  strokeDasharray={radius === 288 ? "2 7" : "1 9"}
                />
              ))}

              <g>
                {graph.edges.map((edge) => {
                  const from = nodeMap.get(edge.from);
                  const to = nodeMap.get(edge.to);
                  if (!from || !to) return null;
                  const isSelected = selectedId
                    ? edge.from === selectedId || edge.to === selectedId
                    : false;
                  const bothVisible = visible(from) && visible(to);
                  const opacity = selectedId
                    ? isSelected
                      ? 0.95
                      : 0.07
                    : bothVisible
                      ? 0.72
                      : 0.07;
                  const width = isSelected ? 1.8 : edge.label === "EVIDENCE" ? 1.1 : 0.8;
                  return (
                    <line
                      key={edge.id}
                      x1={from.x}
                      y1={from.y}
                      x2={to.x}
                      y2={to.y}
                      stroke={EDGE_TONE[edge.tone]}
                      strokeWidth={width}
                      strokeDasharray={edge.label === "EVIDENCE" ? "4 3" : undefined}
                      opacity={opacity}
                    />
                  );
                })}
              </g>

              <g>
                {graph.nodes.map((node) => {
                  const isSelected = selectedId === node.id;
                  const isHovered = hoveredId === node.id;
                  const isRelated = selectedId ? relatedIds.has(node.id) : false;
                  const ok = visible(node);
                  const opacity = !ok
                    ? 0.13
                    : selectedId
                      ? isRelated
                        ? 1
                        : 0.16
                      : 1;
                  const tone = NODE_TONE[node.kind];

                  return (
                    <g
                      key={node.id}
                      role="button"
                      tabIndex={0}
                      aria-label={`${node.kind}: ${node.label}`}
                      onClick={() => setSelectedId((current) => (current === node.id ? null : node.id))}
                      onKeyDown={(event) => {
                        if (event.key === "Enter" || event.key === " ") {
                          event.preventDefault();
                          setSelectedId((current) => (current === node.id ? null : node.id));
                        }
                      }}
                      onMouseEnter={() => setHoveredId(node.id)}
                      onMouseLeave={() => setHoveredId((current) => (current === node.id ? null : current))}
                      style={{ cursor: "pointer", opacity, transition: "opacity 180ms ease", outline: "none" }}
                    >
                      {(isSelected || isHovered) && (
                        <circle
                          cx={node.x}
                          cy={node.y}
                          r={node.radius + 15}
                          fill="none"
                          stroke={tone.stroke}
                          strokeWidth="0.8"
                          opacity="0.5"
                          filter="url(#graph-glow)"
                        />
                      )}
                      {node.flagged ? (
                        <circle
                          cx={node.x}
                          cy={node.y}
                          r={node.radius + (node.kind === "message" ? 8 : 6)}
                          fill="none"
                          stroke={node.kind === "message" ? "rgba(244,63,94,0.8)" : "rgba(245,158,11,0.8)"}
                          strokeWidth="1.1"
                          strokeDasharray="2 2.5"
                        />
                      ) : null}
                      <circle
                        cx={node.x}
                        cy={node.y}
                        r={node.radius}
                        fill="rgba(6,10,18,0.94)"
                        stroke={isSelected ? tone.stroke : tone.fill}
                        strokeWidth={isSelected ? 2.2 : node.kind === "message" ? 1.2 : 1.7}
                        filter={isSelected ? "url(#graph-glow)" : undefined}
                      />
                      <circle cx={node.x} cy={node.y} r={Math.max(2, node.radius * 0.28)} fill={tone.fill} />
                      {labelNode(node) ? (
                        <text
                          x={node.x + (node.x < CX ? -(node.radius + 7) : node.radius + 7)}
                          y={node.y + (node.kind === "course" ? 4 : 3)}
                          textAnchor={node.x < CX ? "end" : "start"}
                          fill={isSelected ? "#f8fafc" : tone.text}
                          fontSize={node.kind === "course" ? 12 : node.kind === "task" ? 9.5 : 7.5}
                          fontFamily="Bahnschrift, Segoe UI, sans-serif"
                          fontWeight={node.kind === "course" ? 600 : 400}
                          opacity={0.94}
                        >
                          {node.kind === "message"
                            ? (node.sublabel ?? node.label).slice(0, 14)
                            : node.label.length > 24
                              ? `${node.label.slice(0, 23)}…`
                              : node.label}
                        </text>
                      ) : null}
                    </g>
                  );
                })}
              </g>

              <text x={CX} y={CY - 6} textAnchor="middle" fill="rgba(226,232,240,0.9)" fontSize="10" fontFamily="Cascadia Code, monospace" letterSpacing="2">
                BONJEE
              </text>
              <text x={CX} y={CY + 12} textAnchor="middle" fill="rgba(148,163,184,0.65)" fontSize="7" fontFamily="Cascadia Code, monospace" letterSpacing="1.5">
                {centerNode ? centerNode.kind.toUpperCase() : "MEMORY CORE"}
              </text>
            </svg>

            <div className="pointer-events-none absolute bottom-3 left-3 flex flex-wrap gap-3">
              {(
                [
                  ["course", "Course"],
                  ["task", "Mission"],
                  ["message", "Signal"],
                ] as [Kind, string][]
              ).map(([kind, label]) => (
                <span key={kind} className="mono flex items-center gap-1.5 text-[8px] uppercase tracking-[0.14em] text-ink-faint">
                  <span className="h-2 w-2 rounded-full" style={{ background: NODE_TONE[kind].fill }} />
                  {label}
                </span>
              ))}
            </div>
            {selectedId ? (
              <button
                onClick={() => setSelectedId(null)}
                className="absolute right-3 top-3 grid h-7 w-7 place-items-center rounded-lg border border-white/10 bg-black/40 text-ink-faint transition-colors hover:text-ink"
                aria-label="Clear selection"
              >
                <X className="h-3.5 w-3.5" />
              </button>
            ) : null}
          </div>
        </Panel>

        <Panel className="p-0">
          <div className="flex items-center justify-between border-b border-white/[0.06] px-4 py-3">
            <div>
              <p className="eyebrow">Inspector</p>
              <p className="mt-0.5 text-sm font-medium text-ink">{selected ? "Entity trace" : "Graph overview"}</p>
            </div>
            <span className="mono text-[8px] uppercase tracking-[0.14em] text-ink-faint">
              {selected ? selected.kind : "no selection"}
            </span>
          </div>

          <div className="scroll-area max-h-[660px] overflow-y-auto p-4">
            {!selected ? (
              <div className="space-y-4">
                <div className="rounded-xl border border-white/[0.06] bg-white/[0.02] p-3">
                  <p className="text-[11px] leading-relaxed text-ink-dim">
                    This graph is built from stored messages, extracted tasks and their explicit links.
                    It is provenance, not a semantic model: every edge can be traced to a source row.
                  </p>
                </div>

                <div>
                  <p className="eyebrow mb-2">Entities</p>
                  <div className="grid grid-cols-3 gap-2 text-center">
                    <MiniStat label="Courses" value={courses.length} />
                    <MiniStat label="Missions" value={tasks.length} />
                    <MiniStat label="Signals" value={messages.length} />
                  </div>
                </div>

                <div>
                  <p className="eyebrow mb-2">Deadline changes</p>
                  <div className="space-y-1.5">
                    {revisionTasks.slice(0, 4).map((task) => (
                      <button
                        key={task.id}
                        onClick={() => setSelectedId(task.id)}
                        className="flex w-full items-center gap-2 rounded-lg border border-white/[0.05] px-2.5 py-2 text-left transition-colors hover:border-amber/25 hover:bg-amber/[0.03]"
                      >
                        <GitBranch className="h-3 w-3 shrink-0 text-amber" />
                        <span className="min-w-0 flex-1 truncate text-[10.5px] text-ink-dim">{task.title}</span>
                        <span className="mono shrink-0 text-[8px] uppercase tracking-[0.1em] text-amber/80">
                          {task.revisions.length}
                        </span>
                      </button>
                    ))}
                    {revisionTasks.length === 0 ? (
                      <p className="text-[10px] text-ink-faint">No revised deadlines in the archive.</p>
                    ) : null}
                  </div>
                </div>

                <div>
                  <p className="eyebrow mb-2">Relationship mix</p>
                  <div className="space-y-1">
                    {Object.entries(
                      graph.edges.reduce<Record<string, number>>((acc, edge) => {
                        acc[edge.label] = (acc[edge.label] ?? 0) + 1;
                        return acc;
                      }, {}),
                    )
                      .sort((a, b) => b[1] - a[1])
                      .slice(0, 6)
                      .map(([label, count]) => (
                        <div key={label} className="flex items-center justify-between">
                          <RelationBadge relation={label} />
                          <span className="mono text-[9px] text-ink-faint">{count}</span>
                        </div>
                      ))}
                  </div>
                </div>

                <div className="rounded-xl border border-cyan/15 bg-cyan/[0.04] p-3">
                  <p className="mono flex items-center gap-1.5 text-[8px] uppercase tracking-[0.14em] text-cyan">
                    <Link2 className="h-3 w-3" /> {relationTotal} evidence links
                  </p>
                  <p className="mt-1 text-[10px] leading-relaxed text-ink-faint">
                    Click any mission or signal to inspect its sources, revisions and neighbours.
                  </p>
                </div>
              </div>
            ) : null}

            {selectedTask ? (
              <div className="space-y-4">
                <div>
                  <div className="flex items-center gap-2">
                    <span className="grid h-8 w-8 place-items-center rounded-lg border border-violet/25 bg-violet/10 text-violet-300">
                      <CheckCircle2 className="h-4 w-4" />
                    </span>
                    <div className="min-w-0">
                      <p className="text-sm font-medium leading-tight text-ink">{selectedTask.title}</p>
                      <p className="mono mt-0.5 text-[8px] uppercase tracking-[0.14em] text-ink-faint">
                        {selectedTask.courseCode ?? "Personal"} · {selectedTask.kind}
                      </p>
                    </div>
                  </div>
                  <div className="mt-3 flex flex-wrap items-center gap-1.5">
                    <TaskStatusBadge status={selectedTask.status} />
                    <PriorityBadge priority={selectedTask.priority} />
                    {selectedTask.dueAt ? (
                      <span className="chip px-2 py-0.5 text-[9px]">{countdownLabel(selectedTask.dueAt, timeZone)}</span>
                    ) : null}
                  </div>
                  <p className="mt-2 text-[10.5px] leading-relaxed text-ink-dim">{selectedTask.description}</p>
                </div>

                <div className="rounded-xl border border-white/[0.06] bg-white/[0.02] p-3">
                  <p className="mono text-[8px] uppercase tracking-[0.14em] text-ink-faint">Due</p>
                  <p className="mt-0.5 text-xs text-ink">{formatDateTime(selectedTask.dueAt, timeZone)}</p>
                  <p className="mono mt-1 text-[8px] text-ink-faint">
                    issued by {selectedTask.issuer} · {timeAgo(selectedTask.updatedAt)} updated
                  </p>
                </div>

                {selectedTask.requirements.length ? (
                  <div>
                    <p className="eyebrow mb-2">Requirements</p>
                    <div className="space-y-1.5">
                      {selectedTask.requirements.map((requirement, index) => (
                        <div key={`${requirement.text}-${index}`} className="flex items-start gap-2 text-[10.5px] leading-relaxed">
                          {requirement.done ? (
                            <CheckCircle2 className="mt-0.5 h-3 w-3 shrink-0 text-lime" />
                          ) : (
                            <Circle className="mt-0.5 h-3 w-3 shrink-0 text-ink-faint" />
                          )}
                          <span className={requirement.done ? "text-ink-faint line-through" : "text-ink-dim"}>
                            {requirement.text}
                          </span>
                        </div>
                      ))}
                    </div>
                  </div>
                ) : null}

                {selectedTask.revisions.length ? (
                  <div>
                    <p className="eyebrow mb-2">Revision trace</p>
                    <div className="space-y-2">
                      {selectedTask.revisions.map((revision, index) => (
                        <div key={`${revision.at}-${index}`} className="rounded-lg border border-amber/15 bg-amber/[0.03] p-2.5">
                          <p className="mono text-[8px] uppercase tracking-[0.12em] text-amber/80">
                            {revision.field.replace(/_/g, " ")} · {revision.evidenceId ?? "owner"}
                          </p>
                          {revision.from || revision.to ? (
                            <p className="mt-1 text-[10px] text-ink-dim">
                              <span className="text-ink-faint line-through">
                                {revision.from ? formatDateTime(revision.from, timeZone) : "unknown"}
                              </span>
                              <span className="mx-1.5 text-amber">→</span>
                              <span className="text-ink">{revision.to ? formatDateTime(revision.to, timeZone) : "unknown"}</span>
                            </p>
                          ) : null}
                          <p className="mt-1 text-[9px] leading-relaxed text-ink-faint">{revision.reason}</p>
                        </div>
                      ))}
                    </div>
                  </div>
                ) : null}

                {linkedMessages.length ? (
                  <div>
                    <p className="eyebrow mb-2">Source messages</p>
                    <div className="space-y-1.5">
                      {linkedMessages.map((message) => (
                        <button
                          key={message.id}
                          onClick={() => setSelectedId(message.id)}
                          className="w-full rounded-lg border border-white/[0.05] p-2.5 text-left transition-colors hover:border-cyan/25 hover:bg-cyan/[0.03]"
                        >
                          <div className="flex items-center gap-1.5">
                            <MessageSquare className="h-3 w-3 text-cyan/70" />
                            <span className="truncate text-[10px] font-medium text-ink">{message.senderName}</span>
                            <span className="mono ml-auto shrink-0 text-[8px] text-ink-faint">{timeAgo(message.timestamp)}</span>
                          </div>
                          <p className="mt-1 line-clamp-2 text-[9.5px] leading-relaxed text-ink-faint">{message.content}</p>
                        </button>
                      ))}
                    </div>
                  </div>
                ) : null}

                <div className="flex gap-2">
                  <Link href={`/tasks?task=${encodeURIComponent(selectedTask.id)}`} className="btn-ghost flex-1 justify-between text-[10px]">
                    Open mission <ArrowUpRight className="h-3 w-3" />
                  </Link>
                  <Link
                    href={`/ask?q=${encodeURIComponent(`Tell me everything about ${selectedTask.title}`)}`}
                    className="btn-ghost flex-1 justify-between text-[10px]"
                  >
                    Ask <Sparkles className="h-3 w-3" />
                  </Link>
                </div>
              </div>
            ) : null}

            {selectedMessage ? (
              <div className="space-y-4">
                <div className="flex flex-wrap items-center gap-1.5">
                  <SourcePill source={selectedMessage.source} />
                  {selectedMessage.suspicious ? (
                    <span className="chip chip-rose px-2 py-0.5 text-[9px]">
                      <ShieldAlert className="h-3 w-3" /> flagged
                    </span>
                  ) : null}
                  {selectedMessage.isForwarded ? <span className="chip px-2 py-0.5 text-[9px]">forwarded</span> : null}
                </div>
                <div>
                  <p className="text-sm font-medium text-ink">{selectedMessage.senderName}</p>
                  <p className="mono mt-0.5 text-[8px] uppercase tracking-[0.12em] text-ink-faint">
                    {selectedMessage.chatName} · {formatDateTime(selectedMessage.timestamp, timeZone)}
                  </p>
                </div>
                <div className="rounded-xl border border-white/[0.06] bg-white/[0.02] p-3">
                  <p className="whitespace-pre-wrap text-[11px] leading-relaxed text-ink-dim">{selectedMessage.content}</p>
                </div>
                {selectedMessage.suspicious ? (
                  <div className="rounded-xl border border-rose/25 bg-rose/[0.06] p-3">
                    <p className="mono flex items-center gap-1.5 text-[8px] uppercase tracking-[0.14em] text-rose">
                      <ShieldAlert className="h-3 w-3" /> instruction-like content
                    </p>
                    <p className="mt-1 text-[10px] leading-relaxed text-rose/80">
                      Treated as untrusted data. No credentials were read, no action was executed.
                    </p>
                  </div>
                ) : null}
                {selectedMessage.topics.length ? (
                  <div className="flex flex-wrap gap-1.5">
                    {selectedMessage.topics.map((topic) => (
                      <span key={topic} className="chip px-2 py-0.5 text-[9px]">{topic}</span>
                    ))}
                  </div>
                ) : null}
                {selectedMessage.relations.length ? (
                  <div>
                    <p className="eyebrow mb-2">Relations</p>
                    <div className="space-y-1.5">
                      {selectedMessage.relations.map((relation) => {
                        const target = graph.messageById.get(relation.targetId);
                        return (
                          <button
                            key={`${relation.type}-${relation.targetId}`}
                            onClick={() => target && setSelectedId(target.id)}
                            className="flex w-full items-center gap-2 rounded-lg border border-white/[0.05] px-2.5 py-2 text-left transition-colors hover:border-violet/25 hover:bg-violet/[0.03]"
                          >
                            <RelationBadge relation={relation.type} />
                            <span className="min-w-0 flex-1 truncate text-[10px] text-ink-dim">
                              {target ? target.content : relation.targetId}
                            </span>
                            <span className="mono shrink-0 text-[8px] text-ink-faint">
                              {Math.round(relation.confidence * 100)}%
                            </span>
                          </button>
                        );
                      })}
                    </div>
                  </div>
                ) : null}
                <Link href={`/inbox?message=${encodeURIComponent(selectedMessage.id)}`} className="btn-ghost w-full justify-between text-[10px]">
                  Open in signal inbox <ArrowUpRight className="h-3 w-3" />
                </Link>
              </div>
            ) : null}

            {selectedCourse ? (
              <div className="space-y-4">
                <div className="flex items-center gap-2">
                  <span className="grid h-8 w-8 place-items-center rounded-lg border border-cyan/25 bg-cyan/10 text-cyan">
                    <BookOpen className="h-4 w-4" />
                  </span>
                  <div>
                    <p className="text-sm font-medium text-ink">{selectedCourse.code}</p>
                    <p className="mono mt-0.5 text-[8px] uppercase tracking-[0.12em] text-ink-faint">
                      {selectedCourse.title}
                    </p>
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-2">
                  <MiniStat label="Missions" value={linkedTasks.length} />
                  <MiniStat
                    label="Signals"
                    value={messages.filter((message) => message.courseCode === selectedCourse.code).length}
                  />
                </div>
                {selectedCourse.lecturer ? (
                  <p className="text-[10px] text-ink-faint">Lecturer: {selectedCourse.lecturer}</p>
                ) : null}
                {linkedTasks.length ? (
                  <div>
                    <p className="eyebrow mb-2">Missions</p>
                    <div className="space-y-1.5">
                      {linkedTasks.map((task) => (
                        <button
                          key={task.id}
                          onClick={() => setSelectedId(task.id)}
                          className="flex w-full items-center gap-2 rounded-lg border border-white/[0.05] px-2.5 py-2 text-left transition-colors hover:border-violet/25 hover:bg-violet/[0.03]"
                        >
                          <span className="min-w-0 flex-1 truncate text-[10px] text-ink-dim">{task.title}</span>
                          <span className="mono shrink-0 text-[8px] text-ink-faint">{countdownLabel(task.dueAt, timeZone)}</span>
                        </button>
                      ))}
                    </div>
                  </div>
                ) : null}
                {linkedMessages.length ? (
                  <div>
                    <p className="eyebrow mb-2">Recent signals</p>
                    <div className="space-y-1.5">
                      {linkedMessages.map((message) => (
                        <button
                          key={message.id}
                          onClick={() => setSelectedId(message.id)}
                          className="w-full rounded-lg border border-white/[0.05] p-2.5 text-left transition-colors hover:border-cyan/25 hover:bg-cyan/[0.03]"
                        >
                          <p className="text-[10px] font-medium text-ink">{message.senderName}</p>
                          <p className="mt-0.5 line-clamp-2 text-[9px] leading-relaxed text-ink-faint">{message.content}</p>
                        </button>
                      ))}
                    </div>
                  </div>
                ) : null}
              </div>
            ) : null}

            {hovered && !selected ? (
              <div className="mt-4 rounded-lg border border-cyan/15 bg-cyan/[0.03] p-2.5">
                <p className="mono text-[8px] uppercase tracking-[0.14em] text-cyan">Hover</p>
                <p className="mt-0.5 text-[10px] text-ink-dim">
                  {hovered.kind === "message" ? hovered.sublabel : hovered.label} · {hovered.relationCount} links
                </p>
              </div>
            ) : null}
          </div>
        </Panel>
      </div>
    </div>
  );
}

function GraphStat({ label, value, tone }: { label: string; value: number; tone: string }) {
  return (
    <div className="rounded-xl border border-white/[0.06] bg-black/20 px-3 py-2 text-center">
      <p className={cn("display text-xl font-semibold", tone)}>{value}</p>
      <p className="mono text-[8px] uppercase tracking-[0.14em] text-ink-faint">{label}</p>
    </div>
  );
}

function MiniStat({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-lg border border-white/[0.05] bg-white/[0.02] p-2">
      <p className="display text-base font-semibold text-ink">{value}</p>
      <p className="mono text-[8px] uppercase tracking-[0.12em] text-ink-faint">{label}</p>
    </div>
  );
}
