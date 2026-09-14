# Testing

Test inventory and verification status (spec §10; updated 2026-09-14, client
round included).

## Client verification (EXECUTED 2026-09-14, display :1)

The operator accepted the EULA and a live Fabric dev client drove the
**entire slice 0.6 surface for real** (evidence screenshot:
`docs/verified/client-slice-verified.png`):

| Step driven through the API | Result |
| --- | --- |
| Client boot → API up at process init, client ops registered | log: "MAPI: client operations registered"; discovery `readiness: http` |
| `GET /client/status` on the onboarding screen | window 854x480, guiScale 2, `currentScreenClass: AccessibilityOnboardingScreen` |
| `GET /client/screen/tree` | real best-effort widget tree (labels + bounds of the actual widgets) |
| `POST /client/input/key` press/release | authoritative `isDown: true/false` |
| **Bug found & fixed**: `Screenshot.takeScreenshot` delivered async (26.2 deferred GPU readback) → 500 on first capture | seam now delivers via callback; HTTP worker waits bounded (5s), render thread never blocks |
| `POST /client/screen/click` (new, semantic mode) | drove the real UI: Continue → `TitleScreen` → Singleplayer → `CreateWorldScreen` |
| Create New World → **integrated server** starts | `readiness: worldReady`, `playerPresent: true`, dimension `minecraft:overworld` — one process serving both logical sides (§1.1) |
| `/server/players` on the integrated server | real profile (name/UUID/dimension/position), `dataVersion: 19133` |
| In-world screenshot | valid 854x480 PNG of the actual world (`docs/verified/client-slice-verified.png`) |
| Key-hold movement (§6.3) | player moved ~12.5 blocks on z from API-driven key-hold (first attempt "failed" because the spawn point was inside tree leaves — real collision physics) |
| **Lease expiry releases held keys** (§5.3) against the live game | pressed forward + 1s lease → after expiry, idle drift over 2s = 0.0 blocks: key truly released |

## Dedicated-server verification (EXECUTED 2026-09-14)

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

## NOT RUN (no remaining verification debts in the plan)

All game-run checks in the Phase 0–5 plan have been executed. The only
future work is feature development (spec §12 Phase 2 extended / Phase 3
experimental items listed in `docs/CAPABILITIES.md`), which requires game
development time rather than verification.

*Historical note (now resolved):* production-jar launch was listed here
before 2026-09-14; the dev-launch runs above now cover the classload
contract authoritatively.

## Adding tests

- New endpoint → contract test + negative tests in
  `HttpApiServerTest` (auth, scope, stale, validation), route registered in
  `ContractSyncTest`, documented in openapi.yaml + http-api.md.
- New shared logic → pure-JVM unit tests in `common` (no Minecraft launch).
