# Local HTTP API

Optional, disabled by default, loopback-only, bearer-token authenticated.
Implemented in `dev.example.mapi.internal.http.HttpApiServer` on the JDK's
built-in `com.sun.net.httpserver` (no bundled HTTP libraries).

Machine-readable description: [`openapi.yaml`](openapi.yaml).

## Lifecycle

- **Process lifetime (slice 0.2).** When enabled, the listener starts at mod
  initialization and stays up for the whole process: before any world
  session, across repeated integrated-server sessions, and until shutdown.
  World state is reported via `readiness`, not via the listener's life.
- Discovery heartbeat: the discovery file is rewritten on every readiness
  change and on a fixed interval (`http.discoveryHeartbeatSeconds`), so
  consumers can detect dead instances (heartbeat age + PID, never PID alone).
- Port conflict → error log (`MAPI HTTP API: failed to bind ...`), the game
  keeps running; no listener that session. `http.portFallback` tries the
  consecutive ports above `http.port`; `http.failFast` turns bind failure
  into a hard startup failure (for harness/CI runs).
- If enabled without a resolvable token, the API refuses to start (explicit
  error log); the game is unaffected.

## Configuration

Config file: `<configDir>/mapi.properties` (per instance; in dev runs that is
`<runDir>/config/mapi.properties`). Environment variables override the file.
See `docs/examples/mapi.properties.example`.

| File key | Env var | Default | Meaning |
| --- | --- | --- | --- |
| `http.enabled` | `MAPI_HTTP_ENABLED` | `false` | enable the listener |
| `http.port` | `MAPI_HTTP_PORT` | `25586` | loopback port |
| `http.token` | `MAPI_HTTP_TOKEN` | — | bearer token (≥16 chars; env preferred) |
| `http.tokenFile` | `MAPI_HTTP_TOKEN_FILE` | `<gameDir>/mcapi/token` | token file; auto-generated when enabled and absent |
| `http.rateLimitPerMinute` | `MAPI_HTTP_RATE_LIMIT_PER_MINUTE` | `60` | per-client request budget |
| `http.instanceId` | `MAPI_INSTANCE_ID` | `mapi-<port>` | instance identifier (sanitized to `[a-z0-9-]`, ≤32 chars) |
| `http.portFallback` | `MAPI_HTTP_PORT_FALLBACK` | `0` | try up to N consecutive ports above `http.port` |
| `http.failFast` | `MAPI_HTTP_FAIL_FAST` | `false` | hard-fail startup when no port can be bound |
| `http.discoveryHeartbeatSeconds` | `MAPI_DISCOVERY_HEARTBEAT_SECONDS` | `30` | discovery file refresh interval (5–3600) |
| `http.scopes` | `MAPI_HTTP_SCOPES` | all scopes | comma-separated scope set bound to the token |

### Token resolution

When the API is enabled, the token is resolved in this order:

1. `MAPI_HTTP_TOKEN` environment variable,
2. `http.token` in `mapi.properties`,
3. the token file (`http.tokenFile`, default `<gameDir>/mcapi/token`).

If the token file does not exist, MAPI generates a 256-bit random token and
writes it atomically with owner-only permissions (POSIX `rw-------` where
supported). The token value is **never logged**; new tokens are logged with
a non-secret 12-hex-digit SHA-256 fingerprint and the file location only.
If no token can be resolved or generated, the API refuses to start (explicit
error log) and the game is unaffected.

The bind address is fixed to loopback and is not configurable.

## Endpoints (protocol version 1)

All require `Authorization: Bearer <token>`. Reads are `GET`; the mutating
surface (`POST /api/v1/tasks`, `DELETE /api/v1/tasks/{id}`,
`POST /api/v1/events/ticket`) uses `POST`/`DELETE`. Every response carries an
`X-MAPI-Request-Id` header (echoing a client `X-Request-Id` ≤64 printable
chars when supplied); every error body includes it as `requestId`.

### `GET /api/v1/health`

```json
{"protocolVersion":1,"status":"ok"}
```

### `GET /api/v1/live`

Process liveness: the listener answers, independent of world state.

```json
{"protocolVersion":1,"live":true}
```

### `GET /api/v1/ready`

Readiness states. `readiness` is the coarse state (`http` = listener
answering, no world session; `worldReady` = a server session is active;
`clientJoined` is reserved for client operations and never reported yet).

```json
{"protocolVersion":1,"readiness":"worldReady",
 "states":{"http":true,"worldReady":true,"clientJoined":false}}
```

### `GET /api/v1/time`

Clocks for cross-instance correlation. `wallClock` (epoch ms) and
`monotonicNanos` are process-local values; cross-instance ordering must use
observed conditions, not timestamp alignment. `serverTick` reports the tick
count via the same bounded snapshot as `/api/v1/server/status` — unknown data
is explicit, never empty:

```json
{"protocolVersion":1,"wallClock":1726200000000,"monotonicNanos":1234567890123,
 "serverTick":{"available":true,"value":1234}}
```

Server not running (200):

```json
{"protocolVersion":1,"wallClock":1726200000000,"monotonicNanos":1234567890123,
 "serverTick":{"available":false,"reason":"SERVER_NOT_RUNNING"}}
```

If the bounded snapshot times out → **503** `SERVER_BUSY`.

### `GET /api/v1/info`

```json
{"protocolVersion":1,"name":"mapi","version":"0.1.0","apiVersion":"0.1.0",
 "minecraftVersion":"26.2","platform":"fabric","platformVersion":"0.19.5",
 "instanceId":"mapi-25586","processSessionId":"0f1e…","worldSessionId":"9a8b…",
 "physicalSide":"dedicatedServer","availableLogicalSides":["server"]}
```

Identity fields (see `docs/roadmap.md` for the target session model):

- `instanceId` — configured (`http.instanceId`) and sanitized identifier.
- `processSessionId` — unique per launch.
- `worldSessionId` — present only while a world/server session is active;
  changes when a world is opened/closed/replaced. Absent (not empty/null)
  otherwise.
- `physicalSide` — `client` or `dedicatedServer`; a physical client is
  `client` even while hosting an integrated server.
- `availableLogicalSides` — logical sides this process can currently serve
  (`["server"]` while a server session runs; `client` appears once client
  operations exist).

### `GET /api/v1/server/status`

Server not running (200):

```json
{"protocolVersion":1,"running":false}
```

Server running (200) — fields in this exact order:

```json
{"protocolVersion":1,"running":true,"capturedAtEpochMs":1726200000000,
 "startedAtEpochMs":1726199900000,"uptimeMs":100000,"playerCount":0,
 "maxPlayers":20,"tickCount":1234,"averageTickTimeMs":1.42,"motd":"A Minecraft Server"}
```

`motd` is the plain-text message of the day; player identities are never
exposed. If the server thread cannot produce the snapshot within the bounded
wait → **503**:

```json
{"error":{"code":"SERVER_BUSY","message":"Server thread busy; status snapshot timed out. Retry shortly."},"protocolVersion":1}
```

### `GET /api/v1/files?path=`, `PUT|POST /api/v1/files` (slice 3.2, experimental)

Sandboxed file surface (spec §4.5/§6.1): confined to the instance game
directory, symlink-escaped paths rejected, no-follow writes, and a denylist
protecting `mcapi/token`, `config/mapi.properties`, `eula.txt`,
`server.properties`, `ops.json`, whitelist/ban caches, and `logs/`. Requires
the `files.read` scope for reads and `files.write` for writes, **plus** the
`files.enabled` switch (default false) — otherwise **403** `DISABLED`.

- `GET …?path=` — empty path lists the root; a directory returns
  `{type:"dir",entries:[{name,dir,size,symlink}]}`; a file returns
  `{type:"file",encoding:"utf-8"|"base64",content,truncated,size}` (content
  bounded at 1 MiB with `truncated:true` beyond).
- `POST {"path","contentBase64"}` — create/overwrite (≤1 MiB), refusing
  symlink leaves; `files.written` audit event.
- Outside/protected paths → **403** `DENIED_PATH`; missing → **404**.

### `POST /api/v1/client/screen/click` (semantic mode)

Screen interaction (spec §5.2/§6.3 core): routes a click through the open
screen's own mouse handlers (`mouseClicked`/`mouseReleased`) — the same
mechanism a physical click funnels into. Body: `{"x":213,"y":223,"mode":
"semantic"}` (GUI space; `mode` must be `semantic` or absent — other modes
rejected, no silent fallbacks). Response reports `consumed` (whether the
screen handled the click) and emits `client.screen.click`.

### `POST /api/v1/unsafe/reflect`, `POST /api/v1/unsafe/invoke` (slice 3.1, experimental)

Trusted developer execution (spec §4.3): **runs with the privileges of the
Minecraft process — no sandbox is claimed.** Requires the `unsafe.execute`
scope **and** the `reflection.enabled` switch (default false; spec §4.5) —
otherwise **403** `DISABLED`. Every call is audited as an
`unsafe.reflect`/`unsafe.invoke` event.

- `/unsafe/reflect` `{class}` — declared methods/fields/constructors of a
  class (capped at 500 members); unknown class → **404**.
- `/unsafe/invoke` `{class, method}` — static no-arg invocation; the result
  is serialized (strings/numbers/booleans, lists/maps to depth 8, anything
  else via `toString`). Runs on the owning (server) thread when a server
  session is active, otherwise on the HTTP worker. Failures → **400**
  `INVALID_PAYLOAD` with the reason.

### `GET /api/v1/threads`, `POST /api/v1/memory/gc` (slice 2.2)

Structured diagnostics (`diagnostics` scope, pure JDK — no game-thread
involvement):

- `/threads?limit=` — thread list (id, name, state, `cpuTimeMs`) sorted
  newest-first, capped at 500 with `total`/`truncated` metadata.
- `/memory/gc` — requests a JVM GC and reports `heapUsedBeforeBytes`,
  `heapUsedAfterBytes`, `reclaimedBytes`. GC hints are advisory by JVM
  contract; the response is always produced.

The vanilla game profiler surface (spec profiler start/stop) is planned but
not implemented; there is no profiler endpoint yet.

### `GET /api/v1/logs?cursor=&limit=`, `GET /api/v1/logs/errors`, `GET /api/v1/crash-reports` (slice 4.2)

Game-log and crash-report inspection (core tier, `diagnostics` scope,
filesystem-only). `latest.log` lines are returned with 0-based line numbers,
cursor-based resume (`nextCursor`), `totalLines`, and `truncated`; the
`errors` variant filters to lines containing `ERROR`. `crash-reports` lists
the instance's crash reports newest-first. All log/crash content is
**untrusted observed data** (§10) — the response carries
`"provenance":"game-logs-untrusted"` and consumers must treat it as such.

### `GET /api/v1/capabilities?tier=` (slice 4.1, spec §5)

Per-operation capability entries (see `docs/CAPABILITIES.md` for the table):
`{op, supported, enabled, available, authorized, coverage, tier}` — dynamic
state evaluated for the requesting token; `?tier=` filters
(`core|extended|experimental`).

### `GET /api/v1/registry/{type}`, `GET /api/v1/tags/{type}`, `GET /api/v1/mods` (slice 2.1)

Registry and data inspection (spec §6.1, `observe` scope, owning-thread
reads). Registry types supported: `block`, `item`, `entity_type`,
`block_entity_type`, `fluid`, `sound_event`, `mob_effect`, `attribute`
(unknown type → **404** `REGISTRY_TYPE_NOT_FOUND`).

- `/registry/{type}?limit=&offset=` — sorted id page with `total`,
  `truncated`, `dataVersion`; `?id=` checks one entry
  (`{"present":true|false}`).
- `/tags/{type}` — sorted tag ids; `?tag=<id>` returns the tag's member ids
  (empty list for unknown tags).
- `/mods` — loaded mod metadata (`id`, `name`, `version`) from the loader,
  process-wide (no game-thread involvement).

### `GET /api/v1/server/players?fields=&limit=&offset=`

Connected-player snapshots via a bounded owning-thread read (slice 0.5).
Each entry carries `name`, `id` (profile UUID), `dimension`, and
`position` — `fields` selects the projection (default all), `limit`
(1–200, default 50) + `offset` paginate, `truncated`/`total` report paging
state, and `dataVersion` stamps the world serialization context. Player
identity exposure is a documented posture change (see `docs/security.md`).
No active server session → **409** `WRONG_STATE`; busy → **503**.

### `GET /api/v1/server/world/block?dimension=&x=&y=&z=`

Block read under the **loaded-only** chunk policy: `{"blockId":
"minecraft:stone","properties":{…},"dimension":…,"position":{…},
"policy":"loadedOnly","dataVersion":…}`. Unknown dimension → **404**
`DIMENSION_NOT_FOUND`; unloaded chunk → **409** `CHUNK_UNLOADED` (explicit
failure, never an empty guess).

### `GET /api/v1/server/world/block-entity?dimension=&x=&y=&z=` (slice 2.3)

Block-entity read (loaded-only policy, `world.read` scope):

- Loaded chunk + block entity present → `{"available":true,"typeId":
  "minecraft:chest","position":{…},"nbt":{…},"policy":"loadedOnly",
  "dataVersion":…}`.
- Loaded chunk, no block entity → `{"available":false,"reason":
  "NO_BLOCK_ENTITY"}`.
- Unloaded chunk → **409** `CHUNK_UNLOADED`; unknown dimension → **404**
  `DIMENSION_NOT_FOUND`.

The `nbt` payload uses the **typed NBT JSON** convention (§7, lossless):
bytes `{"b":n}`, shorts `{"s":n}`, longs `{"l":"n"}` (string), floats
`{"f":n}` (string of the value), byte/int/long arrays as `{"ba":[]}` /
`{"ia":[]}` / `{"la":["…"]}`, lists as `{"list":[…]}`; ints, doubles,
strings, and compounds are JSON-native. Read-only: this endpoint never
mutates state.

### `GET /api/v1/server/world/storage?dimension=&x=&y=&z=` (slice 2.5)

Container storage read (spec §6.2 storage adapter, read side; loaded-only
policy, `world.read` scope). Vanilla containers (chest, furnace, hopper, …)
expose their slots: `{"available":true,"typeId":"minecraft:chest",
"slots":[{"slot":0,"itemId":"minecraft:diamond","count":3},…],
"totalSlots":27,"units":"item-counts"}` — counts are native item units;
empty slots are omitted. Not a container → `{"available":false,"reason":
"NOT_A_CONTAINER","typeId":…}`; no block entity → `NO_BLOCK_ENTITY`;
unloaded chunk → **409** `CHUNK_UNLOADED`. Insert/extract (mutation side
with remainders) requires the loaders' transactional transfer APIs and is
planned, not implemented.

### `GET /api/v1/server/world/time?dimension=`

World clocks (26.2 time model): `gameTime`, `overworldClockTime`,
`defaultClockTime` for the requested dimension.

### `GET /api/v1/tasks?state=&limit=` and `GET /api/v1/tasks/{id}`

Task protocol (spec §3.1). States: `queued → running → succeeded | failed`,
`cancelRequested → cancelled` (cooperative), and `expired` on wall-time
deadline. Tasks record progress (`units`, `total`, `estimated`), a JSON
`result` on success, `partialEffects` that already happened, `cleanup`
steps, and an `error {code,message}` on failure. Listing supports
`state` (wire name) and `limit` (1–200, default 50) with `truncated`/`total`
metadata.

### `POST /api/v1/tasks`

Body: `{"kind":"wait-for-tick","payload":{"targetTick":1234},"deadlineMs":30000}`.
Returns **202** with the task snapshot and `Location: /api/v1/tasks/{id}` —
never shape-switches on speed. Unknown `kind` → **400** `UNSUPPORTED`;
malformed body → **400** `INVALID_JSON` / `INVALID_PAYLOAD`.

Built-in kinds (Phase 0): `wait-for-tick` — succeeds when the server tick
counter reaches `payload.targetTick`; fails `WRONG_STATE` when no server
session is active, `DEADLINE_EXCEEDED` on expiry, or is cancelled
cooperatively between polls.

### `DELETE /api/v1/tasks/{id}`

Requests cancellation: running/queued tasks move to `cancelRequested` then
`cancelled` (or `failed` with `LIFECYCLE_CHANGED` if a world session ends
first). Cancelling an already-finished task → **409** `WRONG_STATE`.

### `Idempotency-Key` (POST /api/v1/tasks)

Header-scoped per token + process session; the first 202 response is
replayed for identical bodies (`Idempotent-Replay: true`) and reuse with a
different body → **422** `IDEMPOTENCY_MISMATCH`. Keys are in-memory, kept
for up to 24h (bounded by process session and a 1000-entry cap).

### `GET /api/v1/events?after=&limit=`

Event cursor poll (spec §3.3). Events carry a per-process-session monotonic
`seq`, `eventType`, `source`, clocks, session ids, and `data`. `after` is an
exclusive cursor (default 0 = retained history); the response includes
`headSeq`, `oldestSeq`, `truncated`, and an explicit `gap: {from, to}`
object when the requested history was evicted from the ring buffer
(capacity 1024).

Event types published in Phase 0: `api.started`, `api.stopped`,
`server.starting`, `server.stopped`, `readiness.changed`, and
`task.state_changed` — sources `api-originated` / `instrumented`.

### `POST /api/v1/events/ticket`

Mints a short-lived single-use ticket (30s TTL) for browser-style WebSocket
auth: `{"ticket":"…","expiresAtEpochMs":…,"events":{"scheme":"ws","host":
"127.0.0.1","port":…}}`. SDKs/tools use the Authorization header on the WS
port instead.

### `GET /api/v1/client/status` and `GET /api/v1/client/screen/tree` (slice 0.6)

Client-side observations, served only on physical clients with registered
client operations (Fabric client entrypoint / NeoForge `@OnlyIn` classes);
otherwise **409** `WRONG_STATE`. Reads run on the client thread with the
same bounded wait as server reads (busy → **503**).

- `/client/status`: window and GUI-scale dimensions, the open screen's
  simple class name (absent while the HUD shows), `playerPresent`,
  `dimension`, and `gameTime` (absent when not in a world).
- `/client/screen/tree`: best-effort semantic widget tree of the open screen
  — `{"coverage":"best-effort","root":{widgetClass,label,x,y,width,height,
  children:[…]}}`. The root has no `widgetClass` when the HUD is showing.

### `POST /api/v1/client/input/key` (input mode)

Drives the game's own key-mapping path (`KeyMapping.set`/`click`) — the same
mechanism the physical keyboard funnels into; no window-event spoofing, no
direct state writes. Body:

```json
{"mapping": "key.forward", "action": "press", "mode": "input"}
```

`mapping` must be a name the client itself reports (the supported set covers
movement/interaction keys: forward, left, back, right, jump, sneak, sprint,
inventory, drop, chat, attack, use, pickItem, swapHands, playerlist,
togglePerspective — unknown names → **400** `INVALID_PAYLOAD`). `action` is
`press` | `release` | `tap` (tap = event-style click, no held state —
movement keys should use press/release). `mode` must be `input` or absent:
other values are rejected, **no silent fallbacks** (spec §5.2). The response
reports the authoritative state right after the action:
`{"mapping":…,"action":…,"isDown":true}` (`isDown` absent for `tap`).

### `POST /api/v1/client/screenshot`

Captures the main framebuffer as PNG into the instance's
`mcapi/screenshots/frame-<frameId>.png` (controlled path; retrieval is via
the game directory on the same host). `frameId` is a monotonic per-process
identifier of the captured frame instance:

```json
{"protocolVersion":1,"frameId":1,"path":"mcapi/screenshots/frame-1.png",
 "width":1920,"height":1080,"bytes":245123}
```

Both actions emit `client.input.key` / `client.screenshot` events
(`api-originated`). HUD toggle, region capture, annotated screenshots with a
render-lifecycle `frameId`, and macro record/replay are future slice 0.6
work (documented, not yet implemented).

### `POST /api/v1/server/commands/execute`

Executes a command **as the console** through the game's own dispatcher with
a permission **ceiling** (`http.commandPermissionLevel`, default 2 =
gamemaster; 0..4). Requires the `commands.execute` scope. Arbitrary modded
commands are documented as **broad authority** — the ceiling bounds what the
source may do, and every execution emits a `server.command` event. Body:
`{"command":"say hi","expectedWorldSessionId":…}` (leading slash optional).
Response: `{"result":<n>,"success":true,"feedback":["…"],"permissionLevel":2}`
— `result`/`success` are absent when the command failed validation before
execution (the failure text is in `feedback`). Feedback is captured through
the command source during the execution window; server logs remain separate.
"Run as player" is not part of this surface: console execution never
inherits a player identity.

### `POST /api/v1/leases`, `POST /api/v1/leases/{id}/renew`, `DELETE /api/v1/leases/{id}`, `GET /api/v1/leases`

Control leases (spec §5.3): expiring, renewable grants for
`client.input`, `client.ui`, `client.camera`, `server.tick`,
`world.bulkEdit`. Acquire body: `{"lease":"client.input","ttlMs":60000,
"conflict":"reject|queue|preempt"}` (defaults: ttl 60s clamped to
[1s, 1h], conflict `reject`). Conflict handling: `reject` → **409**
`LEASE_HELD` with the held lease under `held`; `queue` → FIFO activation on
release/expiry; `preempt` → the current holder is marked `preempted` and
its cleanup hook runs. Renewal: `{"ttlMs":…}` from now. States:
`held`, `queued`, `released`, `expired`, `preempted`.

On expiry, release, preemption, disconnect, or world-session end every
lease of that type runs its cleanup hook — for `client.input` that releases
every key the API is currently holding (physical user input is untouched).
Lease changes emit `lease.changed` events. Scopes: acquiring
`client.*`/`world.bulkEdit`/`server.tick` leases requires
`client.control`/`world.write`/`lifecycle.manage` respectively; listing
requires `observe`.

### `GET|POST /api/v1/ext/{id}/…` (slice 2.4)

Mod-provided extension surface (spec §6.2 ext SPI): every registered
`MapiHttpExtension` (public Java API) exposes operations under its own
prefix. Requests are authenticated and checked against the extension's
declared `requiredScope()`; `GET …/$schema` returns the extension's
self-describing schema; unknown extensions → **404**; handler failures →
**500**. Every invocation emits an `ext.invoked` event. The extension adds
no authority beyond the token's scopes — see `docs/api.md` for the
contract mod authors implement.

### WebSocket event stream

The JDK HTTP stack cannot host protocol upgrades, so the event stream runs
on a **dedicated loopback port** (OS-assigned; advertised in `/api/v1/info`
as `events` and in the discovery file). Auth: `Authorization: Bearer …`
header, or `?ticket=` from the endpoint above. Flow:

1. Server sends `{"type":"hello","protocolVersion":1,"processSessionId":…,
   "headSeq":…,"oldestSeq":…}`.
2. Client sends `{"type":"subscribe","after":<seq>,"policy":"drop-oldest"|`
   `"disconnect"}`; server answers `{"type":"subscribed",…}`.
3. Server pushes `{"type":"event","event":{seq,…}}` messages; history is
   replayed from `after`, with an explicit `{"type":"gap",…}` when the
   cursor precedes retained history or when a slow consumer forces drops
   (queue capacity 256 per connection).

## Discovery file

While the API is running, MAPI writes
`<gameDir>/mcapi/discovery.json` (schema version 1, written atomically) so
local tools can find the instance without guessing ports:

```json
{"schemaVersion":1,"instanceId":"client-2","processSessionId":"…","pid":123,
 "startedAt":"…","lastSeen":"…","readiness":"worldReady","physicalSide":"client",
 "loader":"fabric","mcVersion":"26.2",
 "api":{"scheme":"http","host":"127.0.0.1","port":25586},"labels":{}}
```

- The file contains **no secrets**; the bearer token is never written to it.
- `instanceId` is the sanitized `http.instanceId` value.
- It is written when the API starts and removed when it stops; a leftover
  file after a crash is detected as stale by consumers (PID + heartbeat, not
  PID alone — harness rules land with the harness).
- Readers must treat the content as untrusted data.

## Scopes (spec §4.2)

The token carries a scope set (`http.scopes`, default: all). Authorization
is effect-based, not route-based: reads and event streams require
`observe`; `/api/v1/server/world/*` requires `world.read`;
`/api/v1/client/input/key` requires `client.control`; task orchestration
requires `observe` plus the task kind's own requirement. A request whose
token lacks the required scope → **403** `FORBIDDEN_SCOPE` with
`"required":"<scope>"`. `/api/v1/info` reports the token's scopes. Known
scopes: `observe`, `diagnostics`, `client.control`, `world.read`,
`world.write`, `commands.execute`, `lifecycle.manage`, `files.read`,
`files.write`, `unsafe.execute` (the latter five gate endpoints that land
with later Phase 1/2 slices).

## Errors and status codes

| Status | Code | Cause |
| --- | --- | --- |
| 400 | `INVALID_JSON` / `INVALID_PAYLOAD` / `INVALID_QUERY` / `INVALID_HEADER` | malformed body, payload fields, query params, or headers |
| 400 | `UNSUPPORTED` | unknown task kind or unsupported operation |
| 401 | `UNAUTHORIZED` | missing/invalid bearer token (response includes `WWW-Authenticate: Bearer`) |
| 403 | `FORBIDDEN_HOST` | Host header not loopback (`localhost`, `127.0.0.1`, `[::1]`) |
| 403 | `FORBIDDEN_ORIGIN` | Origin header present but not the local listener origin; CORS stays disabled |
| 404 | `NOT_FOUND` | unknown path or task id |
| 404 | `DIMENSION_NOT_FOUND` | dimension id unknown to this server |
| 405 | `METHOD_NOT_ALLOWED` | method outside GET/POST/DELETE (`Allow: GET, POST, DELETE`) |
| 409 | `WRONG_STATE` | operation requires an active server session / task already finished |
| 409 | `CHUNK_UNLOADED` | block read hit an unloaded chunk under the loaded-only policy |
| 413 | `PAYLOAD_TOO_LARGE` | body above 8192 bytes |
| 422 | `IDEMPOTENCY_MISMATCH` | Idempotency-Key reused with a different body |
| 429 | `RATE_LIMITED` | over the per-client rate limit (`Retry-After`) |
| 500 | `INTERNAL` | unexpected server-side failure |
| 503 | `SERVER_BUSY` | snapshot timeout; also observed when workers saturate (connection may be dropped) |

Task-level error codes (`error.code` inside task snapshots):
`INVALID_PAYLOAD`, `WRONG_STATE`, `DEADLINE_EXCEEDED`, `CANCELLED`,
`LIFECYCLE_CHANGED`, `INTERNAL`.

Response headers always include `Content-Type: application/json; charset=utf-8`,
`Cache-Control: no-store`, and `X-MAPI-Protocol-Version: 1`.

## curl examples

```bash
export MAPI_HTTP_TOKEN='...your token...'
BASE=http://127.0.0.1:25586
AUTH="Authorization: Bearer $MAPI_HTTP_TOKEN"

curl -sS -H "$AUTH" "$BASE/api/v1/health"
curl -sS -H "$AUTH" "$BASE/api/v1/info"
curl -sS -H "$AUTH" "$BASE/api/v1/server/status"

# expected failures, for the paranoid:
curl -sS -i "$BASE/api/v1/health"                      # 401
curl -sS -i -H "Host: evil.example.com" -H "$AUTH" "$BASE/api/v1/health"   # 403
curl -sS -i -X POST -H "$AUTH" "$BASE/api/v1/health"                        # 405
```

## Standalone client example

`scripts/mapi-client.py` (Python 3 stdlib, token via env var):

```bash
export MAPI_HTTP_TOKEN='...'
python3 scripts/mapi-client.py status
python3 scripts/mapi-client.py info
python3 scripts/mapi-client.py health --base http://127.0.0.1:25586
```

## Versioning

- The URL prefix `/api/v1/` and the `X-MAPI-Protocol-Version`/
  `protocolVersion` fields are the **HTTP protocol version** (currently `1`).
  It changes only with a breaking HTTP contract change and is independent of
  the mod version (`docs/api.md`) and the Minecraft version.
- Additive changes (new optional fields such as the `/api/v1/info` identity
  fields) do not bump the protocol version; consumers must ignore unknown
  fields. The target-architecture endpoint catalog (`/v1/…` prefix) is
  planned as **protocol version 2** — see `docs/roadmap.md`.

## What this API will not do (current phase)

The Phase 0 surface is read-only for game state plus a small, safe mutation
surface: task orchestration (`/tasks`) and event streaming (`/events`).
There are no endpoints for command execution, file access, world mutation,
chat, or source-code editing. Connected-player identity is now exposed by
`/api/v1/server/players` under the documented posture change
(`docs/security.md`, `docs/roadmap.md` §2.1 D7). Wider mutation scopes
(commands, world writes, files) land only with the Phase 1 scope model.