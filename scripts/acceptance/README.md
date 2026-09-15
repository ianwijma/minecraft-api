# Acceptance measurement procedures

Version-controlled procedures for every gate in
`docs/acceptance-targets.md` (adopted 2026-09-13). Status: **draft
harnesses** — each gate's executable harness lands with the chunk that owns
it (see the table). Rules that apply to all gates:

1. No benchmark number is recorded until the reference environment
   (hardware, JVM build, rendering stack, fixtures, workload) is pinned in
   `docs/acceptance-targets.md` (chunks 0.6/8.1).
2. First-attempt outcomes stay visible even when diagnostic retries occur.
3. Automation failures are never relabeled as infrastructure failures
   without evidence.
4. Zero failures in a finite run is a release gate, not proof of zero
   failure probability.
5. Results are machine-readable JSON written under `build/acceptance/` and
   are never committed.

## Gate procedures (draft)

| Gate | Harness (chunk) | Procedure summary |
| --- | --- | --- |
| Contract compliance | contract suite (2.8, 3.13) | `./gradlew testAll`; every documented operation has schema + status-code tests |
| Loader parity | behavior corpus (6.3) | same corpus executed per loader; per-loader result files |
| Deterministic baseline | `run-gate.sh reliability` (8.2) | 300 consecutive runs per designated critical scenario per loader; count unexplained failures; JSON report |
| Lifecycle leaks | `run-gate.sh leaks` (8.2) | 100 load/unload cycles; assert zero leaked held inputs, leases, tickets via lifecycle introspection added by chunks 2.2/3.12 |
| Parallel isolation | `run-gate.sh parallel` (8.2) | two server groups × two clients; repeated suite; cross-instance interference check |
| Smoke duration | `run-gate.sh smoke` (8.2) | timed `scripts/server-smoke.sh <loader>` excluding build; ≤ 15 min on reference worker |
| API transport p95 | `scripts/acceptance/run-gate-transport.sh` (8.1, **executable draft**) | measures p50/p95/p99 of /api/v1/info against a live instance; gate p95 < 100 ms. Reference-environment pinning pending: numbers before pinning are indicative |
| Boundary dispatch | `scripts/acceptance/run-gate-boundaries.sh` (8.1, **executable draft**) | dispatches waypoint moves and verifies position delta per attempt; ≥80% dispatch rate |
| Disabled-mode overhead | `run-gate.sh overhead` (8.1) | tick/frame benchmark with mod absent vs installed-disabled; no listener/jobs; within noise budget |
| Enabled idle overhead | `run-gate.sh overhead` (8.1) | same benchmark, mod enabled idle; ≤ 5% regression |
| Security | security suite (8.3) | policy-bypass, destructive-chain, stream/artifact authz, estop/lease-expiry cases |
| Packaged artifacts | release-jar suites (8.4) | dedicated server, client, integrated-server suites against release JARs |
| SDK interoperability | SDK suite (7.4) | same behavioral SDK tests against Fabric and NeoForge instances |

`run-gate.sh <gate>` exists as the stable entrypoint; unimplemented gates
exit non-zero with `NOT IMPLEMENTED` rather than reporting success.
