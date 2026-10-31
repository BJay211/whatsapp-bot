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

3.3 adds two optional features, both off until you set them up:

- **Laptop control** - run `laptop-agent/start-agent.ps1` on your computer, then paste
  the printed address and token into **Automations → Laptop Control**. Commands are
  approved before they run. See [`laptop-agent/README.md`](./laptop-agent/README.md).
- **AI answers** - put a Groq (or any OpenAI-compatible) API key and model into
  **Automations → AI Assistant**. General questions then go straight to that provider
  instead of n8n; if it fails, the app shows why and falls back to the archive.

3.4 added **Friday**, the laptop half of the assistant: a native Windows app with
its own voice, tools and encrypted phone link. The phone keeps every 3.3 feature;
Friday is an extra destination for commands you address to it by name.

3.5 turns both halves into a conversation and makes the link work away from home.

- **JARVIS is voice-first.** One tap opens a continuous conversation: JARVIS
  listens, answers aloud, and keeps listening for a follow-up for 30 seconds
  without another tap. Speak while it is talking and it stops and handles the
  correction. The wake phrases **"Hey JARVIS"**, **"JARVIS"**, **"Little help
  here, JARVIS"** and **"JARVIS, you up?"** start it from the visible
  notification; the microphone service is a foreground service, not a hidden
  listener. See [`docs/FRIDAY_SETUP.md`](./docs/FRIDAY_SETUP.md).
- **Voice settings** - pick the voice, rate and pitch, turn the wake service on
  or off, and hear a real test sentence from **JARVIS → Voice**.
- **Friday on the laptop** - run `apps\friday\dist\Friday-1.1.0.exe` (or the
  Desktop copy). It needs the .NET 10 Desktop Runtime. Friday stays hands-free
  for 30 seconds after "Friday", speaks sentences as they are written, and keeps
  local context. See [`docs/FRIDAY_SETUP.md`](./docs/FRIDAY_SETUP.md).
- **Friday on the phone** - the Friday screen takes either the LAN address
  (`192.168.1.20`, port 8790) or an internet relay URL. Compare the SHA-256
  fingerprint on the LAN, enter the eight-character code, and pair once. On the
  LAN the certificate is pinned; through the relay the laptop id plus the device
  token identify the laptop.
- **Away from home** - deploy `relay/` with `render.yaml`, enable **Settings >
  Phone > Internet relay** on the laptop, and paste the same URL on the phone.
  No port forwarding, no router change. The relay forwards only `/v1/` traffic
  and keeps no keys, tokens or command logic.
- **Routing** - a command that starts with the wake word ("Friday, open Spotify")
  or names the laptop ("what is my laptop's battery") goes to Friday. Everything
  else stays a normal JARVIS question. Phone-only commands (torch, calls, alarms)
  never leave the phone.
- **Safety** - every laptop tool declares a capability and a risk level, safe
  tools follow the Access switches, destructive ones always ask, and
  `Ctrl+Alt+Esc` / the phone's **EMERGENCY STOP** cancels everything. Details:
  [`docs/FRIDAY_SECURITY.md`](./docs/FRIDAY_SECURITY.md) and the protocol in
  [`docs/FRIDAY_API.md`](./docs/FRIDAY_API.md).
- **What was actually tested** - 44 self-test, 25 network, 10 relay-harness and
  8 real relay end-to-end checks pass. The Android side is built and inspected
  but has not been run on a phone; no AI key was available; no hosted relay was
  reached from mobile data. Read
  [`docs/FRIDAY_TESTING.md`](./docs/FRIDAY_TESTING.md) before trusting it with
  anything important.

The release APK is `apps\android-notifier\dist\JARVIS-3.5.apk` (`versionCode`
17, package `com.bonjee.notifier` unchanged, debug-signed). Friday's source is in
[`apps/friday`](./apps/friday); the new build is
`apps\friday\dist\Friday-1.1.0.exe`.

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
4. Dashboard URL: `https://bonjee-dashboard.onrender.com` (only used if that service is
   deployed; the side-menu modules are native now - Memory Core, Inbox, Tasks, Calendar,
   Automations, Message Rules and Devices & n8n all work without the dashboard)
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
DEFAULT ASSISTANT** to jump straight to that screen. On Android 10+ that button
opens the system assistant-role dialog (`RoleManager.ROLE_ASSISTANT`); older
releases fall back to the settings screen. Android only allows the user to change
the default, so the app never switches it silently.

The picker lists the service label, which is **JARVIS** - not the launcher
label. If JARVIS is missing from the list, open **App Settings > CHECK ASSISTANT
STATUS**: it runs the same `PackageManager` / `RoleManager` queries the picker uses
and prints a shareable report (build version, whether the service answers
`android.service.voice.VoiceInteractionService`, the parsed `sessionService`, role
availability, and `Settings.Secure voice_interaction_service`).

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
