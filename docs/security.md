# Security model

Scope: the optional local HTTP API and general secret handling in this
repository.

## Network posture

- **Loopback only.** The listener binds `127.0.0.1` explicitly (IPv4
  loopback, deterministic even under
  `-Djava.net.preferIPv6Addresses=system`, which NeoForge dev runs set);
  the bind address is not configurable. Remote clients cannot connect even
  if the port were forwarded.
- **Host validation.** Requests whose `Host` header is not `localhost`,
  `127.0.0.1`, or `[::1]` are rejected with 403 `FORBIDDEN_HOST` (DNS
  rebinding defense). The listener itself binds the IPv4 loopback.
- **CORS disabled.** No `Access-Control-*` response headers are ever
  emitted. A request carrying an `Origin` that is not the local listener
  origin is rejected (403 `FORBIDDEN_ORIGIN`).
- **Bearer token required on every endpoint**, including `/health`. Tokens
  are compared with constant-time `MessageDigest.isEqual`. Tokens shorter
  than 16 characters are refused at startup.
- **No TLS.** Loopback traffic is unencrypted by design; a token prevents
  other local users/processes from casually reading status data. Do not
  expose the port beyond loopback (no tunneling without understanding the
  consequences).

## Resource limits

| Limit | Value | Behavior when hit |
| --- | --- | --- |
| Request body | 8192 bytes | 413 (declared size checked before reading) |
| Worker threads | 2 steady-state daemon (headroom to 10 only for event-stream connections, capped at 8 concurrent streams) | bounded queue (32); saturation drops connections; 429 beyond stream cap |
| Rate limit | 60/min per client (configurable) | 429 + `Retry-After: 60` |
| Snapshot wait | 500 ms | 503 `SERVER_BUSY` |
| Bind conflict | n/a | error log, game unaffected |

## Operation metadata and scopes (spec §14)

Every operation endpoint declares its security metadata through
`internal/operation/OperationDescriptor` and every call is checked by
`OperationGuard`:

- `requiredScopes` — the operation's normal scopes
  (`client:connect`, `client:settings`, `server:tick-control`,
  `server:publish`).
- `destructive` — destructive operations additionally require the
  `operations:destructive` grant **and** explicit request intent (428
  `DESTRUCTIVE_INTENT_REQUIRED` without it). A request flag alone never
  grants permission; a grant alone never replaces intent. Matching
  target/world identity is enforced by world-scoped handlers.
- `sideEffectClass` — `read-only`, `local`, `game`, or `unrestricted`.
  `unrestricted` operations (administrative access) must require the
  `operations:unrestricted` scope and are never classified by parsing
  command names.
- `requiresLease` — whether control-lease ownership is needed.
- `supportedExecutionModes` — `raw-input`, `client-logic`, `privileged`;
  unsupported requested modes fail with 422 `EXECUTION_MODE_UNSUPPORTED`
  (no silent fallback).

Scope grants are config-backed: `http.scopes` / `MAPI_HTTP_SCOPES` lists
the scopes the token grants (comma-separated wire names). When unset, the
token grants the full set, which preserves today's behavior: the token
authenticates, authorization metadata still gates destructive and
unrestricted operations by intent.

`executionMode` is not a permission system: it selects the mechanism, while
scopes/destructive/intent select the authorization.

## Threat model (explicit)

- **In scope:** a local user/process reading basic server status without the
  token; scripts hammering the port; malicious web pages in a local browser
  (blocked by Host/Origin checks + no CORS).
- **Out of scope:** remote attackers (cannot reach a loopback socket),
  malicious mods on the same server (they can call the Java API directly —
  the Java API exposes no secrets), and physical access.
- The API is read-only by construction: no POST/PUT/DELETE handlers exist,
  and no endpoint touches the filesystem, runs commands, mutates the world,
  or exposes player identities, chat, paths, or environment variables.

## Thread-safety of game state

HTTP worker threads never read live game objects. Status snapshots are
produced on the Minecraft server thread via `ServerHandle.executeOnServerThread`
with a bounded wait; timeouts yield 503 rather than stale guesses. The tick
thread is never blocked by network work.

## Secrets handling in this repository

- Tokens come from `MAPI_HTTP_TOKEN` (preferred) or `http.token` in
  `mapi.properties`; both are gitignored patterns. `docs/examples/` contains
  placeholder-only examples.
- Never log token values (the code logs lengths/enablement only); never
  commit `.env`, tokens, `eula.txt`, run directories, logs, or worlds.
- `scripts/server-smoke.sh` requires `MAPI_ACCEPT_EULA=true` explicitly;
  CI never accepts the EULA on anyone's behalf.

## Incident checklist

If a token leaks: generate a new one
(`python3 -c "import secrets; print(secrets.token_urlsafe(32))"`), update the
env/config, restart the server session. Tokens are per-instance by design.