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
| `http.rateLimitPerMinute` | `MAPI_HTTP_RATE_LIMIT_PER_MINUTE` | `60` | per-client request budget |

The bind address is fixed to loopback and is not configurable.

## Endpoints (protocol version 1)

All require `Authorization: Bearer <token>`. GET only.

### `GET /api/v1/health`

```json
{"protocolVersion":1,"status":"ok"}
```

### `GET /api/v1/info`

```json
{"protocolVersion":1,"name":"mapi","version":"0.1.0","apiVersion":"0.1.0",
 "minecraftVersion":"26.2","platform":"fabric","platformVersion":"0.19.5"}
```

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
| 403 | `INSUFFICIENT_SCOPE` | caller lacks a required scope/grant (operation endpoints; spec §14) |
| 428 | `DESTRUCTIVE_INTENT_REQUIRED` | destructive request without explicit intent (spec §14) |

Error codes are centrally registered in
`internal/problem/ProblemCode` (wire name, HTTP status, retryability).
Operation-level codes named by the product specification
(`EXECUTION_MODE_UNSUPPORTED`, `WORLD_NOT_LOADED`, `STALE_WORLD`,
`WORLD_UNLOADED`, `SERVER_PAUSED`, `CLOCK_NOT_ADVANCING`,
`SNAPSHOT_EXPIRED`, `TOOLTIP_SEMANTICS_UNAVAILABLE`) are registered but not
yet emitted — they appear as the corresponding endpoints land
(`docs/execution-plan.md`). Error bodies may include a structured
`details` object alongside `code`/`message` when an operation provides one.

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
- Additive changes (new optional fields) do not bump the protocol version;
  consumers must ignore unknown fields.

## What this API will never do

No endpoints for command execution, file access, world mutation, player
identities, chat, or source-code editing. Coding-agent interaction uses the
developer's own authorized tools, never the mod's HTTP surface
(`docs/llm-workflow.md`).