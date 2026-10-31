# Friday - security notes

This document says what Friday protects, how, and where the boundary actually
ends. It describes the code as shipped, not an aspiration. Anything not
implemented is listed in [Residual risks](#8-residual-risks-and-what-is-not-covered).

The short version: the phone link is TLS, each phone has its own hashed token,
every task passes a permission gate, and nothing lets a caller run a raw shell.
On the LAN the laptop certificate is pinned; across the internet an optional
outbound relay carries only `/v1/` requests, and Friday still checks the token.

---

## 1. Trust boundaries

```
 microphone / keyboard            phone (JARVIS 3.5)
        |                                |
   Friday UI  ---------------------  TLS :8790 + pinned cert
        |                                |         (LAN)
   permission gate  <--- policy ---- request validation
        |                                |
   tools: apps, files, input,       optional relay
   screen, shell, system, web       (outbound long poll)
        |
   the operating system and the user's own account
```

- The person at the laptop is trusted. Anything they approve is allowed.
- The phone is **not** trusted by default. It must pair, it gets its own token,
  and every capability is still checked on the laptop.
- The AI model is **not** trusted. It may only return tool calls; the laptop
  re-checks each call against the same policy a typed command goes through.

---

## 2. Transport

- The phone service is TLS only: `SslStream` with TLS 1.2 and 1.3, no SSL 3.0,
  no TLS 1.0/1.1. `UseTls` defaults to `true`; turning it off is an explicit
  downgrade and the phone shows `TLS: OFF` when it sees plain HTTP.
- The certificate is self-signed, generated on this machine
  (`server.pfx`, protected with a random password in `server.secret`), valid for
  the machine name and local addresses.
- The phone **pins the certificate by SHA-256**. The Android client installs a
  `PinTrustManager` that accepts exactly one certificate; hostname verification
  is replaced because a LAN IP is not in the certificate, and the pin is the
  stronger check. A different certificate is rejected before the request or the
  token is sent.
- Regenerating `server.pfx` therefore breaks pairing on purpose: every phone must
  pair again. That is the intended recovery from a suspected interception.
- The server speaks HTTP/1.1 and keeps a connection open for further requests
  (30 s idle budget, `Connection: close` honoured). Reuse never skips the pin:
  the pin is checked during the TLS handshake, and a new connection is a new
  check.
- Plain HTTP is still available if someone deliberately turns TLS off (for
  example while debugging on the loopback interface). It is never the default and
  the Android client reports it.

### 2.1 Internet relay

The optional relay (`relay/server.js`) lets a phone reach the laptop from another
network. The laptop makes only **outbound HTTPS** requests to it; no inbound port
is opened.

- The phone calls `https://<relay>/r/<laptopId>/v1/...` over the relay's own
  publicly-trusted TLS. The laptop's self-signed pin does not apply to that hop.
- The laptop proves it owns the id with a separate 256-bit relay key in a header.
  The relay stores only a **SHA-256 hash**, in memory. A restart forgets it and
  the laptop re-registers on its next poll. The key is DPAPI-encrypted on disk;
  `FRIDAY_RELAY_KEY` can override it from the environment.
- The relay forwards only paths starting with `/v1/`, rejects `..`, caps bodies
  (2 MB by default) and answers a duplicate response for the same request id with
  `409`. It has no command logic, shell, filesystem access or device tokens.
- Friday validates the phone's device token itself on every relayed request, so
  revoking a device still takes effect immediately. The relay cannot bypass
  pairing, approvals, policy or the emergency stop.
- Pairing via the relay is broadcast to connected laptops and rate-limited to 20
  attempts per IP per minute. The winning laptop's id is returned to the phone as
  `relay_laptop`, so no manual id copying is needed.
- A relay restart is survivable by design: Friday reconnects with backoff, and
  in-flight requests fail with `503`/`504` rather than being replayed.


## 3. Pairing and device identity

- **Code**: 8 characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (no `0/O/1/I`),
  shown as `ABCD-EFGH`. The alphabet gives 32^8 ≈ 1.1 × 10^12 combinations.
- Codes are **single use**, expire after 10 minutes by default (1-60 configurable),
  and are replaced when a new one is generated.
- A wrong or expired code returns `403` after a deliberate 500 ms delay, so an
  attacker cannot try thousands of guesses inside the code's lifetime. Only one
  code is live at a time.
- On success the laptop issues a **random 32-byte token** (base64url). The phone
  stores it in its private preferences. The laptop stores only
  `SHA-256(token)` in `devices.json` - the file cannot be replayed as a
  credential if it leaks.
- Every device has its own id, name, platform, creation time and last-seen time.
- **Revocation** is immediate: an unpaired or revoked token stops authenticating
  on the next request. The phone's **UNPAIR** calls `/v1/devices/revoke` first,
  then forgets the token and the pin. If the laptop is unreachable, the phone
  says so and tells you to remove the device on the laptop.
- Pairing requires physical access to the laptop screen (the code is only shown
  there). Over a relay the phone does not need to be on the same network; the
  code still has to come from the laptop and is forwarded only through the
  outbound relay tunnel.
- Through the relay the laptop id is learned from the pairing response, not typed
  by hand. The phone never stores the relay key.

## 4. Request handling

- Every endpoint except `/v1/ping` and `/v1/pair` requires
  `Authorization: Bearer <token>`; a missing, unknown or revoked token is `401`.
- `protocol` must start with `1.` or the request is refused with `426`, so an old
  client cannot be surprised by a new contract.
- **Idempotency**: `request_id` is required. The same id is never executed twice;
  a retry gets the original task with `"duplicate": true`. This is what makes a
  flaky Wi-Fi connection safe rather than dangerous.
- **Rate limit**: 120 requests per minute per device (`429` after that). The
  Android client polls at 300 ms for the first 10 seconds of a task and 800 ms
  afterwards specifically to stay inside this budget.
- Bodies are capped at 1 MB (`MaxBodyKb`), chunked encoding is refused, headers
  are capped at 64 KB, and each request has a 30 s budget. A connection cannot be
  used to hold the server hostage.
- Long work returns `202` with a task id instead of holding the HTTP request
  open, so a slow task cannot look like a hung phone.

## 5. Permissions and approvals

Every tool declares a capability and a risk level:

| Risk | Behaviour |
|---|---|
| `Safe` | Runs without asking **while** `Auto-approve safe actions` is on (default). Turn it off and even a folder listing asks first. |
| `Confirm` | Always asks, whatever the auto-approve switch says. |
| `Destructive` | Asks with a red "Confirm destructive action" card. |

These are the levels declared in `AppTools`, `FileTools`, `InputTools`, `SystemTools`, `ProjectTools`, `ShellTools`, `WebTools` and `MemoryTools`:

- **Safe** - `open_app`, `open_url`, `open_path`, `list_apps`, `list_windows`,
  `activate_window`, `active_window`, `list_directory`, `search_files`,
  `read_file`, `find_large_files`, `find_duplicates`, `list_projects`,
  `inspect_project`, `git_status`, `git_diff`, `search_code`, `system_status`,
  `media_control`, `list_processes`, `connected_devices`, `recall`,
  `web_search`, `fetch_page`, `list_background`, and `run_command` **when the
  command is on the allowlist**. `write_file` and `create_document` are also
  `Safe`, but `write_file` raises an ordinary confirmation of its own before it
  overwrites an existing file.
- **Confirm** - `type_text`, `press_keys`, `move_mouse`, `click`, `scroll`,
  `screenshot`, `copy_path`, `move_path`, `zip_path`, `unzip`, `window_action`,
  `set_volume`, `set_brightness`, `lock_screen`, `download_file`, `remember`,
  `run_tests`, `open_terminal`, `start_background`, `stop_process`, and any
  `run_command` that is not on the allowlist (when freeform shell is enabled).
- **Destructive** - `delete_path` and `power_action` (sleep, restart, shutdown,
  cancel a pending shutdown).

`activate_window` and `media_control` are intentionally `Safe`: they change
what is in front of you or what is playing, not the contents of a file.

The switches below are enforced in the registry, not just drawn:

- Capability switches in **Settings > Access**: input control, screen capture,
  file writes, delete, downloads, power controls. A tool whose switch is off is
  refused before its handler runs and audited as `refused-capability`.
- `Strict confirm` makes **every** action ask, including safe ones.
- `Allow freeform shell commands` is off by default. Shell commands are checked
  against an allowlist (`git status`, `dotnet build`, `dir`, ...) and a blocked
  list (`format`, `diskpart`, `bcdedit`, `reg add/delete`, `netsh`, shadow-copy
  deletion, event-log clearing, scheduled-task changes, and download-and-execute
  one-liners). Allowlisted commands run; anything else waits for approval; with
  freeform off it is refused outright.
- Approvals from the phone can be disabled (`Allow approvals from the phone`).
  Then the phone can see the request but only the laptop can decide; a phone
  approval returns `403`.
- Approvals expire after 180 s; only the **first** answer wins, so a double tap
  cannot approve twice.
- Every approval decision is written to `audit.jsonl` with the device id and the
  request id.
- `remember` (approve and remember this exact action) exists in the protocol but
  the Android client currently always sends `false`; the desktop card is the only
  place that can make an allowance stick.

## 6. What the assistant can touch

**Files.** `PathGuard` resolves a requested path, expands `~` and environment
variables, then requires it to sit inside one of the configured roots. Defaults:

- `%USERPROFILE%\Desktop`
- `%USERPROFILE%\Documents`
- `%USERPROFILE%` (the whole profile, so notes anywhere under it are reachable)
- `%USERPROFILE%\Downloads`
- the folder Friday was started from

Inside those roots, credential stores are always refused regardless of settings:
`.ssh`, `.aws`, `.azure`, `.gnupg`, Windows Credential Manager folders,
`Windows\System32\config`, and the file names `id_rsa`, `id_ed25519`,
`credentials`, `credentials.json`, `.env`, `.netrc`, `ntuser.dat`, `sam`,
`system`, `security`, `login data`, `cookies`.

Because the profile folder is a root, `AppData` is technically reachable except
for those denied places. If that is too broad, remove the profile entry under
**Settings > Access** and keep only Desktop/Documents/Downloads - the guard is
then correspondingly narrower.

**Screenshots** go to `%APPDATA%\Friday\screenshots`, `1920x1080` on this
machine.

**Shell** is the allowlist above. There is no endpoint that accepts a raw
command string over the network; `/v1/command` takes natural language and the
laptop decides.

**Memory.** New memories are stored **unapproved** and are not sent to the model
until you approve them. Only approved memories appear in the prompt. This is
covered by a self-test check.

## 7. Secrets, logs and privacy

- API keys come from `FRIDAY_AI_KEY` (environment, preferred) or from
  `Settings > AI`, where they are encrypted with **DPAPI, current user**. There is
  no plaintext key in `config.json`, source, logs, the APK or the audit trail.
- Device tokens are stored only as SHA-256 hashes on the laptop and in the
  phone's private preferences. Neither the token nor the pairing code is ever
  logged.
- The relay key is a separate 256-bit secret, DPAPI-encrypted in `config.json`
  (or read from `FRIDAY_RELAY_KEY`). The relay keeps only its SHA-256 hash in
  memory. The laptop id is public routing information, not a credential. Neither
  is written to the audit log.
- `audit.jsonl` records: timestamp, action, actor, device id, request id,
  decision, ok flag, and a trimmed detail. That is enough to answer "who asked
  for this and what was decided" without storing credentials.
- `conversation.jsonl` stores what was said and answered. **Retention** (default
  90 days, configurable 1-3650) is enforced at startup: lines older than the
  cutoff are removed from `audit.jsonl` and `conversation.jsonl`. Runtime
  diagnostics in `logs\friday.log` are not pruned by that setting.
- The transcript is reloaded on restart for display only. Nothing loaded from it
  is re-sent to the AI provider.
- When the AI is used, the provider sees your question, the system prompt, the
  tool results needed for that turn, and your **approved** memories. It does not
  see your token, your paired-device list or your audit log.
- The assistant is local-first: time, app launch, system status, screenshots,
  files and shell commands never need the network. General questions are the only
  thing that goes to the provider.

## 8. Emergency stop, cancellation and the audit trail

- **Cancel** stops one task. **Emergency stop** (`Ctrl+Alt+Esc`, tray menu, or the
  phone) cancels queued and running work and makes `/v1/command` return `423`
  until released.
- The stop is deliberately **not persisted**: restarting Friday releases it,
  because a stuck stop with no way to clear it would be worse. The tray tooltip
  and the red banner show when it is engaged.
- Every task ends in exactly one terminal state (`succeeded`, `failed`,
  `cancelled`), and the audit line records the outcome.
- `/v1/ping` and `/v1/status` both report `emergency_stop`, so a client can tell
  the difference between "off" and "refusing on purpose".

## 9. Residual risks and what is not covered

These are real, and you should know them before relying on this:

1. **Android runtime.** The 3.5 client has not been run on a phone or emulator in
   this environment: continuous conversation, wake listening, TTS, relay pairing
   and the relay transport compile and are exercised on the laptop side, but no
   on-device handshake has been observed. Treat them as unverified until you pair
   once and watch it succeed.
2. **No code signing.** `friday.exe` is not signed, so Windows SmartScreen may
   warn. Nothing verifies that the exe you downloaded is the one that was built.
3. **No auto-update.** Updating means replacing the file by hand. There is no
   signed update channel.
4. **The relay is infrastructure you run.** The code and its local checks are in
   this repository, but the deployed service and a real cross-network phone were
   not tested here. Whoever runs the relay sees traffic metadata (timing, sizes,
   laptop id) even though bodies are encrypted by HTTPS. Use a relay you control,
   or a private tunnel. Never port-forward 8790.
5. **Same-account processes are trusted.** Anything running as your Windows user
   can read `%APPDATA%\Friday`, call the tools, or decrypt the stored key with
   DPAPI. Friday does not defend against malware already on the machine.
6. **Reparse points are not resolved.** `PathGuard` compares resolved full paths
   but does not follow junctions/symlinks to check their target. A junction
   placed inside an allowed root by you or by software you run could point
   elsewhere. Friday refuses obvious credential names, not every possible path.
7. **The AI provider is a third party.** Questions, approved memories and tool
   results needed for that turn leave the machine. If that is unacceptable, do
   not set a key; local commands keep working.
8. **Voice is Windows speech.** Recognition is local, but its accuracy and its
   default-device limitation are Windows', not Friday's. The wake word is on by
   default, so the microphone is open while Friday runs; the status bar shows
   `MIC` only when capture is really active, and **Settings > Voice** turns it
   off. Some Windows TTS voices are noticeably robotic.
9. **Firewall is manual.** Friday does not create a firewall rule. Until you add
   one, the phone cannot reach it.
10. **The audit log and transcript are plain files.** Protected by NTFS
    permissions on your profile only, not encrypted at rest. Retention limits how
    long they live, not who can read them.
11. **Rate limiting is per process and in memory.** Restarting Friday resets the
    counters.

## 10. If something looks wrong

- Phone shows a **different fingerprint** than the laptop: do not approve
  anything. Regenerate the certificate on the laptop (which invalidates old
  pairings) only after checking why it changed.
- A device you do not recognise in **Settings > Phone**: select it and
  **REVOKE SELECTED**.
- Anything unexpected ran: press `Ctrl+Alt+Esc`, then read `audit.jsonl`
  (Settings > Privacy > AUDIT LOG).
- Lost phone: revoke it on the laptop. Its token stops working immediately.
- Suspected key leak: **REMOVE** the key in Settings > AI and set a new one in
  `FRIDAY_AI_KEY`.
