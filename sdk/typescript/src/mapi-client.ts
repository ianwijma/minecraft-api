/**
 * MAPI TypeScript client — GENERATED from docs/openapi.yaml (MAPI local HTTP API).
 * Do not edit; rerun scripts/generate-sdks.py instead.
 * Wire semantics implemented per docs/openapi.yaml + docs/security.md:
 * bearer auth, problem-code envelopes, loopback-only usage.
 */

export interface MapiResult {
  status: number;
  body: unknown;
  ok: boolean;
}

export class MapiError extends Error {
  readonly status: number;
  readonly code: string;
  constructor(status: number, code: string, message: string) {
    super(`HTTP ${status} ${code}: ${message}`);
    this.status = status;
    this.code = code;
  }
}

export class MapiClient {
  private readonly base: string;
  private readonly token: string;
  private readonly timeoutMs: number;
  constructor(base: string, token: string, timeoutMs = 10_000) {
    if (!token) throw new Error('a bearer token is required');
    this.base = base.replace(/\/+$/, '');
    this.token = token;
    this.timeoutMs = timeoutMs;
  }

  private async request(method: 'GET' | 'POST', path: string,
                        body?: unknown): Promise<MapiResult> {
    const headers: Record<string, string> = {
      Authorization: `Bearer ${this.token}`,
      Host: '127.0.0.1',
    };
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }
    const response = await fetch(this.base + path, {
      method, headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(this.timeoutMs),
    });
    const parsed = await response.json();
    const ok = response.status >= 200 && response.status < 300;
    if (!ok && parsed && typeof parsed === 'object'
        && parsed.error && typeof parsed.error.code === 'string') {
      throw new MapiError(response.status, parsed.error.code,
                         String(parsed.error.message ?? ''));
    }
    return { status: response.status, body: parsed, ok };
  }

  /** Client bridge identity and capabilities. CAPABILITY_UNAVAILABLE semantics apply per feature on dedicated servers (no client bridge). */
  getClientInfo(): Promise<MapiResult> {
    return this.request('GET', `/api/v1/client`);
  }

  /** Hold a key for N client ticks (raw-input only, spec §3.4/§4.2). Returns an action receipt; unsupported modes fail with EXECUTION_MODE_UNSUPPORTED (no silent fallback). Key-up is always dispatched before a deadline failure surfaces (release-all). */
  holdKey(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/client/actions/hold-key`, body);
  }

  /** Execute straight-line waypoints (raw-input; required mod, spec §16). No teleport fallback. Receipt reports per-leg boundaries; effects verified separately via world queries. */
  moveWaypoints(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/client/movement/waypoints`, body);
  }

  /** Screenshot capture with frame/scale metadata (spec §20) */
  captureScreenshot(): Promise<MapiResult> {
    return this.request('GET', `/api/v1/client/screenshots`);
  }

  /** Window state with framebuffer/logical distinction (spec §9.1) */
  getWindowState(): Promise<MapiResult> {
    return this.request('GET', `/api/v1/client/window`);
  }

  /** Request fullscreen on/off. 26.2 exposes no public setter on the window: the actual state is returned and the window revision bumps, never assumed (spec §9.1 honesty rule). */
  setFullscreen(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/client/window/set-fullscreen`, body);
  }

  /** Set GUI scale (0 = auto, 1..4) */
  setGuiScale(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/client/window/set-gui-scale`, body);
  }

  /** Set windowed dimensions (OS may adjust; effective values returned, spec §9.1) */
  setWindowed(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/client/window/set-windowed`, body);
  }

  /** Server-Sent Events stream of runtime events (spec §13) */
  streamEvents(cursor?: number, keepaliveSeconds?: number, types?: string, world?: string): Promise<MapiResult> {
    return this.request('GET', `/api/v1/events/stream?cursor=${encodeURIComponent(String(cursor))} & keepaliveSeconds=${encodeURIComponent(String(keepaliveSeconds))} & types=${encodeURIComponent(String(types))} & world=${encodeURIComponent(String(world))}`);
  }

  /** Liveness/auth check */
  getHealth(): Promise<MapiResult> {
    return this.request('GET', `/api/v1/health`);
  }

  /** Mod, API, Minecraft, and platform versions */
  getInfo(): Promise<MapiResult> {
    return this.request('GET', `/api/v1/info`);
  }

  /** Job view (state, milestones, failure, result) */
  getJob(): Promise<MapiResult> {
    return this.request('GET', `/api/v1/jobs/${encodeURIComponent(String(id))}`);
  }

  /** Bounded log capture with cursor reads and explicit gaps (spec §17.1) */
  readLogs(cursor?: number, limit?: number): Promise<MapiResult> {
    return this.request('GET', `/api/v1/logs?cursor=${encodeURIComponent(String(cursor))} & limit=${encodeURIComponent(String(limit))}`);
  }

  /** Operation metadata registry (spec §14) */
  listOperations(): Promise<MapiResult> {
    return this.request('GET', `/api/v1/operations`);
  }

  /** Request graceful local shutdown (administrative, spec §1.1/§14: operations:unrestricted). CAPABILITY_UNAVAILABLE until the loader adapter implements it. */
  requestShutdown(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/process/shutdown`, body);
  }

  /** Dispatch a server command in the console context. Administrative access: requires the operations:unrestricted grant (spec §14). Dispatch completion is distinct from asynchronous effects. */
  dispatchCommand(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/server/commands`, body);
  }

  /** Publish the integrated server to LAN (spec §9.3; network exposure, distinct server:publish scope). Disabled unless server.lan.enabled. The game port is exposed independently of API authentication. */
  publishLan(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/server/lan`, body);
  }

  /** Unpublish LAN (spec §9.3; 26.2 supports unpublish without world unload) */
  unpublishLan(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/server/lan/stop`, body);
  }

  /** Block id and block-entity data at a position (typed NBT when present) */
  queryBlock(dimension?: string, x?: number, y?: number, z?: number): Promise<MapiResult> {
    return this.request('GET', `/api/v1/server/queries/block?dimension=${encodeURIComponent(String(dimension))} & x=${encodeURIComponent(String(x))} & y=${encodeURIComponent(String(y))} & z=${encodeURIComponent(String(z))}`);
  }

  /** Loaded entities within a bounded region */
  queryEntities(dimension?: string, max?: number, radius?: number, x?: number, y?: number, z?: number): Promise<MapiResult> {
    return this.request('GET', `/api/v1/server/queries/entities?dimension=${encodeURIComponent(String(dimension))} & max=${encodeURIComponent(String(max))} & radius=${encodeURIComponent(String(radius))} & x=${encodeURIComponent(String(x))} & y=${encodeURIComponent(String(y))} & z=${encodeURIComponent(String(z))}`);
  }

  /** Online players with non-empty inventory slots (bounded) */
  queryPlayers(max?: number): Promise<MapiResult> {
    return this.request('GET', `/api/v1/server/queries/players?max=${encodeURIComponent(String(max))}`);
  }

  /** Registry summaries */
  listRegistries(): Promise<MapiResult> {
    return this.request('GET', `/api/v1/server/queries/registries`);
  }

  /** Sorted entry ids of one registry (bounded) */
  queryRegistryEntries(max?: number, registryId: string): Promise<MapiResult> {
    return this.request('GET', `/api/v1/server/queries/registry?max=${encodeURIComponent(String(max))} & registryId=${encodeURIComponent(String(registryId))}`);
  }

  /** Bounded path-level diff between two retained snapshots */
  diffSnapshots(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/server/snapshot-diffs`, body);
  }

  /** Capture and retain a bounded observation at the current boundary (spec §12) */
  captureSnapshot(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/server/snapshots`, body);
  }

  /** Read-only server status snapshot */
  getServerStatus(): Promise<MapiResult> {
    return this.request('GET', `/api/v1/server/status`);
  }

  /** Tick-control state (available:false when unsupported, spec §5) */
  getTickState(): Promise<MapiResult> {
    return this.request('GET', `/api/v1/server/ticks`);
  }

  /** Freeze the tick loop (lease-required, spec §5) */
  freezeTicks(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/server/ticks/freeze`, body);
  }

  /** Acquire the exclusive tick-control lease */
  acquireTickLease(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/server/ticks/lease`, body);
  }

  /** Set the tick rate within configured bounds (lease-required) */
  setTickRate(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/server/ticks/rate`, body);
  }

  /** Request a bounded sprint (asynchronous in vanilla; poll tick state) */
  sprintTicks(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/server/ticks/sprint`, body);
  }

  /** Step a bounded number of simulation ticks (202 + job) */
  stepTicks(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/server/ticks/step`, body);
  }

  /** Step ticks and capture a snapshot at the completion boundary (202 + job) */
  stepAndObserve(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/server/ticks/step-and-observe`, body);
  }

  /** Stop stepping/sprinting (lease-required) */
  stopTickWork(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/server/ticks/stop`, body);
  }

  /** Unfreeze the tick loop (lease-required) */
  unfreezeTicks(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/server/ticks/unfreeze`, body);
  }

  /** World-session phase, capabilities, and named clocks */
  getWorldInfo(): Promise<MapiResult> {
    return this.request('GET', `/api/v1/server/world`);
  }

  /**
   * Streams runtime events (GET /api/v1/events/stream).
   * Establish the cursor before triggering actions, then wait
   * from it (spec §13.2). Gap comments surface as
   * `{ gap: true, droppedUpToSeq }` yields.
   */
  async *streamEvents(cursor?: number): AsyncGenerator<
      { gap: false; id: string; event: string; data: unknown } |
      { gap: true; droppedUpToSeq: number }> {
    const query = cursor === undefined ? '' : `?cursor=${cursor}`;
    const response = await fetch(
        this.base + '/api/v1/events/stream' + query, {
      headers: { Authorization: `Bearer ${this.token}`,
               Host: '127.0.0.1' },
      signal: AbortSignal.timeout(this.timeoutMs),
    });
    if (!response.ok || !response.body) {
      throw new MapiError(response.status, 'STREAM_FAILED',
                         'event stream unavailable');
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let id = '';
    let event = 'message';
    let data: string[] = [];
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let index;
      while ((index = buffer.search(/\r\n|\n|\r/)) >= 0) {
        const line = buffer.slice(0, index);
        buffer = buffer.slice(index + (buffer[index] === '\r' && buffer[index + 1] === '\n' ? 2 : 1));
        if (line.startsWith(':')) {
          const comment = line.slice(1).trim();
          if (comment.startsWith('event-gap')) {
            const value = comment.split('droppedUpTo=')[1];
            yield { gap: true, droppedUpToSeq: Number(value) };
          }
          continue;
        }
        if (!line) {
          if (data.length > 0) {
            const text = data.join('\n');
            let parsed: unknown = text;
            try { parsed = JSON.parse(text); } catch { /* text */ }
            yield { gap: false, id, event, data: parsed };
          }
          id = '';
          event = 'message';
          data = [];
          continue;
        }
        const colon = line.indexOf(':');
        const field = colon < 0 ? line : line.slice(0, colon);
        let value = colon < 0 ? '' : line.slice(colon + 1);
        if (value.startsWith(' ')) value = value.slice(1);
        if (field === 'id') id = value;
        else if (field === 'event') event = value || 'message';
        else if (field === 'data') data.push(value);
      }
    }
  }
}
