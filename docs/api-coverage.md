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

The `fixture-mod`, `fixture-fabric`, and `fixture-neoforge` Gradle modules
produce acceptance-only consumer JARs. Release launch tasks include them only
with `-PmapiFixture=true`. `MAPI_FIXTURE_REPORT` names the isolated report path.
The consumer uses only `dev.example.mapi.api`; reports include public-member
checks, first-attempt failures, snapshots, and lifecycle callback counts.
`e2e/api/java-members.json` separates JVM/bootstrap checks from live consumer
checks. A reflection/source inventory test fails when public types or members
are added without coverage. The singleton bootstrap test uses an isolated
class loader and does not modify the running test suite's facade.

The SDK matrix uses a shared TypeScript orchestrator, generated TypeScript
operation methods, and Python/Java JSON-lines worker adapters using their SDK
request core and named helpers where available. `./gradlew apiHarnessTest` builds the Java worker and exercises
every operation's wire serialization plus authentication, fragmented UTF-8
multiline SSE, gap notices, and resource cleanup against offline loopback
servers. No Minecraft instance is needed.

Java adds `MapiSdkClient.streamEvents(...)` and the closeable
`MapiEventStream`; the lifetime is bounded and gap comments are explicit
events. TypeScript streams accept an optional cancellation signal and cancel
their reader on exit. Python streams accept optional open/gap callbacks for
supervisor cleanup while preserving existing iterator event behavior.

## Running and CI

Build the packaged mods, fixtures, and Java worker with `./gradlew verify
:sdk:apiWorkerJar`. Run the same entrypoint used by CI:

```bash
MAPI_ACCEPT_EULA=true MAPI_USE_XVFB=true LIBGL_ALWAYS_SOFTWARE=true \
  node --experimental-strip-types --no-warnings e2e/api/run.ts
MAPI_ACCEPT_EULA=true MAPI_USE_XVFB=true LIBGL_ALWAYS_SOFTWARE=true \
  node --experimental-strip-types --no-warnings e2e/api/run.ts \
  --loader fabric --sdk python --operation queryBlock --out build/api-debug
node --experimental-strip-types --no-warnings e2e/api/aggregate.ts build/api-coverage
```

The owner has given standing EULA acceptance for supervised tests. No repeated
acceptance prompt is required. `--loader` and `--sdk` default to `all` and run
sequential fresh corpora. An operation filter selects diagnostic evidence;
fixture preparation and dependent actions still run. Filtered runs carry
`full: false` and cannot satisfy CI. Use a fresh output directory for each
attempt; the runner refuses to overwrite existing evidence. Old reports are
diagnostic evidence, never automatic retries.

Each corpus provisions isolated packaged client and dedicated-server processes,
uses fixed seeds and prepared blocks/entities/inventory, exercises both server
kinds, and shuts down last through its SDK. The runtime JAR SHA-256 must match
the built distributable. Bounded polling observes game state instead of assuming
that tick freeze pauses input, rendering, or network traffic. Live screenshots
are evidence; this suite never creates visual baselines automatically.

`api-e2e` runs all six SDK/loader combinations on every PR with Ubuntu 24.04,
Xvfb, and software rendering. Mesa and Xvfb versions are pinned from the CI
runner’s verified package provenance (run 37134645012); Lavapipe is installed
explicitly and its ICD is discovered from the package manifest. It records
installed rendering package versions and the ICD digest;
semantic assertions are primary functional evidence. The `Complete API coverage
(required)` aggregate is enforced by branch protection on `develop`, including
administrator merges. It fails
when the repository variable `MAPI_ACCEPT_EULA` is absent, a prerequisite job
fails, a game job is skipped, evidence is incomplete, or fixture reports fail.
Configure that variable as `true` under the owner's standing acceptance.

Artifacts include JSON/JUnit, request traces, coverage matrices, runtime
provenance, requested/effective profiles, lifecycle reports, logs, and failure
screenshots. Traces and logs redact process credentials. The aggregate checks
six unique reports from one revision and canonical contract (matching
`GITHUB_SHA` on CI), emits a diagnostic matrix even for missing corpora, and validates
successful request evidence as well as behavior assertions. It never converts
a failed first attempt into success using diagnostic retries.

The client fixture limits software rendering to 30 FPS, two-chunk rendering,
and five-chunk simulation (Minecraft 26.2's normal minimum), disables Vsync and focus pausing, and records and
verifies the saved options after shutdown. API dispatch deadlines remain
unchanged. Step-and-observe captures within the completion server-thread pass;
its result and milestone use the actual retained observation boundary, avoiding
drift from server-loop ticks that continue while simulation is frozen.

After fixture teleportation and edits, preparation observes a readable block
in each of the nine forced chunks and forty normal server ticks before starting assertions.
Only this prerequisite phase accepts an explicitly recorded `SERVER_BUSY`
readiness probe and restarts its progress window; other errors fail. During
asynchronous sprint, join, movement, or inventory completion, explicit GET
progress probes also treat `SERVER_BUSY` as pending until the original polling
deadline. Each busy response is labeled in the trace and earns no successful
coverage. Other errors, failed assertions, and expired progress deadlines fail
immediately. Mutations and ordinary asserted reads are never retried.
Probes enter the request trace before invoking the SDK, so transport failures
remain visible even when no HTTP response arrives.

After integrated-world setup, a separate bounded `/server/status` prerequisite
probe waits for the server-thread snapshot path to respond. This handles the
short startup interval where world metadata is available while the render and
server threads are still catching up. Only `503 SERVER_BUSY` is retried, with
each response labeled in the trace and excluded from coverage; any other status
fails immediately. The asserted `getServerStatus` corpus request remains a
single attempt, and the API's 500 ms snapshot bound is unchanged.

The live corpus also uncovered and fixes an HTTP worker-pool starvation defect:
long-lived SSE connections occupied both core threads while normal requests
queued behind them. The bounded worker pool now reserves ordinary-request
capacity alongside the maximum eight streams; a real-listener JVM test holds
all eight streams open and requires the health endpoint to remain responsive.

## Wrap-up checkpoint — 2026-10-04

Implementation is pushed on `codex/complete-api-coverage` in draft PR #7.
`./gradlew verify apiHarnessTest` passed (40 offline harness tests), and the
latest fixture minimum correction passed `./gradlew verify`. The original
six-corpus local matrix passed at revision `0fb14f5`, with 48 operations per
SDK/loader, 444 successful environment cases, and 120 capability rejections;
its evidence is in `build/api-coverage-complete`.

Subsequent CI exposed a completion/snapshot boundary race (fixed with a JVM
regression assertion) and 500 ms busy reads under default software-rendering
load. The client now uses bounded rendering settings and verifies saved
options. A rerun correctly rejected the initial simulation-distance setting
of 2: Minecraft 26.2's Options bytecode confirms the normal minimum is 5.
That correction is committed, but a passing full matrix on the final revision
has not yet been established. The interrupted local rerun remains under
`build/api-coverage-final` and earns no complete coverage.

Resume by inspecting the latest PR/push CI runs, including all six corpora and
the required aggregate. Verify the corrected saved options and completion
boundary in a fresh local output directory if further diagnosis is needed.
Investigate any remaining `SERVER_BUSY` or packaged visual-smoke failures;
keep the first failure reports and do not loosen assertions or count retries
as success. Update PR validation once final-revision CI passes. The separate
reliability/lifecycle/performance/isolation campaigns remain outstanding.
