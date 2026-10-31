# Friday - what was tested, and what was not

This is the evidence file. It records what was actually run on the build machine,
the exact numbers observed, and - just as important - what could not be tested
here. Nothing below is a claim about a device or a provider that was never
exercised.

- Machine: Windows, 64-bit, .NET SDK `10.0.403`, .NET Desktop Runtime `10.0.12`.
- Friday: `net10.0-windows`, Release, published framework-dependent single file
  `win-x64`.
- Android: JARVIS 3.5 (`versionCode` 17), package `com.bonjee.notifier`, built
  offline with AGP/SDK 34 and the debug keystore.
- Date of these runs: 2026-10-30 to 2026-10-31.

Everything here is reproducible with the two commands in
[Reproducing](#7-reproducing-the-runs).

---

## 1. Summary

| Area | Result |
|---|---|
| Friday build (Release) | **Pass** - 0 warnings, 0 errors |
| `friday.exe --selftest` | **Pass** - 44 checks, 0 failures, exit code 0 |
| `friday.exe --netcheck` | **Pass** - 25 checks, 0 failures, 31 requests, exit code 0 |
| Relay harness (`relay/test-relay.js`) | **Pass** - 10 checks, 0 failures |
| Real relay end-to-end (`phone.js`) | **Pass** - 8 checks, 0 failures, 8+ requests forwarded |
| Relay restart and reconnect | **Pass** - Friday re-registered and relaying resumed |
| Desktop launch | **Pass** - window titled `FRIDAY` stays up, `/v1/ping` answers |
| Local command latency | **19-25 ms** |
| API command round trip | **32-42 ms** |
| API command, 10-call median | **7 ms** |
| Connection reuse | **Pass** - second request on one socket, 4.3 ms |
| Android debug build | **Pass** - `assembleDebug` |
| Android release build | **Pass** - `assembleRelease`, APK inspected and signed |
| Android on a real phone | **Not tested** - no device or emulator available |
| AI provider round trip | **Not tested** - no API key on this machine |
| Deployed relay / cross-network phone | **Not tested** - no hosted relay credentials here |
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
PASS  wake word extracts the instruction
PASS  wake word ignores an unrelated sentence
PASS  a bare wake word is recognised without a command
PASS  voice-first defaults are on
PASS  spoken dot extensions become real extensions
PASS  a dropped dot is still understood
PASS  app names resolve to a real executable, never a folder
PASS  chrome resolves to chrome.exe, not a folder
PASS  folder aliases resolve to real folders
PASS  quick lookup finds a file on the Desktop
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

The voice tests are pure text checks, not microphone tests: the wake word parser
extracts "open chrome" from "hey Friday, open chrome", ignores a sentence that
does not name Friday, and recognises a bare "Friday" as an acknowledgement. Other
checks prove that a spoken "dave dot text" becomes `dave.txt`, a missing dot is
still understood, app names resolve to executables (Chrome to `chrome.exe`, never
to a folder), folder aliases resolve to folders, and a Desktop filename can be
found. None of that proves a phrase is recognised from audio.

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
requests served: 31
```

The "different certificate is refused" check is the important one: it uses the
phone's pinning logic and confirms a second, valid-but-wrong certificate is
rejected **before** any token is sent.

### 3.1 Relay harness

`relay/test-relay.js` starts the real relay (`relay/server.js`) on a local port
with a fake laptop that long-polls it. Ten checks, all passing:

```
PASS  health endpoint
PASS  says the laptop is offline before it registers
PASS  delivers a phone request to a long-polling laptop
PASS  rejects a mismatched relay key after registration
PASS  times out a laptop that does not answer, then stays clean
PASS  refuses paths outside /v1/
PASS  refuses an invalid laptop id
PASS  pairs through the relay and reports relay_laptop
PASS  limits pairing attempts per IP
PASS  health never exposes keys or tokens
```

"What clean means" is specific: after a timeout the queued envelope is removed,
so a late response cannot be delivered to a different request. Health reports
only counts, never a key, a token or a pairing code.

### 3.2 Real end-to-end: phone -> relay -> Friday -> relay -> phone

The harness in `%TEMP%\friday-e2e` runs a **real** `FridayRuntime` against a real
relay process on `127.0.0.1`, with a throw-away home folder and a relay key from
the environment. `phone.js` then behaves as the Android client: it pairs, sends
commands, polls, and revokes. Eight checks, all passing:

```
PASS relay sees the laptop online
PASS paired through relay as <device id>
PASS relayed ping
PASS relayed authenticated status
PASS relayed request with a wrong token is refused (401)
PASS real command through relay: succeeded      ("system status")
PASS duplicate request id did not run twice
PASS revocation took effect through the relay
All end-to-end checks passed (relay forwarded 8).
```

A separate restart check killed the relay, restarted it, and confirmed Friday
re-registered (`online=1`) and a relayed `/v1/ping` returned `200` again. This is
real traffic, not a mock, but all of it is on the loopback interface. It does not
prove a phone on mobile data reaching a hosted relay.

The published single-file build was also started with `--background` and answered
a real `GET /v1/ping` from `curl -k`:

```json
{"ok":true,"name":"Friday","version":"1.1.0","protocol":"1.0",
 "machine":"TAVEH-LAPTOP","tls":true,
 "fingerprint":"9C617DEA6D73EB5DDB8C97636A49E0F3575DB4E462538E543773A642703778DB",
 "paired_devices":0,"pairing_required":true,"emergency_stop":false}
```

That confirms the executable, the version string, TLS and the listener - not a
phone handshake.

---

## 4. Response time

Two different numbers matter, and they were measured separately.

### 4.1 Local command, submit to terminal (in-process)

Reported by `--selftest` around the real `TaskQueue`:

| Build | Time |
|---|---|
| Before this round of work | **137 ms** |
| After | **19-25 ms** |

The 137 ms was the old 120 ms polling loop in `WaitForAsync`: a command that
finished in 8 ms still waited up to 120 ms for the next look. The wait is now
event-driven - the task sets a completion signal the instant it reaches a
terminal state.

### 4.2 Over the wire, as the phone sees it (`--netcheck`)

| Measurement | Before | After |
|---|---|---|
| Command round trip (submit to answer) | 163 ms | **32-42 ms** |
| Local command latency, median of 10 | 134 ms | **7 ms** |
| Local command latency, best of 10 | 10 ms | **5 ms** |

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
BUILD SUCCESSFUL
```

`aapt2 dump badging` on the release APK:

```
package: name='com.bonjee.notifier' versionCode='17' versionName='3.5'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
uses-permission: name='android.permission.POST_NOTIFICATIONS'
uses-permission: name='android.permission.FOREGROUND_SERVICE'
uses-permission: name='android.permission.FOREGROUND_SERVICE_DATA_SYNC'
uses-permission: name='android.permission.FOREGROUND_SERVICE_MICROPHONE'
uses-permission: name='android.permission.USE_FULL_SCREEN_INTENT'
uses-permission: name='android.permission.RECORD_AUDIO'
uses-permission: name='android.permission.CAMERA'
uses-permission: name='android.permission.READ_CONTACTS'
uses-permission: name='android.permission.READ_CALENDAR'
uses-permission: name='android.permission.CALL_PHONE'
uses-permission: name='android.permission.SEND_SMS'
uses-permission: name='com.bonjee.notifier.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
application-label:'JARVIS'
launchable-activity: name='com.bonjee.notifier.ui.JarvisHomeActivity'
```

`apksigner verify --print-certs`:

```
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: a1725c2f83b0692673d9ed740df3fd95a31e506e714d131f5f031ff7caf084e0
```

| Property | Value |
|---|---|
| File | `JARVIS-3.5.apk` |
| Size | 5,789,866 bytes |
| SHA-256 | `9FEAABE378E75506B7A01E825DACC5BF460BC1BDF9E824C2E7054090FF6EF87F` |
| Package | `com.bonjee.notifier` (unchanged) |
| Version | 3.5, code 17 (was 3.4, code 16) |
| Label | `JARVIS` (unchanged) |
| Signing | **debug keystore** - see the warning below |

**This is a debug-signed release build.** It is the same key used for 3.3 and
3.4, so it upgrades in place, but it is not Play-Store signing. Do not publish it;
use it for your own phone. Two permissions are new in 3.5:
`FOREGROUND_SERVICE_MICROPHONE` for the visible wake-listening service and
`USE_FULL_SCREEN_INTENT` so a wake phrase can bring the conversation forward on
Android 14.

What was checked in the APK: package id, version, label, permission list, signer,
the launchable `JarvisHomeActivity`, `VoiceSettingsActivity` and
`JarvisWakeService` in the manifest, and that the voice and relay classes are
compiled into the dex. What was **not** checked: that it installs, that it
launches, that a phrase is heard, that it speaks, or that it pairs.

---

## 6. Not tested, mocked, or dependent on configuration

Be sceptical of anything in this list until you have seen it work:

1. **Android runtime.** No phone, emulator or `adb` was available. The continuous
   conversation, automatic restart, barge-in, wake-word foreground service,
   Android TTS, voice settings, relay pairing, certificate pinning on a device,
   the Friday screen, approval dialogs, cancel and adaptive polling were never
   exercised on hardware. They compile and the laptop side is tested, but that is
   all.
2. **AI provider.** No `FRIDAY_AI_KEY` and no phone-side key. Neither Friday's
   streaming client nor JARVIS's SSE answer path has made a real provider request
   from this build. The self-test explicitly reports `AI configured: no`. Sentence
   chunking and the fallback to non-streaming were exercised only through code and
   unit-shaped checks.
3. **Spoken audio.** Windows reports one recogniser and two TTS voices, and the
   app reports both "ready", but no microphone capture or playback was performed
   in this environment. Android TTS was never heard on a device. "Ready" means
   the engines loaded, not that a phrase was understood or spoken.
4. **n8n / dashboard.** The JARVIS web endpoints return 404 from here, so the
   archive fallback is unverified.
5. **Deployed relay and cross-network access.** The relay passes its harness and a
   real local end-to-end run, but no hosted relay was deployed from this machine
   and no phone reached it over mobile data. That specific hop - phone on a
   different network -> public TLS -> hosted relay -> laptop - is unverified.
   `relay/README.md` and `render.yaml` are the deployment path.
6. **Windows Firewall.** No rule for port 8790 was created by the build. The
   command is in `FRIDAY_SETUP.md`; whether the phone can reach the laptop on
   your network is unverified. Relay mode needs no inbound rule.
7. **TLS pinning against an active attacker.** The logic is tested with a second
   certificate in `--netcheck`, but not against a proxy or a real interception.
   Relay mode deliberately does not pin the laptop certificate; it relies on the
   relay's public TLS plus the device token.
8. **Long-running tasks, sleep/resume, network loss mid-task.** The design
   handles them (202 + task id, idempotent request ids, reconnect with backoff),
   and a relay restart was tested locally, but phone network loss mid-command was
   not rehearsed.
9. **Retention pruning.** `FLog.PruneOlderThan` runs at startup and was exercised
   by code inspection and a startup log line, not by seeding 91-day-old records.
10. **Upgrade in place.** 3.5 uses the same package and signer as 3.4, so it
    should upgrade; it was not installed over 3.4 on a device.
11. **Wake phrase in the background.** `JarvisWakeService` is a real microphone
    foreground service with a visible notification, but Android's battery
    optimisers, OEM task killers and microphone-access rules vary by device. It
    was not run on a phone, so no claim is made that it survives every OEM's
    background limits.

---

## 7. Reproducing the runs

```powershell
cd apps\friday
.\.toolchain\dotnet\dotnet.exe build Friday.csproj -c Release -v minimal

# 44 checks, writes %APPDATA%\Friday\logs\selftest.txt
.\bin\Release\net10.0-windows\friday.exe --selftest

# 25 checks on a throw-away TLS server, does not touch your real data
.\bin\Release\net10.0-windows\friday.exe --netcheck
```

Both exit with code 0 only when every check passes. The latency lines are
`local command latency:` (selftest) and `command round trip:` /
`local command latency over 10 calls:` (netcheck).

The relay harness is self-contained:

```powershell
cd relay
node test-relay.js
```

It prints ten `PASS` lines and exits 0. The end-to-end run used an ad-hoc harness
in `%TEMP%\friday-e2e` (`Harness.csproj` hosting a real `FridayRuntime`, plus
`phone.js`) against a relay on `127.0.0.1:8877`; it is evidence from this machine,
not a committed test suite.

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

The laptop half is tested: builds clean, 44 + 25 checks pass, the relay passes 10
harness checks, and a real relay -> Friday -> task round trip passes 8 end-to-end
checks including pairing, authentication, idempotency, revocation and reconnect.
The response-time work is measured, not estimated: ~20 ms in-process and ~35 ms
over the wire, with a 7 ms median for a warm connection.

The phone half is **built, not run on a phone**. The AI path is **wired, not
called against a provider**. The relay is **real code with local proof, not a
deployed service reached from mobile data**. Those are the three things to try
first on your own hardware, and the three places where a surprise is still
possible.

Nothing above should be read as "it works on your phone". Pair it once, speak to
it once, and use it from mobile data once before trusting it.


