# Security model

Scope: the optional local HTTP API and general secret handling in this
repository.

## Network posture

- **Loopback only.** The listener binds `127.0.0.1` explicitly (IPv4
  loopback, deterministic even under
  `-Djava.net.preferIPv6Addresses=system`, which NeoForge dev runs set);
  the bind address is not configurable. Direct off-host connections cannot
  reach the listener. A proxy or tunnel can forward remote traffic to a
  loopback socket; loopback binding does not protect against that exposure.
- **Host validation.** Requests whose `Host` header is not `localhost`,
  `127.0.0.1`, or `[::1]` are rejected with 403 `FORBIDDEN_HOST` (DNS
  rebinding defense). The listener itself binds the IPv4 loopback.
- **Browser origins use an exact allowlist.** The hosted dashboard origin
  `https://mapi.wij.ma` is included in new configs by default and can be
  removed. `http.allowedOrigins` (or `MAPI_HTTP_ALLOWED_ORIGINS`) accepts only
  exact `http(s)` origins, with no wildcards. Preflight is restricted to those
  origins and the API's required methods/headers; local-network preflight permission
  is sent only when such a request explicitly asks for it. Responses include
  CORS headers only for an allowed origin. A foreign origin is rejected with
  403 `FORBIDDEN_ORIGIN`.
- **Bearer token required on every endpoint by default**, including
  `/health`. Tokens are compared with constant-time `MessageDigest.isEqual`.
  Explicitly short tokens (< 16 chars) are refused at startup.
- **Generated configs ship with a generated token.** First-run configs
  (NeoForge `mapi-common.toml`, Fabric `mapi.json`) contain a fresh 32-byte
  base64url token — read it from the config file or copy it from the local
  `/mapi status` client command. Client feedback never prints the token in
  visible chat text. The explicit dedicated console `mapi status`/`mapi enable`
  commands print credentials to the local console (and may therefore record
  them in server logs); no feedback is broadcast to players.
  `MAPI_HTTP_TOKEN` (env) wins over the file.
- **Blank token = authentication disabled (explicit operator choice).**
  Setting `http.token` to an empty string skips auth on every endpoint with
  a loud startup warning. Danger: every local process gains full control,
  including administrative operations. Never enable on shared machines.
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
  (blocked unless the operator explicitly allowlists that exact origin; bearer
  authentication and operation authorization still apply).
- **Out of scope:** remote attackers (cannot reach a loopback socket),
  malicious mods on the same server (they can call the Java API directly —
  the Java API exposes no secrets), and physical access.
- POST routes are authorized through operation metadata before their handlers
  run. The router requires every POST path to map to a registered descriptor,
  and the OpenAPI contract references the descriptor with
  `x-mapi-operation`. Window mutations require `client:settings`.
- Client input and inventory actions require the exclusive `input` lease.
  Tick-control and LAN publication operations require the `tick-control`
  lease. Lease ownership is checked at route dispatch and again inside queued
  client input or game-thread work, so an expired lease cannot authorize work
  that has not yet reached its dispatch point.
- Inventory click uses `client-logic`. Its response confirms only that the
  click was dispatched; it reports `effectVerified: false` because server
  application is not observed or confirmed.
- Direct and API-driven menu connections require `client:connect`, the current
  `input` lease, and an exact allowlist match for the requested host and port.
  The registered client safety hooks carry that authorization into
  `ConnectScreen`'s resolver thread. Every resolver input, including an SRV
  redirect target, must also match the allowlist. After vanilla resolution,
  the final numeric address and exact port must independently match a literal
  IP entry such as `203.0.113.7:25565` or `[2001:db8::7]:25565`. A hostname
  entry does not approve its DNS result. MAPI does not use reverse DNS or
  resolve the hostname again after selecting the socket destination.
- API connection ownership follows server-initiated transfers from that
  Minecraft client session. The original input lease is checked again when a
  transfer starts, before each resolver input, and before accepting the final
  numeric destination. An explicit manual join clears API ownership; manual
  sessions and their transfers keep vanilla behavior.
- The connection hooks are required mixins in both loader artifacts. If a
  Minecraft update or another mod prevents either required hook from applying,
  startup fails closed rather than exposing API connection control without
  the checks. Ordinary manual connections have no API request context and keep
  vanilla behavior.

## Thread-safety of game state

HTTP worker threads never read live game objects. Status snapshots are
produced on the Minecraft server thread via `ServerHandle.executeOnServerThread`
with a bounded wait; timeouts yield 503 rather than stale guesses. The tick
thread is never blocked by network work.

## Secrets handling in this repository

- Tokens come from `MAPI_HTTP_TOKEN` (preferred) or `http.token` in
  the loader-native config file (NeoForge `mapi-common.toml`, Fabric
  `mapi.json`); both are gitignored patterns. `docs/examples/` contains
  placeholder-only examples.
- Automatic diagnostics never log token values. Explicit local console
  connection commands print the credentials requested by the operator; protect
  those console logs as credentials. Never commit `.env`, tokens, `eula.txt`, run directories, logs, or worlds.
- Configure `MAPI_ACCEPT_EULA=true` for supervised tests under the owner’s
  standing acceptance; do not request acceptance repeatedly.

## Incident checklist

If a token leaks: generate a new one
(`python3 -c "import secrets; print(secrets.token_urlsafe(32))"`), update the
env/config, restart the server session. Tokens are per-instance by design.
