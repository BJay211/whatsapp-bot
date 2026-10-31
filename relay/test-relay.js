'use strict';

/**
 * Local harness for relay/server.js.
 *
 * Run: node test-relay.js
 *
 * It starts the real relay on a random localhost port and plays both the phone
 * and the laptop. Every assertion is on real HTTP behaviour; nothing is mocked
 * inside the server.
 */

const assert = require('assert');
const path = require('path');
const { spawn } = require('child_process');

const PORT = 18000 + Math.floor(Math.random() * 1000);
const BASE = `http://127.0.0.1:${PORT}`;
const ID = 'lap' + 'a'.repeat(10);
const ID2 = 'lap' + 'b'.repeat(10);
const KEY = 'test-relay-key-' + 'k'.repeat(32);
const WRONG_KEY = 'test-relay-key-' + 'z'.repeat(32);

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function laptopHeaders(key = KEY) {
  return {
    'x-friday-relay-id': ID,
    'x-friday-relay-key': key,
  };
}

async function pollOnce(key = KEY, timeout = 6000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeout);
  try {
    const response = await fetch(`${BASE}/laptop/poll?wait=25`, {
      method: 'POST',
      headers: { ...laptopHeaders(key), 'content-type': 'application/json' },
      body: '{}',
      signal: controller.signal,
    });
    if (response.status === 204) return null;
    assert.strictEqual(response.status, 200, `poll status ${response.status}`);
    return await response.json();
  } finally {
    clearTimeout(timer);
  }
}

async function respond(envelope, status, payload) {
  return fetch(`${BASE}/laptop/respond`, {
    method: 'POST',
    headers: { ...laptopHeaders(), 'content-type': 'application/json' },
    body: JSON.stringify({
      id: envelope.id,
      status,
      headers: { 'content-type': 'application/json' },
      body_b64: Buffer.from(JSON.stringify(payload)).toString('base64'),
    }),
  });
}

async function waitForHealth(attempts = 100) {
  for (let i = 0; i < attempts; i += 1) {
    try {
      const response = await fetch(`${BASE}/health`);
      if (response.ok) return await response.json();
    } catch {
      // not listening yet
    }
    await sleep(100);
  }
  throw new Error('relay did not become healthy');
}

const results = [];
function pass(name, detail) {
  results.push({ name, ok: true, detail });
  console.log(`  PASS  ${name}${detail ? ' — ' + detail : ''}`);
}
function check(name, fn) {
  return Promise.resolve()
    .then(fn)
    .then((detail) => pass(name, detail));
}

async function main() {
  const child = spawn(process.execPath, [path.join(__dirname, 'server.js')], {
    cwd: __dirname,
    env: {
      ...process.env,
      PORT: String(PORT),
      POLL_WAIT_MS: '1500',
      REQUEST_TIMEOUT_MS: '2500',
      PAIR_TIMEOUT_MS: '2000',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  let stderr = '';
  child.stderr.on('data', (chunk) => {
    stderr += chunk.toString();
  });

  try {
    const health = await waitForHealth();
    assert.strictEqual(health.ok, true);
    pass('health endpoint', `service=${health.service}`);

    await check('says the laptop is offline before it registers', async () => {
      const response = await fetch(`${BASE}/r/${ID2}/v1/ping`);
      assert.strictEqual(response.status, 503);
      const body = await response.json();
      assert.strictEqual(body.error, 'laptop_offline');
      return 'HTTP 503';
    });

    await check('delivers a phone request to a long-polling laptop', async () => {
      const poll = pollOnce();
      await sleep(200);
      const phone = fetch(`${BASE}/r/${ID}/v1/status`, {
        headers: {
          authorization: 'Bearer device-token',
          'x-request-id': 'req-123',
          accept: 'application/json',
        },
      });

      const envelope = await poll;
      assert.ok(envelope, 'laptop received an envelope');
      assert.strictEqual(envelope.method, 'GET');
      assert.strictEqual(envelope.path, '/v1/status');
      assert.strictEqual(envelope.headers.authorization, 'Bearer device-token');
      assert.strictEqual(envelope.headers['x-request-id'], 'req-123');

      const ack = await respond(envelope, 200, { ok: true, machine: 'TEST-PC' });
      assert.strictEqual(ack.status, 200);

      const answer = await phone;
      assert.strictEqual(answer.status, 200);
      const body = await answer.json();
      assert.strictEqual(body.machine, 'TEST-PC');

      const again = await respond(envelope, 200, { ok: true });
      assert.strictEqual(again.status, 409, 'duplicate response is refused');
      const duplicate = await again.json();
      assert.strictEqual(duplicate.error, 'unknown_or_answered_request');
      return 'request, response and duplicate protection';
    });

    await check('rejects a mismatched relay key after registration', async () => {
      const response = await fetch(`${BASE}/laptop/poll`, {
        method: 'POST',
        headers: { ...laptopHeaders(WRONG_KEY), 'content-type': 'application/json' },
        body: '{}',
      });
      assert.strictEqual(response.status, 401);
      const body = await response.json();
      assert.strictEqual(body.error, 'relay_key_mismatch');
      return 'HTTP 401';
    });

    await check('times out a laptop that does not answer, then stays clean', async () => {
      const phone = await fetch(`${BASE}/r/${ID}/v1/tasks`);
      assert.strictEqual(phone.status, 504);
      const body = await phone.json();
      assert.strictEqual(body.error, 'laptop_timeout');

      const next = await pollOnce(KEY, 4000);
      assert.strictEqual(next, null, 'no stale request is delivered after a timeout');
      return 'HTTP 504 and empty queue';
    });

    await check('refuses paths outside /v1/', async () => {
      const response = await fetch(`${BASE}/r/${ID}/etc/passwd`);
      assert.strictEqual(response.status, 400);
      const body = await response.json();
      assert.strictEqual(body.error, 'path_not_allowed');
      return 'HTTP 400';
    });

    await check('refuses an invalid laptop id', async () => {
      const response = await fetch(`${BASE}/r/not-a-laptop/v1/ping`);
      assert.strictEqual(response.status, 400);
      const body = await response.json();
      assert.strictEqual(body.error, 'invalid_laptop_id');
      return 'HTTP 400';
    });

    await check('pairs through the relay and reports relay_laptop', async () => {
      const poll = pollOnce(KEY, 6000);
      await sleep(200);
      const pair = fetch(`${BASE}/v1/pair`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ code: 'ABCDEFGH', device_name: 'Test phone', platform: 'android' }),
      });

      const envelope = await poll;
      assert.ok(envelope, 'pairing request reached the laptop');
      assert.strictEqual(envelope.path, '/v1/pair');
      const decoded = JSON.parse(Buffer.from(envelope.body_b64, 'base64').toString('utf8'));
      assert.strictEqual(decoded.code, 'ABCDEFGH');

      const ack = await respond(envelope, 200, {
        token: 'device-token',
        device_id: 'dev-1',
        device_name: 'Test phone',
        protocol: '1.0',
      });
      assert.strictEqual(ack.status, 200);

      const answer = await pair;
      assert.strictEqual(answer.status, 200);
      const body = await answer.json();
      assert.strictEqual(body.relay_laptop, ID);
      assert.strictEqual(body.token, 'device-token');
      return `relay_laptop=${body.relay_laptop}`;
    });

    await check('limits pairing attempts per IP', async () => {
      let limited = 0;
      for (let i = 0; i < 30; i += 1) {
        const response = await fetch(`${BASE}/v1/pair`, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: 'not json',
        });
        if (response.status === 429) limited += 1;
        else assert.strictEqual(response.status, 400);
      }
      assert.ok(limited >= 1, 'at least one attempt was rate limited');
      return `${limited} of 30 limited`;
    });

    await check('health never exposes keys or tokens', async () => {
      const response = await fetch(`${BASE}/health`);
      const text = await response.text();
      assert.ok(!text.includes(KEY));
      assert.ok(!text.includes('device-token'));
      assert.ok(!text.includes('ABCDEFGH'));
      return 'no secrets in response';
    });

    console.log(`\n${results.length} relay checks passed.`);
    child.kill();
    process.exitCode = 0;
  } catch (error) {
    console.error('\nRELAY TEST FAILED');
    console.error(error && error.stack ? error.stack : error);
    if (stderr.trim()) console.error('\nrelay stderr:\n' + stderr.trim());
    child.kill();
    process.exitCode = 1;
  }
}

main();
