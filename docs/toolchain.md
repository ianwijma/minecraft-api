# Toolchain — verified versions and sources

Verification date: **2026-09-13**. Every version below was checked against the
official source listed. Do not bump anything without re-verification, and
never silently change the Minecraft target (26.2) — ask the owner first.

| Component | Version | Source |
| --- | --- | --- |
| Minecraft (target) | **26.2** (latest release; releaseTime 2026-06-16) | https://piston-meta.mojang.com/mc/game/version_manifest_v2.json |
| Java | **25** (`javaVersion.majorVersion=25` in the 26.2 manifest; "Mojang ships Java 25 to end users in 26.2") | 26.2.json entry of the version manifest; NeoForgeMDKs/MDK-26.2-ModDevGradle `gradle.properties` |
| Gradle | **9.7.1** (current at verification; sha256 `acd53f1e…804d20a` embedded in the wrapper properties) | https://services.gradle.org/versions/current |
| Fabric Loader | **0.19.5** | https://meta.fabricmc.net/v2/versions/loader and FabricMC/fabric-example-mod branch `26.2` |
| Fabric API | **0.160.0+26.2** | https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml |
| Fabric Loom | **1.17.20**, plugin id `net.fabricmc.fabric-loom` | https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml; example-mod uses `1.17-SNAPSHOT` of the same minor |
| Mappings (Fabric) | **none needed** — 26.2 ships unobfuscated; the official example-mod declares no `mappings` dependency | 26.2.json `downloads` contains only `client`/`server` (no `*_mappings`); fabric-example-mod `26.2` build.gradle |
| NeoForge | **26.2.0.87** | https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml; NeoForgeMDKs/MDK-26.2-ModDevGradle |
| ModDevGradle | **2.0.147**, plugin id `net.neoforged.moddev` | MDK-26.2-ModDevGradle `build.gradle`; https://maven.neoforged.net/releases/net/neoforged/moddev-gradle/maven-metadata.xml |
| Toolchain resolver | foojay-resolver-convention **1.0.0** | MDK-26.2-ModDevGradle `settings.gradle`; https://plugins.gradle.org |
| Spotless | **8.10.2** | https://plugins.gradle.org (plugin marker metadata) |
| JUnit | BOM **5.14.1** (Jupiter + platform launcher) | referenced by NeoForged ModDevGradle's own `testproject/build.gradle` |
| slf4j-api | **2.0.19** (compileOnly — provided by the game at runtime, never bundled) | https://repo1.maven.org/maven2/org/slf4j/slf4j-api/ |
| Wrapper-verified JDK used for local builds | Temurin **25.0.4.1+1** | https://api.adoptium.net (Gradle wrapper distribution checksum independently verified against services.gradle.org) |

## Architecture decisions that follow from verification

1. **Unobfuscated workflow.** MC 26.x distributes unobfuscated jars and no
   mapping files. Consequences, all verified against the official
   `fabric-example-mod` 26.2 template:
   - Loom is configured **without any `mappings` declaration**.
   - `fabric-loader` and `fabric-api` are declared with plain
     `implementation` (legacy `modImplementation`/remapping is not used).
   - The shared `common` classes can be merged directly into each loader jar
     (`from(project(':common').sourceSets.main.output)`) because both loaders
     run on the official (unobfuscated) class names.
2. **No third-party multi-loader abstraction** (Architectury etc.) is used:
   the loader seam is the internal `MapiPlatform` interface, and the common
   module does not compile against Minecraft at all, so no mappings story is
   needed for shared code.
3. **NeoForge mods.toml**: per the 26.2 MDK, `neoforge.mods.toml` lives in
   `src/main/templates/` and no longer declares `modLoader`/`loaderVersion`;
   it declares `license`, `[[mods]]`, and `[[dependencies]]` blocks only.
4. **Gradle**: Loom 1.17.20 and ModDevGradle 2.0.147 both run on Gradle 9.x
   (templates pin 9.2.1–9.5.1; 9.7.1 verified in this repository's build).
5. **Configuration cache is disabled** because Loom is not yet
   configuration-cache compatible (fabric-loom issue #1349, noted in the
   official example template's `gradle.properties`).
6. **HTTP server**: the local HTTP API uses the JDK's built-in
   `com.sun.net.httpserver.HttpServer` (`jdk.httpserver` module) — zero
   bundled HTTP dependencies. Standard JDK/JRE images include this module;
   `HttpApiServer` logs a clear error if it is absent.

## Re-verification checklist (run before bumping anything)

1. Minecraft: `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json`
   → find the exact target id → fetch its `.json` and check `javaVersion`.
2. Fabric Loader/API: `https://meta.fabricmc.net/v2/versions/loader`,
   `https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml`.
3. Loom: `https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml`
   + the `fabric-example-mod` branch for the target MC version.
4. NeoForge: `https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml`
   (pick `<mc>.<x>.<y>.<build>` matching the MC version) and the matching
   `NeoForgeMDKs/MDK-<mc>-ModDevGradle` template for plugin usage.
5. Gradle: `https://services.gradle.org/versions/current` (+ checksum), then
   regenerate the wrapper and re-run `./gradlew verify`.

## Minecraft API notes (verified against the official 26.2 jar)

- `MinecraftServer#getMotd()` returns `String`.
- Tick timing: `MinecraftServer#getAverageTickTimeNanos()` (long, nanos).
- Max players: `MinecraftServer#getPlayerList().getMaxPlayers()`.
- Task queueing onto the server thread: inherited
  `BlockableEventLoop#execute(Runnable)`.
- 26.2 jars are unobfuscated (official names); `javap` works directly on the
  downloaded client jar.
- Physical side detection (verified via `javap` against the cached jars):
  Fabric — `FabricLoader#getEnvironmentType()` returning
  `net.fabricmc.api.EnvType`; NeoForge —
  `net.neoforged.fml.loading.FMLEnvironment#getDist()` (**method**, FML 11
  removed the old public `dist` field) returning
  `net.neoforged.api.distmarker.Dist` (`CLIENT`/`DEDICATED_SERVER`).
- 26.2 server-read APIs (verified via `javap` against
  `fabric-loom/26.2/minecraft-merged.jar`, 2026-09-13; used by slice 0.5):
  `PlayerList#getPlayers()`; `ServerPlayer#getGameProfile()` → authlib 9
  **record** (`name()`/`id()`); `Entity#position()` → `Vec3` (`x()/y()/z()`);
  `Entity#level()`; `Level#dimension()` → `ResourceKey` with
  **`identifier()`** (26.2 renamed `ResourceLocation` → `Identifier`,
  `location()` → `identifier()`); `Identifier#parse(String)`;
  `MinecraftServer#getLevel(ResourceKey)`; `Level#hasChunkAt(BlockPos)`;
  `Level#getBlockState(BlockPos)`; `BlockState#getBlock()` +
  `BuiltInRegistries.BLOCK#getKey(Block)`; `BlockState#getProperties()` +
  `Property#getName()` / `Property#getName(T)`; time (26.2 clock model):
  `LevelAccessor#getGameTime()`, `Level#getOverworldClockTime()`,
  `Level#getDefaultClockTime()`; `MinecraftServer#getWorldData()#getVersion()`
  (data version).
- 26.2 client-read APIs (verified via `javap` against the merged jar,
  2026-09-13; used by slice 0.6): `Minecraft#getInstance()`;
  `Minecraft#getWindow()` → `com.mojang.blaze3d.platform.Window`
  (`getWidth/getHeight/getGuiScaledWidth/getGuiScaledHeight/getGuiScale`);
  current screen is **`Minecraft#gui#screen()`** (26.2 moved the screen off
  `Minecraft` — there is no `screen` field/getter on `Minecraft` anymore);
  `Screen#children()` → `List<? extends GuiEventListener>`;
  `AbstractWidget#getX/getY/getWidth/getHeight/getMessage`;
  `Component#getString()`; `Entity#level()`; `NeoForge`'s
  `net.neoforged.api.distmarker.OnlyIn` still exists in 26.2
  (`@OnlyIn(Dist.CLIENT)` + dist guard + lazy classloading is the client
  isolation contract for `dev.example.mapi.client.*` classes).
- 26.2 client input/screenshot APIs (verified via `javap`, 2026-09-13;
  slice 0.6): key injection goes through the static
  `KeyMapping#set(InputConstants$Key, boolean)` / `KeyMapping#click(...)`
  with `KeyMapping#getDefaultKey()` and runtime-reported
  `KeyMapping#getName()` — the old `KeyboardHandler#keyPress` /
  `MouseHandler#mouseButtonPress` are gone (input moved to the
  `net.minecraft.client.input` event system); mappings are reachable via
  public `Options#keyUp/keyLeft/...` fields; screenshots via
  `Screenshot#takeScreenshot(RenderTarget, Consumer<NativeImage>)` with
  `GameRenderer#mainRenderTarget()` (NOT `Minecraft#getMainRenderTarget`)
  and `NativeImage#writeToFile(Path)` + `close()`.

Version bumps and target changes require explicit owner approval — see
AGENTS.md §7.