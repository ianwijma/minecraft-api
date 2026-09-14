# Testing

Test inventory and verification status (spec §10; updated 2026-09-13).

## What runs in `./gradlew verify`

| Module | Suite | Covers |
| --- | --- | --- |
| `common` | 145+ JUnit tests | config parsing (tokens, scopes, switches), JSON parser/writer, rate limiter, discovery file (schema, atomicity, no-secrets), session identity, HTTP contract + negative tests per endpoint (auth, scope, stale-session, idempotency, validation), task protocol (lifecycle, cancellation, expiry, partial effects, LIFECYCLE_CHANGED), WebSocket E2E (JDK client: hello/subscribe/replay/gap/tickets/slow-consumer/revocation), named failure scenarios (§10), lease manager, reflection surface, file sandbox (traversal/symlink/denylist), registry/tags/mods fakes, contract-sync tripwipes (routes ↔ openapi ↔ http-api.md, pinned subset + `$ref` resolution) |
| `harness` | discovery validation, staleness (heartbeat age, never PID alone), readiness waiter |
| `mcp-adapter` | E2E against a live in-process instance (initialize, tools/list, tools/call incl. commands/ui, unknown tool/method) |
| `sdk:java` | E2E via discovery + token files (reads, task flow with idempotency + preconditions, leases/commands, event cursors) |
| `example-consumer` | public-API-only consumption path |
| `sdk/python` (`sdkPythonTest`) | stub-server suite: discovery hardening, auth errors, idempotent tasks, leases/commands, event gap reporting |
| `sdk/typescript` (`sdkTypeScriptTest`) | stub-server suite mirroring the Python coverage |

Also in verify: Spotless formatting, `verifyDistributions` (jar metadata,
shared classes exactly once, no bundling, **classload tripwire** forbidding
`net/minecraft/client` references outside `dev/example/mapi/client/`), and
`validateManifest`.

## NOT RUN (environment-gated)

These require an operator/CI environment that can launch Minecraft 26.2
(EULA acceptance is an explicit operator step; `AGENTS.md` §7):

- `scripts/server-smoke.sh <loader>` — dedicated-server startup + live HTTP
  probe (CI runs it only when `vars.MAPI_ACCEPT_EULA == 'true'`).
- `scripts/launch-matrix.sh` (`./gradlew launchMatrix`) — two-instance
  matrix through discovery + HTTP contracts.
- In-game verification of the client endpoints (slice 0.6) on a
  display-capable machine.
- Production-jar launch test (authoritative dedicated-server classload
  check — the packaging scan is a tripwire, not a proof).
- Failure scenarios that need a real game: token revocation on a live game
  WS, frozen-tick under real load, mixed-loader E2E.

## Adding tests

- New endpoint → contract test + negative tests in
  `HttpApiServerTest` (auth, scope, stale, validation), route registered in
  `ContractSyncTest`, documented in openapi.yaml + http-api.md.
- New shared logic → pure-JVM unit tests in `common` (no Minecraft launch).
