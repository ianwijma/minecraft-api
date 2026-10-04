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
  headers: Record<string, string>;
}

function queryValue(value: string | number | boolean | string[] | number[]): string {
  return Array.isArray(value) ? value.join(',') : String(value);
}

export class MapiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly body: unknown;
  readonly headers: Record<string, string>;
  constructor(status: number, code: string, message: string,
              body: unknown = undefined, headers: Record<string, string> = {}) {
    super(`HTTP ${status} ${code}: ${message}`);
    this.status = status;
    this.code = code;
    this.body = body;
    this.headers = headers;
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
    const responseHeaders = Object.fromEntries(response.headers.entries());
    const ok = response.status >= 200 && response.status < 300;
    if (!ok && parsed && typeof parsed === 'object'
        && parsed.error && typeof parsed.error.code === 'string') {
      throw new MapiError(response.status, parsed.error.code,
                         String(parsed.error.message ?? ''), parsed, responseHeaders);
    }
    return { status: response.status, body: parsed, ok, headers: responseHeaders };
  }

  /** Generic GET for a documented or extension path. */
  get(path: string): Promise<MapiResult> {
    return this.request('GET', path);
  }

  /** Generic POST for a documented or extension path. */
  post(path: string, body: unknown): Promise<MapiResult> {
    return this.request('POST', path, body);
  }

  /** Client bridge identity and capabilities. CAPABILITY_UNAVAILABLE semantics apply per feature on dedicated servers (no client bridge). */
  getClientInfo(): Promise<MapiResult> {
    const suffix = '';
    return this.request('GET', `/api/v1/client` + suffix);
  }

  /** Click the active screen at GUI coordinates through client logic. Requires the exclusive input lease; returns dispatch and consumed state. */
  clickScreen(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/client/actions/click`, body);
  }

  /** Hold a key for N client ticks (raw-input only, spec §3.4/§4.2). Returns an action receipt; unsupported modes fail with EXECUTION_MODE_UNSUPPORTED (no silent fallback). Key-up is always dispatched before a deadline failure surfaces (release-all). */
  holdKey(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/client/actions/hold-key`, body);
  }

  /** Join a server through the vanilla connect flow (§9.2; requires client:connect scope and the client.connect.allowlist — empty list denies all). */
  connectServer(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/client/connect`, body);
  }

  /** Acquire or renew the exclusive client input lease */
  acquireClientControlLease(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/client/control/lease`, body);
  }

  /** Player inventory menu slots (§10.3): non-empty slots with itemId and count, carried-stack size, container id. */
  inspectInventory(): Promise<MapiResult> {
    const suffix = '';
    return this.request('GET', `/api/v1/client/inventory` + suffix);
  }

  /** Container click (client-logic mode, §3.1/§10.3): dispatches through client logic. The response reports dispatch acceptance; it does not verify the resulting server state. */
  clickInventory(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/client/inventory/click`, body);
  }

  /** Computed tooltip lines for a slot's item (§10.2: calculated without reproducing hover — rendered capture is a separate path). Includes item name, durability, enchantments, and lore. */
  getTooltip(slot: number): Promise<MapiResult> {
    const query = new URLSearchParams();
    query.set('slot', queryValue(slot));
    const suffix = query.size ? `?${query}` : '';
    return this.request('GET', `/api/v1/client/inventory/tooltip` + suffix);
  }

  /** Rendered tooltip capture (§10.2): opens the inventory, hovers over the slot, captures a screenshot showing the tooltip as the game actually displayed it. Returns both the visual evidence and the computed lines. */
  captureRenderedTooltip(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/client/inventory/tooltip-rendered`, body);
  }

  /** Execute straight-line waypoints (raw-input; required mod, spec §16). No teleport fallback. Receipt reports per-leg boundaries; effects verified separately via world queries. */
  moveWaypoints(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/client/movement/waypoints`, body);
  }

  /** Inspect the active screen's widgets (§10.1-lite: recognized widget structures with rendered text and bounds). Empty at in-world state. */
  inspectScreen(): Promise<MapiResult> {
    const suffix = '';
    return this.request('GET', `/api/v1/client/screen` + suffix);
  }

  /** Screenshot capture with frame/scale metadata (spec §20) */
  captureScreenshot(): Promise<MapiResult> {
    const suffix = '';
    return this.request('GET', `/api/v1/client/screenshots` + suffix);
  }

  /** Window state with framebuffer/logical distinction (spec §9.1) */
  getWindowState(): Promise<MapiResult> {
    const suffix = '';
    return this.request('GET', `/api/v1/client/window` + suffix);
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

  /** Saved singleplayer worlds */
  listWorlds(): Promise<MapiResult> {
    const suffix = '';
    return this.request('GET', `/api/v1/client/worlds` + suffix);
  }

  /** Create a fresh world with vanilla defaults and a NORMAL preset (202; async — poll /server/world for phase ACTIVE). Creation over an existing id fails; never overwrites. */
  createWorld(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/client/worlds/create`, body);
  }

  /** Delete a saved world (destructive: requires operations:destructive grant AND "confirm": true, spec §14). */
  deleteWorld(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/client/worlds/delete`, body);
  }

  /** Load a saved world (202; loads asynchronously — poll GET /api/v1/server/world for phase ACTIVE). */
  loadWorld(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/client/worlds/load`, body);
  }

  /** Liveness/auth check */
  getHealth(): Promise<MapiResult> {
    const suffix = '';
    return this.request('GET', `/api/v1/health` + suffix);
  }

  /** Mod, API, Minecraft, and platform versions */
  getInfo(): Promise<MapiResult> {
    const suffix = '';
    return this.request('GET', `/api/v1/info` + suffix);
  }

  /** Job view (state, milestones, failure, result) */
  getJob(id: string): Promise<MapiResult> {
    const suffix = '';
    return this.request('GET', `/api/v1/jobs/${encodeURIComponent(String(id))}` + suffix);
  }

  /** Bounded log capture with cursor reads and explicit gaps (spec §17.1) */
  readLogs(cursor?: number, limit?: number): Promise<MapiResult> {
    const query = new URLSearchParams();
    if (cursor !== undefined) query.set('cursor', queryValue(cursor));
    if (limit !== undefined) query.set('limit', queryValue(limit));
    const suffix = query.size ? `?${query}` : '';
    return this.request('GET', `/api/v1/logs` + suffix);
  }

  /** Operation metadata registry (spec §14) */
  listOperations(): Promise<MapiResult> {
    const suffix = '';
    return this.request('GET', `/api/v1/operations` + suffix);
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

  /** Unpublish LAN (spec §9.3; requires the tick-control lease) */
  unpublishLan(body: Record<string, unknown>): Promise<MapiResult> {
    return this.request('POST', `/api/v1/server/lan/stop`, body);
  }

  /** Block id and block-entity data at a position (typed NBT when present) */
  queryBlock(dimension?: string, x?: number, y?: number, z?: number): Promise<MapiResult> {
    const query = new URLSearchParams();
    if (dimension !== undefined) query.set('dimension', queryValue(dimension));
    if (x !== undefined) query.set('x', queryValue(x));
    if (y !== undefined) query.set('y', queryValue(y));
    if (z !== undefined) query.set('z', queryValue(z));
    const suffix = query.size ? `?${query}` : '';
    return this.request('GET', `/api/v1/server/queries/block` + suffix);
  }

  /** Loaded entities within a bounded region */
  queryEntities(dimension?: string, max?: number, radius?: number, x?: number, y?: number, z?: number): Promise<MapiResult> {
    const query = new URLSearchParams();
    if (dimension !== undefined) query.set('dimension', queryValue(dimension));
    if (max !== undefined) query.set('max', queryValue(max));
    if (radius !== undefined) query.set('radius', queryValue(radius));
    if (x !== undefined) query.set('x', queryValue(x));
    if (y !== undefined) query.set('y', queryValue(y));
    if (z !== undefined) query.set('z', queryValue(z));
    const suffix = query.size ? `?${query}` : '';
    return this.request('GET', `/api/v1/server/queries/entities` + suffix);
  }

  /** Online players with non-empty inventory slots (bounded) */
  queryPlayers(max?: number): Promise<MapiResult> {
    const query = new URLSearchParams();
    if (max !== undefined) query.set('max', queryValue(max));
    const suffix = query.size ? `?${query}` : '';
    return this.request('GET', `/api/v1/server/queries/players` + suffix);
  }

  /** Registry summaries */
  listRegistries(): Promise<MapiResult> {
    const suffix = '';
    return this.request('GET', `/api/v1/server/queries/registries` + suffix);
  }

  /** Sorted entry ids of one registry (bounded) */
  queryRegistryEntries(registryId: string, max?: number): Promise<MapiResult> {
    const query = new URLSearchParams();
    query.set('registryId', queryValue(registryId));
    if (max !== undefined) query.set('max', queryValue(max));
    const suffix = query.size ? `?${query}` : '';
    return this.request('GET', `/api/v1/server/queries/registry` + suffix);
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
    const suffix = '';
    return this.request('GET', `/api/v1/server/status` + suffix);
  }

  /** Tick-control state (available:false when unsupported, spec §5) */
  getTickState(): Promise<MapiResult> {
    const suffix = '';
    return this.request('GET', `/api/v1/server/ticks` + suffix);
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
    const suffix = '';
    return this.request('GET', `/api/v1/server/world` + suffix);
  }

  /**
   * Streams runtime events (GET /api/v1/events/stream).
   * Establish the cursor before triggering actions, then wait
   * from it (spec §13.2). Gap comments surface as
   * `{ gap: true, droppedUpToSeq }` yields.
   */
  async *streamEvents(cursor?: number, types?: string | string[],
                       world?: string, keepaliveSeconds?: number, signal?: AbortSignal): AsyncGenerator<
      { gap: false; id: string; event: string; data: unknown } |
      { gap: true; droppedUpToSeq: number }> {
    const params = new URLSearchParams();
    if (cursor !== undefined) params.set('cursor', String(cursor));
    if (types !== undefined) params.set('types', Array.isArray(types) ? types.join(',') : types);
    if (world !== undefined) params.set('world', world);
    if (keepaliveSeconds !== undefined) params.set('keepaliveSeconds', String(keepaliveSeconds));
    const query = params.size ? `?${params}` : '';
    const response = await fetch(
        this.base + '/api/v1/events/stream' + query, {
      headers: { Authorization: `Bearer ${this.token}`,
               },
      signal,
    });
    if (!response.ok || !response.body) {
      throw new MapiError(response.status, 'STREAM_FAILED',
                         'event stream unavailable', undefined,
                         Object.fromEntries(response.headers.entries()));
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let id = '';
    let event = 'message';
    let data: string[] = [];
    try {
    while (true) {
      const { done, value } = await reader.read();
      if (done && !buffer) break;
      buffer += done ? decoder.decode() : decoder.decode(value, { stream: true });
      let index;
      while ((index = buffer.search(/\r\n|\n|\r/)) >= 0) {
        if (!done && buffer[index] === '\r' && index === buffer.length - 1) break;
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
      if (done) break;
    }
    } finally {
      await reader.cancel();
      reader.releaseLock();
    }
  }
}
