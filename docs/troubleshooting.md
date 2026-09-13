# Troubleshooting

## Build

**`Unsupported class file major version` / weird toolchain errors**
Gradle is running on the wrong JDK. Set `JAVA_HOME` to JDK 25
(`./gradlew doctor` shows what it detected).

**First build is slow / downloads fail**
The first build fetches Minecraft 26.2, Fabric API, and NeoForge. Check
network access to `piston-meta.mojang.com`, `maven.fabricmc.net`,
`maven.neoforged.net`, `services.gradle.org`, and Maven Central
(`scripts/doctor.sh` probes all of them). Corporate proxies: configure
Gradle proxy settings in `~/.gradle/gradle.properties`.

**`Could not resolve net.fabricmc.fabric-loom` or `net.neoforged.moddev`**
`settings.gradle` pins the plugin repositories (Fabric maven, NeoForged
maven, Gradle Plugin Portal). Offline builds are not supported for the first
sync.

**Wrapper checksum mismatch**
`gradle/wrapper/gradle-wrapper.properties` pins
`distributionSha256Sum`. If Gradle complains, someone changed the pinned
version without updating the checksum. Regenerate per `docs/toolchain.md`.

**Spotless fails with format violations**
Run `./gradlew formatApply`, inspect the diff, then `./gradlew verify`.

## Runtime (dev runs)

**`MAPI HTTP API: failed to bind 127.0.0.1:25586`**
Port in use. Change `http.port` or stop the other listener; the game keeps
running without the API.

**`MAPI: HTTP API not started: ...`**
The config refused startup: missing token, token < 16 chars, invalid port.
The remediation text tells you exactly which env var/file to fix.

**Server run asks for EULA**
Dedicated server runs require Mojang's EULA acceptance — an explicit
operator step. Either open `eula.txt` in the run dir and set
`eula=true` yourself (never commit it), or use the gated smoke script:
`MAPI_ACCEPT_EULA=true scripts/server-smoke.sh <loader>`.

**HTTP enabled but `/api/v1/server/status` returns `running:false`**
No server is running in that process (e.g. you queried while a client sat at
the title screen). The HTTP API binds only while a server instance is
active.

**503 `SERVER_BUSY` from `/api/v1/server/status`**
The tick thread was too busy within the 500 ms snapshot bound. Retry;
persistently high values indicate server-side lag.

**Client class errors on a dedicated server**
Should not happen (no client-only code exists). If you added some, isolate
it per `docs/architecture.md` ("Client-only code").

**`jdk.httpserver` missing**
Only on extremely trimmed JREs; the mod logs a clear error and continues.
Use a standard JDK/JRE image (Temurin, Mojang's bundled runtime).

## Tests / checks

**Tests hang on first run after a crash**
A previous test JVM may hold sockets: `pkill -f GradleWorkerMain`, then
re-run.

**`project.manifest.json is stale`**
The manifest references a missing file/task or disagrees with
`gradle.properties`/`gradle/libs.versions.toml`. Fix the manifest or the
build — they must agree (`./gradlew validateManifest`).

**HTTP tests fail with h2c/`Upgrade` header weirdness**
The test clients pin HTTP/1.1 (`HttpClient.Version.HTTP_1_1`); the JDK
client's default h2c upgrade can drop headers against
`com.sun.net.httpserver`. Keep that pin.

## Getting help

Include: `./gradlew doctor` output, the failing Gradle task, and the relevant
log section (never tokens or run-directory contents).