# Bonjee Notifier (Android)

Captures notifications posted on your Android device and forwards them to your
n8n instance. Also gives you a place to ask your assistant questions.

**No Android Studio required.** Everything below runs from PowerShell.

---

## 1. What it does

| Feature | Where it runs |
|---|---|
| Capture every posted notification (title, text, app name, package, timestamp) | `NotificationListener` (device-side) |
| Durable offline queue with automatic retry | `DeliveryQueue` (SQLite on device) |
| Forward payloads to the n8n webhook | `QueueDrainer` -> OkHttp |
| Ask the assistant a question | `AskActivity` -> AI provider, then `POST {base}/webhook/personal-ai-ask` |
| Run a command on your own computer | `LaptopActivity` / "on my laptop ..." -> `laptop-agent` over the LAN |
| Answer general questions with your own LLM | `AiSettingsActivity` + `ai/AiClient` (Groq by default) |
| Connection self-test | `MainActivity` -> test button |
| Per-app ignore list, pause/resume, visible status | `MainActivity` + persistent notification |

### Privacy model
* Nothing is sent anywhere except the base URL **you** type into the app, the AI
  provider you configure, and the laptop agent on your own network.
* No backend secrets or API keys are bundled. An AI key you add at runtime is kept in
  this app's private storage and sent only to your provider. The laptop token comes from
  the agent's `config.json` and never leaves your Wi-Fi.
* The app never reads other apps' data, only notifications the OS hands it
  through the user-granted **Notification access** permission.
* If a package is on the ignore list (for example your password manager) it is
  never captured.

---

## 2. Build it (command line only)

### One-time setup (downloads JDK 17, Android SDK, Gradle)

```powershell
cd "C:\Users\Dell Max\Desktop\Bonjee bot\apps\android-notifier"
powershell -ExecutionPolicy Bypass -File setup-toolchain.ps1
```

This creates a self-contained toolchain in `.toolchain\`:

```
.toolchain\jdk                 JDK 17 (Temurin)
.toolchain\android-sdk         Android SDK (platform-tools, android-34, build-tools 34.0.0)
.toolchain\gradle-8.7          Gradle 8.7
```

It also writes `local.properties` and generates `gradlew.bat` / `gradlew`.

### Build the APK

```powershell
powershell -ExecutionPolicy Bypass -File build-apk.ps1
```

Output:

```
app\build\outputs\apk\debug\app-debug.apk
```

Release build (still debug-signed so it installs without a keystore):

```powershell
powershell -ExecutionPolicy Bypass -File build-apk.ps1 -Release
```

### If you already have Gradle / Android Studio installed

```powershell
.\gradlew.bat assembleDebug
```

---

## 3. Put it on your phone

1. Copy `app\build\outputs\apk\debug\app-debug.apk` to the phone (USB, Drive, or email it to yourself).
2. On the phone: **Settings -> Apps -> Special app access -> Install unknown apps** -> allow the app you used to copy the file (Files / Chrome).
3. Tap the APK and install.
4. Open **Bonjee Notifier**.

---

## 4. Configure it

| Field | Value for this project |
|---|---|
| n8n base URL | `https://bonjee-n8n.onrender.com` |
| Notification webhook path | `/webhook/notifications-ingest` |
| Assistant (ask) path | `/webhook/personal-ai-ask` |
| Auth token / header | leave blank unless you add header auth to n8n |
| Apps to ignore | e.g. `com.eg.android.AlipayGphone,com.android.chrome` (optional) |
| Laptop agent (Automations > Laptop Control) | address + token printed by `laptop-agent/start-agent.ps1` |
| AI (Automations > AI Assistant) | base URL, API key and model, e.g. Groq `llama-3.3-70b-versatile` |

Steps in the app:

1. Fill in the fields, tap **Save settings**.
2. Tap **Test connection to n8n** - it posts a synthetic notification. A `200`
   means the app can reach n8n. If you get `404` the workflow is not active or
   the path is wrong; `503` usually means n8n itself is not ready.
3. Tap **Grant notification access** and enable **Bonjee Notifier** in the
   system list.
4. Tap **Start capture service**. A persistent "Bonjee Notifier active"
   notification appears - that is the honest status indicator.
5. Tap **Ask your assistant** to query your archive.
6. Optional: **Automations > Laptop Control** to reach your computer, and
   **Automations > AI Assistant** for direct LLM answers. Both are off until configured;
   laptop commands are approved before they run.

---

## 5. Verify end to end

1. Trigger any notification on the phone (e.g. a message from another app).
2. n8n -> **Executions** should show the `notifications-ingest` execution.
3. Postgres -> `SELECT * FROM notifications ORDER BY post_time DESC LIMIT 5;`
4. The app's status card should flip `Last delivery: SUCCESS`.

If notifications are missing, check the **Queued notifications** counter. If it
is above zero, the app captured them but n8n was unreachable - the queue
retries every 30 s and you can force it with **Sync queued notifications now**.

---

## 6. How the two n8n workflows fit together

```
Android phone
    |  POST {base}/webhook/notifications-ingest
    v
n8n "Bonjee Bot" workflow  ->  notifications table
    |
    +--> (separate) POST {base}/webhook/personal-ai-ask  <-- AskActivity
                |
                v
         messages table -> keyword/vector search -> evidence
```

The Evolution API (WhatsApp) side is independent of the app and uses its own
webhook path. See `docs/SETUP_RENDER.md` in the repo root for the WhatsApp
side.

---

## 7. Files

```
app/src/main/java/com/bonjee/notifier/
  MainActivity.kt          config, status, self-test, service control
  AskActivity.kt           assistant question screen
  laptop/LaptopCommand.kt  pure parser for "on my laptop ..." sentences
  laptop/LaptopClient.kt   HTTP client for the laptop agent
  laptop/LaptopDiscovery.kt  finds the agent on the phone's Wi-Fi
  laptop/LaptopHistoryStore.kt  last 40 runs, stored locally
  ai/AiClient.kt           OpenAI-compatible chat (Groq by default)
  ui/LaptopActivity.kt     connection, safety switches, run, history
  ui/AiSettingsActivity.kt key/model settings and TEST
  NotificationListener.kt  capture + enqueue
  DeliveryQueue.kt         SQLite durable queue
  QueueDrainer.kt          delivery + retry logic
  ForegroundService.kt     keeps capture alive, surfaces queue depth
  ApiClient.kt             OkHttp wrapper
  Prefs.kt                 configuration storage
build-apk.ps1              one-command APK build
setup-toolchain.ps1        one-command toolchain install
```
