# JARVIS app ⇄ n8n contract (WhatsApp + memory, one workflow)

Status: **app side implemented; backend side NOT implemented or verified.**
`https://bonjee-n8n.onrender.com/webhook/app-api` returned **HTTP 404** when checked, so
the app cannot be described as "connected". Every screen reports the real HTTP result.

This document is the exact contract the existing single workflow must implement. It is
not a request to redesign the workflow: it adds one path and one switch to the workflow
that already receives WhatsApp events from Evolution API.

---

## 1. One workflow, two different webhooks

| Hook | Owner | Purpose |
| :-- | :-- | :-- |
| `POST /webhook/personal-ai-whatsapp` | **Evolution API → n8n** | Existing inbound WhatsApp events. Untouched. |
| `POST /webhook/notifications-ingest` | Android notification listener → n8n | Existing generic notification archive. Untouched. |
| `POST /webhook/personal-ai-ask` | Android "Ask JARVIS" → n8n | Existing archive Q&A. Untouched. |
| `GET /webhook/health` | Monitoring | Untouched. |
| **`POST /webhook/app-api`** | **Android app → n8n (new)** | **The single app-facing entry point for every new action.** |

Rules:

- Do **not** create a second workflow.
- Do **not** create one webhook per action. All actions arrive at `/webhook/app-api`
  and are dispatched on the `action` field.
- Evolution API keeps calling the existing inbound hook. It is a server-to-server
  webhook and is unrelated to the app entry point.
- The app never calls `personal-ai-whatsapp`, `notifications-ingest` (except as it
  already did), or any provider API directly.

The app's endpoint path is configurable in **Message Settings → Workflow** and defaults
to `/webhook/app-api` (base URL is the existing n8n base URL setting).

---

## 2. Transport and authentication

- HTTPS only. The app refuses nothing at compile time, but the workflow must be HTTPS.
- `Content-Type: application/json; charset=utf-8`
- Authentication header: `Authorization: Bearer <token>` by default. The header name is
  configurable (`Prefs.authHeaderName`) but the app never embeds a token, key, or secret.
- The workflow **must** validate the token before touching any data. A missing/invalid
  token returns **401**; a valid token without permission for the device returns **403**.
- `device_id` is a random UUID generated once per install. It is a correlation key,
  **not** proof of identity. The server must not trust `device_id`, `sender`, or
  `category` supplied by the client for authorisation decisions.
- Recommended server-side checks: signature/token, device row is enabled, sender is a
  known WhatsApp conversation for that device, message belongs to that conversation,
  request body size limit, rate limit per device/action.
- Timeouts: app uses connect 15 s, write 15 s, read 30 s, call 45 s. A timeout is
  `code = 0` on the app side and is **never** retried for a send.
- Safe read-only actions may be retried once by the app: `message.acknowledge`,
  `reply.status`, `conversation.history`, `memory.search`, `contact.settings.update`,
  `task.create`, `device.register`. `reply.generate` and `reply.approve` are not retried.

### Error responses

```json
{ "ok": false, "error": { "code": "invalid_request", "message": "sender is not in this device's conversations" } }
```

| HTTP | Meaning | App behaviour |
| :-- | :-- | :-- |
| 200 | Accepted | Parse `data.status` where relevant |
| 400 / 422 | Schema/validation failure | Show message, nothing sent |
| 401 / 403 | Bad token / not allowed | Show message, prompt for token |
| 404 | Endpoint missing | Explicitly says the workflow is not updated |
| 429 | Rate limited | Show message, no retry loop |
| 5xx | Workflow failure | Show message, no fake success |

The app treats a 2xx with no `status` for a send as **"accepted, delivery unconfirmed"**
and offers `CHECK STATUS`. It never displays "sent" without a backend confirmation.

---

## 3. Envelope (every request)

```json
{
  "version": 1,
  "request_id": "3f0c0b1e-8f0e-4f0b-9d0b-2a7f6a1c9e21",
  "device_id": "b0f2a5c6-2a3c-4f0a-b9d1-6c3f5a7e8d90",
  "action": "reply.generate",
  "timestamp": "2026-10-26T09:14:03.512Z",
  "payload": {
    "conversation_id": "wa:+2348031234567",
    "message_id": "wa-1a2b3c4d5e6f7a8b",
    "sender": "+2348031234567",
    "sender_name": "Ada",
    "text": "Are we still meeting at 5?",
    "instructions": "",
    "mode": "approval",
    "revision": 0
  }
}
```

- `version`: integer, currently `1`. Reject unknown versions with 400.
- `request_id`: unique per logical request. For sends it is deterministic
  (`send-<message_id>-r<attempt>`), so a replayed approval is recognisable.
- `timestamp`: ISO-8601 UTC with milliseconds, produced by the app.
- `payload`: action-specific, always a JSON object.

### Idempotency

The workflow must store `request_id` and return the previous result for a duplicate
instead of performing the side effect twice. In particular:

- two `reply.approve` calls with the same `request_id` → exactly one WhatsApp send;
- `reply.generate` with the same `request_id` may return the cached draft;
- `contact.settings.update` is upsert semantics;
- a repeated `reply.cancel` is harmless.

The app never resends after a timeout. It calls `reply.status` first.

---

## 4. Actions

Only these actions are implemented by the app. Implement only those the workflow can
actually perform; an unsupported action must return 400, not a fake success.

### 4.1 `message.acknowledge`

Tells the workflow the app has seen the inbound message (used for read state/logging).
It does not affect WhatsApp read receipts unless the workflow chooses to mark them.

```json
{
  "message_id": "wa-1a2b3c4d5e6f7a8b",
  "conversation_id": "wa:+2348031234567",
  "sender": "+2348031234567",
  "sender_name": "Ada",
  "received_at": 1793000000000,
  "message_type": "text",
  "category": "important",
  "auto_reply": false
}
```

Response: `{ "ok": true }`. Failures are non-fatal for the app.

### 4.2 `reply.generate`

Ask for a draft. **This must not send a WhatsApp message** when `mode` is `"approval"`.

```json
{
  "conversation_id": "wa:+2348031234567",
  "message_id": "wa-1a2b3c4d5e6f7a8b",
  "sender": "+2348031234567",
  "sender_name": "Ada",
  "text": "Are we still meeting at 5?",
  "instructions": "say I'll be 10 minutes late",
  "mode": "approval",
  "revision": 1,
  "contact_rule": {
    "number": "+2348031234567",
    "category": "important",
    "auto_reply_enabled": false,
    "auto_reply_mode": "off",
    "template": ""
  }
}
```

- `text` is the **inbound message text**, not the user's instruction.
- `instructions` is the user's typed/dictated guidance; empty when none.
- `mode = "auto"` is sent only for Auto-Reply contacts that the server already
  authorised. The workflow may then generate **and send** under its own policy, or
  return a draft for approval.

Success response (either shape is accepted; `data` is preferred):

```json
{
  "ok": true,
  "data": {
    "reply": "Yes - running 10 minutes late, see you at 5:10.",
    "message_id": "wa-1a2b3c4d5e6f7a8b",
    "status": "pending_approval",
    "conversation_id": "wa:+2348031234567"
  }
}
```

- `status` must be one of `pending_approval`, `sending`, `sent`, `failed`,
  `cancelled`, `approved` when the workflow acted.
- If `mode` was `approval`, the app ignores a `sent` status and still requires the user
  to approve the text it displays. The app never auto-sends a draft.
- If no reply text is available, return `reply: ""` and `ok: true`; the app says so.

### 4.3 `reply.approve`

The only action that may cause an outgoing WhatsApp message from a user-approved reply.

```json
{
  "conversation_id": "wa:+2348031234567",
  "message_id": "wa-1a2b3c4d5e6f7a8b",
  "sender": "+2348031234567",
  "text": "Yes - running 10 minutes late, see you at 5:10.",
  "approved": true,
  "auto": false,
  "contact_rule": {
    "number": "+2348031234567",
    "category": "important",
    "auto_reply_enabled": false,
    "auto_reply_mode": "off",
    "template": ""
  }
}
```

Requirements:

1. `text` is the **exact final text** the user saw and approved. Send exactly that; do
   not regenerate, trim, or replace it.
2. `auto: false` means a human pressed APPROVE in the app. Treat that as explicit
   consent for this one message only.
3. `auto: true` is used for a configured template; the workflow must still verify the
   contact has auto-reply enabled and server authorisation. Never use it to bypass
   approval on an AI draft.
4. Response **must** report the real send outcome:

```json
{
  "ok": true,
  "data": {
    "message_id": "wa-1a2b3c4d5e6f7a8b",
    "status": "sent",
    "provider_message_id": "3EB0C767D8F1",
    "sent_at": "2026-10-26T09:15:02.120Z"
  }
}
```

If Evolution API accepted the message but delivery is not confirmed, use `"sending"`.
If it failed, use `"failed"` and include `"error"`. **Do not return `sent` unless
Evolution API accepted the send.** The app displays the exact status.

### 4.4 `reply.cancel`

```json
{ "conversation_id": "wa:+2348031234567", "message_id": "wa-...", "reason": "user cancelled" }
```

Cancels a pending draft/approval server-side. Response `{ "ok": true }`. If the message
was already sent, return `ok: false` with `"status": "sent"`; the app will show that.

### 4.5 `reply.status`

The timeout resolver. Must be safe to call repeatedly and must never send.

```json
{ "message_id": "wa-...", "conversation_id": "wa:+2348031234567", "request_id": "send-wa-...-r0" }
```

Response:

```json
{
  "ok": true,
  "data": {
    "message_id": "wa-...",
    "status": "sent",
    "provider_message_id": "3EB0...",
    "request_id": "send-wa-...-r0",
    "error": "",
    "updated_at": "2026-10-26T09:15:02.120Z"
  }
}
```

`status` is the same vocabulary as the app states. Returning `sending` is valid; the
app keeps polling only when the user asks.

### 4.6 `conversation.history`

```json
{ "sender": "+2348031234567", "limit": 20 }
```

Response:

```json
{
  "ok": true,
  "data": {
    "messages": [
      {
        "message_id": "wa-...",
        "conversation_id": "wa:+2348031234567",
        "sender": "+2348031234567",
        "sender_name": "Ada",
        "text": "Are we still meeting at 5?",
        "message_type": "text",
        "timestamp": 1793000000000,
        "inbound": true,
        "category": "important",
        "state": "received"
      }
    ]
  }
}
```

Newest first or oldest first both parse; the app sorts locally. Timestamps are epoch
milliseconds.

### 4.7 `contact.settings.update`

The app saves locally first, then mirrors the rule. The **response is authoritative for
authorisation**:

```json
{
  "number": "+2348031234567",
  "name": "Ada",
  "category": "auto_reply",
  "auto_reply_enabled": true,
  "auto_reply_mode": "template",
  "template": "I'll call you back shortly.",
  "announce": true,
  "priority": false,
  "requested_authorization": true
}
```

Response:

```json
{
  "ok": true,
  "data": {
    "number": "+2348031234567",
    "category": "auto_reply",
    "auto_reply_enabled": true,
    "auto_reply_mode": "template",
    "auto_reply_authorized": true
  }
}
```

- `auto_reply_authorized` (or `authorized`) is the server's decision. The app stores it
  and **will not auto-reply without it**.
- If the workflow cannot authorise the contact, return `false`. The app keeps the rule
  for notifications but will not auto-reply.
- Disabling or demoting a contact must revoke authorisation; the app also clears its
  local grant when the user disables the rule.
- Category values: `important`, `auto_reply`, `other`. Mode values: `off`, `template`,
  `ai`.

### 4.8 `memory.search`

```json
{ "query": "meeting with Ada", "limit": 10 }
```

Response: `{ "ok": true, "data": { "results": [ { "text": "...", "score": 0.82, "source": "memory" } ] } }`.

### 4.9 `task.create`

```json
{ "title": "Call Ada back", "due": "2026-10-26T17:00:00Z", "source_message_id": "wa-..." }
```

Response: `{ "ok": true, "data": { "task_id": "..." } }`.

### 4.10 `device.register`

Called by **Message Settings → Register this device**. Authenticated by the same token.

```json
{
  "device_name": "Xiaomi 23106RN0DA",
  "platform": "android",
  "app_version": "3.2",
  "push_provider": "none",
  "push_token": "",
  "notification_capture": true
}
```

Response: `{ "ok": true, "data": { "device_id": "...", "push_provider": "none" } }`.

The app sends `push_provider: "none"` because this build has no Firebase dependency.
See §7 for adding FCM later.

---

## 5. Auto-reply authorisation (server side)

The app enforces a conservative policy before asking (`AutoReplyPolicy`), and the
workflow must enforce it again:

1. Global switch on (`auto_reply_global`), per-contact switch on, category `auto_reply`.
2. Server authorisation on for that number.
3. Not a group, not a message from the account owner.
4. Message does not look like OTP, bank, payment, password or a link request. The app
   ignores these locally; the server must also refuse.
5. Template mode sends only the stored approved template. AI mode may generate, but the
   workflow decides whether to send or return a draft.
6. Every decision is logged (rule, template/generation, result). The app writes an audit
   line on the message (`selected rule` + result).
7. Important ≠ auto-reply. An `important` contact with auto-reply off must never be
   answered automatically.

Kill switch: the app's master switch stops all auto-reply immediately on the device.
The workflow must also expose a server-side switch; the app cannot stop a send that the
server has already begun.

---

## 6. Reply safety invariants (app-enforced, must not be weakened)

- `reply.generate` mode `approval` never sends.
- Reading a draft, opening the screen, dictating instructions, or `message.acknowledge`
  is never permission to send.
- `reply.approve` is sent only after a confirmation dialog that displays the exact text.
- `reply myself` opens `https://wa.me/<number>`; it never calls `reply.approve`.
- Timeout/failure → `failed` + "check status"; no automatic resend.
- Only a backend `status: sent` renders `SENT`.

---

## 7. Push notifications

**Current truth:** the app has no Firebase dependency. Incoming WhatsApp messages are
captured by the existing `NotificationListener` (the user granted notification access),
stored locally, and shown as a local notification. That path works while the app process
is alive and survives reopening because the message store is on disk. It is not a
guarantee after the OS kills the notification listener; the system restarts listener
services, but OEM battery managers can delay that.

Do not tell the user the app is "receiving push" today.

### Adding FCM later (backend + app work)

1. Backend: store the token from `device.register` (`push_provider: "fcm"`,
   `push_token: "<token>"`). Send **data-only** messages via FCM HTTP v1 from the same
   workflow (HTTP Request node). Do not include message bodies in the payload.
2. Minimal payload:

```json
{
  "type": "important_message",
  "message_id": "wa-...",
  "conversation_id": "wa:+2348031234567",
  "priority": "high"
}
```

   The app then fetches the full message with `conversation.history` or `reply.status`
   using its authenticated token. No OpenAI/Evolution/Supabase key ever reaches the
   device.
3. App: add `firebase-messaging`, a `FirebaseMessagingService`, POST_NOTIFICATIONS
   handling, and upload the token with `device.register`. This is deliberately **not**
   implemented here because the dependency is not available offline and shipping a
   half-wired service would be misleading.
4. If the changed app/backend is deployed, forbidden push of secrets: never put
   service-role keys, provider API keys, or full message text in the FCM payload.

---

## 8. Database migrations (PostgreSQL / Supabase)

Run once, idempotently. These tables support the contract; adjust names to match the
existing schema instead of duplicating what already exists.

```sql
-- Contacts and their rules. `number` is E.164 with a leading +.
create table if not exists wa_contacts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null,
  number text not null,
  display_name text not null default '',
  category text not null default 'other'
    check (category in ('important','auto_reply','other')),
  announce boolean not null default true,
  priority boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, number)
);

-- Auto-reply permission is its own row: it is never implied by category.
create table if not exists wa_auto_reply_rules (
  contact_id uuid primary key references wa_contacts(id) on delete cascade,
  enabled boolean not null default false,
  mode text not null default 'off' check (mode in ('off','template','ai')),
  template text not null default '',
  authorized boolean not null default false,
  authorized_by uuid,
  authorized_at timestamptz,
  updated_at timestamptz not null default now()
);

create table if not exists wa_conversations (
  id text primary key,                 -- e.g. wa:+2348031234567
  user_id uuid not null,
  contact_number text not null,
  last_message_at timestamptz,
  unique (user_id, contact_number)
);

create table if not exists wa_messages (
  id text primary key,                 -- app message_id / Evolution id
  conversation_id text not null references wa_conversations(id) on delete cascade,
  user_id uuid not null,
  direction text not null check (direction in ('in','out')),
  sender text not null,
  body text not null default '',
  message_type text not null default 'text',
  provider_message_id text,
  received_at timestamptz not null default now(),
  state text not null default 'received'
    check (state in ('received','pending_approval','approved','sending','sent','failed','cancelled')),
  created_at timestamptz not null default now()
);
create index if not exists idx_wa_messages_conversation on wa_messages(conversation_id, received_at desc);

-- Idempotency ledger: one row per client request_id.
create table if not exists app_requests (
  request_id text primary key,
  device_id text not null,
  action text not null,
  message_id text,
  response jsonb,
  created_at timestamptz not null default now(),
  completed_at timestamptz
);

create table if not exists device_registrations (
  device_id text primary key,
  user_id uuid not null,
  device_name text not null default '',
  platform text not null default 'android',
  app_version text not null default '',
  push_provider text not null default 'none',
  push_token text,
  notification_capture boolean not null default false,
  enabled boolean not null default true,
  registered_at timestamptz not null default now(),
  last_seen_at timestamptz
);

create table if not exists auto_reply_events (
  id uuid primary key default gen_random_uuid(),
  message_id text not null references wa_messages(id) on delete cascade,
  contact_id uuid references wa_contacts(id) on delete set null,
  rule text not null,
  decision text not null check (decision in ('ignore','template','ai')),
  result text not null default '',
  created_at timestamptz not null default now()
);
```

Important: `wa_auto_reply_rules.authorized` starts `false`. Only a server-side action
(not a client-supplied field alone) may set it to `true`.

---

## 9. n8n implementation checklist (existing workflow)

1. Add **one** Webhook node: `POST`, path `app-api`, response mode "Using Respond to
   Webhook node" (or "When last node finishes" with a single response shape).
2. Add an IF/Code node that validates:
   - method POST, JSON object, `version === 1`;
   - `request_id`, `device_id`, `action`, `timestamp` present;
   - `Authorization` header equals the configured token (use a credential, not a literal);
   - device row is enabled.
3. Add a Switch node on `action` with the ten actions in §4. No new webhook per action.
4. Before any side effect, look up `app_requests.request_id`. If present and completed,
   return the stored response.
5. `reply.generate` (approval mode): call the existing AI/memory sub-nodes and return a
   draft; do not call Evolution send.
6. `reply.approve`: re-check contact rule + authorisation, then call the existing
   Evolution send node. Persist `provider_message_id` and `state`. Store the response
   under `request_id`.
7. `reply.status`: read `wa_messages.state` by message id; return it.
8. `contact.settings.update`: upsert contact + auto-reply rule; return
   `auto_reply_authorized` based on the server rule and any server-side policy. Never
   trust `requested_authorization`.
9. `device.register`: upsert `device_registrations`, return the device id.
10. Respond with JSON. Never respond 200 with an empty body for `reply.approve` unless
    the send result truly is unknown - and then the app shows "unconfirmed".
11. Keep the Evolution inbound webhook and its processing exactly as they are, and route
    its message writes through the same `wa_messages`/`wa_conversations` tables so the
    app and the workflow see one conversation.

### Current verification

| Check | Result |
| :-- | :-- |
| `GET /webhook/health` | 404 on 2026-10-26 (from this workstation) |
| `POST /webhook/app-api` | Not deployed; the app reports HTTP 404 |
| Evolution inbound | Unchanged; not exercised in this task |
| APK side | Builds, installs over 3.0 (same debug key); logic self-checks pass |

No claim is made that messages are being sent through n8n until the endpoint returns
real `reply.approve`/`reply.status` responses.
