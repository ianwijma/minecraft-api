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

## Token bootstrap

When the API is enabled, the token is resolved from `MAPI_HTTP_TOKEN`, then
`http.token`, then the token file (`http.tokenFile`, default
`<gameDir>/mcapi/token`). A missing token file is **auto-generated**
(256-bit random) and written atomically with owner-only permissions (POSIX
`rw-------` on the file and `rwx------` on the containing directory; on
filesystems without POSIX attributes, default platform protections apply).

- The token value is **never logged** — neither in plain logs nor in the
  discovery file, HTTP responses, or crash reports.
- Newly generated tokens log a non-secret 12-hex-digit SHA-256 fingerprint
  plus the file location for correlation.
- The `mcapi/` directory is a runtime secret/data directory; commit patterns
  (`.gitignore`: `*token*`) keep its contents out of version control.

## Discovery file

`<gameDir>/mcapi/discovery.json` advertises the running instance to local
tools (schema version 1, atomic writes). It contains no secrets — instance
id, process/session identifiers, PID, readiness, versions, and the loopback
endpoint only. Consumers must treat it as untrusted data and validate the
endpoint before sending credentials. It is removed when the API stops.

## Resource limits

| Limit | Value | Behavior when hit |
| --- | --- | --- |
| Request body | 8192 bytes | 413 (declared size checked before reading) |
| Worker threads | 2 daemon | bounded queue (32); saturation drops connections |
| Rate limit | 60/min per client (configurable) | 429 + `Retry-After: 60` |
| Snapshot wait | 500 ms | 503 `SERVER_BUSY` |
| Bind conflict | n/a | error log, game unaffected |

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

- Tokens come from `MAPI_HTTP_TOKEN` (preferred), `http.token` in
  `mapi.properties`, or the token file `<gameDir>/mcapi/token` (auto-written
  when enabled and absent); all are gitignored patterns. `docs/examples/`
  contains placeholder-only examples.
- Never log token values (the code logs lengths/enablement only); never
  commit `.env`, tokens, `eula.txt`, run directories, logs, or worlds.
- `scripts/server-smoke.sh` requires `MAPI_ACCEPT_EULA=true` explicitly;
  CI never accepts the EULA on anyone's behalf.

## Incident checklist

If a token leaks: generate a new one
(`python3 -c "import secrets; print(secrets.token_urlsafe(32))"`), update the
env/config, restart the server session. Tokens are per-instance by design.