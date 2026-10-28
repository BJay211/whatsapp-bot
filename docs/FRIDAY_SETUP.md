# Friday - install and first run

Friday is the laptop half of JARVIS: a native Windows app that owns the
microphone, the speakers, the files and the apps, and answers the JARVIS app on
your phone over an encrypted local connection.

This guide covers installing it, the first five minutes, hooking up the phone,
and the things that usually go wrong. The protocol itself is in
[`FRIDAY_API.md`](./FRIDAY_API.md); the threat model is in
[`FRIDAY_SECURITY.md`](./FRIDAY_SECURITY.md).

---

## 1. What you need

| Requirement | Notes |
|---|---|
| Windows 10 or 11, 64-bit | Developed and tested on this machine only (see `FRIDAY_TESTING.md`). |
| .NET 10 Desktop Runtime | **Required.** The published `friday.exe` is a single file but framework-dependent. `10.0.12` was installed here; any 10.0.x Desktop Runtime should work. Check with `dotnet --list-runtimes` - you need `Microsoft.WindowsDesktop.App 10.0.x`. |
| A microphone and speakers | Optional but expected. Without them the app still runs; it reports "Voice input: unavailable" instead of pretending. |
| The phone on the same Wi-Fi | Only needed for phone control. Voice and AI work with no network at all. |
| An AI API key | Optional. Without one Friday still runs local laptop commands ("what time is it", "open notepad", "lock the screen"). Open questions need a key. |

The machine this was built on has one English (US) recogniser
("Microsoft Speech Recognizer 8.0") and two TTS voices (David, Zira). Other
languages depend on what is installed in Windows.

---

## 2. Install

1. Copy `friday.exe` to any folder you like. It was delivered on the Desktop.
2. Double-click it. On first run Windows Firewall asks about network access.
   Tick **Private networks** and allow it - that is the phone service on port
   8790. If the prompt never appeared, see step 4 below.
3. The window opens full-screen with the core in the middle. That is the normal
   state; nothing is hidden and nothing is recording until you ask it to.

Friday keeps everything it owns in one folder:

```
%APPDATA%\Friday\
  config.json        settings (no secrets in plaintext)
  server.pfx         the TLS certificate for the phone service
  server.secret      the password that protects server.pfx
  devices.json       paired phones, token hashes only
  tasks.json         recent tasks and their results
  memory.json        memory entries (only approved ones reach the AI)
  conversation.jsonl transcript
  audit.jsonl        one line per security-relevant decision
  logs\friday.log    runtime log
  logs\selftest.txt  the last self-test report
  screenshots\       screenshots Friday took
```

Nothing is written next to the executable, and no API key is stored in any of
these files in readable form.

---

## 3. The first five minutes

**Type, don't speak, first.** Click the input bar at the bottom and type
`what time is it`. The reply appears in the transcript pane and the core flashes
green. This proves the queue, the policy layer and the display all work before
voice is involved.

Then try, in this order:

| Type this | What should happen |
|---|---|
| `what time is it` | Instant local answer, no AI key needed. |
| `open notepad` | Notepad starts. This is an app launch, not a shell command. |
| `system status` | CPU, memory, disk and battery summary. |
| `lock the screen` | Screen locks (you will have to sign back in). |
| `run \`git status\`` | A real PowerShell command. `git status` is on the allowlist, so it runs; anything not on the allowlist asks first, and freeform shell is off by default. |
| `remember that my project folder is C:\work\atlas` | Waits for your approval before it is stored. |

**Buttons and keys**

- Header, right to left: **EXIT**, **COMPACT**, **SETTINGS**, **MIN**.
- `COMPACT` shrinks Friday to a small always-on-top strip. Click it again for
  the normal window. Dragging the title bar in compact mode moves the strip.
- `Esc` leaves full-screen (or compact) without quitting. In a normal window it
  cancels whatever is running.
- `Ctrl+Alt+Space` push-to-talk (hold-free: press once, speak, it stops after the
  phrase).
- `Ctrl+Alt+F` toggles listening on and off.
- `Ctrl+Alt+Esc` emergency stop: cancels everything and refuses new commands
  until you release it.
- `Ctrl+,` opens Settings. `Ctrl+L` puts the cursor in the input bar.
- Closing the window hides Friday to the notification area. The tray menu has
  **Open Friday**, **Compact mode**, **Push to talk**, **Emergency stop**,
  **Settings** and **Exit**. Use **Exit** to really quit.

**States you will see** (they are real, not decoration): idle, listening,
thinking, speaking, executing, awaiting approval, success, error, offline. The
colour of the ring and the status line follow the actual state of the assistant.

**Voice**

Voice is on, but the wake word is off by default - so Friday does not sit
listening to the room. Press `Ctrl+Alt+Space` or click the core to talk; press it
again to stop. Turn the wake word on under **Settings > Voice** if you want
"Friday, ..." hands-free, knowing that this leaves the microphone capturing
between commands. The status bar shows `MIC` only while capture is actually open.

Voice limitations worth knowing now:

- Windows speech (System.Speech) always uses the **default** input and output
  device. The device picker says so rather than offering a choice that would not
  work.
- Recognition and speech synthesis are local; audio is never written to disk and
  never leaves the machine.
- Replies are spoken by default (`Privacy > Speak replies`). If you interrupt by
  speaking, Friday stops talking.

---

## 4. Give it a brain (optional)

Without a key Friday answers laptop commands and tells you plainly that open
questions need a key. Two ways to set one, in order of preference:

**Environment variable (nothing on disk):**

```powershell
setx FRIDAY_AI_KEY "gsk_..."
```

Restart Friday. `FRIDAY_AI_KEY` always wins over a stored key.

**Settings > AI:** paste the key and press SAVE. It is encrypted with Windows
DPAPI for your user account (`api_key_protected` in `config.json`); copying the
file to another machine or account will not decrypt it. **REMOVE** deletes it.

The default provider is Groq (`https://api.groq.com/openai/v1`,
`llama-3.3-70b-versatile`). Any OpenAI-compatible endpoint and model name works;
change both under **Settings > AI**. Friday never puts the key in a URL and never
logs it.

What the AI may do: only through the same tools the local commands use, with the
same permission rules. It cannot ask for a raw shell beyond your
`Allow freeform shell commands` setting, and destructive actions always stop for
your approval.

---

## 5. Connect the phone

### On the laptop

1. **Settings > Phone**. Leave **Run the phone service** on, port `8790`, and
   **Accept connections from the local network** on.
2. Make sure **Encrypt with TLS** and **Require a paired device** are on. They
   are on by default; turning them off is a deliberate downgrade.
3. Note the address list, for example `https://192.168.1.20:8790`. Use the
   address that matches the Wi-Fi the phone is on. (`localhost` is not useful for
   the phone.)
4. Press **NEW CODE** and read the eight characters, shown as `ABCD-EFGH`. It is
   single use and expires in 10 minutes by default.
5. Leave **Allow approvals from the phone** on if you want to approve work from
   the phone; off means the phone can watch but only the laptop can approve.

If the phone cannot reach the laptop at all, allow the port once in an
administrator PowerShell:

```powershell
netsh advfirewall firewall add rule name="Friday 8790" dir=in action=allow protocol=TCP localport=8790 profile=private
```

Remove it again with `netsh advfirewall firewall delete rule name="Friday 8790"`.

### On the phone

1. Install `JARVIS-3.4.apk` (versionCode 16). It upgrades 3.3 in place.
2. Open **Automations** and find the **FRIDAY (LAPTOP ASSISTANT)** card, or use
   **PAIR OR OPEN FRIDAY**. This opens the Friday screen.
3. Type the laptop address (`192.168.1.20`; the app adds `https://` and the port
   itself), leave port `8790`, and keep the wake word `friday`.
4. Press **SAVE**, then **TEST**. You should see Friday's version, machine name,
   `TLS: on`, the emergency-stop state and its SHA-256 fingerprint.
5. Compare that fingerprint with **TLS fingerprint** on the laptop's Phone page,
   or press **COPY FINGERPRINT** and paste it into a message to yourself. They
   must match exactly.
6. Type the pairing code and press **PAIR**. The phone stores its own device
   token and pins the certificate; nothing else is stored.
7. **REFRESH STATUS** should now show the laptop's state and recent tasks.

If pairing fails, the code is the usual reason: codes are single use, expire,
and a new code invalidates the old one.

### Using it from the phone

- Say or type **"Friday, open Spotify"** - the configured word (default
  `friday`, also `hey friday`, `ok friday`) sends the rest to the laptop.
- A sentence that names the laptop **and** asks for something on it also goes to
  Friday: "what is my laptop's battery", "take a screenshot on the pc".
- Everything else stays a normal JARVIS question, and phone commands (torch,
  volume, calls, alarms) stay on the phone. There is no topic-based guessing.
- While a task runs, the phone shows its state and a **Cancel task on laptop**
  button. Approvals appear as a dialog, once per approval id.
- **UNPAIR THIS PHONE** revokes the token on the laptop and forgets it locally.
- **EMERGENCY STOP** cancels everything on the laptop and blocks new commands
  until you release it on the laptop itself.

The older **Laptop Control** screen (port 8787, `laptop-agent/`) still exists and
still owns sentences starting with "on my laptop ..." while it is configured. If
it is not configured, those sentences now go to Friday instead of dead-ending.

---

## 6. Start with Windows (optional)

**Settings > Appearance > Launch at login** writes a single entry under
`HKCU\Software\Microsoft\Windows\CurrentVersion\Run`. It is removed when you turn
the switch off. Friday then starts in the notification area
(`StartMinimized`), so it does not take over the screen at login.

---

## 7. Away from home

The phone service listens on your local network. It is not exposed to the
internet, and this build does not include a relay or a public endpoint.

If you want it while you are out, put both devices on a private tunnel such as
Tailscale and use the laptop's Tailscale address instead of its LAN address.
That is a configuration you make outside this app; it was **not** tested here,
and there is no NAT-traversal, relay or port-forwarding support inside Friday.
Do not forward port 8790 from your router.

---

## 8. Command line

```
friday.exe                 normal window (full-screen by default)
friday.exe --compact       start as the small always-on-top strip
friday.exe --background    start hidden in the notification area
friday.exe --selftest      headless evidence run; writes logs\selftest.txt
friday.exe --netcheck      end-to-end TLS API test on a throw-away home folder
```

`--selftest` and `--netcheck` print to the console and exit with code 0 when
everything passed. `--netcheck` never touches your real config, devices or audit
log: it redirects `FRIDAY_HOME` to a temporary folder.

---

## 9. Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| "Friday is already running" | One instance only, by design. Use the tray icon. |
| Window opens but nothing answers | Check `logs\friday.log`. If the AI is needed, the status bar says "AI not configured". |
| "No AI key is configured" | Set `FRIDAY_AI_KEY` or save a key under **Settings > AI**. Local commands still work. |
| Voice says unavailable | No recogniser installed, or the microphone is in use by another app. Windows Settings > Time & Language > Speech. |
| Phone says "Connection refused" | Friday is not running, the port is wrong, or the firewall rule is missing. |
| Phone says the certificate was refused | The laptop's certificate changed (new `server.pfx`), or something is impersonating it. Pair again from the laptop; do not continue past a mismatch. |
| Phone gets 401 | The token was revoked (unpaired, or revoked on the laptop). Pair again. |
| Phone gets 423 | Emergency stop is engaged. Release it on the laptop. |
| Phone gets 426 | App and laptop speak different protocol versions. Update the older one; the contract is `1.0`. |
| Phone gets 429 | More than 120 requests in a minute from one device. Wait a few seconds. |
| "Port 8790 is already in use" | Another program owns the port. Change it in **Settings > Phone** and use the same port on the phone. |
| Pairing code rejected | Codes are single use and expire. Generate a new one. |
| Replies are slow | Local commands answer in tens of milliseconds; open questions depend on the AI provider. See the measured numbers in `FRIDAY_TESTING.md`. |

---

## 10. Uninstalling

1. **Settings > Phone**: revoke the phone(s), or **UNPAIR THIS PHONE** on the
   phone.
2. Tray > **Exit**.
3. Turn off **Launch at login** first if it is on (otherwise the Run entry stays).
4. Delete `friday.exe` and the folder `%APPDATA%\Friday`.
5. Remove the firewall rule if you created one:
   `netsh advfirewall firewall delete rule name="Friday 8790"`.

The JARVIS app on the phone is independent; uninstalling Friday does not affect
notifications, WhatsApp summaries or the rest of JARVIS.
