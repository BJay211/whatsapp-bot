# Changes - JARVIS 3.4 and Friday

This lists what changed, file by file, and why. It covers the Friday laptop app
(entirely new) and the JARVIS 3.4 phone changes. It does not repeat the protocol
or the security model; those are in `FRIDAY_API.md` and `FRIDAY_SECURITY.md`.

Nothing existing was removed. JARVIS 3.3 keeps every feature; `laptop-agent/`
still works and still handles "on my laptop ..." while it is configured.

---

## 1. At a glance

| Change | Why |
|---|---|
| New Windows app `apps/friday` | The laptop needs to be a real agent, not a webhook button: it owns the mic, speakers, files and apps, and it must keep working with no cloud. |
| Encrypted phone link (TLS + pin + per-device token) | A phone command can delete files and type into windows. It cannot be a shared secret over plain HTTP. |
| Permission gate with capabilities and risk levels | The model may ask, but only the laptop decides. Safe / Confirm / Destructive, plus six Access switches. |
| Task queue with approval and cancellation | Long work must not hold an HTTP request open, and emergency stop must actually stop things. |
| JARVIS 3.4: Friday routing, pairing screen, approvals, cancel | The phone is the remote control, without losing any existing JARVIS behaviour. |
| Response time: event-driven completion, keep-alive, adaptive polling | The old path was 137 ms in-process and 163 ms over the wire for an answer that takes 8 ms. |
| Access switches now enforced | Four of the six switches were saved but never checked. They are now checked before any tool runs. |

---

## 2. New: `apps/friday`

Everything below is new in this change.

### Project and entry point

| File | Purpose |
|---|---|
| `Friday.csproj` | `net10.0-windows`, WinForms, single-file publish, `NoWarn=CA1416;WFO1000`. |
| `app.manifest` | Per-monitor DPI awareness, no UAC elevation. |
| `src/Program.cs` | Single-instance guard (brings the existing window forward), `--selftest`, `--netcheck`, `--compact`, `--background`. |
| `src/SelfTest.cs` | 34 headless checks; writes `%APPDATA%\Friday\logs\selftest.txt`; measures local command latency. |
| `src/NetCheck.cs` | 25 over-the-wire checks on a throw-away TLS server; measures round trip and a 10-call latency sample. |

### Core

| File | Purpose |
|---|---|
| `src/Core/FridayRuntime.cs` | Owns config, paths, state, voice, tasks, server, memory, conversation. Runs retention pruning at startup. |
| `src/Core/FridayConfig.cs` | JSON settings with clamping; DPAPI-protected API key; no default key. |
| `src/Core/AppPaths.cs` | `%APPDATA%\Friday` layout; `FRIDAY_HOME` override for the throw-away test home. |
| `src/Core/TaskQueue.cs` | Single worker, durable history, real cancellation, approval gate, event-driven `WaitForAsync`. |
| `src/Core/StateHub.cs` | The state the animated core reads: idle, listening, thinking, speaking, executing, awaiting approval, success, error, offline. |
| `src/Core/Events.cs` | In-memory conversation, persisted transcript, recent-turn reload. |
| `src/Core/FLog.cs` | Runtime log, append-only `audit.jsonl`, and `PruneOlderThan(days)`. |
| `src/Core/MemoryStore.cs` | Approved/unapproved memories; only approved ones enter the prompt. |
| `src/Core/StartupManager.cs` | Optional `HKCU\...\Run` entry, removed cleanly when switched off. |

### Networking and security

| File | Purpose |
|---|---|
| `src/Net/FridayServer.cs` | TLS server on 8790, all `/v1/*` routes, keep-alive loop, rate limit, idempotency, approvals. |
| `src/Net/HttpLite.cs` | Small HTTP/1.1 reader; added `Version`, `KeepAlive`, `HasExtraBytes`. |
| `src/Net/ServerIdentity.cs` | Self-signed certificate generation and SHA-256 fingerprint. |
| `src/Net/PairingStore.cs` | 8-character single-use codes; SHA-256 token hashes at rest; revocation. |
| `src/Security/PathGuard.cs` | Authorised roots, credential-name denylist, `~` and variable expansion. |
| `src/Security/ShellPolicy.cs` | Allowlist, blocklist, download-and-execute refusal. |

### Tools

| File | Tools |
|---|---|
| `src/Tools/ToolRegistry.cs` | Capability/risk gate, approval cards, audit, `DisabledReason` for the Access switches. |
| `src/Tools/AppTools.cs` | `open_app`, `open_url`, `open_path`, `list_apps`, window listing/activation/actions. |
| `src/Tools/FileTools.cs` | Read/search/list/write/create, copy/move/delete, zip/unzip, large/duplicate search, documents. |
| `src/Tools/InputTools.cs` | `type_text`, `press_keys`, `move_mouse`, `click`, `scroll`, `screenshot`. |
| `src/Tools/SystemTools.cs` | Status, volume, brightness, media, lock, power, processes, devices. |
| `src/Tools/ShellTools.cs` | Allowlisted `run_command`, terminal, background start/stop/list. |
| `src/Tools/ProjectTools.cs` | Project discovery, git status/diff, test runner, code search. |
| `src/Tools/WebTools.cs` | Search, page fetch, download (to an authorised folder only). |
| `src/Tools/MemoryTools.cs` | `remember` (proposes) and `recall` (approved only). |
| `src/Tools/Native.cs` | Win32 helpers used by input, windows and power. |

### AI

| File | Purpose |
|---|---|
| `src/Ai/LocalIntents.cs` | Answers time/date/status/app launch and similar without a network or a key. |
| `src/Ai/Orchestrator.cs` | Model returns tool calls only; every call goes back through the registry. |
| `src/Ai/AiClient.cs` | OpenAI-compatible client, pooled connection, gzip/deflate, 120 s timeout. |

### UI and voice

| File | Purpose |
|---|---|
| `src/UI/MainForm.cs` | Full-screen core, transcript pane, input history, compact mode, tray, task progress, approval card, emergency stop. |
| `src/UI/JarvisCore.cs` | GDI+ gold/orange core: rings, spikes, particles, state-driven animation, reduced-motion and FPS limits. |
| `src/UI/SettingsForm.cs` | Appearance, voice, AI, phone/pairing, access, privacy, diagnostics. |
| `src/UI/Theme.cs`, `src/UI/Controls.cs` | Palette and the custom dark controls. |
| `src/Voice/VoiceEngine.cs` | Local STT/TTS, push-to-talk, optional wake word, interruption, honest availability reporting. |

### Packaging

| File | Purpose |
|---|---|
| `dist/friday.exe` | Published framework-dependent single file (`.NET 10 Desktop Runtime` required). |
| `README.md` | Build, publish and test instructions for the folder. |

---

## 3. Changed: JARVIS on Android (3.4)

| File | Change |
|---|---|
| `app/build.gradle` | `versionCode` 15 → 16, `versionName` 3.3 → 3.4. Nothing else in the build. |
| `AndroidManifest.xml` | Adds `FridayActivity` (not exported, portrait); no new permissions. |
| `java/.../friday/FridayClient.kt` | **New.** Pinned TLS client: `PinTrustManager`, pairing, status, command, task, approval, cancel, emergency stop, revoke. |
| `java/.../ui/FridayActivity.kt` | **New.** Address/port/wake word, save/test, fingerprint compare, pairing code, status, unpair, emergency stop. |
| `java/.../AskActivity.kt` | Routes Friday-addressed text to the laptop, shows approval dialogs once per id, polls adaptively (300 ms → 800 ms), offers cancel. Non-Friday text is untouched. |
| `java/.../Prefs.kt` | Friday settings block (address, port, token, pin, wake word, device id/name); token and pin in private prefs. |
| `java/.../ui/AutomationsActivity.kt` | Friday card and **PAIR OR OPEN FRIDAY** entry. |
| `res/layout/activity_friday.xml` | **New.** The pairing/status screen. |
| `res/layout/activity_ask.xml` | Shows the Friday destination and the cancel button. |
| `res/layout/activity_automations.xml` | Friday card. |
| `res/values/strings.xml` | `assistant_service_label` is now `JARVIS` (was `JARVIS (Bonjee)`) - the only place the old name was still visible. |
| `res/layout/{activity_jarvis_home,activity_main,activity_web_module,nav_header}.xml` | Remaining visible "Bonjee" text rebranded to JARVIS. |

The package id stays `com.bonjee.notifier` and the signing key is unchanged, so
3.4 upgrades 3.3 in place.

`res/values/strings.xml` also carries the assistant-service comment explaining
why the label must be recognisable in Android's assistant picker.

---

## 4. Response-time work (this pass)

These are the changes behind the numbers in `FRIDAY_TESTING.md`.

| File | Change | Effect |
|---|---|---|
| `Core/TaskQueue.cs` | `WaitForAsync` no longer polls every 120 ms. Each running task gets a `TaskCompletionSource` in `_finished`; `Update` signals it on a terminal state. Signals are removed when they fire and when old tasks are trimmed. | 137 ms → ~20 ms in-process; the HTTP fast path answers the moment the task ends. |
| `Net/FridayServer.cs` | One connection now serves a loop of requests instead of one request per TLS handshake. `Connection: keep-alive` / `close` is chosen per request; extra pipelined bytes force a close rather than being lost. | Removes a handshake from every phone poll. |
| `Net/HttpLite.cs` | Parses the request version and `Connection`, and flags bytes belonging to a following request. | Correct keep-alive decisions. |
| `Ai/AiClient.cs` | `SocketsHttpHandler` with gzip/deflate and a 10-minute pooled connection lifetime. | Second and later AI calls skip the handshake; compressed replies arrive sooner on a slow link. (Not exercised - no key.) |
| `AskActivity.kt` | 900 ms flat polling → 300 ms for the first 10 s, then 800 ms. | Short commands feel immediate; long tasks stay under 120 requests/minute. |
| `NetCheck.cs` | Measures against `127.0.0.1`, not `localhost`, and reports round trip plus a 10-call median. | Removes a ~2 s IPv6 fallback that was hiding the real numbers. |
| `SelfTest.cs` | Reports submit-to-terminal latency. | Makes regressions visible. |

---

## 5. Correctness fixes found while testing

| Area | Before | Now |
|---|---|---|
| `AutoApproveSafe` | Saved in config and drawn in Settings, but the gate ignored it. | Enforced: off means even `list_directory` asks. Covered by a self-test. |
| `AllowFileWrites` / `AllowDelete` / `AllowPowerControls` / `AllowDownloads` | Saved and drawn, never checked. | Enforced by tool name in `ToolRegistry.DisabledReason`; each refusal is audited as `refused-capability`; each has a self-test. |
| Retention | Setting existed; files grew forever. | `FLog.PruneOlderThan` runs at startup and removes old lines from `audit.jsonl` and `conversation.jsonl`. |
| Settings wording | "Auto-approve safe actions (reads, new files)" described only part of the Safe set. | "(reads, launches, media)" matches what Safe actually covers. |
| `AppPaths` comment | Claimed the user profile was not a file root. | States that `%USERPROFILE%` **is** a root by default and that credential names are still denied. Behaviour unchanged. |

---

## 6. Binaries delivered

| File | Size | SHA-256 |
|---|---|---|
| `Desktop\friday.exe` | 1,645,630 bytes | `626E40BDFDF978BD2374C2CD2E1B3339A193F1A80312844762942E04B8620846` |
| `Desktop\JARVIS-3.4.apk` | 5,746,793 bytes | `921465A8B0D15B1183CB6CD4B24B05AB74C49B179963E94FF00A7BA9B0AD641B` |

Repo copies: `apps\friday\dist\friday.exe`,
`apps\android-notifier\dist\JARVIS-3.4.apk`. The APK is debug-signed (same key
as 3.3); `friday.exe` is unsigned. Rebuilding either file changes its hash.

---

## 7. Documentation

| File | Contents |
|---|---|
| `docs/FRIDAY_API.md` | Versioned contract, every endpoint, task states, error codes, examples. |
| `docs/FRIDAY_SETUP.md` | Install, first run, pairing, firewall, troubleshooting, uninstall. |
| `docs/FRIDAY_SECURITY.md` | Trust boundaries, transport, pairing, permissions, residual risks. |
| `docs/FRIDAY_TESTING.md` | What passed, exact numbers, what was not tested. |
| `docs/FRIDAY_CHANGES.md` | This file. |
| `docs/ARCHITECTURE.md` | Updated to place Friday next to the existing pipeline. |
| `README.md` | 3.4 section; assistant label corrected. |
| `apps/friday/README.md` | Build and publish commands for the folder. |
