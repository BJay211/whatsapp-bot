# Friday - what was tested, and what was not

This is the evidence file. It records what was actually run on the build machine,
the exact numbers observed, and - just as important - what could not be tested
here. Nothing below is a claim about a device or a provider that was never
exercised.

- Machine: Windows, 64-bit, .NET SDK `10.0.403`, .NET Desktop Runtime `10.0.12`.
- Friday: `net10.0-windows`, Release, published framework-dependent single file
  `win-x64`.
- Android: JARVIS 3.4 (`versionCode` 16), package `com.bonjee.notifier`, built
  offline with AGP/SDK 34 and the debug keystore.
- Date of these runs: 2026-10-28.

Everything here is reproducible with the two commands in
[Reproducing](#7-reproducing-the-runs).

---

## 1. Summary

| Area | Result |
|---|---|
| Friday build (Release) | **Pass** - 0 warnings, 0 errors |
| `friday.exe --selftest` | **Pass** - 34 checks, 0 failures, exit code 0 |
| `friday.exe --netcheck` | **Pass** - 25 checks, 0 failures, 31-32 requests, exit code 0 |
| Desktop launch | **Pass** - window titled `FRIDAY` stays up, `/v1/ping` answers |
| Local command latency | **19-23 ms** (was 137 ms) |
| API command round trip | **32-38 ms** (was 163 ms) |
| API command, 10-call median | **6-7 ms** (was 134 ms) |
| Connection reuse | **Pass** - second request on one socket, 4.3 ms |
| Android debug build | **Pass** - `assembleDebug` |
| Android release build | **Pass** - `assembleRelease`, APK inspected and signed |
| Android on a real phone | **Not tested** - no device or emulator available |
| AI provider round trip | **Not tested** - no API key on this machine |
| n8n / dashboard calls | **Not tested** - endpoints return 404 here |

---

## 2. Friday self-test

`friday.exe --selftest` runs headless against the real configuration and writes
`%APPDATA%\Friday\logs\selftest.txt`. The current build reports:

```
PASS  config round-trips through JSON
PASS  default config has no embedded API key
PASS  path guard allows the Desktop
PASS  path guard blocks C:\Windows
PASS  path guard blocks .ssh keys
PASS  allowlist accepts git status
PASS  blocked list refuses format
PASS  unknown command is not auto-run
PASS  download-and-execute is refused
PASS  tool schema exposes core tools
PASS  TLS certificate loads with a private key
PASS  TLS fingerprint is SHA-256
PASS  pairing code creates a device token
PASS  pairing code is single use
PASS  paired token authenticates
PASS  unknown token is rejected
PASS  revocation invalidates the token
PASS  memory is not sent to the model before approval
PASS  approved memory appears in the prompt
PASS  task queue starts a worker
PASS  with auto-approve off, a safe tool asks first
PASS  deleting files can be switched off
PASS  file writes can be switched off
PASS  power controls can be switched off
PASS  downloads can be switched off
PASS  input control can be switched off
PASS  local command completes through the task queue
PASS  allowlisted PowerShell command executes
PASS  unlisted command waits for real approval, then runs
PASS  a denied command does not run
PASS  blocked command never runs
PASS  blocked command was refused without asking
PASS  emergency stop cancels queued work
PASS  emergency stop releases
RESULT: all checks passed
```

Environment lines from the same run:

```
INFO  AI configured: no (set FRIDAY_AI_KEY)
INFO  Voice input: ready
INFO  Text-to-speech: ready
INFO  Server would listen on: https://192.168.56.1:8790, https://10.49.232.6:8790, https://localhost:8790
```

What this proves, in plain terms: the path guard refuses system folders and
credential names; the shell allowlist and blocklist behave; TLS loads with a
private key and a SHA-256 pin; pairing tokens are single use and revocable;
unapproved memories stay out of the prompt; the task queue runs real work;
approval is genuinely in the path (a denied command does not run); the emergency
stop cancels work and releases; and each Access switch actually refuses its
tools instead of only changing a checkbox.

What it does **not** prove: any of this over a phone, through a firewall, or
against a live AI provider.

---

## 3. Friday network test

`friday.exe --netcheck` starts a real TLS server on `https://127.0.0.1:18790`,
in a throw-away home folder (`%TEMP%\friday-netcheck-*`), and talks to it as a
phone would. It never touches the real config, devices or audit log.

```
PASS  server starts on loopback
PASS  server publishes a SHA-256 fingerprint
PASS  a different certificate is refused before any request is served
PASS  ping answers without a token
PASS  ping reports the same pinned fingerprint
PASS  ping reports protocol 1.0
PASS  status without a token is 401
PASS  a wrong pairing code is refused
PASS  pairing code returns a device token
PASS  pairing response carries the certificate fingerprint
PASS  a pairing code cannot be used twice
PASS  the paired token authenticates
PASS  status names the laptop and the device
PASS  a command is accepted and runs to success
PASS  the same request id is not run twice
PASS  an unknown protocol is refused with 426
PASS  a blocked command ends failed, not running
PASS  a command outside the allowlist waits for approval
PASS  approval from the phone is accepted
PASS  the approved command then runs to success
PASS  emergency stop engages over the wire
PASS  commands are refused with 423 while stopped
PASS  emergency stop releases
PASS  the phone can revoke its own device
PASS  a revoked token stops working
RESULT: all network checks passed
requests served: 31-32
```

The "different certificate is refused" check is the important one: it uses the
phone's pinning logic and confirms a second, valid-but-wrong certificate is
rejected **before** any token is sent.

---

## 4. Response time

Two different numbers matter, and they were measured separately.

### 4.1 Local command, submit to terminal (in-process)

Reported by `--selftest` around the real `TaskQueue`:

| Build | Time |
|---|---|
| Before this round of work | **137 ms** |
| After | **19-23 ms** |

The 137 ms was the old 120 ms polling loop in `WaitForAsync`: a command that
finished in 8 ms still waited up to 120 ms for the next look. The wait is now
event-driven - the task sets a completion signal the instant it reaches a
terminal state.

### 4.2 Over the wire, as the phone sees it (`--netcheck`)

| Measurement | Before | After |
|---|---|---|
| Command round trip (submit to answer) | 163 ms | **32-38 ms** |
| Local command latency, median of 10 | 134 ms | **6-7 ms** |
| Local command latency, best of 10 | 10 ms | **4-5 ms** |

Three changes account for that:

1. **Event-driven completion** - `/v1/command` no longer polls every 120 ms.
2. **HTTP keep-alive** - the server used to send `Connection: close` and drop the
   TLS session after every request. The phone polls several times a second, and
   each poll paid for a full handshake. One socket now serves the whole run.
3. **`127.0.0.1` instead of `localhost` in the test client** - on this machine
   `localhost` resolves to `::1` first and falls back after roughly 2 s when the
   listener is IPv4. That is a property of the test harness, not of the phone;
   the Android client connects to a LAN address. It is still why the first
   netcheck run showed `2250 ms` and why the harness now uses the loopback IP.

Keep-alive, checked independently with curl against the running desktop build:

```
req1 connect=0.000797 tls=0.059189 total=0.075340 newconn=1
req2 total=0.004291 newconn=0
```

`newconn=0` on the second request is the proof: the same TLS connection was
reused, and the request cost 4.3 ms.

### 4.3 Android polling

The phone polls a running task at **300 ms** for the first 10 seconds, then
**800 ms** until it ends. Before, it was a flat 900 ms. The first 10 seconds are
when a short command finishes, so the answer feels immediate; the slower cadence
afterwards keeps a long task well inside Friday's 120-requests-per-minute budget
for one device (300 ms for 10 s is ~33 requests, then 75 per minute).

### 4.4 What these numbers are not

- They are **local command** numbers. They do not include the AI provider.
- An open-ended question still costs a provider round trip (network + model
  time). The AI client now reuses its pooled connection and accepts gzip/deflate,
  so the second and later questions avoid a fresh TLS handshake, but **no live
  provider call was made here** - there is no API key on this machine.
- They were measured on this laptop, over loopback, with the machine otherwise
  idle. A phone on Wi-Fi adds the wireless hop.

---

## 5. Android build

Both variants build offline:

```
BUILD SUCCESSFUL in 26s
83 actionable tasks: 10 executed, 73 up-to-date
```

`aapt2 dump badging` on the release APK:

```
package: name='com.bonjee.notifier' versionCode='16' versionName='3.4'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
uses-permission: name='android.permission.POST_NOTIFICATIONS'
uses-permission: name='android.permission.FOREGROUND_SERVICE'
uses-permission: name='android.permission.FOREGROUND_SERVICE_DATA_SYNC'
uses-permission: name='android.permission.RECORD_AUDIO'
uses-permission: name='android.permission.CAMERA'
uses-permission: name='android.permission.READ_CONTACTS'
uses-permission: name='android.permission.READ_CALENDAR'
uses-permission: name='android.permission.CALL_PHONE'
uses-permission: name='android.permission.SEND_SMS'
uses-permission: name='com.bonjee.notifier.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
application-label:'JARVIS'
```

`apksigner verify --print-certs`:

```
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: a1725c2f83b0692673d9ed740df3fd95a31e506e714d131f5f031ff7caf084e0
```

| Property | Value |
|---|---|
| File | `JARVIS-3.4.apk` |
| Size | 5,746,793 bytes |
| SHA-256 | `921465A8B0D15B1183CB6CD4B24B05AB74C49B179963E94FF00A7BA9B0AD641B` |
| Package | `com.bonjee.notifier` (unchanged) |
| Version | 3.4, code 16 (was 3.3, code 15) |
| Label | `JARVIS` (unchanged) |
| Signing | **debug keystore** - see the warning below |

**This is a debug-signed release build.** It is the same key used for 3.3, so it
upgrades in place, but it is not Play-Store signing. Do not publish it; use it
for your own phone. The permissions are identical to 3.3: nothing new was added
for Friday.

What was checked in the APK: package id, version, label, permission list, signer,
`FridayActivity` present in the manifest, and `FridayClient` compiled into the
dex. What was **not** checked: that it installs, that it launches, that it pairs.

---

## 6. Not tested, mocked, or dependent on configuration

Be sceptical of anything in this list until you have seen it work:

1. **Android runtime.** No phone, emulator or `adb` was available. Pairing,
   certificate pinning on a device, the Friday screen, voice input, approval
   dialogs, cancel, adaptive polling and the Automations card were never
   exercised. They compile and the code paths are unit-shaped, but that is all.
2. **AI provider.** No `FRIDAY_AI_KEY`. The Groq/OpenAI path - including the new
   gzip and pooled-connection changes - has never made a real request from this
   build. The self-test explicitly reports `AI configured: no`.
3. **Spoken audio.** Windows reports one recogniser and two TTS voices, and the
   app reports both "ready", but no microphone capture or playback was performed
   in this environment. "Ready" means the engines loaded, not that a phrase was
   understood.
4. **n8n / dashboard.** The JARVIS web endpoints return 404 from here, so the
   archive fallback is unverified.
5. **Off-LAN access.** Not implemented and not tested. A private tunnel is your
   configuration to make.
6. **Windows Firewall.** No rule for port 8790 was created by the build. The
   command is in `FRIDAY_SETUP.md`; whether the phone can reach the laptop on
   your network is unverified.
7. **TLS pinning against an active attacker.** The logic is tested with a second
   certificate in `--netcheck`, but not against a proxy or a real interception.
8. **Long-running tasks, sleep/resume, network loss mid-task.** The design
   handles them (202 + task id, idempotent request ids), but they were not
   rehearsed over a real link.
9. **Retention pruning.** `FLog.PruneOlderThan` runs at startup and was exercised
   by code inspection and a startup log line, not by seeding 91-day-old records.
10. **Upgrade in place.** 3.3 â†’ 3.4 uses the same package and signer, so it
    should upgrade; it was not installed over 3.3 on a device.

---

## 7. Reproducing the runs

```powershell
cd apps\friday
.\.toolchain\dotnet\dotnet.exe build Friday.csproj -c Release -v minimal

# 34 checks, writes %APPDATA%\Friday\logs\selftest.txt
.\bin\Release\net10.0-windows\friday.exe --selftest

# 25 checks on a throw-away TLS server, does not touch your real data
.\bin\Release\net10.0-windows\friday.exe --netcheck
```

Both exit with code 0 only when every check passes. The latency lines are
`local command latency:` (selftest) and `command round trip:` /
`local command latency over 10 calls:` (netcheck).

For the Android build:

```powershell
cd apps\android-notifier
$env:JAVA_HOME=(Resolve-Path .toolchain\jdk).Path
$env:ANDROID_HOME=(Resolve-Path .toolchain\android-sdk).Path
$env:GRADLE_USER_HOME="$env:USERPROFILE\.gradle"
.\gradlew.bat assembleDebug assembleRelease --offline
```

Verify the APK with `build-tools\34.0.0\aapt2.exe dump badging` and
`apksigner.bat verify --print-certs`.

---

## 8. Honest bottom line

The laptop half is tested: builds clean, 34 + 25 checks pass, and the phone
protocol works end to end over real TLS on loopback, including pinning, pairing,
idempotency, approvals, emergency stop and revocation. The response-time work is
measured, not estimated: 137 â†’ ~20 ms in-process and 163 â†’ ~35 ms over the wire,
with a 6 ms median for a warm connection.

The phone half is **built, not run**. The AI half is **wired, not called**. Those
are the two things to try first on your own hardware, and the two places where a
surprise is still possible.


