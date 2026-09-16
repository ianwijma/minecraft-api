# MAPI Execution Plan

> Status: derives the approved product specification (`docs/product-spec.md`,
> 2026-09-13) into independently executable change sets ("chunks"). The
> architecture ADRs required by spec §15.2 are **not yet approved**; every
> chunk after the Phase 0 human gate is blocked until they are (see
> `AGENTS.md` §1, §7).

## How to use this plan

- A **chunk** is one PR-sized change set: code + tests + docs + manifest sync
  in the same commit, ending with a green `./gradlew verify`.
- `Spec §` citations point at `docs/product-spec.md`. `depends` lists the
  chunks that must land first. `size` is an effort estimate:
  **S** = one focused session, **M** = 1–2 sessions, **L** = decompose
  further before starting.
- Endpoint chunks always include: OpenAPI update, `docs/http-api.md` schema +
  status codes, `docs/security.md` if exposure changes, and HTTP contract
  tests (schema + status codes) — per `AGENTS.md` §6.
- Permanent guardrails (never up for re-planning, spec §2): no source-code
  editing, shell execution, reflection, protocol bots, account auth,
  launcher, anti-cheat evasion, or MCP/LLM transport. Secrets, EULA, and
  version-pinning rules in `AGENTS.md` §7 apply to every chunk.

## Phase overview

| Phase | Outcome | Unlocks |
| --- | --- | --- |
| 0 | ADRs approved, module layout, doc skeletons, measurement scaffolding | Everything |
| 1 | Loader-neutral runtime substrate (jobs, leases, events, clocks, encoding, scopes) | Phases 2–5 |
| 2 | Server-side Minecraft bridge + `/server/*` endpoints | Server capabilities, runner |
| 3 | Client-side Minecraft bridge + `/client/*` endpoints | Client capabilities, runner |
| 4 | Observability, config surface, lifecycle hardening | Release readiness |
| 5 | Reference runner (out-of-process, public API only) | E2E harness, Phase 6/8 |
| 6 | Cross-loader fixture mod + loader-parity suite | Acceptance gates |
| 7 | OpenAPI completion + TS/Python/Java SDKs | SDK interop gate |
| 8 | Acceptance campaigns and definition-of-done audit | Release |

---

## Phase 0 — Governance and scaffolding (gated)

### 0.1 Draft the §15.2 ADRs
Spec §15.1–15.2 · depends — · size M
- Create `docs/adr/` with an ADR template plus: ADR-0001 supported Minecraft
  version(s) and bridge mapping strategy; ADR-0002 repository/module
  organization and Java toolchains; ADR-0003 loader dependency policy and CI
  matrix; ADR-0004 backport and end-of-support policy; ADR-0005 criteria for
  introducing preprocessing later.
- No code changes.

### 0.2 HUMAN GATE — approve ADRs and acceptance targets
Spec §15.2, §18 · depends 0.1 · size S
- Operator approves or revises the ADR drafts and adopts/edits the §18
  proposed release targets. Record decisions in the ADRs and in
  `docs/acceptance-targets.md` (started in 0.5).
- Nothing below this line starts until this gate passes.

### 0.3 Repository module layout per ADR-0002
Spec §15.1 · depends 0.2 · size L
- Introduce the module groups: contract/DTOs, transport/auth, jobs-leases-
  events-scheduling, Minecraft-version bridge, fabric, neoforge, adapters,
  fixture mod, SDK generation, runner. Preserve the existing read-only
  surface and `MapiPlatform` seam; keep `./gradlew verify`, `doctor`,
  `llmContext`, manifest tasks working.

### 0.4 CI matrix per ADR-0003
Spec §15.2 · depends 0.2 · size M
- CI runs both loaders (and per-version matrix if ADR-0001 declares more than
  one), plus the existing verify gate.

### 0.5 Documentation skeletons (§19 set)
Spec §19 · depends 0.2 · size M
- Stub `docs/input-contract.md`, `docs/timing-and-pause.md`,
  `docs/participant-identity.md`, `docs/test-profiles.md`,
  `docs/data-encoding.md`, `docs/mixin-surface.md`,
  `docs/runner-boundary.md`, `docs/acceptance-targets.md`,
  `docs/non-goals.md` with section contracts and `status: draft`. Each
  implementing chunk fills its document in the same change set.

### 0.6 Measurement scaffolding
Spec §18 · depends 0.2 · size M
- Version-controlled measurement procedures for every §18 gate (scripts +
  fixtures + reference-environment pinning doc). Benchmark harness
  skeletons; no thresholds hard-coded into product code.

---

## Phase 1 — Loader-neutral runtime substrate (pure JVM)

### 1.1 Error model and problem codes
Spec §3.3, §4.4, §6, §12 · depends 0.3 · size S
- Registry of all spec error codes (`EXECUTION_MODE_UNSUPPORTED`,
  `WORLD_NOT_LOADED`, `STALE_WORLD`, `SERVER_PAUSED`, `CLOCK_NOT_ADVANCING`,
  `SNAPSHOT_EXPIRED`, `TOOLTIP_SEMANTICS_UNAVAILABLE`, …) with JSON mapping
  and tests.

### 1.2 Operation metadata registry
Spec §14 · depends 1.1 · size M
- Per-operation declaration: `requiredScopes`, `destructive`,
  `sideEffectClass`, `requiresLease`, `supportedExecutionModes`; enforcement
  middleware that every route goes through.

### 1.3 Scope model and destructive/unrestricted authorization
Spec §14 · depends 1.2 · size M
- Scopes incl. `client:connect`, `client:settings`, `server:tick-control`,
  `server:publish`, `operations:destructive`, `operations:unrestricted`.
- Destructive chain: scope + destructive grant + explicit request intent +
  matching target/world identity. Unrestricted grant documented as
  administrative access.

### 1.4 Job system
Spec §5, §6, §13.2 · depends 1.1 · size M
- Bounded jobs, milestones, cancellation, wall-clock deadlines, termination
  reasons (`WORLD_UNLOADED`), wait-for-job operation.

### 1.5 Lease manager
Spec §4.5, §5 · depends 1.4 · size M
- Exclusive control leases; wall-clock expiry watchdog that revokes at API
  level immediately and schedules game-thread cleanup; configurable
  restoration on expiry that never overwrites later manual changes;
  emergency stop.

### 1.6 Event bus, cursors, waits
Spec §6, §13.2 · depends 1.4 · size M
- Ordered lifecycle events, resume cursors, bounded declarative filters;
  wait-for-event, wait-after-cursor, wait-predicate-for-duration. Cursor
  establishment before action triggering.

### 1.7 SSE streaming transport
Spec §13.1 · depends 1.6 · size M
- Fetch-based SSE with the normal bearer header (no query tokens/tickets);
  incremental parsing, reconnect, resume, auth-failure handling, gap
  signaling. CORS remains disabled by default.

### 1.8 Clock registry
Spec §4.1, §4.3 · depends 1.1 · size S
- Named clocks (`wall`, `client-tick`, `client-frame`, `server-tick`,
  `server-simulation-step`), availability/progress interfaces, fakes for
  tests.

### 1.9 Canonical encoding
Spec §11 · depends 1.1 · size L
- Normalized DTOs, codec JSON rules, typed NBT wire representation (§11.2
  table), item-component view (§11.3), version metadata (§11.4);
  hashing/normalization order; size/depth guards; strict JSON (no bare
  NaN/Infinity); test vectors.

### 1.10 Snapshot store and bounded diff engine
Spec §12 · depends 1.9 · size M
- Retention/quotas, `SNAPSHOT_EXPIRED`, diff records with before/after,
  truncation and unavailable-field reporting, coverage-change notes.

### 1.11 Instance identity and participant DTOs
Spec §7.2 · depends 1.1 · size S
- `instanceId`, `bootId`, `worldSessionId`, `connectionSessionId`,
  `participantId` plumbing and publish-side DTOs.

### 1.12 Action receipt model
Spec §3.4 · depends 1.1, 1.8 · size S
- Receipt data type: `actionId`, correlation ids, requested/actual mode,
  backend id/version, boundaries, generations, dispatch outcome vs verified
  effect, partial/cancellation info.

---

## Phase 2 — Version bridge: server side

### 2.1 Bridge skeleton and mixin policy
Spec §15.3 · depends 0.3, ADR-0001 · size M
- Version-bridge module per ADR (public APIs first, loader events, narrow
  bridges, targeted mixins); `docs/mixin-surface.md` starts here and grows
  with every subsequent chunk.

### 2.2 World lifecycle
Spec §6 · depends 2.1, 1.4, 1.5, 1.6, 1.11 · size L
- `worldSessionId` sessions; on unload: stop world-scoped operations,
  terminate jobs, release leases/tickets, invalidate handles/snapshots,
  cancel tied client actions, ordered events; `WORLD_NOT_LOADED` /
  `STALE_WORLD` responses.

### 2.3 Pause and progress detection
Spec §4.4 · depends 2.1, 1.8 · size M
- Distinct singleplayer-pause / tick-freeze / empty-server-pause / focus /
  render-suspension / world-loading / stall states; `SERVER_PAUSED` and
  `CLOCK_NOT_ADVANCING` with reasons and recovery hints; no silent unpause.

### 2.4 Tick control
Spec §5 · depends 2.2, 2.3, 1.4, 1.5 · size L
- `/server/v1/ticks/*`: state, freeze, unfreeze, rate bounds, step, sprint,
  stop, stepAndObserve (job-based); full reporting set incl. subsystems not
  governed by tick control; exclusive-lease ownership; documented freeze
  limitations (players/ridden entities).

### 2.5 Bounded world queries
Spec §20 (server) · depends 2.2, 1.9 · size L
- Entities/players/blocks/block-entities/inventories/registries/chunks; no
  chunk generation unless explicitly requested; chunk-gen jobs report
  milestones and release temporary tickets.

### 2.6 Command dispatch
Spec §20 (server), §14 · depends 2.2, 1.3 · size M
- Console-context commands with context and feedback; dispatch vs
  asynchronous-effect distinction; unrestricted grant path documented as
  administrative.

### 2.7 Snapshot capture at boundaries
Spec §12 · depends 2.5, 1.10 · size M
- Retained server-side snapshots with quotas; diff endpoint
  `/server/v1/snapshot-diffs` with contract tests.

### 2.8 Server endpoint surface
Spec §5, §6, §12, §14 · depends 2.4, 2.6, 2.7 · size M
- Complete `/server/*` v1 surface, per-operation metadata exposure,
  lifecycle-dependent availability (`/server/*` stable namespace, never a
  remote proxy); OpenAPI + docs + contract tests finalized for the family.

---

## Phase 3 — Version bridge: client side

### 3.0 Walking skeleton (vertical slice)
Spec §3, §4.2 · depends 2.1, 1.4, 1.5, 1.8, 1.12 · size M
- One thin end-to-end path over HTTP: synthetic key hold for N client ticks
  with receipt and observed boundaries. De-risks input fidelity before the
  full backend is built; deliberately incomplete.

### 3.1 Input backend
Spec §3.5 · depends 3.0 · size L
- Synthetic key/char/mouse/scroll events, held-state tracking, modifier
  state, character input separate from key presses, tick- and frame-aware
  behavior, coverage report (dispatch/keybinding/helper-polling/screen/
  unsupported native paths).

### 3.2 Input scheduling semantics
Spec §4.2, §4.3 · depends 3.1, 1.8 · size M
- Hold-N-ticks boundary contract, non-collapsing presses, mouse-delta
  application at defined boundaries with applied-frame reporting; wall-clock
  deadlines everywhere.

### 3.3 Action dispatch and receipts
Spec §3.1–3.4 · depends 3.1, 3.2, 1.2, 1.12 · size M
- Execution-mode selection with no silent fallback
  (`EXECUTION_MODE_UNSUPPORTED`), receipt emission, distinct outcomes
  (input delivered / client state changed / server effect confirmed).

### 3.4 Movement primitives
Spec §16 · depends 3.3 · size M
- Straight-line movement and waypoint execution (bounded); teleportation
  never substituted silently.

### 3.5 Screenshot capture
Spec §20 (client) · depends 3.3 · size S
- Frame, scale, and screen metadata on every capture.

### 3.6 UI inspection
Spec §10.1 · depends 3.3 · size L
- Semantic-source hierarchy (adapter/widget/narration/render-capture),
  source identification per field, screens/containers/widgets, narration as
  identified fallback only.

### 3.7 Tooltips
Spec §10.2 · depends 3.6 · size M
- Rendered capture (pointer hover, render-pass wait) vs computed data;
  `TOOLTIP_SEMANTICS_UNAVAILABLE` for custom renders; hover requires lease.

### 3.8 Inventory operations
Spec §10.3 · depends 3.3, 3.6 · size L
- Required high-level ops (pick up/place, split, shift-click, hotbar swap,
  drop, basic crafting, cursor observation, server-confirmed
  postconditions); stale-reference rejection.

### 3.9 Window control
Spec §9.1 · depends 3.3 · size M
- `/client/v1/window/*`: state, resize/fullscreen/GUI scale, effective
  dimensions after platform adjustment, framebuffer vs logical size,
  coordinate transforms, revision reporting.

### 3.10 Direct connection
Spec §9.2, §14 · depends 3.3, 1.3, 1.5 · size L
- `POST /client/v1/connections` (privileged, `client:connect`, ownership);
  allowlist enforcement at final boundary incl. resolved addresses and
  redirection; same policy enforced on menu-path joins; staged reporting,
  cancellation, deadlines.

### 3.11 LAN publication
Spec §9.3, §14 · depends 2.2, 1.3 · size M
- `POST /server/v1/lan` (privileged, distinct exposure permission); actual
  port/exposure reporting; honest unpublish limitations; API listener
  untouched.

### 3.12 Client lifecycle hardening
Spec §3.5, §4.5, §6 · depends 3.1, 1.5 · size M
- Release-all on cancellation/lease expiry/emergency stop; watchdog
  integration; cancel gameplay actions on connection departure.

### 3.13 Client endpoint surface
Spec §3, §9, §10, §14 · depends 3.3–3.12 · size M
- Complete `/client/v1` surface with per-operation metadata; OpenAPI + docs
  + contract tests finalized for the family.

---

## Phase 4 — Operations and observability

### 4.1 Log capture appender
Spec §17.1 · depends 1.1 · size M
- Bounded Log4j appender: no global reconfiguration, filters, secret
  redaction, recursion guard, capture-start metadata; documented
  pre-attachment gap; loader-agnostic via `MapiPlatform`.

### 4.2 Configuration surface
Spec §8.2, §14 · depends 1.3, 2.x/3.x endpoints · size M
- Config-backed scopes, allowlists, quotas, rate bounds, retention, lease
  defaults; auth remains config-backed; remote exposure still opt-in.

### 4.3 Shutdown and process scoping
Spec §6, §17 · depends 2.2, 4.1 · size S
- Graceful local shutdown; process-scoped sessions survive world unload;
  world-scoped work does not.

---

## Phase 5 — Reference runner (out-of-process; public API only)

### 5.1 Runner core and process lifecycle
Spec §1, §1.1 · depends 2.8, 3.13 · size L
- Versioned CLI, machine-readable results; launch/restart/kill/provision;
  stdout/stderr + file capture for early startup; standalone (no SDK
  dependency).

### 5.2 EULA gate
Spec §8.3 · depends 5.1 · size S
- Supervisor requires explicit operator EULA acceptance before provisioning;
  never automatic (mirrors `AGENTS.md` §7).

### 5.3 Versioned test profiles and preflight
Spec §8 · depends 5.1 · size L
- Client + server profile templates, preflight validator with the §8.4
  output; opt-in, test-directories only; fresh-profile suite hooks.

### 5.4 Participant mapping and identity strategies
Spec §7 · depends 5.1, 1.11 · size M
- Offline isolated and externally provisioned online strategies; mapping
  table participant → process → connection → authoritative player; mapping
  basis reporting.

### 5.5 Fixture orchestration
Spec §1.1, §16 · depends 5.4 · size M
- Setup/teardown sequencing over typed SDK-style calls; no mod-side
  orchestration.

### 5.6 Recording, replay, divergence detection
Spec §1.1, §16 · depends 5.5 · size L
- Persist action receipts/events; replay with retry-safe transport;
  divergence reports.

### 5.7 Visual baselines and diffs
Spec §16, §18 · depends 5.6, 3.5 · size M
- Environment-specific baselines, explicit tolerances, diff reports.

### 5.8 Reproducibility bundles
Spec §1.1 · depends 5.6 · size M
- Assemble logs, receipts, profiles, snapshots, versions into a bundle.

### 5.9 Basic path planning
Spec §16 · depends 5.5 · size M
- Loaded-world route planning with stop-on-stuck, composing mod movement
  primitives; no hazard-aware replanning (Extension).

### 5.10 Backup/restore at safe boundaries
Spec §17.2 · depends 5.1, 4.3 · size M
- Full-world backup with process/server stopped; restore while stopped;
  live backup remains documented Extension.

### 5.11 Finalize `docs/runner-boundary.md`
Spec §19 · depends 5.1–5.10 · size S

---

## Phase 6 — Fixture mod and loader parity

### 6.1 Fixture mod skeleton
Spec §15.1, §16 · depends 0.3 · size M
- Cross-loader fixture mod module; loader-agnostic core with thin loader
  adapters.

### 6.2 Fixture content
Spec §20 (testing) · depends 6.1 · size L
- Custom screens, example machine adapter (cross-loader), custom commands,
  custom networking — the required test fixture set.

### 6.3 Loader-parity behavior suite
Spec §18 · depends 6.2, 2.8, 3.13 · size L
- Shared behavior corpus executing on Fabric and NeoForge from the same
  definitions; results comparable.

---

## Phase 7 — SDKs and contract completion

### 7.1 OpenAPI canonical completion
Spec §14, §20 · depends 2.8, 3.13 · size M
- Every core operation documented incl. per-op
  scopes/destructive/sideEffectClass/requiresLease/supportedExecutionModes;
  error codes aligned with 1.1.

### 7.2 SDK generation pipeline
Spec §1, §20 · depends 7.1 · size L
- Reproducible TypeScript, Python, Java client generation; versioned
  artifacts.

### 7.3 Handwritten helpers
Spec §13 · depends 7.2 · size M
- Authenticated fetch SSE streaming, wait helpers, retry-safe transport per
  SDK language where generation is insufficient.

### 7.4 SDK behavioral suite
Spec §18, §20 · depends 7.3, 6.3 · size M
- Same behavioral tests against Fabric and NeoForge instances.

### 7.5 Examples in CI
Spec §20 · depends 7.4 · size S

---

## Phase 8 — Acceptance and release

### 8.1 Performance and overhead gates
Spec §18 · depends 0.6, 2.8, 3.13 · size L
- Transport p95, two-boundary dispatch, disabled/enabled overhead
  benchmarks executed per the 0.6 procedures.

### 8.2 Reliability campaigns
Spec §18 · depends 6.3, 5.6 · size L
- 300-run deterministic baseline per critical scenario per loader; 100
  load/unload cycles with leak checks; parallel isolation (two server
  groups × two clients); smoke-suite duration ≤ 15 min/loader.

### 8.3 Security test suite
Spec §14, §18 · depends 2.8, 3.13, 4.2 · size M
- Policy bypass attempts via supported alternate paths, destructive chain
  enforcement, stream/artifact authorization, emergency-stop/lease-expiry
  dispatch prevention.

### 8.4 Release-JAR environment tests
Spec §20 · depends 6.3 · size M
- Dedicated server, client, integrated-server suites against release JARs
  (extends `scripts/server-smoke.sh`); servers never load client-only
  classes.

### 8.5 Definition-of-done audit
Spec §19, §20 · depends all · size M
- Audit §20 checklist item by item; finalize the nine §19 documents
  (non-goals published); unresolved gaps become explicit known-limitation
  entries, not silent omissions.

---

## Dependency lanes and parallelization

- **Strict chain:** 0.1 → 0.2 (human gate) → 0.3 → Phase 1 → 2.1/2.2.
- After 1.x and 2.1: **server lane** (2.2–2.8) and **client lane**
  (3.0–3.13) can proceed in parallel.
- After 2.8 + 3.13: **runner lane** (Phase 5), **fixture lane** (6.1–6.2),
  and **contract lane** (7.1–7.3) can proceed in parallel.
- Runner chunks 5.1–5.3 can start earlier against fakes, but integration
  evidence waits for real endpoints.
- Documentation is never a separate lane: each chunk updates its §19
  document in the same change set.

## Human gates

1. **0.2** — ADR approval and adoption of §18 acceptance targets (blocks all
   implementation).
2. Any chunk that would change pinned versions, security posture, or the
   declared supported-version set escalates to the operator first
   (`AGENTS.md` §7).
