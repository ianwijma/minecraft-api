# Testing

Test inventory and verification status (spec §10; updated 2026-09-14).

## Game-run verification (EXECUTED 2026-09-14)

The operator accepted the EULA (`MAPI_ACCEPT_EULA=true`) and the following
checks were executed for real, on this repository, against Minecraft 26.2:

| Check | Loaders | Result | Evidence |
| --- | --- | --- | --- |
| `scripts/launch-matrix.sh` (two instances, discovery → `worldReady`, HTTP contract incl. identity fields, `api.started` event, task protocol) | fabric + neoforge | **PASS** | console: "both instances report worldReady", "fabric HTTP contract PASS", "neoforge HTTP contract PASS" |
| `scripts/server-smoke.sh` startup + live HTTP probe (401 unauthenticated / 200 + JSON authenticated on `health`, `info`, `server/status`) | fabric | **PASS** | per-endpoint probe lines in the smoke log |
| same | neoforge | **PASS** | same probe lines, `platform=neoforge` in the `/info` body |
| Authoritative dedicated-server classload check | neoforge | **PASS** | the dedicated server booted with the client source set present in the jar and did not touch client-only classes (no `ClassNotFound`/`NoClassDefFound`); the `@OnlyIn` annotation was removed in the same change set because NeoForge 26.2 no longer strips it and warns |

Issues found and fixed during the runs (all in the launch/smoke scripts, not
the mod itself):

- `launch-matrix.sh` `wait_ready` returned on file-parse instead of
  `worldReady` → instances were declared not-ready immediately.
- Both dev servers bound the same gameplay port 25565; the matrix now writes
  a distinct `server-port` per instance (`server.properties`).
- `server-smoke.sh` wiped `build/smoke` on every invocation, destroying the
  other loader's artifacts; it now wipes only its own loader directory.
- `collect-artifacts.sh` was exercised against a real (post-shutdown) run
  dir: it correctly reports the absent discovery file, collects the log
  tail, and the redaction sweep verified no token material in the bundle.

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

## NOT RUN (still environment-gated)

Only the display-dependent client checks remain — a physical client needs a
GUI to create/join a world (the client endpoints themselves are what would
drive it, so automated client E2E needs an earlier bootstrap path; tracked
as slice 0.6 open item):

- `:fabric:runClient` / `:neoforge:runClient` in-game verification of
  `/client/status`, `/client/screen/tree`, `/client/input/key`,
  `/client/screenshot` on a display-capable machine.
- Two-client same-loader E2E and mixed-loader scenarios with real clients.
- Production-jar (non-dev-launch) server run.

## Adding tests

- New endpoint → contract test + negative tests in
  `HttpApiServerTest` (auth, scope, stale, validation), route registered in
  `ContractSyncTest`, documented in openapi.yaml + http-api.md.
- New shared logic → pure-JVM unit tests in `common` (no Minecraft launch).
