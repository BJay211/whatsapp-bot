# JARVIS laptop agent

A small PowerShell HTTP server that lets the JARVIS Android app send a command
to your computer. The phone talks straight to it over your own Wi-Fi - there is
no cloud hop, no n8n and no account.

It is deliberately boring: one file, no dependencies, a fixed list of commands,
one shared token, and a log that never records the token.

---

## Quick start

1. Copy this folder to the laptop (it can stay anywhere).
2. Open PowerShell in the folder.
3. Run:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\start-agent.ps1
   ```

   The first run creates `config.json`, generates a token and prints something
   like:

   ```
   App address:  http://192.168.1.20:8787
   Token:        3f8c1d0a9b7e4c5f8a2d6e1b4c9f7a30
   ```

4. In the app: **Automations → Laptop Control**. Paste the address and the token,
   tick **Let JARVIS send commands to my laptop**, tap **SAVE**, then **TEST**.
5. Press **Ctrl+C** in the PowerShell window to stop the agent.

If the phone cannot reach it, allow the port on private networks. Either rerun
`start-agent.ps1 -AllowFirewall` from an Administrator PowerShell, or run:

```powershell
New-NetFirewallRule -DisplayName "JARVIS laptop agent" -Direction Inbound `
  -Action Allow -Protocol TCP -LocalPort 8787 -Profile Private
```

`start-agent.ps1` never stores the token anywhere except `config.json`.

---

## Files

| File | What it does |
| --- | --- |
| `agent.ps1` | The server. Reads `config.json`, listens, runs commands. |
| `start-agent.ps1` | Creates `config.json` + token on first run, prints the address, starts the agent. |
| `test-agent.ps1` | Pings the agent, checks the token, runs `whoami`. |
| `config.example.json` | Starter command list. Copied to `config.json` on first run. |
| `config.json` | **Your** token and command list. Git-ignored. Do not share it. |
| `agent.log` | One line per start, run and refusal. No token is ever written. Git-ignored. |

---

## How the phone and the agent talk

Every request is HTTP on your LAN. `X-JARVIS-Token` carries the shared secret.

### `GET /ping` - no token

Used by the app's **FIND MY LAPTOP ON THIS WI-FI** button. It answers only if the
JSON `name` contains "jarvis", so an unrelated server on the same port is not
mistaken for the laptop.

```json
{ "ok": true, "name": "JARVIS laptop agent", "version": "1.0.0", "port": 8787, "time": "2026-10-27T10:00:00.0000000+01:00" }
```

### `GET /health` - token required

```json
{
  "name": "JARVIS laptop agent",
  "version": "1.0.0",
  "mode": "allowlist",
  "host": "DESKTOP-ABC",
  "working_directory": "C:\\Users\\You",
  "allowlist_count": 19,
  "commands": [
    {
      "name": "open_app",
      "description": "Open an app by the name Windows knows it by...",
      "args": ["app"],
      "required": ["app"],
      "aliases": [],
      "preview": "Start-Process -FilePath $env:JARVIS_ARG_APP"
    }
  ]
}
```

The app shows `preview` in the approval dialog, so you see the actual script
before anything runs.

### `POST /execute` - token required

Request:

```json
{
  "request_id": "0d5b6c1e-...",
  "command": "open_app",
  "raw": "open_app app=notepad",
  "args": { "app": "notepad" },
  "client": "android",
  "app_version": "3.3"
}
```

Response (HTTP 200 even when the command failed - read `ok` and `exit_code`):

```json
{
  "ok": true,
  "request_id": "0d5b6c1e-...",
  "command": "open_app app=notepad",
  "exit_code": 0,
  "stdout": "",
  "stderr": "",
  "duration_ms": 412,
  "timed_out": false,
  "error": ""
}
```

Errors use a non-2xx status and an `error` string:

| Status | Meaning |
| --- | --- |
| 400 | Bad JSON, unknown argument, missing required argument, nothing to run |
| 401 | Missing or wrong `X-JARVIS-Token` |
| 403 | Command is not in the allowlist (allowlist mode) |
| 404 | No such route |
| 405 | Wrong method |
| 413 | Body above 256 KB |
| 500 | Agent bug; details are in `agent.log` |

A non-zero exit code is a failure even though HTTP returned 200. The app shows
the exit code and stderr as they came back.

---

## Adding commands

This is the part you will extend. Open `config.json` and add an entry under
`commands`:

```json
"open_whatsapp": {
  "description": "Open WhatsApp Desktop.",
  "args": [],
  "preview": "Start-Process 'WhatsApp'",
  "script": "Start-Process 'WhatsApp'"
}
```

Then restart the agent (**Ctrl+C**, run `start-agent.ps1` again). The new command
appears in the app under **WHAT CAN IT RUN?** and in the approval dialog.

With arguments:

```json
"open_project": {
  "description": "Open a project folder in VS Code.",
  "args": ["path"],
  "required": ["path"],
  "aliases": ["open project", "open my project"],
  "preview": "code <path>",
  "script": "code $env:JARVIS_ARG_PATH"
}
```

`aliases` is optional. It lets you speak a normal sentence instead of the internal name:
with `"aliases": ["open notepad"]`, saying **"on my laptop open notepad"** runs the
`notepad` entry. Aliases only point at an allowlisted entry; they never add a new
command, and the app still shows the exact command before running it. Phrase aliases work
best on commands that take no arguments - a command with `key=value` arguments must be
said in that form.

The phone sends `open_project path=\"C:/Users/You/code/app\"`. The agent puts the
value in an environment variable, so a path with spaces stays one argument and
never becomes part of the PowerShell command line. Argument names are turned
into uppercase env vars: `app` → `$env:JARVIS_ARG_APP`, `file_name` →
`$env:JARVIS_ARG_FILE_NAME`. All arguments are also available as JSON in
`$env:JARVIS_ARGS_JSON`.

Environment variables set for every command:

| Variable | Value |
| --- | --- |
| `JARVIS_ARGS_JSON` | `{"app":"notepad"}` |
| `JARVIS_ARG_<NAME>` | One variable per argument, uppercased |
| `JARVIS_REQUEST_ID` | The request id from the phone |
| `JARVIS_WORKDIR` | The configured `working_directory` |
| `JARVIS_AGENT_VERSION` | Agent version |

The command runs with `working_directory` as its current directory. Output is
capped at 40,000 characters per stream so a runaway command cannot fill the
phone.

### Rules the agent enforces

- The command name must be in `commands` unless `mode` is `free`.
- Arguments not declared in `args` are rejected with 400, so a typo is visible
  instead of silently ignored.
- Arguments listed in `required` must be non-empty.
- The whole command is killed after `timeout_seconds` (default 60). Killing uses
  `taskkill /T`, so child processes started by the script are stopped too.

---

## Free mode (raw PowerShell)

`config.json`:

```json
"mode": "free"
```

Now the `raw` text from the phone is written to a temporary `.ps1` file and run.
The command name is not used. This is powerful and dangerous: any sentence that
reaches the agent runs as your Windows user.

Guard rails that remain:

- the token is still required;
- the app still asks for approval by default (`laptopAskBeforeRun`);
- the phone has its own **Allow raw PowerShell** switch, off by default;
- the agent logs every raw run.

Turn it on only on a network you trust, and only while you need it. A mistyped
or mis-heard command runs with your full user rights.

---

## Security model - read this

- **HTTP, not HTTPS.** On your own Wi-Fi that is a reasonable trade for a
  zero-setup agent. Anyone who can sniff your LAN and read the token can run the
  allowed commands. Do not use this on public Wi-Fi.
- **One shared token, stored in plain text** in `config.json` and in the app's
  private preferences. It is not encrypted at rest. Removing the key from the
  app does not change the token; edit `config.json` and rerun to rotate it.
- **The agent binds to every interface** (`0.0.0.0`) so the phone can reach it.
  Do not forward the port on your router. Do not run it on a machine exposed to
  the internet.
- **`/ping` needs no token.** It reveals only the agent name, version and port.
- **The log records commands, not output.** If a command prints something
  sensitive, it is in the app's result, not in `agent.log`.
- **The token is never logged** and never appears in an error message.
- The agent does not elevate. It runs as the user who started it.

### Using it away from home

If phone and laptop are not on the same Wi-Fi, put both on a private overlay
network such as Tailscale and use the laptop's Tailscale IP as the address. The
agent does not need to change; the traffic is then encrypted by Tailscale. This
project does not configure Tailscale for you.

---

## Troubleshooting

| Symptom in the app | What to check |
| --- | --- |
| `Connection refused` | The agent window is closed, or the port is wrong. |
| `did not answer in time` | Windows Firewall is dropping the packet, or the phone is on a different network. |
| `HTTP 401` | The token in the app does not match `config.json`. |
| `HTTP 403` | In allowlist mode the name is not in `commands`. Check **WHAT CAN IT RUN?**. |
| `HTTP 404 ... /execute` | Something else is on that port. Stop it, or change `port`. |
| Found nothing on **FIND MY LAPTOP** | Some Wi-Fi networks block device-to-device traffic (guest/AP isolation). Type the IP by hand. |
| Works, then stops | The laptop changed IP. Give it a DHCP reservation, or run **FIND MY LAPTOP** again. |

Check the agent side with:

```powershell
powershell -ExecutionPolicy Bypass -File .\test-agent.ps1
```

It prints the real status of `/ping`, `/health` and one `whoami` run, and exits
non-zero if any step fails.
