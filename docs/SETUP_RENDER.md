# Setup on Render

This guide walks you through deploying Bonjee Bot to [Render](https://render.com/). Everything here is tailored to your request (all notifications, personal WhatsApp, timing based off context). 

---

## Prerequisites
- A [Render](https://render.com/) account (free tier possible, but for persistence + Evolution API it's recommended to use paid plans for disk + always on services)
- An Android phone (Android 6+ recommended) to install the notifier app
- Access to your terminal to build/deploy the Next.js dashboard and to sideload the APK
- An OpenAI, Google Gemini, Anthropic or other LLM API key for summarization (n8n can connect to any provider of your choice) 

---

## 1) Create a Postgres Database
1. Go to Render -> New -> PostgreSQL
2. Name: `bonjee-db`
3. Plan: Pick based off of expected volume (start with Free/Starter)
4. Create Database and note down the `Internal Database URL`, `Hostname`, `Port`, `Database`, `Username`, `Password` (or just the full `postgres://` URL). n8n will use the internal connection when services are in the same region. 

--- 

## 2) Deploy Evolution API (WhatsApp)
Evolution API requires a persistent disk to store its session/auth state (so you don't need to re-scan QR every deploy/restart). 

1. Create a new Web Service on Render (or use the Render Blueprint with `render.yaml` later). 
2. Repo: `https://github.com/EvolutionAPI/evolution-api` (or use the official Docker image). Using the prebuilt image is easiest. 
3. Name: `bonjee-evolution`
4. Runtime: Docker
5. Image: `docker.io/evolutionapi/evolution-api:latest` (or a specific version you trust)
6. Add a persistent disk: Mount Path `/data`, Size e.g 1GB (sufficient for auth + configs)
7. Environment Variables (minimum, see their docs for more):
   | Variable | Value | Notes |
   | :-- | :-- | :-- |
   | `PORT` | `8080` (or Render's `$PORT`) | Ensure the app binds to Render's assigned port |
   | `DATABASE_URL` | Your Render Postgres URL | Can share the same DB or use a separate one |
   | `AUTHENTICATION_API_KEY` | Generate a secure random key | Used to protect Evolution's API + webhooks config |
   | `SERVER_URL` | `https://bonjee-evolution.onrender.com` | Replace with your service URL once created |
8. Deploy, then go to `https://your-evolution-url/` and pair your personal WhatsApp via QR code (instance creation via their UI/API). 

Note: Evolution's instance must be configured to send webhooks to n8n for new messages (`webhook` event type). We'll do this after n8n is deployed. 

--- 

## 3) Deploy n8n
1. New -> Web Service on Render. You can use the official n8n Docker image. 
2. Name: `bonjee-n8n`
3. Runtime: Docker
4. Image: `docker.n8n.io/n8nio/n8n:latest`
5. Plan: At least Starter (required for persistence + webhook reliability)
6. Environment Variables: 
   | Variable | Value |
   | :-- | :-- |
   | `PORT` | `$PORT` |
   | `GENERIC_TIMEZONE` | Your timezone (e.g `Africa/Lagos` or `UTC`) |
   | `N8N_ENCRYPTION_KEY` | Generate a long random string (encrypts credentials) |
   | `DB_TYPE` | `postgresdb` |
   | `DB_POSTGRESDB_HOST` | Render DB host |
   | `DB_POSTGRESDB_PORT` | `5432` |
   | `DB_POSTGRESDB_DATABASE` | Render DB name |
   | `DB_POSTGRESDB_USER` | Render DB user |
   | `DB_POSTGRESDB_PASSWORD` | Render DB pass |
   | `WEBHOOK_URL` | `https://bonjee-n8n.onrender.com/` | 
   | `N8N_EDITOR_BASE_URL` | `https://bonjee-n8n.onrender.com/` |
   | `EXECUTIONS_MODE` | `queue` (optional, recommended for reliability) |
7. Persistent Disk: Mount `/home/node/.n8n` (or the path n8n uses to store workflows/credentials) with at least 1GB. 
8. Deploy. You'll be able to access n8n's editor at the service URL. 

--- 

## 4) Configure Evolution API Webhooks
After n8n is up: 

1. In n8n, create a Webhook node for the WhatsApp ingest (or import the workflow from `n8n/workflows/whatsapp-ingest.json` later). It will give you a production URL like `https://bonjee-n8n.onrender.com/webhook/whatsapp-ingest` 
2. In Evolution API, configure your instance to send webhooks on `message.upsert` (or the new message event) to that URL. Include any required headers if you add auth to the webhook (recommended).
3. Test by sending a message to your WhatsApp (DM or in a group). You should see a run in n8n's executions. 

--- 

## 5) Android Notifier App
The app lives under `apps/android-notifier`. 

### Requirements to build
- Android Studio (or command line tools with JDK 17)
- Android SDK (min SDK 21, target SDK 34)

### Steps
1. Open the project in Android Studio (`apps/android-notifier`)
2. Open `local.properties` (or `BuildConfig`/strings) and set `N8N_WEBHOOK_URL` to your n8n webhook e.g `https://bonjee-n8n.onrender.com/webhook/notifications-ingest` 
3. Build a release APK (`Build -> Generate App Bundle/APK -> APK`)
4. Transfer the APK to your Android phone and sideload it (e.g via ADB, Google Files or Send Files)
5. Open the app -> Grant "Notification Access" permission (Settings -> Special App Access -> Notification Access -> enable "Bonjee Notifier")
6. (Optional) Configure allowlist. By default, with "all notifications" requested, the listener will forward everything. You can filter in the app or in n8n (recommended to filter in n8n to be able to change without rebuilding). 
7. Test: receive any notification and check n8n executions for the notifications webhook. 

> Security: Only ever point this to your own HTTPS n8n endpoint. Do not hardcode any third party URLs. 

---

## 6) Deploy the Dashboard
The Next.js app is under `apps/dashboard`. It will query the Postgres DB directly (e.g via Prisma or Supabase client). For a single user dashboard, you can protect it with basic auth via Render's "Password Protection" or implement your own auth. 

### Option A: Render Web Service (SSR/API routes)
1. New Web Service, connect your repo or use the built Dockerfile
2. Build Command: `npm run build`
3. Start Command: `npm start`
4. Add env vars for DB connection + any auth secrets

### Option B: Static Site
If it's fully client side and you use a serverless DB proxy, Render static sites work too. Starting with a Web Service is easier for API routes.

Deploy after setting your `.env` with the DB connection string and `N8N_BASE_URL`. 

---

## 7) Configure n8n Workflows
Import the JSONs from `n8n/workflows/` into your n8n instance: 

1. `notifications-ingest.json` - Receives POSTs from Android app, normalizes, deduplicates, inserts into `notifications` table
2. `whatsapp-ingest.json` - Receives webhooks from Evolution API, normalizes messages (DMs + Groups), inserts into `messages` table
3. `context-summarizer.json` - Runs after ingest events (or on a webhook call from the other two). Classifies context, applies timing rules, calls LLM for summary if threshold met, writes to `summaries` and updates `context_state`. 

After importing, add your credentials for your LLM provider (e.g OpenAI/Gemini/Anthropic) to the `AI Agent` or `LLM` nodes, and your Postgres credential for the database nodes. 

---

## Tips & Notes
- Debounce: Start with 10 minutes between summaries for non urgent items, 1-2 minutes for urgent. Tune to your liking in the workflow's variables.
- Filtering noise: Even with "all notifications", you'll want to block certain apps (e.g system updates, ads) in n8n. Add an allowlist/blocklist column in settings later.
- Costs: LLM summarization is the main variable cost. Context based timing directly combats this (only when needed). Use cheaper models for classification and a stronger one for the final briefing if desired.
- Render cold starts: If on free tier, services may spin down. For real time webhooks (especially WhatsApp + notifications) use at least a paid "Starter" plan for n8n and Evolution API to avoid missed events.
- Backups: Render Postgres has automated backups depending on your plan. Consider exporting your `messages` and `summaries` periodically if this is important to you.