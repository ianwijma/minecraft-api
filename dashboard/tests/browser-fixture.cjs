const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');

const root = path.resolve(__dirname, '..');
const catalogPath = path.join(root, 'src/generated/operations.ts');
const source = fs.readFileSync(catalogPath, 'utf8');
const marker = 'export const operations: Operation[] = ';
const operations = JSON.parse(source.split(marker)[1].split(' satisfies Operation[];')[0]);
const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.txt': 'text/plain; charset=utf-8', '.woff2': 'font/woff2' };

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      server.removeListener('error', reject);
      resolve(server.address().port);
    });
  });
}

function createFixture(staticDir, basePath = '') {
  const state = { calls: [], mode: 'normal', nextLease: 1, issuedLeaseIds: [], activeStreams: 0, streamClosed: 0, jobPolls: 0, worldPolls: 0 };
  const api = http.createServer(async (req, res) => {
    const origin = req.headers.origin;
    if (origin && /^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(origin)) {
      res.setHeader('Access-Control-Allow-Origin', origin);
      res.setHeader('Vary', 'Origin');
      res.setHeader('Access-Control-Expose-Headers', 'Retry-After, X-MAPI-Protocol-Version');
    }
    if (req.method === 'OPTIONS') {
      const url = new URL(req.url, 'http://127.0.0.1');
      state.calls.push({ method: 'OPTIONS', path: url.pathname, query: Object.fromEntries(url.searchParams), body: undefined, authorization: req.headers.authorization, origin, requestedMethod: req.headers['access-control-request-method'], requestedHeaders: req.headers['access-control-request-headers'] });
      res.writeHead(origin ? 204 : 403, {
        'Access-Control-Allow-Methods': 'GET, POST',
        'Access-Control-Allow-Headers': 'Authorization, Content-Type',
        'Access-Control-Max-Age': '600',
      });
      return res.end();
    }
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    let body;
    try { body = chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : undefined; } catch { body = null; }
    const url = new URL(req.url, 'http://127.0.0.1');
    state.calls.push({ method: req.method, path: url.pathname, query: Object.fromEntries(url.searchParams), body, authorization: req.headers.authorization, origin });
    const json = (status, value, extra = {}) => {
      res.writeHead(status, { 'Content-Type': 'application/json', 'X-MAPI-Protocol-Version': '1', ...extra });
      res.end(JSON.stringify(value));
    };
    if (!['Bearer browser-fixture-token', 'Bearer dedicated-fixture-token', 'Bearer conflict-fixture-token'].includes(req.headers.authorization)) {
      return json(401, { error: { code: 'UNAUTHORIZED', message: 'A bearer token is required' } });
    }
    if (url.pathname === '/api/v1/health') {
      if (state.mode === 'delay-health-failure') {
        await new Promise(resolve => setTimeout(resolve, 1200));
        return json(503, { error: { code: 'FIXTURE_DELAYED_FAILURE', message: 'delayed fixture response' } });
      }
      return json(200, { protocolVersion: 1, status: 'ok' });
    }
    if (url.pathname === '/api/v1/info') return json(200, { modVersion: 'fixture', apiVersion: '1', minecraftVersion: '26.2', platform: 'fixture' });
    if (url.pathname === '/api/v1/operations') return json(200, { operations: operations.map(op => ({ id: op.id, summary: op.summary, requiredScopes: op.security.requiredScopes, destructive: op.security.destructive, sideEffectClass: op.security.sideEffectClass, requiresLease: op.security.requiresLease, supportedExecutionModes: op.security.supportedExecutionModes })) });
    if (url.pathname === '/api/v1/server/status') return json(200, { protocolVersion: 1, running: true, capturedAtEpochMs: Date.now(), startedAtEpochMs: Date.now() - 10_000, uptimeMs: 10_000, playerCount: 0, maxPlayers: 8, tickCount: 200, averageTickTimeMs: 1, motd: 'Browser fixture' });
    if (url.pathname === '/api/v1/server/world') {
      const phase = state.mode === 'world-loading' && state.worldPolls++ === 0 ? 'LOADING' : 'ACTIVE';
      if (phase === 'ACTIVE' && state.mode === 'world-loading') state.mode = 'normal';
      return json(200, { protocolVersion: 1, phase, worldSessionId: 'fixture-world-1', bridgeId: 'fixture', capabilities: ['client', 'world', 'tick-control', 'commands'], tickControl: true, worldQueries: true, commands: true, clocks: {} });
    }
    if (url.pathname === '/api/v1/client') return json(200, req.headers.authorization === 'Bearer dedicated-fixture-token'
      ? { protocolVersion: 1, bridgeId: 'none', capabilities: [], input: false, screenshots: false, window: false }
      : { protocolVersion: 1, bridgeId: 'fixture-client', capabilities: ['input', 'screenshots', 'window'], input: true, screenshots: true, window: true });
    if (url.pathname === '/api/v1/server/ticks') return json(200, { available: true, frozen: false, tickRate: 20 });
    if (url.pathname === '/api/v1/events/stream') {
      state.activeStreams++;
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', 'X-MAPI-Protocol-Version': '1' });
      const cursor = Number(url.searchParams.get('cursor') ?? 0);
      if (state.mode === 'event-flood') {
        for (let seq = cursor + 1; seq <= cursor + 260; seq++) res.write(`id: ${seq}\nevent: changed\ndata: ${JSON.stringify({ type: 'changed', seq })}\n\n`);
      } else {
        res.write(`:event-gap droppedUpTo=${cursor + 1}\n\n`);
        res.write(`id: ${cursor + 2}\nevent: world.phase\ndata: ${JSON.stringify({ type: 'world.phase', seq: cursor + 2, phase: 'ACTIVE' })}\n\n`);
      }
      const timer = setInterval(() => res.write(': keepalive\n\n'), 1000);
      res.on('close', () => { clearInterval(timer); state.activeStreams--; state.streamClosed++; });
      return;
    }
    if (url.pathname.endsWith('/control/lease') || url.pathname.endsWith('/ticks/lease')) {
      if (state.mode === 'lease-conflict' && !body?.leaseId) return json(409, { error: { code: 'LEASE_HELD', message: 'Another client holds this lease' } });
      const leaseId = body?.leaseId ?? `fixture-lease-${state.nextLease++}`;
      if (!body?.leaseId) state.issuedLeaseIds.push(leaseId);
      return json(200, { leaseId, expiresAtEpochMs: Date.now() + 300_000, renewed: Boolean(body?.leaseId) });
    }
    if (url.pathname === '/api/v1/client/worlds/delete' && state.mode === 'delete-error') return json(500, { error: { code: 'FIXTURE_FAILURE', message: 'fixture operation failed', details: { reason: 'requested by test' } } });
    if (url.pathname === '/api/v1/process/shutdown') {
      state.jobPolls = 0;
      return json(202, { jobId: 'fixture-job-1', state: 'PENDING' });
    }
    if (url.pathname === '/api/v1/client/worlds/create' || url.pathname === '/api/v1/client/worlds/load') {
      state.mode = 'world-loading';
      state.worldPolls = 0;
      return json(202, { accepted: true, levelId: body?.levelId });
    }
    if (url.pathname.startsWith('/api/v1/jobs/')) {
      state.jobPolls++;
      if (state.mode === 'job-poll-error') return json(503, { error: { code: 'JOB_STATUS_UNAVAILABLE', message: 'job polling failed' } });
      return json(200, { id: url.pathname.split('/').pop(), kind: 'shutdown', state: state.jobPolls < 2 ? 'RUNNING' : 'SUCCEEDED', cancellationRequested: false, submittedAtEpochMs: Date.now() - 2000, startedAtEpochMs: Date.now() - 1000, endedAtEpochMs: state.jobPolls < 2 ? undefined : Date.now(), result: { completed: state.jobPolls >= 2 } });
    }
    const operation = operations.find(op => new RegExp(`^${op.path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/\\\{[^}]+\\\}/g, '[^/]+')}$`).test(url.pathname) && op.method === req.method);
    if (!operation) return json(404, { error: { code: 'NOT_FOUND', message: 'No fixture route' } });
    return json(200, { ok: true, operationId: operation.id, query: Object.fromEntries(url.searchParams), body: body ?? null, phase: 'ACTIVE', worldSessionId: 'fixture-world-1' });
  });
  const app = http.createServer((req, res) => {
    let pathname;
    try { pathname = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname); } catch { res.writeHead(400); return res.end(); }
    const prefix = basePath ? `/${basePath.replace(/^\/+|\/+$/g, '')}` : '';
    if (prefix && !(pathname === prefix || pathname.startsWith(`${prefix}/`))) { res.writeHead(404); return res.end('Not found'); }
    pathname = pathname.slice(prefix.length) || '/';
    let file = path.resolve(staticDir, `.${pathname}`);
    if (!file.startsWith(path.resolve(staticDir) + path.sep) && file !== path.resolve(staticDir)) { res.writeHead(403); return res.end(); }
    try { if (fs.statSync(file).isDirectory()) file = path.join(file, 'index.html'); } catch {}
    fs.readFile(file, (error, data) => {
      if (error) { res.writeHead(404); return res.end('Not found'); }
      res.writeHead(200, { 'Content-Type': mime[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
      res.end(data);
    });
  });
  return {
    state,
    start: async () => {
      const apiPort = await listen(api), appPort = await listen(app);
      return { appUrl: `http://127.0.0.1:${appPort}${basePath ? `/${basePath.replace(/^\/+|\/+$/g, '')}` : ''}/`, apiUrl: `http://127.0.0.1:${apiPort}` };
    },
    close: async () => Promise.all([api, app].map(server => new Promise(resolve => server.close(resolve)))),
  };
}

module.exports = { createFixture, operations };
