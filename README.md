# minecraft-api (MAPI)

A Minecraft Java Edition **26.2** mod development repository for the
"Minecraft API" (MAPI) project, supporting **Fabric** and **NeoForge** from
one shared codebase.

**Implemented capabilities**

- `common` provides the loader-neutral public Java API, authenticated local
  HTTP transport, jobs, leases, events, snapshots, and bounded game operations.
- `fabric` and `neoforge` provide server and isolated client bridges for tick
  control, world queries, commands, input, UI/inventory, screenshots, window
  settings, world management, and connection setup. Capabilities are reported
  by each running instance; implementation does not imply release acceptance.
- `sdk` contains Java, Python, and generated TypeScript HTTP clients;
  `runner` provides out-of-process automation utilities.
- `e2e` contains the TypeScript visual harness and scenarios.
  `example-consumer` demonstrates the public Java API and is excluded from mod jars.

The HTTP API is disabled by default and binds only to loopback. When enabled,
its mutation endpoints can control the game; configure scopes for the intended
workload and follow [the security model](docs/security.md).

The approved [product specification](docs/product-spec.md) defines the target
product. [The execution plan](docs/execution-plan.md) separates implemented
surfaces from remaining fixture, parity, and release acceptance work. The mod
works independently of the runner, an LLM, or an IDE.

## Prerequisites

- **JDK 25** (Temurin recommended). Minecraft 26.2 requires Java 25; Gradle
  runs on the same JDK. If you have a different JDK installed, the build will
  try to download JDK 25 automatically (foojay resolver), but installing it
  is the reliable path.
- Network access for the first build (downloads Minecraft 26.2, Fabric API,
  NeoForge, and build tooling).
- Linux/macOS shell for `scripts/*.sh` (Windows: use Git Bash or WSL).

## Quick start (fresh clone)

```bash
git clone <your-repo-url> minecraft-api && cd minecraft-api

# 1) Diagnose the environment
./gradlew doctor            # or: scripts/doctor.sh

# 2) Build both loader artifacts
./gradlew buildAll

# 3) Run all tests + packaging validation
./gradlew verify

# 4) (optional) publish the common API artifact locally
./gradlew publishLocal
```

### Windows (cmd/PowerShell) equivalents

```bat
gradlew.bat doctor
gradlew.bat buildAll
gradlew.bat verify
gradlew.bat publishLocal
```

Setting `JAVA_HOME` explicitly (example, adjust path):

```bash
# Linux/macOS
export JAVA_HOME=/path/to/jdk-25
# Windows (cmd)
set JAVA_HOME=C:\path\to\jdk-25
```

## IDE import and debugging

- **IntelliJ IDEA**: Open the repository root as a Gradle project; use JDK 25
  as the Gradle JVM. Loom (fabric) and ModDevGradle (neoforge) create run
  configurations automatically ("Minecraft Client"/"Minecraft Server" per
  module). Set **Gradle JVM** to JDK 25 in Settings → Build Tools → Gradle.
- **VS Code**: install the *Extension Pack for Java* + *Gradle for Java*,
  then import at the root. Run configs come from `gradlew` tasks.
- Breakpoints in `common` code work from both loader runs; launch
  `:fabric:runClient` or `:neoforge:runClient` via the IDE run config.

## Build outputs and installation

| Artifact | Path |
| --- | --- |
| Fabric mod | `fabric/build/libs/minecraft-api-fabric-0.1.0.jar` |
| NeoForge mod | `neoforge/build/libs/minecraft-api-neoforge-0.1.0.jar` |
| Common API jar (for consumers) | `common/build/libs/minecraft-api-common-0.1.0.jar` |
| Public API javadoc | `common/build/docs/javadoc/index.html` |

**Install exactly one loader artifact** — the Fabric jar into a Fabric
`mods/` folder, or the NeoForge jar into a NeoForge `mods/` folder. Never
install both together: they contain the same shared classes and duplicate
registration would be attempted.

The development runs use **separate directories** per loader and environment
(`fabric/run/client`, `fabric/run/server`, `neoforge/run/client`,
`neoforge/run/server`).

## Java API consumption

Publish locally, then depend on it (see `docs/api.md` for the full contract):

```kotlin
// your mod's build.gradle(.kts)
repositories { mavenLocal() }
dependencies { implementation("dev.example.mapi:minecraft-api-common:0.1.0") }
```

```java
MapiApi.require().services().register("your-service", service);
Optional<ServerStatusSnapshot> status = MapiApi.get().flatMap(Mapi::serverStatus);
```

A runnable consumer example lives in `example-consumer/`.

## Local HTTP API — explicitly opt-in, secured by default

The HTTP API is **off by default**. To enable it for a session:

```bash
export MAPI_HTTP_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
# In the instance's config dir (<runDir>/config/ - loader-native, auto-generated):
#   http.enabled=true
#   http.token=<the same long token>   (env var is preferred)
```

Then:

```bash
curl -s -H "Authorization: Bearer $MAPI_HTTP_TOKEN" http://127.0.0.1:25586/api/v1/health
```

Defaults: loopback-only bind, bearer token required on every endpoint,
60 req/min rate limit, no CORS. Full details: `docs/http-api.md` and
`docs/security.md`.

## How an LLM should start working here

1. Read `AGENTS.md` — it is authoritative.
2. Generate a bounded repo snapshot: `./gradlew llmContext` → `build/llm/CONTEXT.md`.
3. Follow the workflow in `docs/llm-workflow.md`
   (read → inspect → propose → implement → verify → report).

## Known limitations / unverified environments

- Dedicated-server smoke tests and client runs require a display-capable or
  EULA-accepting environment; see `docs/development.md` (server checks are
  gated behind `MAPI_ACCEPT_EULA=true`, which is an explicit operator step).
- The Java package/group `dev.example.mapi` is a **placeholder** the project
  owner must replace before publishing.
- **No license is selected yet** — see `LICENSE.pending.md`; do not publish
  until the owner picks one.
- The HTTP API targets `jdk.httpserver`, included in standard JDK images;
  extremely trimmed JREs may lack it (checked at startup and logged).
- Authors list and repository URLs are intentionally empty until provided by
  the owner.