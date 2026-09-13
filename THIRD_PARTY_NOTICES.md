# Third-party notices (development tooling and runtime-provided libraries)

The distributable jars (fabric/neoforge/common) intentionally bundle **no
third-party code**. The following libraries are used to build, test, or are
provided by the game runtime; they are not redistributed by this project.

| Component | Role | License | Source |
| --- | --- | --- | --- |
| Gradle 9.7.1 | build tool | Apache-2.0 | services.gradle.org |
| Fabric Loom 1.17.20 | Fabric build plugin | Apache-2.0 | maven.fabricmc.net |
| ModDevGradle 2.0.147 | NeoForge build plugin | LGPL-2.1 (see upstream) | maven.neoforged.net |
| Fabric Loader 0.19.5 / Fabric API 0.160.0+26.2 | runtime dependency of the Fabric artifact only | Apache-2.0 | fabricmc.net |
| NeoForge 26.2.0.87 | runtime dependency of the NeoForge artifact | LGPL-2.1 | maven.neoforged.net |
| slf4j-api 2.0.19 | compileOnly; provided by Minecraft at runtime | MIT | slf4j.org |
| JUnit 5 (BOM 5.14.1) | test-only | EPL-2.0 | junit.org |
| Spotless 8.10.2 | build-time formatting | Apache-2.0 | github.com/diffplug/spotless |
| foojay-resolver-convention 1.0.0 | JDK toolchain download | Apache-2.0 | plugins.gradle.org |

Minecraft itself is never bundled. Its EULA applies when running the game;
acceptance is an explicit operator step (see `docs/development.md`).

If any dependency is ever bundled into a distributable jar (none are
today), its license text must be added to this file and to the jar's legal
notices before release.