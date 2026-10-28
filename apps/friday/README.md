# Friday

The laptop half of JARVIS: a native Windows assistant that owns the microphone,
speakers, files and applications, and answers the JARVIS app on your phone over
an encrypted, pinned local connection.

- Black and gold core with real states (idle, listening, thinking, speaking,
  executing, awaiting approval, success, error, offline).
- Local commands that need no network and no API key.
- Optional AI for open questions, through any OpenAI-compatible endpoint.
- Every tool is gated by capability and risk; destructive actions ask first.
- The phone link is TLS with a pinned certificate and one hashed token per
  device.

User documentation lives in the repo:

- [Install and first run](../../docs/FRIDAY_SETUP.md)
- [Phone protocol](../../docs/FRIDAY_API.md)
- [Security model](../../docs/FRIDAY_SECURITY.md)
- [What was tested](../../docs/FRIDAY_TESTING.md)
- [What changed](../../docs/FRIDAY_CHANGES.md)

## Requirements

| | |
|---|---|
| OS | Windows 10 or 11, 64-bit |
| Runtime | .NET 10 Desktop Runtime (`Microsoft.WindowsDesktop.App 10.0.x`) |
| Build | .NET SDK 10. The repo has a local one at `.toolchain\dotnet`. |
| Optional | A microphone/speaker for voice, an API key for open questions |

## Build

```powershell
cd apps\friday
.\.toolchain\dotnet\dotnet.exe build Friday.csproj -c Release -v minimal
```

The project targets `net10.0-windows` and suppresses `CA1416` and `WFO1000`
(Windows-only APIs and designer-serialisation warnings that do not apply here).

## Publish

Framework-dependent single file:

```powershell
.\.toolchain\dotnet\dotnet.exe publish Friday.csproj -c Release -r win-x64 `
  --self-contained false -p:PublishSingleFile=true -o dist
```

`dist\friday.exe` then needs the .NET 10 Desktop Runtime on the target machine.
A self-contained publish needs the runtime packs from NuGet, which is not
available offline on this machine.

## Run

```
friday.exe                 normal window (full-screen by default)
friday.exe --compact       small always-on-top strip
friday.exe --background    start hidden in the notification area
friday.exe --selftest      headless checks, writes %APPDATA%\Friday\logs\selftest.txt
friday.exe --netcheck      end-to-end TLS test on a throw-away home folder
```

`--selftest` reports 34 checks and `--netcheck` 25; both exit 0 only when all
pass. `--netcheck` sets `FRIDAY_HOME` to a temp folder, so it does not touch your
real config, devices or audit log.

## Data

Everything Friday owns is under `%APPDATA%\Friday`; nothing is written next to
the executable:

```
config.json        settings (API key encrypted with DPAPI)
server.pfx         TLS certificate
server.secret      password protecting server.pfx
devices.json       paired phones, SHA-256 token hashes only
tasks.json         recent tasks
memory.json        memories (only approved ones reach the AI)
conversation.jsonl transcript
audit.jsonl        one line per security decision
screenshots\       screenshots
logs\              runtime log and self-test report
```

`FRIDAY_HOME` overrides the folder, which is how the network test stays isolated.

## Layout

```
src/Ai/          local intents, orchestrator, provider client
src/Core/        runtime, config, task queue, state, events, log, memory, startup
src/Net/         TLS server, HTTP reader, certificate, pairing
src/Security/    path guard, shell policy
src/Tools/       the tool registry and every tool group
src/UI/          main window, animated core, settings, theme, controls
src/Voice/       speech recognition and synthesis
```

## Safety defaults

- Web/reading/app-launch tools are `Safe`; they run while **Auto-approve safe
  actions** is on, which is the default.
- Input, screenshots, file moves, archives, volume/brightness, lock, downloads,
  memory and non-allowlisted shell commands are `Confirm` - they always ask.
- Delete and power actions are `Destructive` - red card, always ask.
- Freeform shell is off; allowlisted commands only.
- Six Access switches (input, screenshots, file writes, delete, downloads,
  power) are enforced in the registry, not just drawn.
- `Ctrl+Alt+Esc` and the phone's **EMERGENCY STOP** cancel everything and refuse
  new commands until released on the laptop.

## Known gaps

The phone client has not been run on a real device here, and no AI key was
available, so provider calls are untested. See
[`FRIDAY_TESTING.md`](../../docs/FRIDAY_TESTING.md) for the full, honest list.
