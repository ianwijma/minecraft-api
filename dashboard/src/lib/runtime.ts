import type { Operation } from "./catalog.ts";
import {
  classifyCapabilities,
  emptyCapabilities,
  isExpectedCapabilityUnavailable,
} from "./capabilities.ts";
import { omitTransient } from "./storage.ts";
import { redactText, redactValue } from "./redaction.ts";
import type {
  ApiErrorSnapshot,
  ApiResponse,
  CapabilityContext,
  ConnectionProfile,
  ProbeResult,
  RequestSnapshot,
  RunOptions,
  RunRecord,
  RuntimeEvent,
} from "./types.ts";

const API_PREFIX = "/api/v1";
export class MapiApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details?: unknown;
  readonly headers: Record<string, string>;
  readonly request?: RequestSnapshot;
  constructor(
    status: number,
    code: string,
    message: string,
    details?: unknown,
    headers: Record<string, string> = {},
    request?: RequestSnapshot,
  ) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
    this.headers = headers;
    this.request = request;
    this.name = "MapiApiError";
  }
  toSnapshot(): ApiErrorSnapshot {
    return {
      status: this.status,
      code: this.code,
      message: redactText(this.message),
      details: redactValue(this.details),
      headers: safeHeaders(this.headers),
    };
  }
}
export class ProtocolMismatchError extends Error {
  readonly version: unknown;
  constructor(version: unknown) {
    super(`Unsupported MAPI protocol version: ${String(version)}`);
    this.version = version;
    this.name = "ProtocolMismatchError";
  }
}
interface Job {
  promise: Promise<any>;
  controller: AbortController;
  subscribers: number;
  settled: boolean;
}
interface Bucket {
  nextAt: number;
  retryAt: number;
  recent: number[];
  queue: Promise<void>;
}
const buckets = new Map<string, Bucket>(),
  inFlightGets = new Map<string, Job>();
function endpointKey(p: ConnectionProfile): string {
  return `${p.baseUrl.replace(/\/$/, "")}|${p.token ?? ""}`;
}
function bucketFor(key: string): Bucket {
  let b = buckets.get(key);
  if (!b) {
    b = { nextAt: 0, retryAt: 0, recent: [], queue: Promise.resolve() };
    buckets.set(key, b);
  }
  return b;
}
function abortError(): Error {
  return new DOMException("The operation was aborted", "AbortError");
}
function joinJob<T>(job: Job, signal?: AbortSignal): Promise<T> {
  job.subscribers++;
  return new Promise<T>((resolve, reject) => {
    let done = false;
    const finish = () => {
      if (done) return;
      done = true;
      signal?.removeEventListener("abort", onAbort);
      job.subscribers = Math.max(0, job.subscribers - 1);
      if (!job.settled && job.subscribers === 0) job.controller.abort();
    };
    const onAbort = () => {
      finish();
      reject(abortError());
    };
    if (signal?.aborted) {
      onAbort();
      return;
    }
    signal?.addEventListener("abort", onAbort, { once: true });
    job.promise.then(
      (v: T) => {
        if (done) return;
        finish();
        resolve(v);
      },
      (e: unknown) => {
        if (done) return;
        finish();
        reject(e);
      },
    );
  });
}
async function budget(key: string, signal?: AbortSignal): Promise<void> {
  const b = bucketFor(key);
  let release!: () => void;
  const previous = b.queue;
  b.queue = new Promise<void>((r) => (release = r));
  await previous;
  try {
    for (;;) {
      if (signal?.aborted) throw abortError();
      const now = Date.now();
      b.recent = b.recent.filter((t) => now - t < 60_000);
      const ready = Math.max(
        b.nextAt,
        b.retryAt,
        b.recent.length >= 60 ? b.recent[0] + 60_000 : 0,
      );
      if (ready <= now) {
        b.recent.push(now);
        b.nextAt = now + 1000;
        return;
      }
      await new Promise<void>((res, rej) => {
        const onAbort = () => {
          clearTimeout(timer);
          rej(abortError());
        };
        const timer = setTimeout(
          () => {
            signal?.removeEventListener("abort", onAbort);
            res();
          },
          Math.min(ready - now, 1000),
        );
        signal?.addEventListener("abort", onAbort, { once: true });
      });
    }
  } finally {
    release();
  }
}
function linkedStream(
  body: ReadableStream<Uint8Array>,
  controller: AbortController,
  outer?: AbortSignal,
): ReadableStream<Uint8Array> {
  const reader = body.getReader();
  const onAbort = () => {
    controller.abort();
    // The fetch body may already be errored with the abort reason. This is a
    // fire-and-forget cleanup path, so observe the cancellation rejection.
    void reader.cancel().catch(() => {});
  };
  outer?.addEventListener("abort", onAbort, { once: true });
  const cleanup = () => outer?.removeEventListener("abort", onAbort);
  return new ReadableStream<Uint8Array>({
    async pull(target) {
      try {
        const part = await reader.read();
        if (part.done) {
          cleanup();
          target.close();
          reader.releaseLock();
          return;
        }
        target.enqueue(part.value);
      } catch (e) {
        cleanup();
        target.error(e);
      }
    },
    async cancel(reason) {
      cleanup();
      controller.abort();
      try {
        await reader.cancel(reason);
      } catch {}
      reader.releaseLock();
    },
  });
}
function getHeaders(h: Headers): Record<string, string> {
  const o: Record<string, string> = {};
  h.forEach((v, k) => (o[k] = v));
  return o;
}
function safeHeaders(
  headers: Record<string, string>,
  token?: string,
): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [key, value] of Object.entries(headers))
    if (
      !["authorization", "set-cookie", "proxy-authenticate"].includes(
        key.toLowerCase(),
      )
    )
      out[key.toLowerCase()] = redactText(value, token);
  return out;
}
function retryDelay(headers: Record<string, string>): number {
  const v = headers["retry-after"];
  if (!v) return 60_000;
  const secs = Number(v);
  const at = Number.isFinite(secs)
    ? Date.now() + Math.max(0, secs) * 1000
    : Date.parse(v);
  return Number.isFinite(at) ? Math.max(0, at - Date.now()) : 60_000;
}
function queryString(q?: Record<string, unknown>): string {
  const s = new URLSearchParams();
  for (const [k, v] of Object.entries(q ?? {}))
    if (v !== undefined && v !== null)
      s.set(k, Array.isArray(v) ? v.join(",") : String(v));
  const x = s.toString();
  return x ? `?${x}` : "";
}
function safeEndpoint(
  base: string,
  path: string,
  q?: Record<string, unknown>,
): string {
  const url = new URL(
    path.replace(/\{([^}]+)\}/g, (_m, k) => encodeURIComponent(k)),
    base,
  );
  url.search = queryString(q);
  return url.toString();
}

export interface ApiClient {
  request<T = unknown>(
    method: "GET" | "POST",
    path: string,
    options?: {
      query?: Record<string, unknown>;
      body?: unknown;
      signal?: AbortSignal;
      timeoutMs?: number;
      stream?: boolean;
    },
  ): Promise<ApiResponse<T>>;
  profile: ConnectionProfile;
}
export function createApiClient(profile: ConnectionProfile): ApiClient {
  const parsedBase = new URL(profile.baseUrl);
  if (
    !["http:", "https:"].includes(parsedBase.protocol) ||
    parsedBase.username ||
    parsedBase.password ||
    parsedBase.search ||
    parsedBase.hash
  )
    throw new Error(
      "MAPI URL must be an HTTP(S) origin without credentials, query, or fragment",
    );
  const cleanProfile = { ...profile, baseUrl: parsedBase.origin };
  const key = endpointKey(cleanProfile);
  async function request<T>(
    method: "GET" | "POST",
    path: string,
    options: {
      query?: Record<string, unknown>;
      body?: unknown;
      signal?: AbortSignal;
      timeoutMs?: number;
      stream?: boolean;
    } = {},
  ): Promise<ApiResponse<T>> {
    const fullPath = path.startsWith("/") ? path : `${API_PREFIX}/${path}`;
    if (
      options.body !== undefined &&
      new TextEncoder().encode(JSON.stringify(options.body)).byteLength > 8192
    )
      throw new MapiApiError(
        413,
        "PAYLOAD_TOO_LARGE",
        "Request body exceeds the MAPI 8192-byte limit",
      );
    const req: RequestSnapshot = {
      method,
      path: fullPath,
      query: options.query,
      body:
        options.body === undefined
          ? undefined
          : redactValue(options.body, new WeakSet(), profile.token),
    };
    const cacheKey = `${key}|${method}|${fullPath}|${JSON.stringify(options.query ?? {})}|${JSON.stringify(options.body ?? null)}`;
    const shareable = method === "GET" && !options.stream;
    if (shareable) {
      const pending = inFlightGets.get(cacheKey);
      if (pending) return joinJob<ApiResponse<T>>(pending, options.signal);
    }
    const controller = new AbortController();
    const outer = options.signal;
    const abort = () => controller.abort();
    if (!shareable) outer?.addEventListener("abort", abort, { once: true });
    const promise: Promise<ApiResponse<T>> = (async () => {
      const start = performance.now();
      const timeout = options.stream
        ? undefined
        : setTimeout(() => controller.abort(), options.timeoutMs ?? 15_000);
      try {
        await budget(key, controller.signal);
        const response = await fetch(
          safeEndpoint(cleanProfile.baseUrl, fullPath, options.query),
          {
            method,
            headers: {
              Authorization: `Bearer ${cleanProfile.token ?? ""}`,
              ...(options.body === undefined
                ? {}
                : { "Content-Type": "application/json" }),
            },
            body:
              options.body === undefined
                ? undefined
                : JSON.stringify(options.body),
            signal: controller.signal,
            cache: "no-store",
          },
        );
        if (options.stream) outer?.removeEventListener("abort", abort);
        const headers = getHeaders(response.headers);
        if (response.status === 429)
          bucketFor(key).retryAt = Date.now() + retryDelay(headers);
        let data: any;
        const contentType = response.headers.get("content-type") ?? "";
        if (
          contentType.includes("text/event-stream") &&
          options.stream &&
          response.body
        )
          data = linkedStream(response.body, controller, outer);
        else if (contentType.includes("text/event-stream"))
          data = response.body;
        else {
          const text = await response.text();
          try {
            data = text ? JSON.parse(text) : null;
          } catch {
            data = text;
          }
        }
        const durationMs = performance.now() - start;
        if (!response.ok) {
          const error = data?.error ?? data;
          throw new MapiApiError(
            response.status,
            typeof error?.code === "string" ? error.code : "HTTP_ERROR",
            typeof error?.message === "string"
              ? error.message
              : `Request failed with HTTP ${response.status}`,
            error?.details,
            headers,
            req,
          );
        }
        return {
          data,
          status: response.status,
          headers,
          durationMs,
          request: req,
        } as ApiResponse<T>;
      } finally {
        if (timeout) clearTimeout(timeout);
        if (!shareable) outer?.removeEventListener("abort", abort);
      }
    })();
    if (shareable) {
      const job: Job = { promise, controller, subscribers: 0, settled: false };
      inFlightGets.set(cacheKey, job);
      promise
        .finally(() => {
          job.settled = true;
          if (inFlightGets.get(cacheKey) === job) inFlightGets.delete(cacheKey);
        })
        .catch(() => {});
      return joinJob<ApiResponse<T>>(job, options.signal);
    }
    return promise;
  }
  return { profile: cleanProfile, request };
}

export type LeaseTopic = "input" | "tick-control";
interface HeldLease {
  id: string;
  expiresAt: number;
}
const sharedLeaseManagers = new Map<
  string,
  { manager: ReturnType<typeof makeLeaseManager>; refs: number }
>();
export function createLeaseManager(client: ApiClient) {
  const key = endpointKey(client.profile);
  const old = sharedLeaseManagers.get(key);
  if (old) {
    old.refs++;
    return old.manager;
  }
  const manager = makeLeaseManager(client);
  sharedLeaseManagers.set(key, { manager, refs: 1 });
  return manager;
}
function makeLeaseManager(client: ApiClient) {
  const requests = new Set<AbortController>();
  const leaseRequest: ApiClient["request"] = async (
    method,
    path,
    options = {},
  ) => {
    const c = new AbortController();
    requests.add(c);
    const abort = () => c.abort();
    options.signal?.addEventListener("abort", abort, { once: true });
    try {
      return await client.request(method, path, {
        ...options,
        signal: c.signal,
      });
    } finally {
      options.signal?.removeEventListener("abort", abort);
      requests.delete(c);
    }
  };
  const held = new Map<LeaseTopic, HeldLease>(),
    locks = new Map<LeaseTopic, Promise<void>>();
  let disposed = false;
  const route = (topic: LeaseTopic) =>
    topic === "input"
      ? `${API_PREFIX}/client/control/lease`
      : `${API_PREFIX}/server/ticks/lease`;
  async function serialized<T>(
    topic: LeaseTopic,
    fn: () => Promise<T>,
  ): Promise<T> {
    const before = locks.get(topic) ?? Promise.resolve();
    let done!: () => void;
    const lock = new Promise<void>((r) => (done = r));
    locks.set(topic, lock);
    await before;
    try {
      return await fn();
    } finally {
      done();
      if (locks.get(topic) === lock) locks.delete(topic);
    }
  }
  async function acquire(
    topic: LeaseTopic,
    ttlSeconds?: number,
  ): Promise<HeldLease> {
    if (disposed) throw new Error("Connection runtime is disconnected");
    return serialized(topic, async () => {
      const old = held.get(topic);
      if (old && old.expiresAt > Date.now()) return old;
      held.delete(topic);
      const body =
        topic === "input"
          ? { ttlSeconds: Math.min(300, Math.max(1, ttlSeconds ?? 60)) }
          : { ttlSeconds: Math.min(3600, Math.max(1, ttlSeconds ?? 300)) };
      try {
        const r = await leaseRequest("POST", route(topic), {
          body,
          timeoutMs: 10_000,
        });
        const v = r.data as any;
        if (
          typeof v?.leaseId !== "string" ||
          typeof v?.expiresAtEpochMs !== "number"
        )
          throw new Error("MAPI returned an invalid lease response");
        const lease = { id: v.leaseId, expiresAt: v.expiresAtEpochMs };
        held.set(topic, lease);
        return lease;
      } catch (e) {
        if (e instanceof MapiApiError && e.code === "LEASE_HELD")
          throw new MapiApiError(
            e.status,
            e.code,
            `Another client holds the ${topic} lease. Wait for it to expire, then retry.`,
            e.details,
            e.headers,
            e.request,
          );
        throw e;
      }
    });
  }
  async function acquireExplicit(
    topic: LeaseTopic,
    ttlSeconds?: number,
    signal?: AbortSignal,
  ): Promise<
    | { reused: true; lease: HeldLease }
    | { reused: false; lease: HeldLease; response: ApiResponse }
  > {
    if (disposed) throw new Error("Connection runtime is disconnected");
    return serialized(topic, async () => {
      const current = held.get(topic);
      if (topic === "tick-control" && current && current.expiresAt > Date.now())
        return { reused: true, lease: current };
      const body: Record<string, unknown> = {
        ttlSeconds:
          topic === "input"
            ? Math.min(300, Math.max(1, ttlSeconds ?? 60))
            : Math.min(3600, Math.max(1, ttlSeconds ?? 300)),
      };
      if (topic === "input" && current && current.expiresAt > Date.now())
        body.leaseId = current.id;
      else if (current) held.delete(topic);
      try {
        const response = await leaseRequest("POST", route(topic), {
          body,
          signal,
          timeoutMs: 10_000,
        });
        const data = response.data as any;
        if (
          typeof data?.leaseId !== "string" ||
          typeof data?.expiresAtEpochMs !== "number"
        )
          throw new Error("MAPI returned an invalid lease response");
        const lease = { id: data.leaseId, expiresAt: data.expiresAtEpochMs };
        held.set(topic, lease);
        return { reused: false, lease, response };
      } catch (error) {
        if (error instanceof MapiApiError && error.code === "LEASE_HELD")
          throw new MapiApiError(
            error.status,
            error.code,
            `Another client holds the ${topic} lease. Wait for it to expire, then retry.`,
            error.details,
            error.headers,
            error.request,
          );
        throw error;
      }
    });
  }
  async function withLease<T>(
    topic: LeaseTopic,
    action: (leaseId: string) => Promise<T>,
  ): Promise<T> {
    const lease = await acquire(topic);
    let stopped = false;
    let renewal: ReturnType<typeof setTimeout> | undefined;
    const schedule = () => {
      if (topic !== "input" || stopped || disposed) return;
      const delay = Math.max(1000, lease.expiresAt - Date.now() - 15_000);
      renewal = setTimeout(async () => {
        try {
          if (disposed) return;
          const r = await leaseRequest("POST", route(topic), {
            body: { leaseId: lease.id, ttlSeconds: 60 },
            timeoutMs: 10_000,
          });
          const v = r.data as any;
          lease.expiresAt = v.expiresAtEpochMs ?? Date.now() + 60_000;
        } catch {
          held.delete(topic);
        }
        if (!stopped) schedule();
      }, delay);
    };
    schedule();
    try {
      return await action(lease.id);
    } finally {
      stopped = true;
      if (renewal) clearTimeout(renewal);
    }
  }
  function adopt(topic: LeaseTopic, id: string, expiresAt: number) {
    if (!disposed) held.set(topic, { id, expiresAt });
  }
  function invalidate(topic?: LeaseTopic) {
    if (topic) held.delete(topic);
    else held.clear();
  }
  function dispose() {
    const shared = sharedLeaseManagers.get(endpointKey(client.profile));
    if (shared && shared.refs > 1) {
      shared.refs--;
      return;
    }
    sharedLeaseManagers.delete(endpointKey(client.profile));
    disposed = true;
    for (const request of requests) request.abort();
    requests.clear();
    held.clear();
    locks.clear();
  }
  return {
    acquire,
    acquireExplicit,
    withLease,
    adopt,
    invalidate,
    dispose,
    getState: (topic: LeaseTopic) => {
      const l = held.get(topic);
      return l && l.expiresAt > Date.now()
        ? { state: "ready" as const, expiresAt: l.expiresAt }
        : { state: "idle" as const };
    },
  };
}

interface EventSubscriber {
  options: Record<string, unknown>;
  onEvent: (event: RuntimeEvent) => void;
  onError?: (error: unknown) => void;
}
interface EventHub {
  client: ApiClient;
  controller: AbortController;
  subscribers: Set<EventSubscriber>;
  cursor?: number;
  running: boolean;
}
const eventHubs = new Map<string, EventHub>(),
  eventCursors = new Map<string, number>();
function subscribeEndpointEvents(
  client: ApiClient,
  options: Record<string, unknown>,
  onEvent: (event: RuntimeEvent) => void,
  onError?: (error: unknown) => void,
): () => void {
  const key = endpointKey(client.profile);
  const streamOptions: Record<string, unknown> = {};
  if (typeof options.types === "string" || Array.isArray(options.types))
    streamOptions.types = Array.isArray(options.types)
      ? options.types.join(",")
      : options.types;
  if (typeof options.world === "string") streamOptions.world = options.world;
  streamOptions.keepaliveSeconds =
    typeof options.keepaliveSeconds === "number"
      ? options.keepaliveSeconds
      : 15;
  const cursorKey = `${key}|events|${JSON.stringify(streamOptions)}`;
  const streamKey = `${cursorKey}|cursor:${typeof options.cursor === "number" ? options.cursor : "resume"}`;
  let hub = eventHubs.get(streamKey);
  if (!hub) {
    hub = {
      client,
      controller: new AbortController(),
      subscribers: new Set(),
      cursor:
        typeof options.cursor === "number"
          ? options.cursor
          : eventCursors.get(cursorKey),
      running: false,
    };
    eventHubs.set(streamKey, hub);
  }
  const subscriber = { options, onEvent, onError };
  hub.subscribers.add(subscriber);
  const dispatch = (event: RuntimeEvent) => {
    for (const sub of hub!.subscribers) {
      if (event.gap) {
        sub.onEvent(event);
        continue;
      }
      const types = sub.options.types;
      const allowed =
        typeof types === "string"
          ? types.split(",")
          : Array.isArray(types)
            ? types
            : undefined;
      if (allowed && event.type && !allowed.includes(event.type)) continue;
      if (
        typeof sub.options.world === "string" &&
        event.worldSessionId !== sub.options.world
      )
        continue;
      sub.onEvent(event);
    }
  };
  if (!hub.running) {
    hub.running = true;
    (async () => {
      let backoff = 1000;
      while (!hub!.controller.signal.aborted) {
        try {
          const query: Record<string, unknown> = {
            keepaliveSeconds: options.keepaliveSeconds ?? 15,
            ...streamOptions,
          };
          if (hub!.cursor !== undefined) query.cursor = hub!.cursor;
          const res = await hub!.client.request(
            "GET",
            `${API_PREFIX}/events/stream`,
            {
              query,
              signal: hub!.controller.signal,
              stream: true,
              timeoutMs: 0,
            },
          );
          const reader = (res.data as ReadableStream<Uint8Array>).getReader(),
            decoder = new TextDecoder();
          let buffer = "";
          while (!hub!.controller.signal.aborted) {
            const part = await reader.read();
            if (part.done) break;
            buffer = (
              buffer + decoder.decode(part.value, { stream: true })
            ).replace(/\r\n/g, "\n");
            let split;
            while ((split = buffer.indexOf("\n\n")) >= 0) {
              const frame = buffer.slice(0, split);
              buffer = buffer.slice(split + 2);
              if (frame.startsWith(":")) {
                if (frame.includes("event-gap")) {
                  const dropped = frame.match(/droppedUpTo=(\d+)/);
                  if (dropped) {
                    hub!.cursor = Math.max(
                      hub!.cursor ?? 0,
                      Number(dropped[1]),
                    );
                    eventCursors.set(cursorKey, hub!.cursor);
                  }
                  dispatch({ gap: true, seq: hub!.cursor });
                }
                continue;
              }
              const data = frame
                .split("\n")
                .filter((line) => line.startsWith("data:"))
                .map((line) => line.slice(5).trim())
                .join("\n");
              const id = frame
                .split("\n")
                .find((line) => line.startsWith("id:"))
                ?.slice(3)
                .trim();
              if (data) {
                const event = JSON.parse(data) as RuntimeEvent;
                if (id && /^\d+$/.test(id)) event.seq = Number(id);
                if (event.seq !== undefined) {
                  hub!.cursor = event.seq;
                  eventCursors.set(cursorKey, event.seq);
                }
                dispatch(event);
              }
            }
          }
          backoff = 1000;
        } catch (error) {
          if (hub!.controller.signal.aborted) break;
          for (const sub of hub!.subscribers) sub.onError?.(error);
          try {
            await delayWithSignal(backoff, hub!.controller.signal);
          } catch {
            break;
          }
          backoff = Math.min(30_000, backoff * 2);
        }
      }
      if (eventHubs.get(streamKey) === hub) eventHubs.delete(streamKey);
    })();
  }
  return () => {
    hub!.subscribers.delete(subscriber);
    if (hub!.subscribers.size === 0) {
      hub!.controller.abort();
      if (eventHubs.get(streamKey) === hub) eventHubs.delete(streamKey);
    }
  };
}

export interface ConnectionRuntime {
  profile: ConnectionProfile;
  client: ApiClient;
  leases: ReturnType<typeof createLeaseManager>;
  capabilities: CapabilityContext;
  probe(options?: { signal?: AbortSignal }): Promise<ProbeResult>;
  reconnect(options?: { signal?: AbortSignal }): Promise<ProbeResult>;
  execute(
    operation: Operation,
    values: Record<string, unknown>,
    options?: RunOptions,
  ): Promise<RunRecord>;
  subscribeEvents(
    options: Record<string, unknown>,
    onEvent: (event: RuntimeEvent) => void,
    onError?: (error: unknown) => void,
  ): () => void;
  disconnect(): void;
  dispose(): void;
}
export function createConnectionRuntime(
  profile: ConnectionProfile,
): ConnectionRuntime {
  let capabilities = emptyCapabilities();
  let disposed = false;
  const controllers = new Set<AbortController>();
  const unsubscriptions = new Set<() => void>();
  const baseClient = createApiClient(profile);
  const client: ApiClient = {
    profile,
    request: async (method, path, options = {}) => {
      if (disposed) throw abortError();
      const ctl = new AbortController();
      controllers.add(ctl);
      const outer = options.signal;
      const abort = () => ctl.abort();
      outer?.addEventListener("abort", abort, { once: true });
      try {
        return await baseClient.request(method, path, {
          ...options,
          signal: ctl.signal,
        });
      } finally {
        outer?.removeEventListener("abort", abort);
        controllers.delete(ctl);
      }
    },
  };
  const leases = createLeaseManager(baseClient);
  const probe = async (
    options: { signal?: AbortSignal } = {},
  ): Promise<ProbeResult> => {
    let info: any,
      ops: any,
      status: any,
      world: any,
      cli: any,
      ticks: any,
      healthAuthenticated = false;
    try {
      const h = await client.request<any>("GET", `${API_PREFIX}/health`, {
        signal: options.signal,
      });
      healthAuthenticated = true;
      const version = h.data?.protocolVersion;
      if (version !== 1) throw new ProtocolMismatchError(version);
      const jobs = [
        client.request<any>("GET", `${API_PREFIX}/info`, {
          signal: options.signal,
        }),
        client.request<any>("GET", `${API_PREFIX}/operations`, {
          signal: options.signal,
        }),
        client.request<any>("GET", `${API_PREFIX}/server/status`, {
          signal: options.signal,
        }),
        client.request<any>("GET", `${API_PREFIX}/server/world`, {
          signal: options.signal,
        }),
        client.request<any>("GET", `${API_PREFIX}/client`, {
          signal: options.signal,
        }),
        client.request<any>("GET", `${API_PREFIX}/server/ticks`, {
          signal: options.signal,
        }),
      ];
      const settled = await Promise.allSettled(jobs);
      const val = (i: number) =>
        settled[i].status === "fulfilled" ? settled[i].value.data : undefined;
      info = val(0);
      ops = val(1);
      status = val(2);
      world = val(3);
      cli = val(4);
      ticks = val(5);
      for (let i = 0; i < settled.length; i++) {
        const item = settled[i];
        if (item.status === "rejected") {
          const e = item.reason;
          if (
            i < 3 ||
            !isExpectedCapabilityUnavailable({
              code: e instanceof MapiApiError ? e.code : undefined,
              status: e instanceof MapiApiError ? e.status : undefined,
            })
          )
            throw e;
        }
      }
      if (!info || !ops)
        throw new Error(
          "MAPI info and operation metadata are required for a complete connection check",
        );
      const nextCapabilities = classifyCapabilities({
        serverStatus: status,
        worldInfo: world,
        clientInfo: cli,
        tickState: ticks,
      });
      const worldSessionChanged =
        capabilities.worldSessionId !== undefined &&
        capabilities.worldSessionId !== nextCapabilities.worldSessionId;
      const worldUnloaded =
        capabilities.worldPhase === "ACTIVE" &&
        (nextCapabilities.worldPhase === "NONE" ||
          nextCapabilities.worldPhase === "UNLOADING");
      if (worldSessionChanged || worldUnloaded)
        leases.invalidate("tick-control");
      capabilities = nextCapabilities;
      return {
        ok: true,
        authenticated: true,
        compatible: true,
        protocolVersion: 1,
        info,
        operations: ops,
        capabilities,
      };
    } catch (e) {
      const err = errorSnapshot(e, profile.token);
      return {
        ok: false,
        authenticated: healthAuthenticated,
        compatible: !(e instanceof ProtocolMismatchError),
        protocolVersion:
          e instanceof ProtocolMismatchError ? Number(e.version) : undefined,
        capabilities,
        error: err,
      };
    }
  };
  async function execute(
    operation: Operation,
    values: Record<string, unknown>,
    options: RunOptions = {},
  ): Promise<RunRecord> {
    if (disposed) throw new Error("Connection runtime is disconnected");
    const started = performance.now();
    const vals = { ...values };
    if (operation.security.destructive && !options.confirmDestructive)
      throw new Error(
        "This destructive operation requires fresh confirmation.",
      );
    let path = operation.path;
    const query: Record<string, unknown> = {};
    for (const p of operation.parameters ?? []) {
      if (p.in === "path" && vals[p.name] !== undefined) {
        path = path.replace(
          `{${p.name}}`,
          encodeURIComponent(String(vals[p.name])),
        );
        delete vals[p.name];
      } else if (p.in === "query" && vals[p.name] !== undefined) {
        query[p.name] = vals[p.name];
        delete vals[p.name];
      }
    }
    const acquisitionTopic = operation.path.endsWith("/client/control/lease")
      ? "input"
      : operation.path.endsWith("/server/ticks/lease")
        ? "tick-control"
        : undefined;
    const body = operation.method === "POST" ? { ...vals } : undefined;
    if (body && "leaseId" in body) delete (body as any).leaseId;
    const topic = operation.security.requiresLease
      ? operation.path.includes("/ticks/") || operation.path.includes("/lan")
        ? "tick-control"
        : "input"
      : undefined;
    let request: RequestSnapshot = {
      method: operation.method,
      path,
      query: Object.keys(query).length ? query : undefined,
      body: redactValue(body, new WeakSet(), profile.token),
    };
    const id = crypto.randomUUID(),
      at = new Date().toISOString();
    try {
      const run = () =>
        client.request(operation.method, path, {
          query,
          body,
          signal: options.signal,
          timeoutMs: options.timeoutMs,
        });
      let result: ApiResponse;
      if (acquisitionTopic) {
        const acquired = await leases.acquireExplicit(
          acquisitionTopic,
          typeof vals.ttlSeconds === "number" ? vals.ttlSeconds : undefined,
          options.signal,
        );
        if (acquired.reused) {
          return {
            id,
            operationId: operation.id,
            values: redactValue(
              omitTransient(values),
              new WeakSet(),
              profile.token,
            ) as Record<string, unknown>,
            saved: false,
            at,
            request: {
              method: "LOCAL",
              path: `${operation.path} (existing tick lease reused)`,
            },
            response: {
              topic: acquisitionTopic,
              reused: true,
              expiresAtEpochMs: acquired.lease.expiresAt,
            },
            durationMs: performance.now() - started,
          };
        }
        result = acquired.response;
        request = result.request;
      } else if (topic) {
        result = await leases.withLease(topic, (leaseId) => {
          const payload = { ...((body as any) ?? {}), leaseId };
          request.body = redactValue(payload, new WeakSet(), profile.token);
          return client.request(operation.method, path, {
            query,
            body: payload,
            signal: options.signal,
            timeoutMs: options.timeoutMs,
          });
        });
      } else result = await run();
      let responseData: any = result.data;
      let completionError: ApiErrorSnapshot | undefined;
      if (
        result.status === 202 &&
        responseData &&
        typeof responseData === "object"
      ) {
        const admission = responseData;
        const deadline =
          Date.now() + Math.min(options.timeoutMs ?? 30_000, 60_000);
        try {
          if (typeof admission.jobId === "string") {
            let job: any;
            do {
              if (Date.now() >= deadline) break;
              await delayWithSignal(750, options.signal);
              job = (
                await client.request<any>(
                  "GET",
                  `${API_PREFIX}/jobs/${encodeURIComponent(admission.jobId)}`,
                  { signal: options.signal, timeoutMs: 10_000 },
                )
              ).data;
            } while (job && ["PENDING", "RUNNING"].includes(job.state));
            responseData = {
              admission,
              completion: job ?? { state: "PENDING", timedOut: true },
            };
          } else if (
            operation.path.includes("/worlds/create") ||
            operation.path.includes("/worlds/load")
          ) {
            let state: any;
            do {
              if (Date.now() >= deadline) break;
              await delayWithSignal(1000, options.signal);
              state = (
                await client.request<any>("GET", `${API_PREFIX}/server/world`, {
                  signal: options.signal,
                  timeoutMs: 10_000,
                })
              ).data;
            } while (state?.phase === "LOADING");
            responseData = {
              admission,
              completion: state ?? { phase: "LOADING", timedOut: true },
            };
          }
        } catch (error) {
          completionError = errorSnapshot(error, profile.token);
          responseData = {
            admission,
            completion: {
              state: "UNVERIFIED",
              error: completionError,
            },
          };
        }
      }
      const r: RunRecord = {
        id,
        operationId: operation.id,
        values: redactValue(
          omitTransient(values),
          new WeakSet(),
          profile.token,
        ) as Record<string, unknown>,
        saved: false,
        at,
        request: result.request,
        response: redactValue(responseData, new WeakSet(), profile.token),
        status: result.status,
        responseHeaders: safeHeaders(result.headers, profile.token),
        durationMs: performance.now() - started,
        ...(completionError ? { error: completionError } : {}),
      };
      if (operation.path.endsWith("/control/lease")) {
        const d: any = result.data;
        if (d?.leaseId && d?.expiresAtEpochMs)
          leases.adopt("input", d.leaseId, d.expiresAtEpochMs);
      }
      if (operation.path.endsWith("/ticks/lease")) {
        const d: any = result.data;
        if (d?.leaseId && d?.expiresAtEpochMs)
          leases.adopt("tick-control", d.leaseId, d.expiresAtEpochMs);
      }
      const session =
        (responseData as any)?.worldSessionId ??
        (responseData as any)?.completion?.worldSessionId;
      if (
        typeof session === "string" &&
        capabilities.worldSessionId &&
        session !== capabilities.worldSessionId
      ) {
        leases.invalidate("tick-control");
        capabilities = {
          ...capabilities,
          world: "unknown",
          worldSessionId: session,
        };
      }
      return r;
    } catch (e) {
      if (e instanceof MapiApiError && e.request) request = e.request;
      return {
        id,
        operationId: operation.id,
        values: redactValue(
          omitTransient(values),
          new WeakSet(),
          profile.token,
        ) as Record<string, unknown>,
        saved: false,
        at,
        request,
        durationMs: performance.now() - started,
        status: e instanceof MapiApiError ? e.status : undefined,
        responseHeaders:
          e instanceof MapiApiError
            ? safeHeaders(e.headers, profile.token)
            : undefined,
        error: errorSnapshot(e, profile.token),
      };
    }
  }
  function subscribeEvents(
    options: Record<string, unknown>,
    onEvent: (event: RuntimeEvent) => void,
    onError?: (error: unknown) => void,
  ): () => void {
    if (disposed) return () => {};
    const unsubscribe = subscribeEndpointEvents(
      baseClient,
      options,
      onEvent,
      onError,
    );
    unsubscriptions.add(unsubscribe);
    return () => {
      unsubscribe();
      unsubscriptions.delete(unsubscribe);
    };
  }
  function disconnect() {
    for (const c of controllers) c.abort();
    controllers.clear();
    for (const unsub of unsubscriptions) unsub();
    unsubscriptions.clear();
  }
  function dispose() {
    if (disposed) return;
    disposed = true;
    disconnect();
    leases.dispose();
  }
  return {
    profile,
    client,
    leases,
    get capabilities() {
      return capabilities;
    },
    probe,
    reconnect: probe,
    execute,
    subscribeEvents,
    disconnect,
    dispose,
  };
}
function errorSnapshot(e: unknown, token?: string): ApiErrorSnapshot {
  if (e instanceof MapiApiError) {
    const snapshot = e.toSnapshot();
    return {
      ...snapshot,
      message: redactText(snapshot.message, token),
      details: redactValue(snapshot.details, new WeakSet(), token),
      headers: safeHeaders(snapshot.headers ?? {}, token),
    };
  }
  if (e instanceof ProtocolMismatchError)
    return { code: "PROTOCOL_MISMATCH", message: e.message };
  if (e instanceof Error)
    return {
      code: e.name === "AbortError" ? "ABORTED" : "NETWORK_ERROR",
      message: redactText(e.message, token),
    };
  return { code: "UNKNOWN_ERROR", message: redactText(String(e), token) };
}
export const createRuntime = createConnectionRuntime;

function delayWithSignal(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(abortError());
      return;
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener("abort", abort);
      resolve();
    }, ms);
    const abort = () => {
      clearTimeout(timer);
      reject(abortError());
    };
    signal?.addEventListener("abort", abort, { once: true });
  });
}
