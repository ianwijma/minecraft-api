# Local HTTP API

Optional, disabled by default, loopback-only, bearer-token authenticated.
Implemented in `dev.example.mapi.internal.http.HttpApiServer` on the JDK's
built-in `com.sun.net.httpserver` (no bundled HTTP libraries).

Machine-readable description: [`openapi.yaml`](openapi.yaml).

## Lifecycle

- **Clients:** starts with the game (available at the main menu, before any
  world), survives world exit, and stops at client shutdown — main-menu
  interaction, screenshots, and window control work without a world. The
  `/server/*` namespace gates on world state (`WORLD_NOT_LOADED` at the
  menu; spec §6).
- **Dedicated servers:** starts when the server starts, stops with it, as
  before.
- Port conflict → error log (`MAPI HTTP API: failed to bind ...`), the game
  keeps running; no listener that session.
- If enabled without a usable token, the API refuses to start (explicit
  error log); the game is unaffected.

## Configuration

Config file: loader-native and auto-generated (see the table below); in dev
runs that is `<runDir>/config/`. Environment variables override the file.

| File key | Env var | Default | Meaning |
| --- | --- | --- | --- |
| `http.enabled` | `MAPI_HTTP_ENABLED` | `false` | enable the listener |
| `http.port` | `MAPI_HTTP_PORT` | `25586` | loopback port |
| `http.token` | `MAPI_HTTP_TOKEN` | generated | bearer token (auto-generated on first run; env wins). **Empty = auth disabled** (dangerous; loud warning) |
| `http.rateLimitPerMinute` | `MAPI_HTTP_RATE_LIMIT_PER_MINUTE` | `60` | per-client request budget |
| `http.scopes` | `MAPI_HTTP_SCOPES` | all | comma-separated granted scopes (spec §14); absent/blank grants the full set |

The bind address is fixed to loopback and is not configurable.

## Endpoints (protocol version 1)

All requests require `Authorization: Bearer <token>`. GET endpoints are
read-only; POST endpoints are operations that dispatch work through the
runtime (spec §5–§14) and enforce the token's granted scopes via
`OperationGuard`. POST bodies are strict JSON objects (≤ 8192 bytes,
parsed by `JsonReader`; unknown fields ignored, consumers must tolerate
unknown fields in responses too).

### `GET /api/v1/health`

```json
{"protocolVersion":1,"status":"ok"}
```

### `GET /api/v1/info`

```json
{"protocolVersion":1,"name":"mapi","version":"0.1.0","apiVersion":"0.1.0",
 "minecraftVersion":"26.2","platform":"fabric","platformVersion":"0.19.5"}
```

### `GET /api/v1/operations`

The operation metadata registry (spec §14): every POST operation with its
`requiredScopes`, `destructive`, `sideEffectClass`, `requiresLease`, and
`supportedExecutionModes`.

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

### `GET /api/v1/server/world`

World-session and capability status: `phase`
(`NONE`/`LOADING`/`ACTIVE`/`UNLOADING`), `worldSessionId` when present,
bridge id, sorted `capabilities`, booleans `tickControl`/`worldQueries`/
`commands`, and the named-clock registry (`clocks`, spec §4.1).

### `GET /api/v1/server/ticks`

Tick-control state: `available:false` when the bridge lacks tick control;
otherwise `frozen`, `sprinting`, `tickRate`, `tickCount`, `rateBounds`
(`min`/`max`), and `leaseId` when the topic is held.

### Tick-control operations (POST, scope `server:tick-control`, lease-required)

| Endpoint | Body | Effect |
| --- | --- | --- |
| `POST /api/v1/server/ticks/lease` | `{"ttlSeconds":1..3600}` (default 300) | acquires the exclusive tick-control lease; returns `leaseId` + `expiresAtEpochMs` |
| `POST /api/v1/server/ticks/freeze` | `{"leaseId"}` | freezes the tick loop; returns state |
| `POST /api/v1/server/ticks/unfreeze` | `{"leaseId"}` | unfreezes; returns state |
| `POST /api/v1/server/ticks/rate` | `{"leaseId","rate"}` | sets the tick rate within configured bounds |
| `POST /api/v1/server/ticks/step` | `{"leaseId","ticks":1..10000}` | **202** + `jobId`; steps a bounded number of ticks (spec §5) |
| `POST /api/v1/server/ticks/step-and-observe` | `{"leaseId","ticks","label"?}` | **202** + `jobId`; steps and captures a snapshot at the completion boundary |
| `POST /api/v1/server/ticks/sprint` | `{"leaseId","ticks"}` | requests a bounded sprint (asynchronous in vanilla; poll `GET /server/ticks`) |
| `POST /api/v1/server/ticks/stop` | `{"leaseId"}` | stops stepping/sprinting |

Tick-freeze limitation (spec §5): vanilla's freeze excludes players and
ridden entities — freezing is not freezing all game state, and `stepAndObserve`
is a scoped synchronization primitive, not global determinism.

Every tick op without a valid `leaseId` → **409** `LEASE_REQUIRED`.
Jobs are inspectable via `GET /api/v1/jobs/{id}` (state, milestones,
failure code) and terminate with `WORLD_UNLOADED` when their world session
ends.

### Queries (GET, read-only, bounded)

| Endpoint | Notes |
| --- | --- |
| `GET /api/v1/server/queries/players?max=` | online players with non-empty inventory slots (`slot`/`itemId`/`count`) |
| `GET /api/v1/server/queries/entities?dimension&x&y&z&radius&max` | loaded entities within a bounded region (radius ≤ 128) |
| `GET /api/v1/server/queries/block?dimension&x&y&z` | block id + block-entity type and typed NBT data when present |
| `GET /api/v1/server/queries/registries` | registry summaries (`id`, `size`) |
| `GET /api/v1/server/queries/registry?registryId&max` | sorted entry ids (max ≤ 1000) |

Queries never generate chunks; only loaded levels/entities are observable.
Unknown dimensions → **400** `BAD_REQUEST` (never guessed). No world loaded
→ **409** `WORLD_NOT_LOADED`.

### Snapshots (spec §12)

| Endpoint | Body | Effect |
| --- | --- | --- |
| `POST /api/v1/server/snapshots` | `{"label","maxPlayers"?,"maxEntities"?}` | captures and retains a bounded observation at the current tick boundary; returns `snapshotId`/`boundary` |
| `POST /api/v1/server/snapshot-diffs` | `{"firstId","secondId","includePaths"?,"maxChanges"?}` | path-level `added`/`removed`/`changed` records with before/after wire values, `truncated`, `unavailablePaths` |

Expired comparisons → **410** `SNAPSHOT_EXPIRED`; unknown ids → **404**.
Absence from a diff is never a destruction claim.

### Commands

`POST /api/v1/server/commands` — body `{"command"}` (leading slash trimmed,
≤ 4096 chars). Requires the `operations:unrestricted` grant — this is
**administrative access** (spec §14): commands are not classified or made
safe by the API, and dispatch completion is distinct from asynchronous
effects. Response: `{"dispatched","success","failure"?,"resultCode"}` plus a
`command.dispatched` event on the stream.

### Jobs

`GET /api/v1/jobs/{id}` — job view (`id`, `kind`, `state`
(`PENDING`/`RUNNING`/`SUCCEEDED`/`FAILED`/`CANCELLED`), timestamps,
`failureCode`/`failureMessage` when present, `result`, ordered
`milestones`). Unknown id → **404**.

## Errors and status codes

| Status | Code | Cause |
| --- | --- | --- |
| 400 | `BAD_REQUEST` | malformed body/parameters |
| 401 | `UNAUTHORIZED` | missing/invalid bearer token (response includes `WWW-Authenticate: Bearer`) |
| 403 | `FORBIDDEN_HOST` | Host header not loopback (`localhost`, `127.0.0.1`, `[::1]`) |
| 403 | `FORBIDDEN_ORIGIN` | Origin header present but not the local listener origin; CORS stays disabled |
| 404 | `NOT_FOUND` | unknown path or resource |
| 405 | `METHOD_NOT_ALLOWED` | method not supported by this endpoint (`Allow` header) |
| 413 | `PAYLOAD_TOO_LARGE` | body above 8192 bytes |
| 422 | `EXECUTION_MODE_UNSUPPORTED` | requested executionMode not supported by the operation (no silent fallback, spec §3.3) |
| 429 | `RATE_LIMITED` | over the per-client rate limit (`Retry-After`) |
| 410 | `SNAPSHOT_EXPIRED` | snapshot retention period elapsed |
| 409 | `WORLD_NOT_LOADED` / `STALE_WORLD` / `WORLD_UNLOADED` | world-session gates (spec §6) |
| 409 | `SERVER_PAUSED` / `CLOCK_NOT_ADVANCING` | simulation progress unavailable (spec §4.4) |
| 409 | `LEASE_HELD` / `LEASE_REQUIRED` | exclusive control-lease gates (spec §5) |
| 503 | `SERVER_BUSY` | bounded server-thread wait elapsed |
| 503 | `CAPABILITY_UNAVAILABLE` | loader bridge does not provide this capability (spec §15.3) |
| 504 | `DEADLINE_EXCEEDED` | wall-clock deadline elapsed (spec §4.3) |
| 428 | `DESTRUCTIVE_INTENT_REQUIRED` | destructive request without explicit intent (spec §14) |
| 500 | `INTERNAL` | unexpected server-side failure |

`ProblemCode` (`internal/problem/ProblemCode.java`) is the authoritative
registry: wire name, HTTP status, retryability. Error bodies may include a
structured `details` object alongside `code`/`message`.

Response headers always include `Content-Type: application/json; charset=utf-8`,
`Cache-Control: no-store`, and `X-MAPI-Protocol-Version: 1`.

## Event stream (SSE)

`GET /api/v1/events/stream` — long-lived `text/event-stream` of runtime
events (spec §13.1). The normal bearer header is required; there are no
query-string tokens or stream tickets, and CORS stays disabled.

Query parameters:

| Param | Default | Meaning |
| --- | --- | --- |
| `cursor` | latest | resume strictly after this sequence number (`0` replays retained history) |
| `types` | all | comma-separated event-type filter |
| `world` | all | restrict to a `worldSessionId` |
| `keepaliveSeconds` | 15 | 1–120; comment-line keepalive interval |

Frame format: `id:` (sequence number), `event:` (type), `data:` (JSON object
with `seq`, `type`, `atEpochMs`, optional `worldSessionId`, `payload`).
Comment lines (`:...`) carry `keepalive <ts>` and
`event-gap droppedUpTo=<seq>` notices for explicit gap handling. A consumer
should establish the cursor before triggering actions, then wait from that
cursor. At most 8 concurrent streams per listener (429 `RATE_LIMITED`
beyond the cap); each stream occupies a worker thread headroom slot while
the steady-state request pool stays at 2.

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
- Additive changes (new optional fields) do not bump the protocol version;
  consumers must ignore unknown fields.

## What this API will never do

No endpoints for command execution, file access, world mutation, player
identities, chat, or source-code editing. Coding-agent interaction uses the
developer's own authorized tools, never the mod's HTTP surface
(`docs/llm-workflow.md`).