# JARVIS 3.3 - manual test checklist

This build was compiled and statically verified. The **agent** was also run and exercised
on a Windows workstation over loopback. There was still no phone, emulator or `adb`, so
none of the app-side runtime behaviour below has been observed on a device. Treat every
unchecked line as unverified.

What *was* verified before delivery:

- `145/145` headless self-checks passed (16 new laptop-parser checks).
- `assembleDebug` and `assembleRelease` succeeded offline.
- APK: versionCode 15, versionName 3.3, 12 declared permissions, 0 embedded secret
  patterns, debug-key signature unchanged.
- Agent, over loopback, on 2026-10-27: `/ping` 200, `/health` 200, `whoami` exit 0,
  `uptime` exit 0, an argument containing a space, unknown command 403, missing required
  argument 400, undeclared argument 400, invalid JSON 400, raw PowerShell refused in
  allowlist mode 403, free mode accepted, non-zero exit reported, 5-second timeout killed
  the process and returned `timed_out: true`.

Install `Bonjee-JARVIS-3.3-assistant.apk` (versionCode 15). Upgrading over 3.2 keeps
your data; laptop/AI settings start empty and off.

---

## 1. Install and open

- [ ] Installs over 3.2 without "app not installed".
- [ ] Android app info shows version 3.3 (versionCode 15).
- [ ] Drawer shows the previous entries **plus** Laptop Control and AI Assistant.
- [ ] Every previous drawer item still opens its real screen.
- [ ] Actions hub shows LAPTOP CONTROL and AI ASSISTANT; both open their screens.
- [ ] Nothing crashes when the laptop and AI are left unconfigured.

## 2. Laptop agent setup (on the PC)

- [ ] In the `laptop-agent` folder, `start-agent.ps1` creates `config.json` and prints a
      32-character token and one or more `http://<ip>:8787` addresses.
- [ ] Running it again reuses the same token (the app stays connected).
- [ ] `test-agent.ps1` prints OK for `/ping`, `/health` and `whoami`, and exits 0.
- [ ] With the agent stopped, `test-agent.ps1` fails with a real connection error.
- [ ] `agent.log` gets one START line and one RUN/DONE line per command.
- [ ] `agent.log` never contains the token.

## 3. Laptop control screen (on the phone, same Wi-Fi)

- [ ] Enter the PC address and token, tick the enable switch, SAVE -> status shows the
      address, `token: set`, and the approval mode.
- [ ] TEST -> "Connected to JARVIS laptop agent", mode and command count; no fake
      "connected" if the agent is stopped.
- [ ] Wrong token -> the message says the token does not match `config.json` (HTTP 401).
- [ ] Wrong port / agent stopped -> "Connection refused. The agent is not running..."
- [ ] FIND MY LAPTOP ON THIS WI-FI finds the PC and fills address + port. On a network
      with AP isolation it says nothing answered and does not invent a device.
- [ ] WHAT CAN IT RUN? lists the commands from `config.json`; tapping one pre-fills the
      box.
- [ ] `notepad` -> approval dialog shows the exact command and the agent preview; Cancel
      sends nothing and the PC stays unchanged.
- [ ] Approve -> Notepad opens; result says "Done (exit 0) in N ms".
- [ ] `open_app app="visual studio code"` keeps the spaces in one argument.
- [ ] An unknown command (e.g. `rm_rf`) shows HTTP 403 and the agent's allowed list.
- [ ] `remove-item C:/temp/x` in the box shows the destructive-command warning before
      you can approve it.
- [ ] Turn "Always show the command and ask me first" off -> it runs without a dialog.
      Turn it back on afterwards.
- [ ] Enable raw PowerShell: a second warning appears. With the agent still in
      `allowlist` mode, a raw command is refused with 403. Switching `config.json` to
      `"mode": "free"` and restarting the agent makes it run.
- [ ] A command that takes longer than `timeout_seconds` reports "Timed out on the
      laptop" and does not claim success.
- [ ] RECENT lists the runs with ok/exit/error; CLEAR HISTORY empties it.
- [ ] Disabling the master switch makes the assistant refuse to send anything.

## 4. AI settings

- [ ] Open Automations > AI Assistant; with no key, status says `key: missing`.
- [ ] TEST with no key says "Add an API key first".
- [ ] Paste a real Groq key and the default model, SAVE, TEST -> "Working" and a short
      reply. (Not run here: no key was available.)
- [ ] A wrong key -> HTTP 401/403 with the provider's message; nothing is retried
      silently.
- [ ] An unavailable model -> HTTP 404 with a message naming the model.
- [ ] LIST MODELS returns this key's models; tapping one sets the model.
- [ ] REMOVE KEY empties the field, turns AI off, and AI questions fall back to n8n.
- [ ] Airplane mode -> TEST reports a real network/DNS error, not a fake answer.

## 5. Assistant routing

- [ ] `open WhatsApp` still runs on the phone (device action wins).
- [ ] `on my laptop notepad` -> laptop approval, then Notepad opens.
- [ ] `open notepad on my laptop` -> same (suffix form).
- [ ] `on my laptops open notepad` -> **not** a laptop command; it is treated as a
      question.
- [ ] `how do I install python on my laptop` -> stays a question, goes to AI/n8n.
- [ ] With AI configured, a general question is answered by the model and the footer
      says `— AI · <model> · <tokens>`.
- [ ] With AI off, the same question goes to the n8n archive exactly as in 3.2.
- [ ] With AI on but failing, the screen shows both the AI error and the archive result.
- [ ] The model never claims it opened an app or ran a command.
- [ ] Voice input still works for both phone and laptop commands.

## 6. Regressions to confirm still work

- [ ] Calling, SMS, contacts, calendar, files, alarms, torch, volume, brightness, media.
- [ ] Notification capture and the offline queue.
- [ ] Important WhatsApp announcements obeying silent/DND/quiet hours/earphones.
- [ ] Reply flow: Generate -> Edit -> Approve / Reply myself / Cancel, with the real
      send status.
- [ ] Message rules, auto-reply authorisation, kill switch.
- [ ] Chained commands (`open WhatsApp then message John`) still split and run.
- [ ] Ask JARVIS still shows real HTTP errors when n8n is unreachable.

## What "pass" looks like

Every laptop command is approved before it runs (by default), the result is the agent's
real exit code and output, and a connection problem is named as a connection problem.
No screen may show "connected", "done" or an AI answer that did not actually happen.
