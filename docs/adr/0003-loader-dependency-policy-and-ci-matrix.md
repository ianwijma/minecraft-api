# 0003. Loader dependency policy and CI matrix

Date: 2026-09-13
Status: Accepted (2026-09-13)
Amended: 2026-09-13 — the dedicated-server smoke job is the single
EULA-gated CI exception (owner decision).

## Context

Spec §15.2 requires the loader dependency policy and CI matrix to be approved
before Milestone 1. The repository already builds first-class Fabric and
NeoForge artifacts from one shared `common` module (docs/architecture.md,
docs/toolchain.md, verified 2026-09-13). No CI workflows exist yet
(`.github/` has no workflows).

## Decision

1. **Both loaders are first-class, permanently.** Every mod capability ships
   on Fabric and NeoForge; loader parity is a release gate (spec §18).
2. **Loader dependency policy.**
   - Loader APIs appear only in `fabric/` and `neoforge/` modules. Shared
     modules compile against the JDK (+ compileOnly slf4j) only.
   - Fabric API is a dependency of the Fabric artifact only; NeoForge's
     equivalent surface arrives via ModDevGradle userdev. Neither is exposed
     through the public Java API.
   - No third-party multi-loader abstraction (Architectury etc.). The loader
     seam is `MapiPlatform`; entrypoints stay thin.
   - Loader-specific metadata (`fabric.mod.json`, `neoforge.mods.toml`) may
     declare loader-version ranges but the Minecraft target stays exactly
     per ADR-0001.
   - Capability parity is declared per capability: anything that cannot be
     implemented on one loader via supported hooks is documented as a known
     loader limitation in that capability's docs, never silently dropped on
     one side (spec §15.3).
3. **CI matrix (established in plan chunk 0.4).**
   - Platform: GitHub Actions, `ubuntu-latest` (Linux x64).
   - JDK: Temurin 25 via the foojay/toolchain mechanism already in the
     build.
   - Matrix axes: none initially (single version per ADR-0001). When
     ADR-0001 gains a second version, the matrix gains that axis
     automatically.
   - Jobs: (a) `./gradlew verify` at root (format, tests, distribution and
     manifest validation); (b) loader build matrix `{fabric, neoforge}` for
     distributable artifacts; (c) consumer example compiled against the
     published artifact (`publishLocal` +
     `-PmapiConsumerUseMavenLocal=true`); (d) `doctor` + `llmContext` with
     the generated context uploaded; (e) dedicated-server smoke test (see
     below). Triggers include `workflow_dispatch`.
   - Game-launching checks: `runClient` and E2E suites are not CI jobs
     initially (CI runners have no display or GPU); they run locally and on
     release gates (chunk 8.4) and are marked NOT RUN when absent. The
     dedicated-server smoke test is the **single exception**: it runs in CI
     only when the owner explicitly sets the repository variable
     `MAPI_ACCEPT_EULA=true`; otherwise the job is skipped. CI never
     accepts the EULA silently — setting the variable is the explicit
     operator decision. Release-gate smoke (chunk 8.4) is required
     regardless.
   - PRs: at least job (a) required green before merge.

## Consequences

- CI adds no new licensing or secret-handling burden; no EULA acceptance
  happens in CI.
- Capability tests that need a real client cannot gate PRs in CI until a
  self-hosted/display runner exists; until then the acceptance campaigns
  (chunk 8.2) carry them.
- Loader parity claims in docs must cite tests that actually ran on both
  loaders.

## Compliance

- `.github/workflows/` contains the matrix described above (chunk 0.4).
- `grep` for loader imports in `common/` returns nothing.
- Loader artifacts never bundle the other loader's API (extend
  `verifyDistributions` if needed).
