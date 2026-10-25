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

---

## Quick start (what actually works today)

### 1. n8n: import one workflow

File: **`Bonjee_bot.json`** (repo root)

1. Open `https://bonjee-n8n.onrender.com` and create the owner account if you have not.
2. **Workflows -> Import from File -> Bonjee_bot.json**.
3. Open the **Postgres** credential field on these four nodes and pick your saved
   `bonjee-db` credential:
   - `Save WhatsApp Message`
   - `Save Notification`
   - `Search Messages`
   - `Search Notifications`
   - `Health Database`
4. **Save**, then toggle the workflow **Active**.

### 2. Webhook URLs

| Method | URL | Used by |
|---|---|---|
| POST | `https://bonjee-n8n.onrender.com/webhook/personal-ai-whatsapp` | Evolution API |
| POST | `https://bonjee-n8n.onrender.com/webhook/notifications-ingest` | Android app |
| POST | `https://bonjee-n8n.onrender.com/webhook/personal-ai-ask` | Android app / browser |
| GET | `https://bonjee-n8n.onrender.com/webhook/health` | uptime checks |

Verify with a browser:

```
https://bonjee-n8n.onrender.com/webhook/health
```

A healthy reply looks like:

```json
{"ok":true,"status":"healthy","service":"bonjee-bot","database":"reachable","message_count":0,"notification_count":0}
```

### 3. Database

Run [`db/schema.sql`](./db/schema.sql) once against the `bonjee-db` database
(TablePlus / DBeaver / pgAdmin using the **External** Database URL from Render).
The workflow needs the `messages` and `notifications` tables.

### 4. Dashboard (Bonjee OS web cockpit)

The Next.js dashboard reads the **same** `bonjee-db` Postgres that n8n writes to, so
WhatsApp messages and Android notifications show up on the web without any extra sync.

Render service settings (`bonjee-dashboard`):

| Setting | Value |
|---|---|
| Root directory | `apps/dashboard` |
| Build command | `npm install --include=dev && npm run build` |
| Start command | `npm start` |
| Health check path | `/api/health` |

Environment variables:

| Key | Value |
|---|---|
| `DATABASE_URL` | Internal Database URL of `bonjee-db` |
| `N8N_BASE_URL` | `https://bonjee-n8n.onrender.com` |
| `EVOLUTION_API_URL` | `https://bonjee-evolution.onrender.com` |
| `EVOLUTION_API_KEY` | the Evolution `AUTHENTICATION_API_KEY` |
| `DASHBOARD_PASSPHRASE` | optional - locks the cockpit behind a passphrase |
| `NODE_ENV` | `production` |

`https://<your-dashboard>/api/health` reports exactly what is wired up:

```json
{"ok":true,"mode":"live","postgres":"SELECT 1 succeeded","n8n":"reachable","evolution":"reachable"}
```

`"mode":"demo"` means `DATABASE_URL` is missing or unreachable - the dashboard then
shows sample data instead of failing.

### 5. Android app

See [`apps/android-notifier/README.md`](./apps/android-notifier/README.md).

```powershell
cd "apps\android-notifier"
powershell -ExecutionPolicy Bypass -File setup-toolchain.ps1   # one time
powershell -ExecutionPolicy Bypass -File build-apk.ps1
```

The APK lands in `apps\android-notifier\app\build\outputs\apk\debug\app-debug.apk`.
Copy it to your phone, install it, then:

1. Base URL: `https://bonjee-n8n.onrender.com`
2. Notification path: `/webhook/notifications-ingest`
3. Ask path: `/webhook/personal-ai-ask`
4. Dashboard URL: `https://bonjee-dashboard.onrender.com` (the side menu opens
   Inbox, Tasks, Calendar, Automations, Memory, Devices and Settings from there)
5. **Save settings -> Test connection -> Grant notification access -> Start capture service**

The home screen is the glowing energy core. Tap the core to speak (a waveform
appears while it listens), or switch to **TYPE** to send a written command. Tap the
menu button - or swipe in from the left edge - to open the side drawer.

No Android Studio is required anywhere in that flow.

#### Make JARVIS the default assistant

The app ships a real `VoiceInteractionService`, so Android lists it under
**Settings > Apps > Default apps > Digital assistant app** (HyperOS/MIUI wording
varies: *Settings > Apps > Default apps > Digital assistant app*).

From inside the app use **menu > Default Assistant** or **App Settings > SET AS
DEFAULT ASSISTANT** to jump straight to that screen. Android only allows the user
to change the default, so the app never switches it silently.

Once selected, the assistant gesture (long-press home / power-button action)
opens JARVIS, starts listening, sends the recognised text to
`/webhook/personal-ai-ask` and reads the answer back with the system TTS engine.

### 6. WhatsApp (Evolution API)

Point the instance webhook at
`https://bonjee-n8n.onrender.com/webhook/personal-ai-whatsapp`
and enable the `MESSAGES_UPSERT` event.

---

## Validate workflows before importing

```powershell
node tests/n8n/validate_workflows.mjs
```

It checks JSON validity, node types, duplicate names/ids, broken connections,
duplicate webhook paths, unsafe SQL interpolation and hardcoded secrets.

## Docs

- [docs/SETUP_RENDER.md](./docs/SETUP_RENDER.md) - deployment to Render
- [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md) - design notes

> Note: Using unofficial WhatsApp APIs can be against WhatsApp's ToS. Only use with your personal account and at your own risk.
