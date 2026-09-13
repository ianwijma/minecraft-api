# Development guide

All commands run from the repository root with JDK 25 active. The canonical
task list lives in `AGENTS.md` §4; this page explains what they do and when
to use them.

## Environment

`./gradlew doctor` (or `scripts/doctor.sh`) checks: JDK 25 availability,
Gradle wrapper integrity, network reachability of the toolchain sources
(Mojang meta, Fabric maven, NeoForged maven, Gradle services, Maven Central),
and available disk space. It exits non-zero on blocking failures.

The build auto-downloads JDK 25 via the foojay resolver if the local JDK is
unsuitable, but pinning `JAVA_HOME` to JDK 25 is recommended (Gradle, the
compiler, and game runs all use it).

## Everyday loop

```bash
./gradlew verify          # formatCheck + testAll + verifyDistributions + validateManifest
./gradlew formatApply     # auto-fix formatting before committing
```

## Development runs (separate dirs per loader/environment)

| Command | Working directory |
| --- | --- |
| `./gradlew :fabric:runClient` | `fabric/run/client` |
| `./gradlew :fabric:runServer` | `fabric/run/server` |
| `./gradlew :neoforge:runClient` | `neoforge/run/client` |
| `./gradlew :neoforge:runServer` | `neoforge/run/server` |

Each run directory gets its own `config/` — that is where `mapi.properties`
lives (see `docs/examples/mapi.properties.example`). The server run directory
can be overridden with `-PmapiServerRunDir=<path>` (used by the smoke script).

**These dev runs do not require the Minecraft EULA** for a plain client, but
dedicated server runs will create `eula.txt` on first start; accepting it is
an explicit operator decision (see below).

## Dedicated-server smoke test (EULA-gated)

`scripts/server-smoke.sh <fabric|neoforge>`:

1. refuses to run unless `MAPI_ACCEPT_EULA=true` is set — accepting Mojang's
   EULA is the operator's decision and is never done silently;
2. uses an isolated temporary run directory (`build/smoke/<loader>/run`;
   absolute paths are used because Loom/ModDevGradle resolve relative run
   dirs against their own module);
3. starts the loader's `runServer` with a timeout, greps the log for
   successful startup, and stops the server;
4. **live HTTP probe** — when `MAPI_HTTP_TOKEN` is exported together with
   `MAPI_HTTP_ENABLED=true`, the script additionally verifies
   `/api/v1/{health,info,server/status}` inside the running game: 401
   without token, 200 + valid JSON with token.

```bash
MAPI_ACCEPT_EULA=true scripts/server-smoke.sh fabric
MAPI_ACCEPT_EULA=true scripts/server-smoke.sh neoforge
```

Never commit `eula.txt` or anything inside run directories.

## Manual client smoke test (when graphics are unavailable)

1. `./gradlew :fabric:runClient` (or `:neoforge:runClient`) on a machine with
   a display.
2. Confirm in the log: `MAPI 0.1.0 initialized (platform=fabric, ...)`.
3. Create a single-player world → confirm `MAPI: local HTTP API is disabled`
   (or, if enabled, the "listening" line).
4. Load a world, quit to title, create another world — confirm the HTTP API
   stops and restarts cleanly (`MAPI HTTP API stopped` between sessions).

## Tests

- `common` tests are pure JVM (JUnit 5): config parsing, JSON writer, rate
  limiter, service registry, lifecycle with fake handles, and full HTTP
  contract tests over a real loopback socket. `./gradlew testAll`.
- Loader-specific code is exercised by `verifyDistributions` (packaging) and
  the smoke scripts (game runtime). There are deliberately no GameTests in
  the initial setup — nothing gameplay-related to test yet.

## Publishing the API locally

```bash
./gradlew publishLocal                 # publishes minecraft-api-common to ~/.m2
./gradlew :example-consumer:build -PmapiConsumerUseMavenLocal=true
scripts/verify-published-api.sh        # publishes + compiles the example against ~/.m2
```

## Documentation

- `./gradlew apiDocs` → `common/build/docs/javadoc/index.html`
- `./gradlew llmContext` → `build/llm/CONTEXT.md` (bounded, secret-free repo
  snapshot for coding agents)
- `./gradlew validateManifest` keeps `project.manifest.json` honest (it is
  also part of `verify`).

## Gradle notes

- `org.gradle.configuration-cache=false` — Fabric Loom is not yet
  configuration-cache compatible (fabric-loom#1349).
- First build downloads Minecraft 26.2, mappings-free dev artifacts, Fabric
  API, and NeoForge; subsequent builds are incremental.
- CI is GitHub Actions (`.github/workflows/ci.yml`): builds both loaders,
  runs `verify`, compiles the consumer example against the published
  artifact, uploads jars/reports, and requires no credentials.