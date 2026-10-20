# Architecture

## Overview
Bonjee Bot is an event driven personal briefing system. Instead of summarizing on a fixed interval, n8n will evaluate every new message/notification and decide if a summary should be generated based off of context. 

## Data Flow

```mermaid
graph TD
  A[Android Phone] --> B[Bonjee Notifier App - NotificationListenerService]
  B --> C[n8n - notifications-ingest webhook]
  D[WhatsApp Personal Account] --> E[Evolution API]
  E --> F[n8n - whatsapp-ingest webhook]
  C --> G[Postgres DB]
  F --> G[Postgres DB]
  G --> H[n8n - context-summarizer workflow]
  H --> I[LLM - Classification & Summarization]
  I --> G
  G --> J[Next.js Dashboard]
```

## Components

| Component | Purpose | Hosting |
| :-- | :-- | :-- |
| Evolution API | QR pair with your personal WhatsApp, forward DMs and Groups via webhooks | Render Web Service + Persistent Disk |
| n8n | Ingest, normalize, classify, deduplicate, and decide when to summarize | Render Web Service + Postgres |
| Postgres | Source of truth for messages, notifications, summaries, and state | Render Managed Postgres |
| Android Notifier App | Uses `NotificationListenerService` to grab all notifications and POST them to n8n | Sideloaded APK onto your Android device |
| Next.js Dashboard | View latest brief, deadlines, action items, timeline, and settings | Render Web Service/Static Site | 

## Context Based Timing
The `context-summarizer` workflow will run after every ingest, or be triggered internally. It will check the following rules against the newly ingested items: 

| Rule | Condition | Action |
| :-- | :-- | :-- |
| Urgent Keywords | Contains words like `asap`, `urgent`, `emergency`, `deadline`, `due`, `overdue`, `pay now` | Flag as urgent, potentially trigger immediate summary if not in debounce window |
| Direct Mentions | You're @'d in a group, or your name is mentioned. Also works for DMs asking for you directly | Increase priority score |
| Questions/Requests | LLM classifies as "needs action", "question directed to you", or "request" | Becomes an action item, can trigger summary |
| Time Entities | Extracted dates/times (e.g "tomorrow at 5pm", "friday", "25th Oct", "by EOD") | Added to `deadlines` table, prioritized if within 24 hours |
| Burst Detection | >= 5 new items within the last 15 minutes | Treat as a busy period and summarize to catch you up |
| High Priority Apps | Notifications from apps such as Gmail, Google Calendar, Outlook, Banking apps, etc. containing time/event info | Boost priority | 

To prevent spam, we use a `context_state` table with `last_summary_at`, `debounce_until`, and `priority_score`. Summaries will only be generated if `priority_score >= THRESHOLD` or if it's been over a configurable cooldown period with new relevant items. 

## Security Notes
- All webhooks from your phone should be sent over HTTPS
- Use strong credentials for n8n, Postgres, and Evolution API
- Consider putting n8n behind basic auth or IP restrictions if needed
- The Android app only needs to hit your n8n webhook, no other external endpoints
- All data stays within your Render services + DB
- RLS should be enabled for the dashboard if you add auth
