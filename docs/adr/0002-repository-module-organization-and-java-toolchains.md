# 0002. Repository module organization and Java toolchains

Date: 2026-09-13
Status: Accepted (2026-09-13)

## Context

Spec §15.1 selects "one repository with common non-Minecraft modules and
explicit per-version bridges" as the build strategy and lists ten module
groups (contract/DTOs, transport/auth, jobs-leases-events-scheduling,
Minecraft-version bridge, Fabric platform, NeoForge platform, optional
adapters, cross-loader fixture mod, SDK generation, reference runner).
Spec §15.2 requires the Java toolchains and per-version source/build
organization to be fixed by ADR.

Verified facts:

- `common/` already compiles against the JDK only (plus compileOnly slf4j)
  and merges into both loader jars; `MapiPlatform` is the single loader seam.
- Java 25 is the game's runtime for 26.2; Gradle 9.7.1 runs both loader
  plugins (Loom 1.17.20, ModDevGradle 2.0.147); foojay resolver 1.0.0
  fetches the toolchain (docs/toolchain.md).

## Decision

1. **Single repository.** All module groups live in this repository.
2. **Staged module introduction — no empty scaffolding.** A Gradle module is
   created when it gains real content, not before. Mapping of module groups
   to locations:

   | Spec §15.1 group | Location now | Becomes separate module when |
   | --- | --- | --- |
   | Contract and DTOs | `common` (`dev.example.mapi.api` + contract packages) | DTO surface grows enough to warrant a published artifact (owner call) |
   | Transport/authentication | `common` (`internal.http`) | — |
   | Jobs, leases, events, scheduling | `common` (`internal.*`) | — |
   | Minecraft-version bridge | loader modules behind `MapiPlatform` | second version approved (ADR-0001) → `bridge-<version>` modules |
   | Fabric / NeoForge platform | `fabric/`, `neoforge/` | already separate |
   | Optional adapters | `adapters/<name>/` | first adapter lands |
   | Cross-loader fixture mod | `fixture-mod/` | chunk 6.1 |
   | SDK generation | `sdk/<language>/` | chunk 7.2 |
   | Reference runner | `runner/` | chunk 5.1 |

   Package namespace stays `dev.example.mapi[.<module>]`.
3. **Dependency rules (enforced by review + `verifyDistributions`).**
   - Contract/DTO, transport, and scheduling code must not compile against
     Minecraft or loader APIs — ever (spec §15.1: "Minecraft classes must
     not leak into the transport contract").
   - Loader modules depend on `common` (and future bridge/substrate
     modules); never the reverse.
   - Runner, SDKs, and fixture mod depend only on the HTTP contract /
     public API — never on `common` internals.
   - Distributable loader jars merge shared module classes exactly once
     (current `verifyDistributions` check generalizes to any new shared
     module).
4. **Java toolchains: Java 25** for all modules (matches the game runtime;
   `javaVersion.majorVersion=25` for 26.2). The Gradle JVM may be newer via
   toolchain resolution; compiled bytecode targets 25.
5. **Build tooling:** Gradle wrapper (9.7.1 at verification), versions
   pinned exact in `gradle/libs.versions.toml` with verification notes in
   `docs/toolchain.md`; Spotless formatting gate; JUnit 5 for pure-JVM tests;
   no test may launch Minecraft (existing rule).
6. Root project-level task aliases (`verify`, `buildAll`, `testAll`,
   `validateManifest`, `doctor`, `llmContext`) remain the stable interface;
   new modules are wired into them as they appear.

## Consequences

- No big-bang restructure: the current four-module layout is compliant
  today; new modules appear exactly when their chunks land (5.1, 6.1, 7.2).
- Package-level discipline inside `common` must be maintained until/unless a
  contract split happens; `verifyDistributions` continues to forbid bundling
  slf4j, loader classes, or example code.
- A future contract-module split is a mechanical extraction, blocked only by
  the owner decision noted above.

## Compliance

- `settings.gradle` module list matches the "Location now" column plus
  modules that have actually landed.
- Dependency-direction violations fail review; `./gradlew verify` stays
  green.
- `gradle.properties` + `libs.versions.toml` remain the only version
  sources.
