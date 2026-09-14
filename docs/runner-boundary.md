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
- **Not yet implemented (tracked in `docs/execution-plan.md`):** test
  profiles/preflight (5.3), participant mapping UI (5.4), visual baselines
  (5.7), reproducibility bundles (5.8), path planning (5.9), backup/restore
  (5.10), real client backends behind split source sets (3.4-3.13), SDK
  generation pipelines (7.2-7.5).
