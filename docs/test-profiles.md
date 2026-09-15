# Test profiles — versioned client/server profiles and preflight

> Status: **draft** (execution-plan chunk 0.5). Normative once chunk 5.3
> lands; final form due by chunk 8.5. Spec source:
> `docs/product-spec.md` §8.

## Section contract (must contain before `status: final`)

1. Profile model: versioned, opt-in, restricted to dedicated test
   directories; the guarantee that installing the mod never overwrites a
   user's normal settings (spec §8 preamble).
2. Client profile schema and verified effective values (window, fullscreen,
   GUI scale, language, sensitivity, auto-jump, toggles, `pauseOnLostFocus`,
   background limiting, distances, frame cap/VSync, onboarding state, chat/
   multiplayer warnings, narrator, resource packs, audio) (spec §8.1).
3. Server profile schema (auth mode, bind/port, capacity, seed, distances,
   difficulty/gamemode, gamerules, empty-server pause, packs, API limits)
   (spec §8.2).
4. EULA responsibility: supervisor requires explicit operator-provided
   acceptance; the mod never accepts automatically (spec §8.3; mirrors
   AGENTS.md §7).
5. Preflight output contract: profile id/version, requested vs effective
   settings, deviations, unsupported settings, identity conflicts, rendering
   readiness, missing permissions, fatal vs advisory issues (spec §8.4).
6. The fresh-profile test suite and the documented bootstrap workflow for
   onboarding states that cannot be set via supported settings (spec §8.1).

## Current state

Not yet implemented. `docs/examples/mapi.properties.example` documents the
API's own configuration only; no test-profile system exists.
