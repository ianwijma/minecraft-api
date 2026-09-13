# Roadmap — target architecture review, reconciliation, and delivery plan

This document bridges the current MAPI 0.1.0 foundation and the **target
architecture spec** for the agent-facing API (sides/sessions model, tasks,
events, scopes, harness, MCP adapter). It has three parts:

1. **Review findings** — gaps, contradictions, and decisions the spec still
   needs from the owner (section 2).
2. **Reconciliation map** — what exists today vs. what the spec requires
   (section 3).
3. **Delivery plan** — Phase 0 sliced into independently shippable,
   verifiable increments (section 4).

Spec references like "§2.4" point at the target architecture spec. Repo rules
(`AGENTS.md`) remain authoritative for toolchain and process; where the spec
and the repo disagree today, the delta is listed as a finding, not silently
resolved.

## 1. Target architecture in one paragraph

One HTTP service per JVM process exposing read/control operations for
external tools, with **explicit session identity** (`processSessionId`,
`worldSessionId`, `connectionSessionId`), **completion levels**
(`applied/observed/rendered/persisted`), **owning-thread execution** for all
game state, a **task protocol** (202 + task objects), a **WebSocket event
stream** with per-session sequence cursors, **scopes + leases + action
modes** for safe interaction, a **harness** for process supervision and
scenarios, and **parity across Fabric and NeoForge** enforced by tests.

## 2. Spec review findings (2026-09-13)

### 2.1 Decisions required before the affected work lands

| # | Finding | Recommendation |
| --- | --- | --- |
| D1 | **URL prefix.** Spec catalog uses `/v1/…` (§6); the repo's documented protocol is `/api/v1/…` with `protocolVersion: 1`. Switching prefixes is a breaking HTTP contract change. | Keep `/api/v1/` for additive Phase 0 work; schedule the `/v1/` catalog as **protocol version 2** with a deprecation window. Per spec §1.3 the contract change lands in the spec repo first. |
| D2 | **Minecraft target.** Spec examples say MC **26.1** (§1.3, §9); the repo pins **26.2** and `AGENTS.md` §7 forbids silent target changes. Two-release support policy (§1.3) conflicts with the single-target-per-branch model. | Keep 26.2 on this branch; document the branch policy in `SUPPORT.md` when written (Phase 0 slice 8). Example JSON in the spec should say 26.2 or "matches branch". |
| D3 | **WebSocket transport.** The JDK's `com.sun.net.httpserver` has **no WebSocket server support** and `AGENTS.md` §3 forbids bundling unnecessary dependencies. | Options: (a) implement a minimal RFC 6455 server (handshake + text frames) on raw sockets behind the same auth middleware; (b) ship a small provided-only WS library. Decide before slice 0.4. Interim: the event **core** (seq, cursors, GAP policy) is transport-agnostic and testable without WS. |
| D4 | **Unix socket transport.** JDK NIO supports Unix domain sockets, but `com.sun.net.httpserver` cannot bind them; a second transport implementation would be needed. | Defer unless an owner use case exists; document as non-goal until then. |
| D5 | **TLS.** Spec §4.5 (self-signed with fingerprint in discovery) requires generating a self-signed certificate — the JDK has **no public API** for certificate generation; that needs a library or a pre-provisioned keystore. | Decide (library vs. operator-provided keystore) before any remote/tunnel support. Loopback-plain remains the default. |
| D6 | **HTTP service lifetime.** Spec §1.1 says one HTTP service per **process**; today HTTP starts/stops with the server session (integrated sessions would drop the listener between worlds, breaking `/live` and event resume). | Move HTTP bootstrap to process init (slice 0.2), keep the listener bound while disabled? No — keep "disabled = no listener", but once enabled it stays up across world sessions; readiness reflects world state. |
| D7 | **Security posture change.** `docs/http-api.md` and `docs/security.md` currently promise **"no command execution, no file access, no world mutation, no player identities, ever"**. The spec introduces commands, files (sandboxed), world writes, and scoped player data (§4.2, §6.2). | When Phase 1 mutation endpoints land, the "will never do" sections are replaced by the scope model + tier table. That is a documented posture change requiring owner sign-off (the spec itself is the sign-off artifact). |
| D8 | **Module restructure.** Spec §1.2 demands 7 modules (`api-core`, `game-common`, `game-client`, `loader-*`, `harness`, `mcp-adapter`, `fixture-mod`); the repo has `common`/`fabric`/`neoforge`/`example-consumer` with a JDK-only shared module. | Restructure in stages (slice 0.5/0.6), not as a big-bang: keep `common` publishing stable while new source sets appear; `verifyDistributions`/`validateManifest` updated per stage. |

### 2.2 Gaps and clarifications (no code decision needed)

| # | Finding | Resolution |
| --- | --- | --- |
| G1 | §7 says "resource IDs in query/body, never path segments", but §3.1 uses `Location: /v1/tasks/{id}`. | Rule applies to **registry/world resource IDs** (blocks, items, dimensions, players). Task/lease/instance IDs are opaque handles; path segments are correct there. Document in `docs/DATA.md` when written. |
| G2 | §3.2 idempotency: "retained ≥ 24h" but keys are scoped per **process session** — a restart changes the session and orphans the keys. | Interpretation: retention is bounded by both (≥24h *and* process session lifetime); document that a new process session invalidates prior keys by design. |
| G3 | §4.1 "per-source rate limiting that cannot be weaponized into global DoS". | Current per-remote-address limiter is already per-source; keep global budgets per source only, never a shared global bucket that one client can exhaust. |
| G4 | §7 "Unknown ≠ empty" vs. the repo's `motd` null-normalization. | Different categories: documented **normalization** (absent → `""`) applies only to fields the game defines as optional strings; **unknown** data is `{"available": false, "reason": …}`. Both stay; distinction documented. |
| G5 | §10 pins an OpenAPI 3.1 subset. | Current `openapi.yaml` is 3.1 and subset-safe; CI check for banned constructs (`$dynamicRef`, etc.) lands with SDK generation (Phase 1). |
| G6 | Two-client E2E scenarios need offline accounts + headless clients + CI display budget. | Harness owns this (slice 0.7); CI feasibility must be proven before Phase 0 is declared complete. |
| G7 | §5.2 "no silent fallback between modes" — needs per-endpoint `mode` validation and error on unsupported modes. | Mode validation is part of every interaction endpoint's contract test from the first one (slice 0.6). |
| G8 | Classload guard for shared code (§1.2): static scan catches constant-pool references but not reflective lookups built from string concatenation. | Scan is a tripwire, not a proof; the dedicated-server launch test (slice 0.7) is the authoritative check. Document both. |
| G9 | Spec §6.1 lists `POST /eval` as core but the repo is GET-only today; POST support arrives with the task/request-body plumbing (slice 0.3). | Ordering noted; body limits (8 KiB) apply to the new POST paths. |

## 3. Reconciliation map (current repo → spec)

| Spec area | Current state (0.1.0) | Gap | Lands in |
| --- | --- | --- | --- |
| §1.1 sessions/identity | none | process/world/connection session IDs, physical side, logical sides | **0.1 (this change set)**: process + world sessions, physical side, logical sides; connection sessions with client work |
| §1.4 transport | loopback HTTP, GET-only, bearer token, rate limit, Host/Origin checks | WS, unix socket, TLS, bounded port fallback, `/live` `/ready` | live/ready in **0.1**; WS in 0.4; rest per D3–D5 |
| §2.1 completion levels | not started | applied/observed/rendered/persisted per operation | 0.3 (tasks), 0.6 (client evidence) |
| §2.2 owning thread | server-thread snapshots with bounded 500 ms wait | generalize into scheduler usable by tasks/events | 0.3 |
| §2.3 revisions/preconditions | none | needs mutation endpoints first | Phase 1 |
| §2.4 time model | none | `/time` with serverTick + clocks | **0.1 (this change set)** (server-side fields only) |
| §3.1 tasks | none | task manager, 202 protocol, cancellation, deadlines | 0.3 |
| §3.2 idempotency | none | needs request bodies (POST) | 0.3 |
| §3.3 events | none | event core (seq, cursor, GAP) 0.4; WS transport 0.4 | 0.4 |
| §4.1 credentials | token via env/config only; never logged | owner-restricted token **file** bootstrap, fingerprint logging | **0.1 (this change set)** |
| §4.2 scopes | single token, no scopes | scope model + token ceiling | Phase 1 |
| §4.5 fine-grained switches | n/a (read-only API) | files/rawState/reflection/codeExec switches | Phase 1+ |
| §5 capabilities | none | `/capabilities` per-op metadata | Phase 1 (with core tier table) |
| §5.3 leases | none | lease manager + key/button release | Phase 1 |
| §6.1 common ops | health, info, server/status | live, ready, time | **0.1 (this change set)**; logs/crash-reports Phase 1 |
| §6.2 server ops | status snapshot only | player/block/entity/world reads on owning thread | 0.5 |
| §6.3 client ops | none (no client code) | client source set, input, screens, screenshots | 0.6 |
| §8 chunk policy/budgets | n/a | needs world writes first | Phase 1 |
| §9 discovery | none | discovery file (schema 1, atomic, sanitized) | **0.1 (this change set)**; heartbeat 0.2 |
| §9 harness | scripts/doctor.sh, server-smoke.sh only | runServerApi/runClientApi/launchMatrix, supervision | 0.7 |
| §10 parity testing | unit + contract tests, jar validation, consumer test | classload tripwire in packaging validation | **0.1 (this change set)** (tripwire); launch tests 0.7 |
| §11 docs | docs/ set complete for current surface | SEMANTICS/EVENTS/CAPABILITIES/DATA/RECIPES/TESTING as those land | per slice |
| §1.3 SUPPORT.md | toolchain.md has verified versions | SUPPORT.md + `/info.support` | 0.8 |

## 4. Delivery plan — Phase 0 in verifiable slices

Each slice must keep `./gradlew verify` green (format, tests, jar
validation, manifest) and update docs in the same change set.

### Slice 0.1 — Foundations (this change set)

- Session identity: `processSessionId` (per launch), `worldSessionId` (per
  server/world session), `physicalSide` (platform seam:
  Fabric `EnvType`, NeoForge `Dist`), `availableLogicalSides`.
- Secure bootstrap: auto-generated owner-restricted token file
  `<gameDir>/mcapi/token` (POSIX 0600 where available), env/config token
  still takes precedence; token never logged, non-secret fingerprint only.
- Discovery file `<gameDir>/mcapi/discovery.json` (schema 1, atomic write,
  sanitized `instanceId`, no secrets), written on API start, removed on stop.
- Endpoints (additive, still GET-only): `/api/v1/live`, `/api/v1/ready`
  (`http`/`worldReady`; `clientJoined` reserved), `/api/v1/time`
  (wall clock, monotonic clock, `serverTick` with unknown≠empty shape);
  `/api/v1/info` extended with identity fields (additive).
- Packaging: classload tripwire — distributable jars must not reference
  `net/minecraft/client` from `dev/example/mapi/**`.

### Slice 0.2 — Process-lifetime service + discovery heartbeat

- HTTP listener starts at process init when enabled (D6): survives repeated
  integrated-server sessions; readiness states reflect world sessions.
- Discovery `lastSeen` heartbeat (bounded interval), stale semantics
  documented for the harness; readiness transitions emit events (once 0.4
  exists, backfill notes).
- Config: `http.portFallback` (bounded port fallback + fail-fast option).

### Slice 0.3 — Owning-thread scheduler, tasks, request bodies

- Generalize the snapshot machinery into a scheduler: bounded queues per
  owner thread (server thread, later client thread), deadline support.
- POST/PUT/DELETE support with body limits, request IDs, common error model
  (`requestId`, `fieldErrors`, `retryable`), G9 ordering.
- Task manager: `202` + `Location`, states
  `queued→running→succeeded|failed|cancelRequested→cancelled|expired`,
  progress, partial effects, cleanup reporting, wall-time deadlines,
  `LIFECYCLE_CHANGED` on world unload/disable.
- Idempotency-Key on the first mutating endpoints (start-task, batch),
  per G2 semantics.

### Slice 0.4 — Event core + WebSocket

- Event log: per-process-session monotonic `seq`, ring buffer with GAP
  policy, subscribe/ack schema, resume `?after=`, per-event authorization,
  slow-consumer policy (`drop-oldest`/`disconnect` at subscribe), source
  tags (§3.3).
- WS transport per D3 decision; browser auth via short-lived single-use
  ticket (§4.5); SDK-style header auth for tools.

### Slice 0.5 — game-common source set + server reads

- New source set/module for Minecraft-aware shared ops (server-safe only);
  `ServerHandle` grows typed read operations (player list snapshot
  (paginated, field-selected), block get with chunk policy `loadedOnly`
  default, dimension/time state).
- DTO + registry-context conventions (§7) land with the first reads;
  `openapi.yaml` contract + negative tests per endpoint.
- Restructure per D8 stage 1: `common` stays the published artifact.

### Slice 0.6 — game-client source set + first client evidence

- Client-only source set per loader (`loom.splitEnvironmentSourceSets()`,
  NeoForge dist guards); **all 26.2 client APIs verified against the real
  jar first** (`docs/toolchain.md` method) — no invented APIs.
- One `input`-mode interaction, screen tree (semantic, with revision),
  screenshot capture with `frameId`, completion levels for client actions
  (`observed: false` + reason when authority is missing).
- Mode parameter from day one (G7): no silent fallback.

### Slice 0.7 — Harness + launch matrix + scenarios

- `runServerApi`/`runClientApi`/`launchMatrix` Gradle tasks (ID/port/
  token-file parameterized), isolated game dirs, process supervision,
  restart, offline test accounts.
- Production-jar launch test; dedicated-server classload launch test on
  both loaders (authoritative check, G8).
- One two-client scenario; failure scenarios from spec §10 (disconnect
  mid-task, frozen tick, world unload during read, disabled API idle).
- EULA: harness never accepts it implicitly (`MAPI_ACCEPT_EULA` gate stays).

### Slice 0.8 — Support manifest

- `SUPPORT.md` + `/api/v1/info` `support` block: exact MC, Java, Gradle,
  loader, Fabric API, NeoForge versions per branch (from
  `libs.versions.toml` + `docs/toolchain.md` verification dates); branch
  policy (D2).

### Phase 1 and beyond

Phase 1 (scopes, leases, revisions, SDKs, MCP adapter, fixture-mod,
failure-scenario suite), Phase 2 (extended tier: storage adapters, GameTest,
region fixtures, ext SPI), Phase 3 (experimental tier) follow spec §12 with
the slice discipline above; each phase re-runs the §13 "complete" checklist
against the then-current docs.
