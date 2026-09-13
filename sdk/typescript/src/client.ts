/**
 * MAPI TypeScript SDK (Node 22+, zero dependencies, spec §10 helper layer):
 * token-file auth, discovery parsing (untrusted-input hardened), task
 * polling with idempotency, event cursors with gap reporting.
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';

export const TERMINAL_TASK_STATES = new Set(['succeeded', 'failed', 'cancelled', 'expired']);
const IDENTIFIER = /^[a-zA-Z0-9._-]{1,64}$/;

export class MapiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(`${status} ${code}: ${message}`);
    this.status = status;
    this.code = code;
  }
}

export interface DiscoveryRecord {
  instanceId: string;
  processSessionId: string;
  pid: number;
  readiness: string;
  apiPort: number;
  eventsPort: number | null;
}

/** Validates a parsed discovery file (schema 1). */
export function parseDiscovery(raw: unknown): DiscoveryRecord {
  if (typeof raw !== 'object' || raw === null) throw new Error('discovery must be a JSON object');
  const d = raw as Record<string, unknown>;
  if (d.schemaVersion !== 1) throw new Error(`unsupported schemaVersion: ${String(d.schemaVersion)}`);
  const identifier = (field: string): string => {
    const value = d[field];
    if (typeof value !== 'string' || !IDENTIFIER.test(value)) throw new Error(`${field} invalid`);
    return value;
  };
  const port = (section: unknown, name: string): number => {
    const portValue = (section as Record<string, unknown> | undefined)?.port;
    if (typeof portValue !== 'number' || !Number.isInteger(portValue) || portValue < 1 || portValue > 65535) {
      throw new Error(`${name}.port must be a valid port`);
    }
    return portValue;
  };
  const readiness = d.readiness;
  if (readiness !== 'http' && readiness !== 'worldReady' && readiness !== 'clientJoined') {
    throw new Error(`unknown readiness: ${String(readiness)}`);
  }
  return {
    instanceId: identifier('instanceId'),
    processSessionId: identifier('processSessionId'),
    pid: typeof d.pid === 'number' ? d.pid : -1,
    readiness,
    apiPort: port(d.api, 'api'),
    eventsPort: d.events === undefined ? null : port(d.events, 'events'),
  };
}

export interface JsonMap { [key: string]: unknown }

export class MapiClient {
  readonly baseUrl: string;
  private readonly token: string;
  private readonly timeoutMs: number;

  private constructor(baseUrl: string, token: string, timeoutMs = 10_000) {
    this.baseUrl = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
    this.token = token;
    this.timeoutMs = timeoutMs;
  }

  /** Resolves the instance via its discovery + token files. */
  static fromGameDir(gameDir: string, timeoutMs?: number): MapiClient {
    const discovery = parseDiscovery(JSON.parse(
      readFileSync(join(gameDir, 'mcapi', 'discovery.json'), 'utf-8')));
    const token = readFileSync(join(gameDir, 'mcapi', 'token'), 'utf-8').trim();
    if (!token) throw new Error('empty token file');
    return new MapiClient(`http://127.0.0.1:${discovery.apiPort}`, token, timeoutMs);
  }

  /** Resolves the instance from an explicit URL and token file. */
  static fromUrl(baseUrl: string, tokenFile: string, timeoutMs?: number): MapiClient {
    const token = readFileSync(tokenFile, 'utf-8').trim();
    if (!token) throw new Error('empty token file');
    return new MapiClient(baseUrl, token, timeoutMs);
  }

  private async request(method: string, path: string, body?: JsonMap, headers?: JsonMap): Promise<JsonMap> {
    const response = await fetch(this.baseUrl + path, {
      method,
      headers: {
        Authorization: `Bearer ${this.token}`,
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(headers ?? {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(this.timeoutMs),
    });
    const payload = (await response.json().catch(() => ({}))) as JsonMap;
    if (!response.ok) {
      const error = (payload.error ?? {}) as JsonMap;
      throw new MapiError(response.status, String(error.code ?? 'UNKNOWN'), String(error.message ?? ''));
    }
    return payload;
  }

  health(): Promise<JsonMap> { return this.request('GET', '/api/v1/health'); }
  info(): Promise<JsonMap> { return this.request('GET', '/api/v1/info'); }
  ready(): Promise<JsonMap> { return this.request('GET', '/api/v1/ready'); }
  serverStatus(): Promise<JsonMap> { return this.request('GET', '/api/v1/server/status'); }
  players(fields?: string[], limit = 50, offset = 0): Promise<JsonMap> {
    const query = new URLSearchParams({ limit: String(limit), offset: String(offset) });
    if (fields?.length) query.set('fields', fields.join(','));
    return this.request('GET', `/api/v1/server/players?${query}`);
  }
  block(dimension: string, x: number, y: number, z: number): Promise<JsonMap> {
    return this.request('GET', `/api/v1/server/world/block?dimension=${encodeURIComponent(dimension)}&x=${x}&y=${y}&z=${z}`);
  }
  worldTime(dimension: string): Promise<JsonMap> {
    return this.request('GET', `/api/v1/server/world/time?dimension=${encodeURIComponent(dimension)}`);
  }

  /** Creates a task; `idempotencyKey` makes retries replay-safe. */
  createTask(kind: string, payload: JsonMap = {}, opts: {
    deadlineMs?: number; idempotencyKey?: string; expectedWorldSessionId?: string;
  } = {}): Promise<JsonMap> {
    const body: JsonMap = { kind, payload };
    if (opts.deadlineMs !== undefined) body.deadlineMs = opts.deadlineMs;
    if (opts.expectedWorldSessionId !== undefined) body.expectedWorldSessionId = opts.expectedWorldSessionId;
    const headers: JsonMap = {};
    if (opts.idempotencyKey !== undefined) headers['Idempotency-Key'] = opts.idempotencyKey;
    return this.request('POST', '/api/v1/tasks', body, headers);
  }

  task(taskId: string): Promise<JsonMap> { return this.request('GET', `/api/v1/tasks/${taskId}`); }
  cancelTask(taskId: string): Promise<JsonMap> { return this.request('DELETE', `/api/v1/tasks/${taskId}`); }

  /** Polls until the task is terminal or the timeout elapses. */
  async waitTask(taskId: string, timeoutS = 30, pollS = 0.2): Promise<JsonMap> {
    const deadline = Date.now() + timeoutS * 1000;
    let snapshot = await this.task(taskId);
    while (!TERMINAL_TASK_STATES.has(String(snapshot.state))) {
      if (Date.now() >= deadline) return snapshot;
      await new Promise((resolve) => setTimeout(resolve, pollS * 1000));
      snapshot = await this.task(taskId);
    }
    return snapshot;
  }

  /** One event page. */
  events(after = 0, limit = 100): Promise<JsonMap> {
    return this.request('GET', `/api/v1/events?after=${after}&limit=${limit}`);
  }

  /** Page plus the effective next cursor under `nextCursor` for resume. */
  async eventsAfter(after: number): Promise<JsonMap> {
    const page = await this.events(after);
    let next = after;
    for (const event of (page.events as JsonMap[] | undefined) ?? []) {
      if (typeof event.seq === 'number' && event.seq > next) next = event.seq;
    }
    if (typeof page.headSeq === 'number' && page.headSeq > next) next = page.headSeq;
    return { ...page, nextCursor: next };
  }

  /**
   * Async generator following the event stream with cursor resume; yields
   * gap markers as `{type: 'gap', from, to}`.
   */
  async *followEvents(after = 0, pollMs = 250): AsyncGenerator<JsonMap> {
    let cursor = after;
    while (true) {
      const page = await this.events(cursor);
      const gap = page.gap as { from: number; to: number } | undefined;
      if (gap) yield { type: 'gap', from: gap.from, to: gap.to };
      for (const event of (page.events as JsonMap[] | undefined) ?? []) {
        if (typeof event.seq === 'number' && event.seq > cursor) {
          yield event;
          cursor = event.seq;
        }
      }
      if (typeof page.headSeq === 'number' && page.headSeq > cursor) cursor = page.headSeq;
      await new Promise((resolve) => setTimeout(resolve, pollMs));
    }
  }
}
