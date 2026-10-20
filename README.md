# Bonjee Bot

A personal assistant that pulls all of your Android notifications and your personal WhatsApp (DMs + Groups), classifies them by context, and gives you brief summaries with deadlines and action items. 

Built to run on [Render](https://render.com/) with n8n, Evolution API, Postgres and a Next.js dashboard. 

## Goals
- Access all Android notifications via a custom NotificationListenerService
- Access your personal WhatsApp account (groups + DMs) via Evolution API
- Context based timing for summaries (no forced 24/7 spam, only when relevant or urgent)
- Extract deadlines, action items, urgent things and FYI
- All self hostable and private

## Stack
- [Evolution API](https://doc.evolution-api.com/) - WhatsApp connection
- [n8n](https://n8n.io/) - Orchestration, classification, LLM calls
- [Postgres](https://www.postgresql.org/) - Storage
- [Kotlin Android App](./apps/android-notifier) - Notification listener that forwards to n8n
- [Next.js Dashboard](./apps/dashboard) - View briefs and settings

## Getting Started
See [docs/SETUP_RENDER.md](./docs/SETUP_RENDER.md) for deployment to Render and [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md) for a deeper dive. 

> Note: Using unofficial WhatsApp APIs can be against WhatsApp's ToS. Only use with your personal account and at your own risk. 
