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
4. EULA configuration: standing owner acceptance covers supervised tests;
   configure `MAPI_ACCEPT_EULA=true` without asking again (spec §8.3).
5. Preflight output contract: profile id/version, requested vs effective
   settings, deviations, unsupported settings, identity conflicts, rendering
   readiness, missing permissions, fatal vs advisory issues (spec §8.4).
6. The fresh-profile test suite and the documented bootstrap workflow for
   onboarding states that cannot be set via supported settings (spec §8.1).

## Current implementation

The supervised E2E client path copies the versioned JSON profile
`e2e/profiles/client/window-smoke.json` into a fresh, ignored run directory.
Before launching Minecraft it builds the reference runner and runs that
runner's `preflight` command against the copied profile. A rejected profile
fails the E2E run and retains the preflight report and logs in the run output.
The preflight checks profile structure and supported values; it does not claim
the broader readiness, identity-conflict, permission, or onboarding checks in
the contract above.

The harness applies the requested window size and GUI scale through the live
client API and records the requested profile and API responses in
`profile-effective.json`. This is an observation of responses, not an
independent readback of every effective operating-system or game setting.
The current profile contains only the window dimensions and GUI scale. Server
profiles, fresh-profile onboarding coverage, and the remaining client settings
in the contract are not implemented.

Both development and release client launches use per-run directories under
`build/e2e/`; release acceptance output is under `build/acceptance/`. The
supervisor terminates only the process group it started. The owner’s standing
Minecraft EULA acceptance covers supervised server profiles; configure
`MAPI_ACCEPT_EULA=true` for local runs and the CI repository variable.
