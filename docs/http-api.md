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
  The `mode` parameter and input interaction land with the next slice 0.6
  increments (no silent fallbacks — spec §5.2).

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