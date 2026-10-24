"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import {
  ArrowUpRight,
  Check,
  Copy,
  Inbox,
  MessageSquare,
  Radio,
  Search,
  ShieldAlert,
  Sparkles,
  X,
} from "lucide-react";
import type { Message } from "@/lib/types";
import { SourcePill, RelationBadge } from "@/components/ui/status";
import { formatDateTime, timeAgo, truncate } from "@/lib/format";
import { cn } from "@/lib/utils";

function CopyId({ id }: { id: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(id);
          setCopied(true);
          window.setTimeout(() => setCopied(false), 1400);
        } catch {
          /* ignore */
        }
      }}
      className="inline-flex items-center gap-1 rounded-md border border-white/10 px-1.5 py-1 text-[9px] text-ink-faint transition-colors hover:text-cyan"
    >
      {copied ? <Check className="h-2.5 w-2.5 text-lime" /> : <Copy className="h-2.5 w-2.5" />}
      {id}
    </button>
  );
}

function Detail({
  message,
  onClose,
  onSelect,
  allMessages,
}: {
  message: Message;
  onClose?: () => void;
  onSelect: (id: string) => void;
  allMessages: Message[];
}) {
  return (
    <div className="flex h-full flex-col">
      <div className="flex items-start justify-between gap-3 border-b border-white/[0.06] p-4">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <SourcePill source={message.source} />
            <span className="text-[11px] text-ink-faint">{message.chatName}</span>
          </div>
          <h2 className="mt-2 text-sm font-medium text-ink">{message.senderName}</h2>
          <p className="mono mt-1 text-[9px] uppercase tracking-[0.12em] text-ink-faint">
            {formatDateTime(message.timestamp)} · {message.isGroup ? "group" : "direct"}
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-1.5">
          <CopyId id={message.id} />
          {onClose ? (
            <button onClick={onClose} className="rounded-md p-1 text-ink-faint hover:text-ink lg:hidden" aria-label="Close">
              <X className="h-4 w-4" />
            </button>
          ) : null}
        </div>
      </div>

      <div className="scroll-area flex-1 overflow-y-auto p-4">
        {message.suspicious ? (
          <div className="mb-3 flex items-start gap-2 rounded-xl border border-rose/25 bg-rose/[0.07] p-3">
            <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 text-rose" />
            <div>
              <p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-rose">Instruction-like content</p>
              <p className="mt-1 text-[11px] leading-relaxed text-rose/90">
                This message tries to issue commands to the assistant. It is stored as evidence only.
                Jarvis will never follow instructions found inside message text.
              </p>
            </div>
          </div>
        ) : null}

        <div className="rounded-xl border border-white/[0.07] bg-black/20 p-4">
          <p className="whitespace-pre-line text-[13px] leading-relaxed text-ink">{message.content}</p>
          {message.caption ? (
            <p className="mt-2 border-t border-white/[0.05] pt-2 text-[11px] text-ink-faint">{message.caption}</p>
          ) : null}
        </div>

        <div className="mt-4 grid grid-cols-2 gap-2">
          <div className="rounded-lg border border-white/[0.06] p-2.5">
            <p className="mono text-[8px] uppercase tracking-[0.12em] text-ink-faint">Type</p>
            <p className="mt-1 text-[11px] capitalize text-ink-dim">{message.type}</p>
          </div>
          <div className="rounded-lg border border-white/[0.06] p-2.5">
            <p className="mono text-[8px] uppercase tracking-[0.12em] text-ink-faint">Processed</p>
            <p className="mt-1 flex items-center gap-1 text-[11px] text-lime">
              <Check className="h-3 w-3" /> {message.processed ? "yes" : "pending"}
            </p>
          </div>
          <div className="rounded-lg border border-white/[0.06] p-2.5">
            <p className="mono text-[8px] uppercase tracking-[0.12em] text-ink-faint">Course</p>
            <p className="mt-1 text-[11px] text-ink-dim">{message.courseCode ?? "—"}</p>
          </div>
          <div className="rounded-lg border border-white/[0.06] p-2.5">
            <p className="mono text-[8px] uppercase tracking-[0.12em] text-ink-faint">Forwarded</p>
            <p className="mt-1 text-[11px] text-ink-dim">{message.isForwarded ? "yes" : "no"}</p>
          </div>
        </div>

        {message.topics.length ? (
          <div className="mt-4">
            <p className="eyebrow mb-2">Extracted topics</p>
            <div className="flex flex-wrap gap-1.5">
              {message.topics.map((topic) => (
                <span key={topic} className="chip">{topic}</span>
              ))}
            </div>
          </div>
        ) : null}

        {message.relations.length ? (
          <div className="mt-4">
            <p className="eyebrow mb-2">Relationships</p>
            <div className="space-y-1.5">
              {message.relations.map((relation) => {
                const target = allMessages.find((m) => m.id === relation.targetId);
                return (
                  <button
                    key={`${relation.type}-${relation.targetId}`}
                    onClick={() => target && onSelect(target.id)}
                    className="flex w-full items-center gap-2 rounded-lg border border-white/[0.06] px-2.5 py-2 text-left transition-colors hover:border-violet/25 hover:bg-violet/[0.04]"
                  >
                    <RelationBadge relation={relation.type} />
                    <span className="min-w-0 flex-1 truncate text-[10px] text-ink-dim">
                      {target ? truncate(target.content, 90) : relation.targetId}
                    </span>
                    {target ? <ArrowUpRight className="h-3 w-3 shrink-0 text-ink-faint" /> : null}
                  </button>
                );
              })}
            </div>
          </div>
        ) : null}
      </div>

      <div className="border-t border-white/[0.06] p-3">
        <Link
          href={`/ask?q=${encodeURIComponent(`What does this message mean for me? ${message.content.slice(0, 160)}`)}`}
          className="btn-primary w-full text-xs"
        >
          <Sparkles className="h-3.5 w-3.5" />
          Ask Jarvis about this
        </Link>
      </div>
    </div>
  );
}

export function InboxExplorer({
  messages,
  initialMessageId,
  mode,
}: {
  messages: Message[];
  initialMessageId?: string;
  mode: "demo" | "live";
}) {
  const [query, setQuery] = useState("");
  const [source, setSource] = useState<"all" | "whatsapp" | "android">("all");
  const [chat, setChat] = useState("all");
  const [selectedId, setSelectedId] = useState<string | null>(initialMessageId ?? messages[0]?.id ?? null);
  const [mobileOpen, setMobileOpen] = useState(false);

  const chats = useMemo(() => {
    const map = new Map<string, { id: string; name: string; count: number }>();
    for (const message of messages) {
      const entry = map.get(message.chatId) ?? { id: message.chatId, name: message.chatName, count: 0 };
      entry.count += 1;
      map.set(message.chatId, entry);
    }
    return [...map.values()].sort((a, b) => b.count - a.count);
  }, [messages]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return messages
      .filter((message) => {
        if (source !== "all" && message.source !== source) return false;
        if (chat !== "all" && message.chatId !== chat) return false;
        if (q) {
          const haystack = `${message.content} ${message.senderName} ${message.chatName}`.toLowerCase();
          if (!haystack.includes(q)) return false;
        }
        return true;
      })
      .sort((a, b) => b.timestamp.localeCompare(a.timestamp));
  }, [messages, query, source, chat]);

  const selected = messages.find((m) => m.id === selectedId) ?? filtered[0] ?? null;

  const select = (id: string) => {
    setSelectedId(id);
    setMobileOpen(true);
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <p className="eyebrow">Archive</p>
          <h1 className="display mt-1 text-xl font-semibold text-ink">Signal inbox</h1>
          <p className="mt-1 text-xs text-ink-faint">
            Original messages, preserved. Filters are local to your archive.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <span className={cn("chip", mode === "live" ? "chip-lime" : "chip-amber")}>
            {mode === "live" ? "live archive" : "synthetic fixtures"}
          </span>
          <span className="chip">{messages.length} messages</span>
        </div>
      </div>

      <div className="panel overflow-hidden">
        <div className="flex flex-wrap items-center gap-2 border-b border-white/[0.06] p-3">
          <div className="relative min-w-[180px] flex-1">
            <Search className="absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-ink-faint" />
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Search messages, senders, chats…"
              className="field h-9 py-0 pl-8 text-xs"
            />
          </div>
          <select
            value={source}
            onChange={(event) => setSource(event.target.value as typeof source)}
            className="field h-9 w-auto py-0 text-xs"
            aria-label="Filter by source"
          >
            <option value="all">All sources</option>
            <option value="whatsapp">WhatsApp</option>
            <option value="android">Android</option>
          </select>
          <select
            value={chat}
            onChange={(event) => setChat(event.target.value)}
            className="field h-9 max-w-[220px] py-0 text-xs"
            aria-label="Filter by chat"
          >
            <option value="all">All chats</option>
            {chats.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name} ({c.count})
              </option>
            ))}
          </select>
        </div>

        <div className="grid lg:grid-cols-[minmax(0,380px)_minmax(0,1fr)]">
          <div className="scroll-area max-h-[calc(100vh-260px)] min-h-[420px] overflow-y-auto border-b border-white/[0.06] lg:border-b-0 lg:border-r">
            {filtered.length === 0 ? (
              <div className="flex flex-col items-center gap-2 px-6 py-16 text-center">
                <Inbox className="h-5 w-5 text-ink-faint" />
                <p className="text-xs text-ink-dim">No messages match those filters.</p>
                <button
                  onClick={() => {
                    setQuery("");
                    setSource("all");
                    setChat("all");
                  }}
                  className="text-[10px] text-cyan hover:underline"
                >
                  Clear filters
                </button>
              </div>
            ) : (
              filtered.map((message) => (
                <button
                  key={message.id}
                  onClick={() => select(message.id)}
                  className={cn(
                    "block w-full border-b border-white/[0.04] px-3 py-3 text-left transition-colors hover:bg-white/[0.03]",
                    selected?.id === message.id && "bg-cyan/[0.05] shadow-[inset_2px_0_0_0_rgba(34,211,238,0.7)]",
                  )}
                >
                  <div className="flex items-center gap-1.5">
                    <span
                      className={cn(
                        "grid h-5 w-5 shrink-0 place-items-center rounded-md border",
                        message.source === "whatsapp"
                          ? "border-lime/20 bg-lime/[0.06] text-lime"
                          : "border-cyan/20 bg-cyan/[0.06] text-cyan",
                      )}
                    >
                      {message.source === "whatsapp" ? <MessageSquare className="h-2.5 w-2.5" /> : <Radio className="h-2.5 w-2.5" />}
                    </span>
                    <span className="truncate text-[11px] font-medium text-ink">{message.senderName}</span>
                    {message.suspicious ? <ShieldAlert className="h-3 w-3 shrink-0 text-rose" /> : null}
                    <span className="mono ml-auto shrink-0 text-[8px] uppercase tracking-[0.1em] text-ink-faint">
                      {timeAgo(message.timestamp)}
                    </span>
                  </div>
                  <p className="mt-1 truncate text-[9px] uppercase tracking-[0.1em] text-ink-faint">{message.chatName}</p>
                  <p className="mt-1 line-clamp-2 text-[11px] leading-relaxed text-ink-dim">
                    {truncate(message.content, 120)}
                  </p>
                  <div className="mt-1.5 flex flex-wrap items-center gap-1">
                    <SourcePill source={message.source} />
                    {message.courseCode ? <span className="chip px-1.5 py-0 text-[8px]">{message.courseCode}</span> : null}
                    {message.relations.slice(0, 1).map((relation) => (
                      <RelationBadge key={relation.type} relation={relation.type} />
                    ))}
                  </div>
                </button>
              ))
            )}
          </div>

          <div className="hidden min-h-[520px] lg:block">
            {selected ? (
              <Detail message={selected} onSelect={(id) => setSelectedId(id)} allMessages={messages} />
            ) : (
              <div className="flex h-full items-center justify-center text-xs text-ink-faint">Select a message.</div>
            )}
          </div>
        </div>
      </div>

      {mobileOpen && selected ? (
        <div className="fixed inset-0 z-[80] flex items-stretch justify-end bg-black/70 p-0 backdrop-blur-sm lg:hidden">
          <div className="h-full w-full max-w-lg border-l border-cyan/10 bg-[#000d1a]">
            <Detail
              message={selected}
              onClose={() => setMobileOpen(false)}
              onSelect={(id) => setSelectedId(id)}
              allMessages={messages}
            />
          </div>
        </div>
      ) : null}
    </div>
  );
}
