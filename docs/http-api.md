# Local HTTP API

Optional, disabled by default, loopback-only, bearer-token authenticated.
Implemented in `dev.example.mapi.internal.http.HttpApiServer` on the JDK's
built-in `com.sun.net.httpserver` (no bundled HTTP libraries).

Machine-readable description: [`openapi.yaml`](openapi.yaml).

## Lifecycle

- Starts when a Minecraft server (dedicated **or** integrated) starts, only
  if enabled; stops with the server. Repeated integrated-server sessions
  start/stop it cleanly.
- Port conflict → error log (`MAPI HTTP API: failed to bind ...`), the game
  keeps running; no listener that session.
- If enabled without a usable token, the API refuses to start (explicit
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

All require `Authorization: Bearer <token>`. GET only.

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
| 401 | `UNAUTHORIZED` | missing/invalid bearer token (response includes `WWW-Authenticate: Bearer`) |
| 403 | `FORBIDDEN_HOST` | Host header not loopback (`localhost`, `127.0.0.1`, `[::1]`) |
| 403 | `FORBIDDEN_ORIGIN` | Origin header present but not the local listener origin; CORS stays disabled |
| 404 | `NOT_FOUND` | unknown path |
| 405 | `METHOD_NOT_ALLOWED` | non-GET (`Allow: GET`) |
| 413 | `PAYLOAD_TOO_LARGE` | body above 8192 bytes |
| 429 | `RATE_LIMITED` | over the per-client rate limit (`Retry-After`) |
| 503 | `SERVER_BUSY` | snapshot timeout; also observed when workers saturate (connection may be dropped) |
| 500 | `INTERNAL` | unexpected server-side failure |

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

## What this API will never do

No endpoints for command execution, file access, world mutation, player
identities, chat, or source-code editing. Coding-agent interaction uses the
developer's own authorized tools, never the mod's HTTP surface
(`docs/llm-workflow.md`).