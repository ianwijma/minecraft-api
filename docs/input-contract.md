# Input contract — execution modes, backends, polling coverage, fidelity

> Status: **draft** (execution-plan chunk 0.5). This document becomes
> normative as chunks 1.12, 3.0–3.3, 3.12 land; final form due by chunk 8.5.
> Spec source: `docs/product-spec.md` §3.

## Section contract (must contain before `status: final`)

1. Execution modes (`raw-input`, `client-logic`, `privileged`): definitions,
   per-action classification table, and the fidelity claims each implies
   (spec §3.1).
2. Mechanism vs authorization: how `requiredScopes`, `requiresLease`,
   `destructive`, side-effect class, and bypass disclosures attach to each
   operation; `executionMode` is not a permission system (spec §3.2).
3. No-silent-fallback policy and `EXECUTION_MODE_UNSUPPORTED` semantics,
   including how the actual mode is always reported (spec §3.3).
4. Action receipt schema: field-by-field contract for `actionId`,
   correlation ids, requested/actual mode, backend id/version, boundaries,
   generations, dispatch outcome vs verified effect, partial/cancellation
   info (spec §3.4); the distinct outcomes "input delivered", "client state
   changed", "server effect confirmed".
5. Input backend requirements and the published coverage report
   (callback/event dispatch, keybinding state, helper polling, screen
   dispatch, unsupported native polling paths), including the GLFW direct
   polling boundary statement (spec §3.5).

## Current state

Not yet implemented. The existing HTTP API is a read-only status surface
(`docs/http-api.md`); no action operations exist.
