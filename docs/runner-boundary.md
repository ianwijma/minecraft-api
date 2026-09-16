# Runner boundary — mod/SDK/runner responsibilities

> Status: **draft** (execution-plan chunk 0.5; finalized at chunk 5.11).
> Spec source: `docs/product-spec.md` §1, §1.1, §16.

## Section contract (must contain before `status: final`)

1. The three deliverable groups and the rule that the mod must work without
   the runner, MCP, an LLM, or an IDE (spec §1).
2. The full responsibility-allocation matrix (mod / SDK helpers / reference
   runner) from spec §1.1, kept in sync with it.
3. The single-runner rule: one reference runner with a versioned CLI and
   machine-readable results; no per-SDK runner implementations; SDKs may
   invoke it externally but never depend on it (spec §1.1).
4. Runner scope per spec §16: required (visual comparison, recording/replay
   + divergence, reproducibility bundles, fixture orchestration, basic path
   planning with stop-on-stuck) vs Extension/Future (hazard-aware replanning,
   machine-state adapters, live backup).
5. Process lifecycle ownership: the runner launches, restarts, kills, and
   provisions; the mod only performs graceful local shutdown on request; the
   EULA is always an explicit operator decision (spec §8.3).
6. Out-of-process communication: the runner uses only the public HTTP API —
   no `common` internals, no in-process access (spec §1; ADR-0002 rule 3).

## Current state (implemented)

- **Mod side (this repository, `common/` + loader modules):** world
  lifecycle, tick control, bounded queries, boundary-aligned snapshots,
  command dispatch, log capture, shutdown seam, client substrate
  (input scheduling + receipts) — all behind the HTTP contract with scopes,
  leases, and problem codes.
- **Runner (`runner/` module):** versioned CLI (`version`, `status`,
  `wait-world`, `run`, `replay`, `provision-server`), declarative JSON plans
  (fixture orchestration), JSONL recording + replay with divergence
  detection, EULA-gated provisioning, machine-readable results and exit
  codes. Depends only on the HTTP contract — never on `common` internals
  (ADR-0002 rule 3).
- **Runtime-verified 2026-09-15** (NeoForge 26.2.0.87, live client):
  process-scoped API at the main menu AND in-world, full menu automation
  (inspect title-screen widgets, click Singleplayer, load world via API),
  movement (11.91 blocks walked via waypoints with 180-degree turn; first
  attempt blocked by terrain — jungle wall — confirming collision honesty),
  tick control (lease → freeze → step 20 → unfreeze, world count 21717 →
  21738), LAN publish/unpublish, screenshots (title screen + in-world),
  auth-off browser access, graceful shutdown endpoint, world CRUD cycle
  (create → load → use → save&quit → delete via API), inventory inspect
  (containerId, carriedCount, slot enumeration), character input dispatch
  (chat/screen path via charTyped), tooltip computed data.
  Known issue: step job reports completed:0 because 26.2 advances the tick
  counter asynchronously after stepGameIfPaused — stepping works (count
  delta proves it). Inventory interaction cycle verified live: PICKUP
  diamond from slot 36 (carried=64), tooltip for emptied slot (correctly
  empty), PICKUP again to place back (carried=0). Command dispatch live:
  give via player name works (spec §20: command context and feedback).
- **Not yet implemented (tracked in `docs/execution-plan.md`):** test
  profiles/preflight (5.3), participant mapping UI (5.4), path planning
  (5.9), in-world movement/UI execution evidence (3.4-3.13 — routes land,
  live in-world runs pending), Java SDK pipeline (7.2).
