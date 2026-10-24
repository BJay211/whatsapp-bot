import { NextResponse } from "next/server";
import { getSnapshot } from "@/lib/store";

export const dynamic = "force-dynamic";

export async function GET(request: Request) {
  const url = new URL(request.url);
  const q = (url.searchParams.get("q") ?? "").trim().toLowerCase();
  const source = url.searchParams.get("source");
  const chat = url.searchParams.get("chat");
  const limit = Math.min(200, Math.max(1, Number(url.searchParams.get("limit") ?? 100)));

  const snapshot = await getSnapshot();
  const messages = snapshot.messages
    .filter((message) => {
      if (source && source !== "all" && message.source !== source) return false;
      if (chat && message.chatId !== chat) return false;
      if (q) {
        const haystack = `${message.content} ${message.chatName} ${message.senderName}`.toLowerCase();
        if (!haystack.includes(q)) return false;
      }
      return true;
    })
    .sort((a, b) => b.timestamp.localeCompare(a.timestamp))
    .slice(0, limit);

  const chats = new Map<string, { id: string; name: string; count: number; source: string }>();
  for (const message of snapshot.messages) {
    const existing = chats.get(message.chatId);
    if (existing) existing.count += 1;
    else chats.set(message.chatId, { id: message.chatId, name: message.chatName, count: 1, source: message.source });
  }

  return NextResponse.json({
    ok: true,
    mode: snapshot.mode,
    count: messages.length,
    messages,
    chats: [...chats.values()].sort((a, b) => b.count - a.count),
  });
}
