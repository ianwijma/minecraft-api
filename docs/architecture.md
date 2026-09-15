# Architecture

## Module groups (ADR-0002)

The target module groups come from `docs/product-spec.md` §15.1; their
concrete locations and introduction policy are fixed in ADR-0002
(**staged introduction**: a Gradle module is created when it gains real
content, never as empty scaffolding).

| Spec §15.1 group | Location now | Becomes separate module when |
| --- | --- | --- |
| Contract and DTOs | `common` (`dev.example.mapi.api` + contract packages) | owner decides the DTO surface warrants a published artifact |
| Transport/authentication | `common` (`internal.http`) | — |
| Jobs, leases, events, scheduling | `common` (`internal.*`) | — |
| Minecraft-version bridge | loader modules behind `MapiPlatform` | second version approved → `bridge-<version>` modules |
| Fabric / NeoForge platform | `fabric/`, `neoforge/` | already separate |
| Optional adapters | `adapters/<name>/` | first adapter lands |
| Cross-loader fixture mod | `fixture-mod/` | execution-plan chunk 6.1 |
| SDK generation | `sdk/<language>/` | execution-plan chunk 7.2 |
| Reference runner | `runner/` | execution-plan chunk 5.1 |

Dependency rules (normative, enforced by review and `verifyDistributions`):

1. Contract/DTO, transport, and scheduling code never compiles against
   Minecraft or loader APIs — Minecraft classes must not leak into the
   transport contract.
2. Loader modules depend on shared modules; never the reverse.
3. Runner, SDKs, and the fixture mod depend only on the HTTP contract /
   public API, never on `common` internals.
4. Distributable loader jars merge shared module classes exactly once
   (`verifyDistributions` asserts this).

## Modules

```
minecraft-api/
├── common/            Shared, loader-neutral code (public API + internals)
├── fabric/            Fabric entrypoint + platform adapter + metadata
├── neoforge/          NeoForge entrypoint + platform adapter + mods.toml
├── example-consumer/  Consumer example (public API only; never shipped)
└── docs/, scripts/, .github/
```

- **common** compiles against the JDK only (plus compileOnly slf4j-api, which
  the game provides). It contains:
  - `dev.example.mapi.api` — the **public Java API** (the only package other
    mods should compile against).
  - `dev.example.mapi.internal` — runtime state, config parsing, JSON
    writer, rate limiter, and the loopback HTTP server.
- **fabric** and **neoforge** implement one seam each:
  `internal.MapiPlatform` (type, versions, directories, logger, lifecycle
  registration) plus `ServerHandle`/`RawServerInfo` for server-thread access.
  Entrypoint classes are deliberately thin.
- **example-consumer** depends on the public API only. With
  `-PmapiConsumerUseMavenLocal=true` it consumes the artifact published by
  `./gradlew publishLocal` from `~/.m2`, validating the real publishing path.

## Packaging

Each distributable jar (fabric/neoforge) **merges the common module's classes
and resources exactly once** (`tasks.jar { from(project(':common').sourceSets.main.output) }`).
No remapping is needed on MC 26.x because the game ships unobfuscated and
both loaders run on official names. `verifyDistributions` (root Gradle task)
asserts this, checks loader metadata, and forbids bundling slf4j, loader
classes, or example code.

## Data flow

```
loader lifecycle event (Fabric/NeoForge)
        │  (loader module adapter)
        ▼
ServerLifecycleListener.onServerStarting(ServerHandle)
        │
        ▼
MapiRuntime ── owns ──► MapiServicesImpl (thread-safe registry)
        │
        ├─► binds dev.example.mapi.api.MapiApi (public facade)
        └─► HttpApiServer (only if config enables it)
                 │  workers (2 daemon threads, bounded queue)
                 ▼
            runtime.trySnapshot()
                 │  FutureTask submitted via ServerHandle.executeOnServerThread
                 ▼
            server thread reads RawServerInfo ──► ServerStatusSnapshot (immutable)
```

Rules baked into this flow:

- The tick thread is never blocked by HTTP work; snapshots use a bounded
  500 ms wait (`MapiRuntime.SNAPSHOT_WAIT_MS`).
- The HTTP worker never touches live game objects directly; it only submits
  the snapshot task and waits with a bound.
- HTTP starts on server start and stops on server stop (integrated servers
  included), so repeated sessions and port conflicts are handled without
  crashing the game.

## Client-only code

There is none. Both entrypoints are `environment: "*"`/`side=BOTH`. If
client-only code is ever added, it must live in a separate source set or
module that dedicated servers never load, per the loaders' documented
mechanisms (`loom.splitEnvironmentSourceSets()` on Fabric,
`net.neoforged.api.distmarker.Dist` guards on NeoForge).

## Extension points for growth

- New loader: add a module implementing `MapiPlatform`, keep `common` intact.
- New endpoint: `HttpApiServer.route()` + tests + `docs/openapi.yaml`.
- New public API: `api` interfaces + `MapiRuntime` + `docs/api.md`.