# Friday phone API (protocol 1.0)

Friday runs on the laptop and exposes one small HTTPS service for the JARVIS
Android app. This document is the contract. It describes what the code in
`apps/friday/src/Net/FridayServer.cs` actually does, and the Android client in
`apps/android-notifier/app/src/main/java/com/bonjee/notifier/friday/FridayClient.kt`
implements exactly this.

Base URL: `https://<laptop-ip>:8790` (default port 8790, TLS on by default).

Across the internet the same routes are reached through the optional relay:

```
https://<relay-host>/r/<laptopId>/v1/...
```

Friday dials out to the relay, so the laptop needs no open port. Pairing uses
`https://<relay-host>/v1/pair`, which broadcasts to the connected laptops and
returns `relay_laptop` so the phone knows the route. The relay forwards only
`/v1/` paths and still leaves every token and policy check to Friday.

- Every route except `/v1/ping` and `/v1/pair` needs
  `Authorization: Bearer <device token>` (or `X-Friday-Token: <token>`).
- Bodies are JSON. Responses are JSON with `"ok": true|false`.
- On the LAN the connection is TLS with a self-signed certificate, and the phone
  pins its SHA-256 at pairing time (`docs/FRIDAY_SECURITY.md`). Through the relay
  the hop uses the relay's publicly-trusted TLS; the laptop id plus the device
  token identify the laptop and the caller.
- Nothing here is a shell endpoint: `/v1/command` accepts a natural-language
  instruction, and Friday applies its own policy before anything runs.

## Task states

Every task moves through this versioned set of states:

| State | Meaning |
|---|---|
| `received` | accepted and queued |
| `validating` | policy is checking the request |
| `awaiting_approval` | waiting for a human to approve a privileged/destructive action |
| `running` | executing |
| `succeeded` | finished and the result is in `summary` |
| `failed` | finished with an error in `error` |
| `cancelled` | stopped by the user, the emergency stop, or shutdown |

`is_terminal` is true for `succeeded`, `failed` and `cancelled`.

## Endpoints

### `GET /v1/ping` - no token

Liveness and identity. Used before pairing and by "is it on?" checks.

```json
{
  "ok": true,
  "name": "Friday",
  "version": "1.1.0",
  "protocol": "1.0",
  "machine": "TAVEH-LAPTOP",
  "tls": true,
  "fingerprint": "69450D04…A55DBA",
  "paired_devices": 1,
  "pairing_required": true,
  "emergency_stop": false
}
```

### `POST /v1/pair` - no token

```json
{ "code": "ABCDEFGH", "device_name": "Pixel 8", "platform": "android" }
```

- The code is 8 characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, displayed as
  `ABCD-EFGH`.
- It is single use and expires (default 10 minutes, configurable 1-60).
- A wrong or expired code returns `403 pairing_failed` after a deliberate
  500 ms delay.

Success (`200`):

```json
{
  "ok": true,
  "device_id": "d1a2b3c4d5",
  "device_name": "Pixel 8",
  "token": "<random 32-byte token, base64url>",
  "fingerprint": "<SHA-256 of Friday's certificate>",
  "server_name": "Friday",
  "protocol": "1.0",
  "relay_laptop": "lap0123456789abcdef"
}
```

`relay_laptop` is present only when the request came through the relay. The phone
stores it and routes later calls to `/r/<relay_laptop>/v1/...`. It is optional for
LAN-only clients, which keep using the pinned certificate.

The phone stores the token and the fingerprint only after this response
succeeds. Friday stores only `SHA-256(token)` on disk.

### `GET /v1/status`

Machine state plus the six most recent tasks.

```json
{
  "ok": true,
  "name": "Friday",
  "version": "1.1.0",
  "protocol": "1.0",
  "machine": "TAVEH-LAPTOP",
  "state": "idle",
  "detail": "Ready",
  "ai_configured": false,
  "voice": true,
  "running_tasks": 0,
  "emergency_stop": false,
  "fingerprint": "69450D04…",
  "paired_devices": 1,
  "device": { "id": "d1a2b3c4d5", "name": "Pixel 8", "last_seen_at": "2026-10-28T11:40:38+00:00" },
  "recent_tasks": [ /* task objects */ ],
  "server_urls": ["https://192.168.1.20:8790", "https://localhost:8790"]
}
```

### `POST /v1/command`

```json
{
  "protocol": "1.0",
  "text": "open Spotify and play my playlist",
  "request_id": "android-7f3c…",
  "client": "android",
  "app_version": "3.5"
}
```

Rules:

- `request_id` is required, 4-100 characters, and must be stable for a retry of
  the same instruction. The same id never runs twice: the original task is
  returned with `"duplicate": true`.
- `protocol` must start with `1.`; anything else returns `426`.
- `text` is limited to 4000 characters.
- Friday waits up to **6 seconds** for fast work and returns `200` with the
  finished task. Longer work returns `202` with the task's current state; poll
  `/v1/task/{id}` instead of holding the request open.

Task object:

```json
{
  "ok": true,
  "duplicate": false,
  "request_id": "android-7f3c…",
  "task_id": "t9f1c2",
  "status": "awaiting_approval",
  "summary": "",
  "progress": "",
  "instruction": "run `Get-Location`",
  "source": "phone",
  "needs_approval": true,
  "approval_id": "a41d0e",
  "error": "",
  "created_at": "2026-10-28T11:40:38+00:00",
  "updated_at": "2026-10-28T11:40:39+00:00",
  "is_terminal": false,
  "approval": {
    "approval_id": "a41d0e",
    "title": "Run this command?",
    "detail": "Get-Location",
    "capability": "shell",
    "destructive": false
  }
}
```

`data` appears when the tool produced structured output, `error` when the
status is `failed`.

### `GET /v1/task/{id}` (also `GET /v1/task?task_id=…`)

Returns the same task object. `404` when the id is unknown. Poll about once a
second; there is no websocket or long-poll in protocol 1.0.

### `GET /v1/tasks`

`{ "ok": true, "tasks": [ …20 most recent… ] }`

### `GET /v1/approvals`

Everything currently waiting for a human.

```json
{
  "ok": true,
  "approvals": [
    {
      "approval_id": "a41d0e",
      "task_id": "t9f1c2",
      "title": "Run this command?",
      "detail": "Get-Location",
      "capability": "shell",
      "destructive": false,
      "created_at": "2026-10-28T11:40:38+00:00"
    }
  ]
}
```

### `POST /v1/approve`

```json
{ "approval_id": "a41d0e", "approved": true, "remember": false }
```

- `remember` adds the exact action to Friday's approved list (used for
  "always allow this"), so use it deliberately.
- `403 forbidden` when **Allow remote approval** is off on the laptop: the phone
  can see the request but the decision must be made on the laptop.
- `404` when the approval was already answered or expired.

### `POST /v1/task/{id}/cancel`

```json
{ "ok": true, "task_id": "t9f1c2", "status": "cancelled", "summary": "Cancelled." }
```

`ok` is false with `status: "not_running"` when the task was already finished.

### `POST /v1/emergency-stop`

```json
{ "engage": true }
```

Engaging cancels queued and running work and makes `/v1/command` return `423`
until it is released. `{ "engage": false }` releases it. The phone cannot leave
it engaged and walk away without noticing: `/v1/ping` and `/v1/status` both
report `emergency_stop`.

### `POST /v1/devices/revoke`

```json
{ "device_id": "d1a2b3c4d5" }
```

Revokes a paired device immediately; its token stops authenticating. The phone
uses this when the user taps **Unpair**.

### `GET /v1/events?since=<seq>`

Incremental progress feed for the phone UI.

```json
{
  "ok": true,
  "latest": 412,
  "events": [
    { "seq": 411, "type": "task", "task_id": "t9f1c2", "status": "running", "summary": "Opening Spotify", "at": "…" }
  ]
}
```

### `GET /v1/health`

Same payload as `/v1/status`; kept for simple uptime checks.

## Status codes

| Code | Meaning |
|---|---|
| 200 | done, or a finished task |
| 202 | accepted, still working - poll the task id |
| 400 | malformed body, missing `request_id`, empty/oversized text |
| 401 | missing/unknown token - pair the phone again |
| 403 | pairing failed, remote approval disabled, or a refused action |
| 404 | unknown route, task or approval |
| 423 | emergency stop engaged |
| 426 | protocol mismatch |
| 429 | more than 120 requests per minute from one device |
| 500 | an unexpected error; details are in Friday's log, not in the response |

Errors:

```json
{ "ok": false, "error": "unauthorized", "message": "This endpoint needs a paired device token." }
```

## Client behaviour the contract expects

1. Pin the certificate. If `ping` reports a different `fingerprint` than the one
   stored at pairing, stop and tell the user; do not send the token.
2. Generate one request id per instruction, and reuse it on retry.
3. Treat `202` as "working", not "done". Poll until `is_terminal`.
4. Ask for approval only when `approval` is present, once per approval id.
5. Offer Cancel while a task is running, and an emergency stop that is clearly
   separate from it.
6. Say "done" only for `succeeded`. `failed`, `cancelled`, timeouts and lost
   connections are reported as what they are.

## What the protocol deliberately does not have

- No endpoint that runs an arbitrary command string. The command path is
  natural language, and Friday's own policy decides.
- No endpoint that reads files directly; file work goes through the same tools
  and approvals as on the laptop.
- No unauthenticated task submission, no "trusted network" bypass, and no
  plain-HTTP mode enabled by default.
- No streaming audio or video. Voice is used on the laptop itself.
