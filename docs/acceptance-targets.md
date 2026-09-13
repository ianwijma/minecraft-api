# Acceptance targets (adopted)

> Status: **adopted 2026-09-13** by owner decision (execution-plan chunk
> 0.2). These are the §18 proposed release targets from
> `docs/product-spec.md`, adopted as written. Measurement procedures are
> version-controlled under `scripts/acceptance/` (chunk 0.6) and must exist
> for every gate before that gate is claimed.

## Gates

| Area | Gate | Measurement procedure |
| --- | --- | --- |
| Contract compliance | Every documented core operation covered by schema/behavior tests | HTTP contract suite + behavior corpus (chunk 6.3) |
| Loader parity | All applicable required tests pass on both loaders | Behavior suite run per loader (chunk 6.3) |
| Deterministic baseline reliability | Zero unexplained failures across 300 consecutive runs of each designated critical scenario per loader | `scripts/acceptance/` reliability runner (chunk 8.2) |
| Lifecycle reliability | Zero leaked held inputs, leases, or tickets across 100 load/unload cycles | Leak-check harness (chunk 8.2) |
| Parallel isolation | Two server groups with two clients each complete repeated tests without cross-instance interference | Parallel campaign harness (chunk 8.2) |
| Smoke-suite duration | At most 15 minutes per loader on the reference CI worker, excluding build and dependency download | Timed smoke suite (chunk 8.2) |
| API transport | Local metadata request p95 below 100 ms under the declared test load | Load probe (chunk 8.1) |
| Safe-boundary dispatch | Eligible actions dispatched within two qualifying boundaries under the declared load | Boundary instrumentation (chunk 8.1) |
| Disabled-mode overhead | No listener or automation jobs; measured gameplay overhead within the agreed noise budget | Tick/frame benchmark, mod disabled (chunk 8.1) |
| Enabled idle overhead | No more than 5% regression in the declared tick/frame benchmark | Same benchmark, mod enabled idle (chunk 8.1) |
| Security | No unresolved critical/high findings in the project's security test suite | Security suite (chunk 8.3) |
| Packaged artifacts | Dedicated server, client, and integrated-server tests pass using release JARs | Release-jar suites (chunk 8.4) |
| SDK interoperability | Same behavioral SDK tests pass against corresponding Fabric and NeoForge instances | SDK behavioral suite (chunk 7.4) |

## Standing rules (spec §18)

- First-attempt outcomes remain visible even when diagnostic retries occur.
- Automation failures are never relabeled as infrastructure failures without
  evidence.
- Zero failures in a finite run is a release gate, not proof that the true
  failure probability is zero.
- Stress/soak tests check bounded memory and artifact retention.
- Visual tests use environment-specific baselines and explicit tolerances.

## Reference environment (to be pinned)

The following must be pinned in chunk 0.6/8.1 before any benchmark number is
recorded: reference hardware, JVM build, rendering stack, fixtures, and
workload definitions. Until pinned, benchmark gates have **no** measured
values — the table above defines targets only.
