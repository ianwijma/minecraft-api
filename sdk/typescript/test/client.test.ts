/**
 * TS SDK tests against a local stub server (node --experimental-strip-types).
 */

import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import type { Server } from 'node:http';
import { mkdtempSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import { MapiClient, MapiError, parseDiscovery } from '../src/client.ts';

const TOKEN = 'ts-sdk-token-0123456789';
let seq = 0;
const idempotency = new Map();

function startStub(): Promise<Server> {
  const server = createServer((request, response) => {
    let body = '';
    request.on('data', (chunk) => (body += chunk));
    request.on('end', () => {
      const authorized = request.headers.authorization === `Bearer ${TOKEN}`;
      const send = (status: number, payload: object) => {
        response.writeHead(status, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify(payload));
      };
      if (!authorized) {
        send(401, { error: { code: 'UNAUTHORIZED', message: 'nope', requestId: 'r' } });
        return;
      }
      const url = new URL(request.url ?? '/', 'http://127.0.0.1');
      if (url.pathname === '/api/v1/health') {
        send(200, { protocolVersion: 1, status: 'ok' });
      } else if (url.pathname.startsWith('/api/v1/tasks/')) {
        const id = url.pathname.split('/').pop() ?? '';
        send(200, { id, state: 'succeeded', partialEffects: [], cleanup: [] });
      } else if (url.pathname === '/api/v1/events') {
        const after = Number(url.searchParams.get('after') ?? '0');
        const events = after >= 3 ? [] : [4, 5].map((s) => ({
          seq: s, eventType: `t.${s}`, source: 'instrumented', wallClock: 1,
          monotonicNanos: s, processSessionId: 'p', data: {},
        }));
        send(200, { protocolVersion: 1, events, truncated: false, headSeq: 10, oldestSeq: 1 });
      } else if (url.pathname === '/api/v1/leases') {
        send(200, { id: 'lease-1', lease: JSON.parse(body || '{}').lease, state: 'held' });
      } else if (url.pathname === '/api/v1/server/commands/execute') {
        send(200, { result: 1, success: true, feedback: [], command: JSON.parse(body || '{}').command });
      } else if (url.pathname === '/api/v1/tasks' && request.method === 'POST') {
        const parsed = body ? JSON.parse(body) : {};
        const key = request.headers['idempotency-key'];
        if (typeof key === 'string' && idempotency.has(key)) {
          send(202, idempotency.get(key));
          return;
        }
        seq += 1;
        const task = { id: `t${seq}`, kind: parsed.kind, state: 'succeeded', partialEffects: [], cleanup: [] };
        if (typeof key === 'string') idempotency.set(key, task);
        response.writeHead(202, { Location: `/api/v1/tasks/${task.id}` });
        response.end(JSON.stringify(task));
      } else {
        send(404, { error: { code: 'NOT_FOUND', message: '', requestId: 'r' } });
      }
    });
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve(server));
  });
}

test('discovery parsing rejects untrusted input', () => {
  assert.throws(() => parseDiscovery({ schemaVersion: 2 }));
  assert.throws(() => parseDiscovery({
    schemaVersion: 1, instanceId: 'bad id!', processSessionId: 'x', pid: 1,
    readiness: 'http', api: { port: 1 },
  }));
  const ok = parseDiscovery({
    schemaVersion: 1, instanceId: 'client-2', processSessionId: 'abc', pid: 5,
    readiness: 'worldReady', api: { port: 25586 }, events: { port: 25587 },
  });
  assert.equal(ok.apiPort, 25586);
  assert.equal(ok.eventsPort, 25587);
});

test('auth, tasks with idempotency, and event cursors', async (t) => {
  const server = await startStub();
  t.after(() => server.close());
  const port = (server.address() as { port: number }).port;

  const client = MapiClient.fromUrl(`http://127.0.0.1:${port}`, tokenFile());
  assert.equal((await client.health()).status, 'ok');

  const bad = new MapiClient(`http://127.0.0.1:${port}`, 'wrong-token');
  await assert.rejects(bad.health(), (error: unknown) => {
    assert.ok(error instanceof MapiError);
    assert.equal(error.status, 401);
    return true;
  });

  const first = await client.createTask('wait-for-tick', { targetTick: 5 },
    { idempotencyKey: 'ts-key-1' });
  const replay = await client.createTask('wait-for-tick', { targetTick: 5 },
    { idempotencyKey: 'ts-key-1' });
  assert.equal(first.id, replay.id);
  const done = await client.waitTask(String(first.id));
  assert.equal(done.state, 'succeeded');

  const outcome = await client.executeCommand('say hi');
  assert.equal(outcome.success, true);
  const lease = await client.acquireLease('client.input', 5000);
  assert.equal(lease.state, 'held');

  const page = await client.eventsAfter(0);
  assert.ok((page.nextCursor as number) >= 1);
  const iterator = client.followEvents(0);
  const firstEvent = (await iterator.next()).value;
  assert.equal(firstEvent.seq, 4);
});

function tokenFile(): string {
  const dir = mkdtempSync(join(tmpdir(), 'mapi-ts-sdk-'));
  mkdirSync(join(dir, 'mcapi'), { recursive: true });
  writeFileSync(join(dir, 'mcapi', 'token'), `${TOKEN}\n`);
  return join(dir, 'mcapi', 'token');
}
