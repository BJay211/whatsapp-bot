'use strict';

/**
 * Bonjee Friday relay.
 *
 * Friday (the laptop) long-polls this service from behind any NAT. The phone
 * calls /r/<laptopId>/v1/... and the relay carries the request to the laptop's
 * existing TLS service, then carries the response back.
 *
 * Trust model:
 *  - The laptop id is public routing information.
 *  - The laptop proves ownership with a 256-bit key in a header. The relay
 *    stores only its SHA-256 hash, and only in memory: a restart forgets it and
 *    the laptop re-registers on its next poll.
 *  - The relay never sees a phone device token in a usable form on disk and
 *    keeps no logs of bodies, tokens or pairing codes.
 *  - Friday still authenticates every phone request. This service has no
 *    command logic, no shell and no filesystem access.
 *
 * Zero dependencies: Node's built-in http/crypto only.
 */

const http = require('http');
const crypto = require('crypto');

const PORT = Number(process.env.PORT || 8787);
const MAX_BODY = Math.max(64 * 1024, Number(process.env.RELAY_MAX_BODY_KB || 2048) * 1024);
const POLL_WAIT_MS = Math.min(29_000, Math.max(1_000, Number(process.env.POLL_WAIT_MS || 25_000)));
const REQUEST_TIMEOUT_MS = Math.min(120_000, Math.max(5_000, Number(process.env.REQUEST_TIMEOUT_MS || 35_000)));
const PAIR_TIMEOUT_MS = Math.min(30_000, Math.max(5_000, Number(process.env.PAIR_TIMEOUT_MS || 12_000)));
const ONLINE_MS = 65_000;
const MAX_QUEUE = 32;

/** laptopId -> session */
const laptops = new Map();
/** ip -> [timestamps] for pairing attempts */
const pairAttempts = new Map();

const startedAt = Date.now();
let totalForwarded = 0;

function sha256(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

function sameKey(a, b) {
  const left = Buffer.from(String(a), 'hex');
  const right = Buffer.from(String(b), 'hex');
  return left.length === right.length && crypto.timingSafeEqual(left, right);
}

function validLaptopId(id) {
  return typeof id === 'string' && /^lap[a-z0-9]{8,40}$/.test(id);
}

function clientIp(req) {
  const forwarded = String(req.headers['x-forwarded-for'] || '').split(',')[0].trim();
  return forwarded || req.socket.remoteAddress || 'unknown';
}

function sendJson(res, status, value, extraHeaders = {}) {
  if (res.writableEnded) return;
  const body = Buffer.from(JSON.stringify(value));
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': body.length,
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
    ...extraHeaders,
  });
  res.end(body);
}

function sendRaw(res, status, contentType, body, extraHeaders = {}) {
  if (res.writableEnded) return;
  res.writeHead(status, {
    'content-type': contentType || 'application/octet-stream',
    'content-length': body.length,
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
    ...extraHeaders,
  });
  res.end(body);
}

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) {
        reject(Object.assign(new Error('body too large'), { status: 413 }));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function authLaptop(req, res) {
  const id = String(req.headers['x-friday-relay-id'] || '').trim();
  const key = String(req.headers['x-friday-relay-key'] || '').trim();
  if (!validLaptopId(id)) {
    sendJson(res, 400, { error: 'invalid_laptop_id' });
    return null;
  }
  if (key.length < 24) {
    sendJson(res, 401, { error: 'missing_relay_key' });
    return null;
  }

  const keyHash = sha256(key);
  let session = laptops.get(id);
  if (!session) {
    session = {
      id,
      keyHash,
      lastSeen: Date.now(),
      queue: [],
      pendingPoll: null,
      requests: new Map(),
    };
    laptops.set(id, session);
  } else if (!sameKey(session.keyHash, keyHash)) {
    sendJson(res, 401, { error: 'relay_key_mismatch' });
    return null;
  }
  session.lastSeen = Date.now();
  return session;
}

function isOnline(session) {
  return Boolean(session) && (session.pendingPoll !== null || Date.now() - session.lastSeen < ONLINE_MS);
}

function endPoll204(session) {
  if (!session.pendingPoll) return;
  const { res, timer } = session.pendingPoll;
  session.pendingPoll = null;
  clearTimeout(timer);
  if (!res.writableEnded) {
    res.writeHead(204, { 'cache-control': 'no-store' });
    res.end();
  }
}

function deliver(session, envelope) {
  if (session.pendingPoll) {
    const { res, timer } = session.pendingPoll;
    session.pendingPoll = null;
    clearTimeout(timer);
    sendJson(res, 200, envelope);
    return true;
  }
  if (session.queue.length >= MAX_QUEUE) return false;
  session.queue.push(envelope);
  return true;
}

function buildEnvelope(method, path, headers, body) {
  return {
    id: crypto.randomBytes(18).toString('hex'),
    method,
    path,
    headers,
    body_b64: body && body.length ? body.toString('base64') : '',
    at: Date.now(),
  };
}

function allowedHeaders(req) {
  const out = {};
  for (const name of ['authorization', 'content-type', 'accept', 'x-request-id', 'x-friday-device']) {
    const value = req.headers[name];
    if (typeof value === 'string' && value.length > 0 && value.length < 4096) out[name] = value;
  }
  return out;
}

async function handlePoll(req, res) {
  const session = authLaptop(req, res);
  if (!session) return;

  if (session.queue.length > 0) {
    const envelope = session.queue.shift();
    sendJson(res, 200, envelope);
    return;
  }

  endPoll204(session);
  const timer = setTimeout(() => {
    if (session.pendingPoll && session.pendingPoll.res === res) endPoll204(session);
  }, POLL_WAIT_MS);
  session.pendingPoll = { res, timer };

  req.on('close', () => {
    if (session.pendingPoll && session.pendingPoll.res === res) {
      clearTimeout(timer);
      session.pendingPoll = null;
    }
  });
}

async function handleRespond(req, res) {
  const session = authLaptop(req, res);
  if (!session) return;

  let payload;
  try {
    const body = await readBody(req, MAX_BODY);
    payload = JSON.parse(body.toString('utf8'));
  } catch (error) {
    sendJson(res, error.status || 400, { error: 'invalid_json' });
    return;
  }

  const id = String(payload.id || '');
  const pending = session.requests.get(id);
  if (!pending) {
    // A late or duplicate response is reported, never applied twice.
    sendJson(res, 409, { error: 'unknown_or_answered_request' });
    return;
  }
  session.requests.delete(id);
  clearTimeout(pending.timer);

  let body = Buffer.alloc(0);
  try {
    body = payload.body_b64 ? Buffer.from(String(payload.body_b64), 'base64') : Buffer.alloc(0);
  } catch {
    body = Buffer.alloc(0);
  }
  const headers = payload.headers && typeof payload.headers === 'object' ? payload.headers : {};
  const status = Number(payload.status) || 502;

  pending.resolve({
    status: status >= 100 && status <= 599 ? status : 502,
    contentType: typeof headers['content-type'] === 'string' ? headers['content-type'] : 'application/json',
    body,
  });
  totalForwarded += 1;
  sendJson(res, 200, { ok: true });
}

async function handlePhone(req, res, laptopId, targetPath) {
  const session = laptops.get(laptopId);
  if (!isOnline(session)) {
    sendJson(res, 503, {
      error: 'laptop_offline',
      detail: 'Friday is not connected to the relay. Check the laptop and try again.',
    });
    return;
  }

  let body;
  try {
    body = await readBody(req, MAX_BODY);
  } catch (error) {
    sendJson(res, error.status || 400, { error: 'request_too_large' });
    return;
  }

  const envelope = buildEnvelope(req.method, targetPath, allowedHeaders(req), body);

  const response = await new Promise((resolve) => {
    const timer = setTimeout(() => {
      session.requests.delete(envelope.id);
      // A timed-out request must also leave the delivery queue, otherwise a
      // late laptop poll would receive it and answer a phone that has gone.
      const queued = session.queue.indexOf(envelope);
      if (queued >= 0) session.queue.splice(queued, 1);
      resolve(null);
    }, REQUEST_TIMEOUT_MS);

    session.requests.set(envelope.id, {
      id: envelope.id,
      timer,
      resolve: (value) => {
        clearTimeout(timer);
        resolve(value);
      },
    });

    if (!deliver(session, envelope)) {
      clearTimeout(timer);
      session.requests.delete(envelope.id);
      resolve({ status: 429, contentType: 'application/json', body: Buffer.from(JSON.stringify({ error: 'relay_queue_full' })) });
    }
  });

  if (!response) {
    sendJson(res, 504, { error: 'laptop_timeout', detail: 'Friday did not answer in time.' });
    return;
  }
  sendRaw(res, response.status, response.contentType, response.body);
}

async function forwardPair(session, body, contentType) {
  return new Promise((resolve) => {
    const envelope = buildEnvelope('POST', '/v1/pair', { 'content-type': contentType }, body);
    const timer = setTimeout(() => {
      session.requests.delete(envelope.id);
      const queued = session.queue.indexOf(envelope);
      if (queued >= 0) session.queue.splice(queued, 1);
      resolve({ ok: false, status: 504, body: Buffer.alloc(0) });
    }, PAIR_TIMEOUT_MS);

    session.requests.set(envelope.id, {
      id: envelope.id,
      timer,
      resolve: (value) => {
        clearTimeout(timer);
        resolve({ ok: value.status === 200, status: value.status, body: value.body });
      },
    });
    if (!deliver(session, envelope)) {
      clearTimeout(timer);
      session.requests.delete(envelope.id);
      resolve({ ok: false, status: 429, body: Buffer.alloc(0) });
    }
  });
}

async function handlePair(req, res) {
  const ip = clientIp(req);
  const now = Date.now();
  const recent = (pairAttempts.get(ip) || []).filter((at) => now - at < 60_000);
  recent.push(now);
  pairAttempts.set(ip, recent);
  if (recent.length > 20) {
    sendJson(res, 429, { error: 'too_many_pairing_attempts', detail: 'Wait a minute and try again.' });
    return;
  }

  let body;
  try {
    body = await readBody(req, 16 * 1024);
    JSON.parse(body.toString('utf8'));
  } catch (error) {
    sendJson(res, 400, { error: 'invalid_json' });
    return;
  }

  const contentType = String(req.headers['content-type'] || 'application/json');
  // Include laptops seen in the last minute, not only the one holding a poll
  // open right now: there is a tiny gap between a poll being answered and the
  // laptop polling again.
  const online = [...laptops.values()].filter((session) => isOnline(session));
  if (online.length === 0) {
    sendJson(res, 503, {
      error: 'no_laptops',
      detail: 'No laptop is connected to the relay right now. Open Friday on the laptop with the relay enabled.',
    });
    return;
  }

  const results = await Promise.all(online.map((session) => forwardPair(session, body, contentType)));
  const winnerIndex = results.findIndex((result) => result.ok);
  if (winnerIndex < 0) {
    const status = results.some((r) => r.status === 403) ? 403 : 410;
    sendJson(res, status, {
      error: 'pairing_failed',
      detail: 'No connected laptop accepted that code. Codes are single use and expire; generate a new one on the laptop.',
    });
    return;
  }

  const winner = results[winnerIndex];
  let parsed = null;
  try {
    parsed = JSON.parse(winner.body.toString('utf8'));
  } catch {
    parsed = null;
  }
  if (parsed && typeof parsed === 'object') {
    parsed.relay_laptop = online[winnerIndex].id;
    sendJson(res, 200, parsed);
  } else {
    sendRaw(res, 200, 'application/json', winner.body);
  }
}

function health(res) {
  const now = Date.now();
  const all = [...laptops.values()];
  sendJson(res, 200, {
    ok: true,
    service: 'bonjee-friday-relay',
    uptime_seconds: Math.round((now - startedAt) / 1000),
    laptops: all.length,
    online: all.filter((session) => session.pendingPoll !== null).length,
    forwarded: totalForwarded,
  });
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url || '/', 'http://relay.local');

    if (url.pathname === '/health' && req.method === 'GET') return health(res);

    if (url.pathname === '/laptop/poll' && req.method === 'POST') return await handlePoll(req, res);
    if (url.pathname === '/laptop/respond' && req.method === 'POST') return await handleRespond(req, res);

    if ((url.pathname === '/v1/pair' || url.pathname === '/pair') && req.method === 'POST') {
      return await handlePair(req, res);
    }

    const match = url.pathname.match(/^\/r\/([^/]+)(\/.*)$/);
    if (match) {
      const laptopId = match[1];
      const targetPath = match[2] + url.search;
      if (!validLaptopId(laptopId)) {
        sendJson(res, 400, { error: 'invalid_laptop_id' });
        return;
      }
      if (!targetPath.startsWith('/v1/') || targetPath.includes('..') || targetPath.length > 2048 ||
          !['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].includes(req.method)) {
        sendJson(res, 400, { error: 'path_not_allowed' });
        return;
      }
      return await handlePhone(req, res, laptopId, targetPath);
    }

    if (url.pathname === '/' && req.method === 'GET') {
      sendJson(res, 200, { service: 'bonjee-friday-relay', health: '/health' });
      return;
    }

    sendJson(res, 404, { error: 'not_found' });
  } catch (error) {
    sendJson(res, 500, { error: 'relay_error' });
  }
});

// Render terminates TLS; the relay itself listens in plain HTTP on the private
// interface. Never log headers or bodies: they contain device tokens.
server.listen(PORT, '0.0.0.0', () => {
  console.log(`[relay] listening on ${PORT} (poll ${POLL_WAIT_MS} ms, request ${REQUEST_TIMEOUT_MS} ms)`);
});

function sweep() {
  const now = Date.now();
  for (const [id, session] of laptops) {
    if (session.pendingPoll !== null) continue;
    if (now - session.lastSeen > 10 * 60_000 && session.requests.size === 0 && session.queue.length === 0) {
      laptops.delete(id);
    }
  }
  for (const [ip, attempts] of pairAttempts) {
    const recent = attempts.filter((at) => now - at < 60_000);
    if (recent.length === 0) pairAttempts.delete(ip);
    else pairAttempts.set(ip, recent);
  }
}
setInterval(sweep, 60_000).unref();

module.exports = { server };
