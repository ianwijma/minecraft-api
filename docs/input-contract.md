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

## Current state (substrate implemented, chunk 3.0-3.3)

- Execution modes are declared per operation (`OperationDescriptor`),
  enforced by `OperationGuard`, and reported in receipts — requested mode is
  kept verbatim on pre-dispatch rejection; `EXECUTION_MODE_UNSUPPORTED`
  fires with no silent fallback (contract-tested).
- `ActionReceipt` (§3.4) is implemented with the three outcome layers
  distinct (structurally: actual mode must equal requested mode; CONFIRMED
  requires evidence; PARTIAL/CANCELLED require a note).
- `InputScheduler` implements the §4.2 hold-N-client-ticks boundary
  contract with wall-clock deadlines and release-all before failure.
- The input backend contract with §3.5 coverage disclosure is implemented
  per loader (keybinding-state dispatch + camera deltas via the player
  input path, sensitivity NOT re-applied — documented at the contract).
  Runtime-verified 2026-09-15 on NeoForge: screenshot of the live title
  screen captured through the full stack.
- `POST /api/v1/client/actions/hold-key` exposes the first action; window
  and screenshot read endpoints exist behind the same capability gate.

## Control ownership and cleanup

Client input and movement HTTP requests require the exclusive `input` lease
and present its `leaseId`. Control is checked at client-thread dispatch and
while waiting for ticks. Expiry or revocation aborts a hold and releases its
synthetic key before reporting failure, including when ticks have stalled.
Long input actions serialize on the shared backend so cleanup from an older
action completes before a later action presses a key. Interruption and elapsed
deadlines also release input; an already elapsed deadline prevents key-down.
Both loaders retain one input backend per client bridge and register its tick
counter once during client setup. Holds and movement observe that same counter
and share the same synthetic held-key state.

Inventory clicks use `client-logic` execution mode. Dispatch acceptance is
reported separately from server confirmation; callers must observe a
postcondition before claiming a verified server effect.
