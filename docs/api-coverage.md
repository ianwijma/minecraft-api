# Complete API coverage

The API coverage suite maps every OpenAPI operation to an explicit behavior
assertion. A successful request alone is not coverage: the corresponding
effect must be observed. Expected capability errors are reported separately.

`e2e/api/manifest.ts` defines applicable environments, prerequisites, behavior
assertions, and named negative cases. `e2e/api/contract.json` is an exported
OpenAPI snapshot, guarded by the source SHA-256. Regenerate it with
`python3 scripts/generate-e2e-contract.py` after contract changes (the existing
SDK-generation PyYAML dependency is required). Never edit the snapshot manually.

`node --experimental-strip-types e2e/api/check.ts` checks definitions without
launching Minecraft. `cd e2e && npm test` includes coverage-gate fault tests.
The root `./gradlew verify` runs the definition gate as well as JVM verification.

Live evidence is tracked separately per SDK, loader, operation, and applicable
environment. Missing evidence and first-attempt assertion failures fail the
gate. Filtered developer runs cannot satisfy the complete CI gate. Finite
passing runs establish operation coverage, not a zero probability of defects.

Implementation proceeds through contract/evidence infrastructure, deterministic
contracts, a cross-loader consumer fixture, the live corpus, and SDK/CI wiring.
The 300-run reliability, 100-cycle lifecycle, performance, and parallel
campaigns remain separate acceptance gates.

The deterministic HTTP suite enumerates the actual route table for missing
and wrong authentication, malformed object bodies, and every undeclared
execution mode. These tests cannot silently omit a newly registered route.
Input deadline tests inject tick, epoch, and monotonic clocks and boundary
waits; they require no sleeping and confirm cleanup even after an epoch-clock
jump. Existing job, snapshot, lease, event, world-lifecycle, and dispatch tests
retain their focused failure and race checks.
