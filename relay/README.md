# Bonjee Friday relay

A deliberately small, zero-dependency Node service that lets the Android phone
reach Friday on the laptop **from any network** without port forwarding, a
public IP, or opening a router port.

```
phone ──HTTPS──▶ relay ◀──HTTPS long-poll── Friday (outbound only)
        /r/<laptopId>/v1/...        /laptop/poll, /laptop/respond
```

The relay is a **byte router**, not an assistant. It has no shell, no command
logic, no filesystem access, and never stores device tokens, pairing codes or
request bodies.

## Why long polling

Friday only makes outbound HTTPS connections, so it works behind CGNAT, hotel
Wi-Fi and mobile hotspots. No WebSocket library and no dependencies are needed;
Render terminates TLS, and the relay speaks plain HTTP on its private port.

## Deploy on Render

`render.yaml` already contains the service:

```yaml
- type: web
  name: bonjee-friday-relay
  runtime: node
  rootDir: relay
  buildCommand: npm install --omit=dev
  startCommand: node server.js
  healthCheckPath: /health
```

1. Push this repository and apply the Blueprint (or create a Web Service with
   root directory `relay`).
2. Wait for the health check to go green. `GET /health` returns JSON such as
   `{"ok":true,"laptops":0,"online":0,...}`.
3. Copy the service URL, for example `https://bonjee-friday-relay.onrender.com`.
4. On the laptop: **Friday → Settings → Phone → Internet relay** → enable it,
   paste that URL, save. Friday generates a laptop id and an encrypted relay key
   on first save.
5. On the phone: **JARVIS → Friday → Relay URL** → paste the same URL, save, then
   pair with the code shown on the laptop. The phone learns the laptop id from
   the pairing response; no manual id entry is needed.
6. Optional: bake the URL into the APK with the `DEFAULT_RELAY_URL`
   `buildConfigField` in `apps/android-notifier/app/build.gradle`.

Use `https://` in production. The laptop sends its relay key on every poll, so a
plain-HTTP relay on the internet would expose it. Friday's settings screen
refuses non-loopback `http://` relay URLs.

## Environment variables

| Variable | Default | Meaning |
| --- | --- | --- |
| `PORT` | `8787` | Listen port (Render sets this automatically). |
| `RELAY_MAX_BODY_KB` | `2048` | Largest request/response body, in KB. |
| `POLL_WAIT_MS` | `25000` | How long a laptop poll is held open. |
| `REQUEST_TIMEOUT_MS` | `35000` | How long the phone waits for the laptop. |
| `PAIR_TIMEOUT_MS` | `12000` | How long a pairing broadcast waits per laptop. |

## Protocol

All laptop calls carry `X-Friday-Relay-Id` and `X-Friday-Relay-Key`.

- `POST /laptop/poll?wait=25` — long poll. Returns `204` when idle, or a JSON
  envelope `{id, method, path, headers, body_b64}`.
- `POST /laptop/respond` — `{id, status, headers, body_b64}`. A second response
  for the same id returns `409`, so a retry cannot apply anything twice.
- `POST /r/<laptopId>/v1/<path>` — the phone's request. Only `/v1/` paths are
  forwarded; the laptop's own device-token authentication still applies.
- `POST /v1/pair` — broadcasts the pairing request to every laptop that is
  currently polling. The first laptop that accepts wins; the response gains
  `relay_laptop` so the phone knows where to route future calls.
- `GET /health` — counts only. No keys, ids of private devices beyond the count,
  or request data.

Relay key storage is in memory only: the relay keeps a SHA-256 hash of each
laptop key, so a restart forgets it and the laptop re-registers on its next
poll. Pairing attempts are limited to 20 per IP per minute.

## Local test

```powershell
cd relay
node test-relay.js
```

The harness starts the relay on a random port and checks: health, long-poll
delivery, response round-trip, duplicate response rejection, offline laptop,
pairing broadcast and `relay_laptop`, key mismatch, path allowlist and the
pairing rate limit.

## What this service cannot do

- It cannot start, stop or command anything by itself.
- It cannot read the laptop's files or execute shell commands.
- It cannot bypass Friday's pairing or per-device revocation.
- It is not a VPN: only `/v1/` requests from a paired phone are routed.

Revoking a phone on the laptop takes effect on the next request even through the
relay, because Friday checks the device token itself.
