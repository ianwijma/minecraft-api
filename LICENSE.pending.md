# License status — NOT SELECTED (owner decision required)

The project owner has not chosen a license yet. This file documents that
fact and what must happen before publishing.

## Decision required before any publishing or distribution

1. Pick a license (e.g. MIT, Apache-2.0, LGPL-2.1 — common choices for
   Minecraft mods; `https://choosealicense.com/` for orientation).
2. Replace this file with the chosen `LICENSE` text.
3. Update the license fields:
   - `gradle.properties` → `mapiLicense`
   - `fabric/src/main/resources/fabric.mod.json` → `license`
   - `neoforge/src/main/templates/META-INF/neoforge.mods.toml` → `license`
   - the POM license entry in `common/build.gradle`
4. Decide whether to add copyright headers to source files.

## Current placeholder

`UNLICENSED` — all rights reserved by default; **the distributable jars
must not be published or redistributed** until a real license is selected.

## Upstream notices

The distributable jars contain **no third-party code**: slf4j-api is
compileOnly (provided by the game at runtime), JUnit is test-scope only, and
loader/build tooling is not redistributed. Third-party tools used to develop
this repository (Gradle, Loom, ModDevGradle, Spotless, JUnit, slf4j, Fabric
API, NeoForge) carry their own licenses; see `THIRD_PARTY_NOTICES.md`.