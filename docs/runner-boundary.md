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

## Current state

No runner exists yet. `scripts/server-smoke.sh` is the only out-of-process
tooling today.
