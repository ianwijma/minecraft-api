# E2E visual scenarios — repeatable examples (TypeScript)

> Status: **implemented** (2026-09-19): harness and all eight scenarios live
> in `e2e/` (unit-tested offline; 📐 pins and ⚙ verifications are tuned on
> the first supervised live run per §7). Every scenario runs against the
> **current** protocol v1 surface
> (`docs/openapi.yaml`) through the generated TypeScript SDK
> (`sdk/typescript`); no new mod endpoints are required. Validation is done by
> **machine screenshot diffing plus structured API assertions** — the LLM/agent
> never eyeballs images to decide pass/fail.
>
> Marker legend used below:
> - **📐 pin-once** — a concrete number (camera pose, tick count, pixel mask)
>   that is tuned during the first implementation run, reviewed by a human,
>   and then frozen forever in the scenario file. Changing a pinned value is a
>   deliberate, reviewable change.
> - **⚙ verify-26.2** — a vanilla mechanic or option key that must be checked
>   against Minecraft 26.2 during implementation (per `AGENTS.md` §7 we do not
>   invent game internals); the assertion contract is stated so the check has
>   a clear target.

---

## 1. What "repeatable" must mean here

A screenshot of Minecraft is only byte-stable if every input to the renderer
is controlled. The following table is the determinism charter; **every**
scenario applies it through shared harness helpers (§3).

| Source of drift | Control | Mechanism |
| --- | --- | --- |
| World generation | Fixed seed `20260919`, single world id `mapi-e2e`, deleted and re-created each session | `worlds/delete` (destructive grant) → `worlds/create` → poll `/server/world` until `ACTIVE` |
| Non-flat vanilla terrain | "Stage" carving: a 97×97 column per scenario is `/fill`ed to air above a floor slab at Y=64 — visually indistinguishable from superflat inside render distance (fog hides the horizon), without needing a world-type preset (see §4) | `/fill` via `POST /server/commands` |
| Hostile mobs | Off | `/difficulty peaceful` |
| Passive mobs (incl. wandering trader, patrols — these have **separate** gamerules) | Off | `spawn_mobs false`, `spawn_wandering_traders false`, `spawn_patrols false`, `/kill @e[type=!minecraft:player]` per stage |
| Daylight drift / changing shades | **Lighting and time contract, §1.1** — absolute time sets only, cycle always off, per-checkpoint assertion | `advance_time false` + `/time set <t>` + `/time query daytime` |
| Weather (rain darkens sky light) | Fixed | `advance_weather false` + `/weather clear` |
| Simulation timing | Tick lease + freeze; advance only by exact `step` jobs | `/server/ticks/*` (spec §5); receipts + job milestones recorded |
| Random-tick systems (grass spread, crop growth, leaf decay, copper oxidation, fire spread, farmland drying/reversion) | Off | `random_tick_speed 0` gamerule (+ `persistent=true` leaves as belt-and-braces) |
| Camera | Exact pose via teleport (privileged; there is deliberately no camera endpoint today) | `/tp @p X Y Z YAW PITCH` |
| HUD/hand/crosshair in frame | Hidden | F1 toggle via `hold-key` (⚙ verify-26.2 GLFW key code), `send_command_feedback false` |
| Attack/use/pick are mouse-bound by default; `hold-key` is keyboard-only | Rebound to free keyboard keys in the E2E profile so `hold-key` drives the normal keybinding path (tick-aware, spec §3.5) | `options.txt` key remaps (⚙ verify-26.2 format) |
| Window/GUI geometry | Fixed | `set-windowed 1280×720`, `set-gui-scale 2`, `set-fullscreen false`; assert the **effective** `WindowStateResponse` (OS may adjust — spec §9.1) |
| Smooth lighting / brightness (change shading globally) | Pinned | client profile `options.txt` (⚙ verify-26.2 keys) |
| Clouds (wall-clock drift) | Off | client profile `options.txt` (⚙ verify-26.2 key) |
| Particles (torch flames, furnace smoke are random) | Minimal + scenario design avoids emitters in view | client profile `options.txt` (⚙ verify-26.2) |
| Animated block textures (water, lava, fire, magma, portal are **wall-clock** animated — tick freeze does not stop them) | No such blocks in any frame; where unavoidable (lit furnace front) use pixel masks | checkpoint `mask` config |
| Title-screen panorama & splash text (random) | Never full-frame diff menu screens; mask to widget regions if ever needed | harness policy |
| Chunk-mesh pop-in after teleport | Render-settle protocol before every capture | §3.3 |
| Font rasterization / GPU AA across machines | Environment-specific baselines + tolerances (spec §18) | §3.4 |
| HTTP rate limit (default 60/min) vs. polling loops | Raised for E2E runs only | `MAPI_HTTP_RATE_LIMIT_PER_MINUTE=3600` |
| Scopes | Dev E2E leaves `http.scopes` blank (full grant); CI uses an explicit list incl. `operations:unrestricted` (commands) and `operations:destructive` (world reset) | config |

If a capture cannot be made stable under this charter, the scenario fails as
**NOT-REPEATABLE** — that is a bug to fix, not a tolerance to raise.

### 1.1 Lighting and time contract ("shades don't change")

The user's requirement, made precise. Enforced four ways, plus profile pins:

1. **Absolute time only.** Every scenario begins with `/time set <t>`
   (absolute daytime). Relative `/time add` is forbidden except as a
   deliberate, documented beat (scenario 8). An absolute set makes any drift
   that happened before — world load, earlier scenarios, long tick steps —
   irrelevant.
2. **The daylight cycle stays off for the whole session**
   (`advance_time false` in the charter gamerules). Then tick freezes,
   steps, sprints, and long wall-clock walks cannot move the sun at all —
   e.g. the furnace scenario's 1600 stepped ticks would otherwise shift
   shading by 80 in-game seconds mid-scenario.
3. **Assert, don't assume.** Before every checkpoint capture the harness runs
   `/time query daytime` and compares the returned value (the command's
   `resultCode` ⚙ verify-26.2) to the checkpoint's pinned `expectedDayTime`;
   a mismatch fails the checkpoint *before* any screenshot is taken. The
   expected and actual values are recorded in the report next to the diff
   metrics.
4. **Prefer flat-slope times.** Strict shots use 6000 (noon) or 18000
   (midnight), where light levels change slowest; transitional times
   (sunrise/sunset) are used only deliberately, in scenario 8.

Weather is pinned clear for the same reason (rain/snow darken sky light),
and the client profile additionally pins smooth-lighting mode and
brightness/gamma (⚙ verify-26.2 option keys) so block-light and sky-light
shading are stable across runs. Moon phase is a pure function of day count,
so absolute time sets also pin the moon for night shots.

---

## 2. The scenarios at a glance

Deliberately different axes of the product; the only shared machinery is the
harness. The user's example ("build a house in a superflat world, mobs off")
is scenario 1. Scenarios 1–5 observe and command the world; **6–8 make the
player mutate it through the normal keybinding path** (attack/use rebound to
keyboard — the existing `hold-key` endpoint drives real gameplay verbs).

| # | Scenario | One-liner | Primary API surfaces | Visual target |
| --- | --- | --- | --- | --- |
| 1 | **The House** | Command-build a house on a flat stage, mobs off; diff 4 exterior + 1 interior fixed-camera shots | worlds lifecycle, commands, block queries, screenshots, window control | Static 3D build |
| 2 | **Clockwork** | Self-starting torch-free redstone lamp ring; freeze/step exact ticks; lamp pattern rotates and returns to identity | tick lease/freeze/step jobs, snapshots + snapshot-diffs, block queries | Timed 3D states |
| 3 | **Inventory Ballet** | `/give` → 2×2 crafting chain (log→planks→sticks, planks→crafting table) via container clicks; rendered tooltip capture | inventory inspect/click, computed + rendered tooltips, screen inspect | 2D GUI |
| 4 | **The Hedge Maze** | Command-build a persistent-leaf maze from a versioned ASCII map; BFS route executed via raw-input waypoints with per-leg position assertions | movement/waypoints receipts, player queries | 3D at rest poses + top-down map shot |
| 5 | **The Furnace Assay** | Load furnaces via `/data merge`, step exactly one smelt, assert typed NBT at each boundary; unlit final frame | block-entity typed NBT (`queryBlock`), tick stepping, snapshot-diffs | 3D states + NBT contract |
| 6 | **The Terraformer** | Player breaks a doorway through a wall and builds a lit lamp post via rebound attack/use keys | `hold-key` on rebound keybinds, block queries, snapshot-diffs, in-run pair diffs | 3D before/after change |
| 7 | **The Garden** | Player tills and plants a field with hoe + seeds (use-on-block, not attack) | use verb via rebound key, block queries, pair diffs | 3D before/after change |
| 8 | **The Sundial** | The lighting contract as a test: pinned-time identity, deliberate time-step change, four lighting moods of one diorama | `/time` commands, daytime assertion, pair diffs, baselines | 3D lighting study |

Stretch (not one of the eight): **LAN Rendezvous** — publish the integrated
server (`/server/lan`), connect a second offline client (`/client/connect`),
both meet at a marker; one screenshot per client. Exercises participant
mapping (spec §7). Deferred because two-process orchestration is the heaviest
infrastructure and adds nothing to the visual-diff contract the others
already cover.

---

## 3. Harness architecture (TypeScript)

New top-level `e2e/` directory. Zero runtime dependencies, matching
`sdk/typescript` (Node ≥ 18, global `fetch`; `node:zlib` for PNG). Runs as
`node --experimental-strip-types`, exactly like `scripts/e2e-audit.ts`.

```
e2e/
  run.ts                  # CLI: --scenario <name|all> --loader <fabric|neoforge>
                          #        [--update-baselines] [--env <envId>]
  harness/
    client.ts             # MapiClient wrapper: waitForPhase, waitForJob,
                          #   waitForPlayer, settleScreenshot, assert helpers
    supervisor.ts         # launch client process (per-loader run dir), inject
                          #   MAPI_HTTP_TOKEN + rate-limit env, health-poll,
                          #   graceful shutdown via /process/shutdown, capture logs
    stage.ts              # world delete/create/load, charter gamerules,
                          #   prepareStage(cx,cz), setTimeAbsolute + assertDayTime,
                          #   setCamera (tp), F1
    visual.ts             # capture → settle → baseline compare/update; pairDiff
    png.ts                # minimal zero-dep PNG decode/encode (zlib + unfilter)
    diff.ts               # per-pixel channel delta, masks, metrics, diff PNG
    report.ts             # machine-readable JSON report (§6)
  scenarios/
    01-house.spec.ts
    02-clockwork.spec.ts
    03-inventory-ballet.spec.ts
    04-hedge-maze.spec.ts
    05-furnace-assay.spec.ts
    06-terraformer.spec.ts
    07-garden.spec.ts
    08-sundial.spec.ts
  profiles/client/options.txt   # E2E client profile (see below)
  baselines/<envId>/<scenario>/<checkpoint>.png
  out/<runId>/...               # captures, diff images, report.json (gitignored)
```

### 3.1 Client profile (`e2e/profiles/client/options.txt`)

Applied by the supervisor to the dedicated E2E run directory only — the mod
never touches a user's normal settings (spec §8 preamble). Keys ⚙ verify-26.2
during implementation; the profile asserts: window 1280×720 windowed, GUI
scale 2, fixed FOV, clouds off, particles minimal, smooth-lighting mode and
brightness/gamma pinned (§1.1), vsync off, framerate cap unset,
render/simulation distance 8, auto-jump off, `pauseOnLostFocus:false`,
narrator off, tutorial/onboarding suppressed where a supported setting exists
(fresh-profile coverage stays a separate suite per spec §8.1). **Key remaps
for scenarios 6–8:** `key_key.attack`, `key_key.use`, `key_key.pick` rebound
from mouse buttons to free keyboard keys (⚙ verify-26.2 option format), so
`hold-key` exercises the ordinary tick-aware keybinding path rather than any
privileged mutation. Effective window values are re-asserted over the API at
runtime; profile-file values that have no API readback are asserted by
re-reading the file pre-launch.

### 3.2 World and stage lifecycle (`stage.ts`)

1. `worlds/delete {levelId:"mapi-e2e", confirm:true}` if present
   (destructive chain honored: scope + grant + intent).
2. `worlds/create {levelId:"mapi-e2e", seed:20260919, gamemode:"creative"}`;
   poll `/server/world` until `phase:"ACTIVE"` (bounded, 120 s).
3. Apply charter gamerules (§1 incl. `random_tick_speed 0`), absolute
   `/time set 6000`, `/weather clear`, `/difficulty peaceful` via
   `/server/commands`.
4. `prepareStage(cx, cz)`: acquire force-load tickets for only the chunks in
   `[cx±48, cz±48]` that were not already forced, then `/fill` air `y=65..95`
   and replace each `y=60..63` layer with air before filling it with stone;
   `y=64` is grass_block. Stage tickets keep the fixture's chunks entity-
   ticking through construction and captures; the runner releases only the
   tickets it added when that scenario exits. Cleanup kills non-player
   entities, steps 21 controlled ticks for vanilla's death timer, kills
   dropped items/XP, and steps one more tick before snapshots.
5. `setTimeAbsolute(t)` = `/time set t` then `assertDayTime(t)` =
   `/time query daytime` compared against `t` (result value ⚙ verify-26.2).
6. `setCamera(x,y,z,yaw,pitch)` = `/tp @p …`; hides HUD via F1 on first use.
7. Per-scenario reset: `/clear @p`, kill non-player entities, re-pin
   time/weather. Stages are ≥1024 blocks apart (§5 coordinates), so
   scenarios never interfere; a session runs all eight in one world.

### 3.3 The checkpoint protocol (`visual.ts`)

Every screenshot assertion in every scenario follows this exact sequence:

1. Put the world in the required state (camera pose; tick freeze or exact
   step count).
2. **Time assertion:** `/time query daytime` must equal the checkpoint's
   `expectedDayTime` (§1.1); fail before any capture otherwise.
3. **Render settle:** capture twice 250 ms apart; accept renderer shimmer only
   up to 0.05% changed pixels and max channel delta 24/255. Retry up to 6
   pairs with 1 s spacing. Failure ⇒ scenario fails `scene not stable` —
   never compared against a baseline in an unsettled state. Supervised smoke
   runs pin clouds off in their isolated profile for deterministic captures.
4. Capture the checkpoint. Assert metadata: `frame` strictly increasing,
   `screenId` as expected, dimensions equal to the baseline's.
5. Diff against the baseline with that checkpoint's masks and tolerances
   (§3.4), and/or pair-diff against a kept earlier capture when the scenario
   asks for change detection.
6. Record metrics in the report (RMS, max channel delta, changed-pixel
   fraction, masks applied, frame ids, expected/actual daytime).

### 3.4 Diff algorithm and baseline policy (`diff.ts`)

Same metric family as the Java reference (`runner/.../VisualDiff.java`) so
the two stacks stay comparable:

- Per-pixel: max absolute channel delta; pixels with delta ≤ `pixelThreshold`
  (default 4/255) count as unchanged.
- Checkpoint passes iff, **outside mask rectangles**: changed-pixel fraction
  ≤ `maxChangedFraction` (default 0.001) **and** normalized RMS ≤ `maxRms`
  (default 0.01) — and dimensions match (size mismatch is a hard fail, never
  a silent pass).
- **In-run pair diffs (`pairDiff`):** the harness can keep any capture in
  memory and diff two captures of the same run, independent of stored
  baselines. Two verbs:
  - `assertChanged(before, after, region?, minChangedFraction)` — the world
    visibly changed, at least this much, inside (optionally) this rectangle;
  - `assertUnchanged(before, after, region?)` — nothing moved (§3.3's settle
    uses this with threshold 0; scenarios 6–8 use it for "everything except
    the intended spot").
  Pair diffs answer "did the world change exactly where intended?" without
  any baseline at all — change detection, not just regression detection.
- Output on failure: `<checkpoint>.diff.png` (changed pixels in red, masks in
  blue) written to `out/` for human debugging. The LLM only reads
  `report.json`.
- Baselines live under `baselines/<envId>/` where `envId` identifies one
  pinned environment (e.g. `linux-ci-fabric`, `linux-ci-neoforge`) per spec
  §18 ("environment-specific baselines and explicit tolerances"). The CI
  house references for `linux-ci-fabric` and `linux-ci-neoforge` are committed
  PNGs. Normal runs compare them; a missing baseline fails and saves an
  `.actual.png` for review. Only `--update-baselines` creates or replaces
  references. Local environments use their own `--env` and remain ignored.
  See `e2e/baselines/README.md` for the CI refresh procedure.
  House exterior views mask the distant background horizon outside the
  fixture silhouette; the interior is compared without masks. These masks
  handle far-chunk mesh availability without widening pixel tolerances.

### 3.5 Supervisor (`supervisor.ts`)

- Restores the E2E profile into a **dedicated** run dir, generates a random
  token, launches the client with `MAPI_HTTP_TOKEN` /
  `MAPI_HTTP_RATE_LIMIT_PER_MINUTE` env (launch pattern follows
  `scripts/run-client-watchdog.sh`, which already handles the GLFW-failure
  zombie case), health-polls `/api/v1/health` (bounded).
- Teardown: `POST /process/shutdown`; on timeout, kill. Logs and the report
  land in `out/<runId>/` (a mini reproducibility bundle, spec §1.1).
- EULA: not needed for the client; any future dedicated-server variant
  requires explicit operator acceptance (`MAPI_ACCEPT_EULA`), never automatic.

---

## 4. Why "superflat" is a stage, not a world type

`worlds/create` today offers the NORMAL preset only. Scenario 1 must be a
flat world with mobs off; three routes:

1. **Adopted — stage carving (§3.2).** Fixed-seed NORMAL world + `/fill`
   stage. Inside render distance 8 the stage reads as a superflat plain in
   every frame; 100 % API-driven, no binary fixtures, no mod change.
2. Optional future chunk (needs operator approval, `AGENTS.md` §7): add a
   `preset` field to `worlds/create` (docs + OpenAPI + contract tests in the
   same change set, per `AGENTS.md` §6).
3. Alternative: versioned world-template archives restored while the client
   is stopped (the runner's `WorldBackup` boundary). Heavier; keep in reserve
   for scenarios that truly need bespoke terrain.

---

## 5. Scenario specifications

Each scenario file exports `{ name, setup(client), checkpoints[],
assertions[], teardown(client) }`. Coordinates below are the stage-local
design; all 📐/⚙ values are pinned/verified at implementation time.

### 5.1 Scenario 1 — The House

**Intent.** The canonical static-build example (the user's request): a house
on flat ground, friendly+hostile mobs off, validated purely by screenshots
from fixed angles plus spot block queries.

**Stage:** center `(0,0)`. House footprint `x∈[-4..4], z∈[-3..3]`.

Build script (all `/server/commands`, executed with ticks **un**frozen —
command block updates are immediate; freeze comes later for the captures):

| Step | Command(s) |
| --- | --- |
| Foundation | `/fill -4 64 -3 4 64 3 stone_bricks` |
| Walls | `/fill -4 65 -3 4 68 3 oak_planks hollow` |
| Windows | `/fill -4 67 -1 -4 67 1 glass_pane`, mirrored on `x=+4` |
| Door (south, closed) | `/setblock 0 65 3 oak_door[half=lower,facing=south]`, `/setblock 0 66 3 oak_door[half=upper,facing=south]` |
| Roof (1-block overhang) | `/fill -5 69 -4 5 69 4 oak_planks`; corners `stone_bricks` |
| Interior light | `/setblock 0 68 0 glowstone` (no torches — flame particles are random) |
| Cleanup | Keep stage chunks entity-ticking, kill non-player entities, step 21 ticks for death removal, kill drops/XP, then step one tick; after construction, repeat bounded cleanup for late-loaded entities and require three consecutive empty radius-32 queries |

**Checkpoints** (camera via `/tp @p`, F1 hidden, time pinned at 6000,
weather clear, ticks frozen during the shoot to pin any accidental state):

| Name | Pose 📐 | Masks | Tolerance |
| --- | --- | --- | --- |
| `house-se` | `( 12.5, 72,  12.5) yaw≈135 pitch≈-20` | none | strict (no animated/particle sources in frame) |
| `house-sw` | `(-12.5, 72,  12.5) yaw≈225 pitch≈-20` | none | strict |
| `house-nw` | `(-12.5, 72, -12.5) yaw≈315 pitch≈-20` | none | strict |
| `house-ne` | `( 12.5, 72, -12.5) yaw≈45  pitch≈-20` | none | strict |
| `house-interior` | `(0, 67.5, 0) yaw 180 pitch≈15` | none | strict |

(Yaw sign convention ⚙ verify-26.2 on first run, then 📐.)

**Structured assertions:**
- `queryBlock` samples: corners `(-4,65,-3)`, `(4,65,3)` are `oak_planks`;
  `(0,65,3)` is `oak_door`; `(0,68,0)` is `glowstone`; `(-4,67,0)` is
  `glass_pane`.
- Snapshot before build / after build → `snapshot-diffs` `changed` count is
  within an expected bound (exact count 📐 after first run).
- `queryEntities` radius 32 around origin: `0` non-player entities (proves
  the mob charter).

**Teardown:** none (next scenario's stage is far away; the world is
re-created next session).

**Repeatability risks:** none identified beyond the charter — this is the
canary scenario; if it flakes, the harness is broken, not the test.

---

### 5.2 Scenario 2 — Clockwork

**Intent.** Prove tick control gives *visual* determinism: a self-running
redstone display whose state is an exact function of stepped ticks.

**Stage:** center `(512,0)`.

**Circuit contract** (design pinned at implementation time): a torch-free,
RNG-free, self-starting clock (candidate: facing-observer pair) driving a
ring of 4 redstone lamps via repeater delays, such that exactly one lamp is
lit and the lit position advances by one per `P/4` ticks, with period `P`
(📐, expected on the order of 16). No droppers/dispensers (random slot
choice), no redstone torches (flame particles), no hoppers with visible
items. Start method: placing the final observer while frozen begins
oscillation on the first stepped tick ⚙ verify-26.2.

**Sequence:** acquire tick lease → freeze → build circuit by commands →
checkpoint `clock-t0` → for k in 1..4: `ticks/step {ticks:P/4}` (job;
await SUCCEEDED) → checkpoint `clock-t<k>` → assert `clock-t4` pixels equal
`clock-t0` (identity beat) → unfreeze → release lease.

**Checkpoints:** camera `(521.5, 70, 9.5) yaw 180 pitch≈-25` 📐 looking
north at the lamp ring; all five shots same pose; no masks; strict
tolerance.

**Structured assertions (the authoritative half of this scenario):**
- After each step k, `queryBlock` on each of the 4 lamp positions: exactly
  lamp `(k mod 4)` has `lit=true` ⚙ blockstate name verify-26.2.
- `captureSnapshot` at t0 and t4 → `snapshot-diffs` restricted to lamp paths
  reports **zero** changed records (visual identity backed by state
  identity); t0 vs t1 reports exactly the two lamp records that flipped.
- Step-job receipt: `requestedSteps == completedSteps`, captured at the
  requested boundary (spec §5 reporting).

**Risks:** subsystems outside tick control (spec §5 limitations) — rendering
and the client keep running; the settle protocol absorbs that. The identity
beat (`t4 == t0`) is the guard against period drift.

---

### 5.3 Scenario 3 — Inventory Ballet

**Intent.** The 2D counterpart: player-faithful container manipulation
(client-logic mode) with server-confirmed postconditions, ending in a
rendered-tooltip capture. GUI frames have no lighting/sky, so within one
environment these are the most byte-stable shots the game can produce.

**Stage:** reuse scenario 1's platform; player stands at a fixed pose
`(8.5, 65, 8.5) yaw 0 pitch 0` 📐.

**Sequence** (ticks frozen; container ops are immediate):

1. `/clear @p`; `/give @p oak_log 3` (+ charter state). Open inventory
   (`hold-key` inventory key ⚙ key code, 1 tick).
2. Checkpoint `inv-given`.
3. Craft chain via `inventory/click` on container 0 (slot map from
   `docs/openapi.yaml`: 0=result, 1–4=craft, 9–44=main):
   - PICKUP log stack (slot of logs) → place 1 log into craft slot 1 →
     PICKUP result slot 0 (4 planks) → place into main slot.
   - Repeat placement to craft sticks (planks in slots 1+3) and a crafting
     table (planks in slots 1,2,3,4). Exact click script 📐 as a data table
     in the spec file.
4. `QUICK_MOVE` the crafting table to the hotbar. Checkpoint `inv-crafted`.
5. `inventory/tooltip?slot=` (computed) — assert exact lines; then
   `inventory/tooltip-rendered` on the crafting-table slot — endpoint opens
   inventory, hovers, captures the frame + lines. Checkpoint
   `tooltip-rendered`.
6. Close inventory; checkpoint `inv-closed` (back in world; proves return to
   the scenario-1 pose for cross-scenario hygiene).

**Structured assertions:**
- After every click step: `inspectInventory` (client view) **and**
  `queryPlayers` inventory (server view) agree on the expected
  `{slot, itemId, count}` multiset — this is the server-confirmed
  postcondition requirement (§10.3) exercised end to end.
- Computed tooltip lines == rendered capture `lines` (same item, both paths,
  spec §10.2).
- `carriedCount == 0` at rest between steps.

**Masks/tolerances:** none within one environment; strict. Cross-machine
font differences are handled by per-env baselines, never by tolerance
inflation.

**Risks:** slot indices depend on container layout (⚙ verify-26.2, then 📐);
onboarding/toast overlays must be suppressed by the profile or masked.

---

### 5.4 Scenario 4 — The Hedge Maze

**Intent.** Movement through raw input with honest verification: the *walk*
is asserted by server-side positions and receipts; the *photos* are taken at
re-seated exact poses (raw-input end positions are not pixel-exact by
design — no silent teleport fallback, spec §3.3).

**Stage:** center `(-512,0)`. Maze = 11×11 cells, cell width 2, walls
`oak_leaves[persistent=true]` (persistent — random-tick decay would
otherwise delete walls non-deterministically; `random_tick_speed 0` already
stops decay, this is belt-and-braces), floor grass. Built by `/fill` from
this versioned ASCII map in the spec file:

```
###########
#S#     # #
# # ###   #
# # #   # #
# # # ### #
#   # #   #
### # # ###
#   # #   #
# ### ### #
#       #E#
###########
```

**Sequence:** TS computes the BFS shortest path S→E on the map → expands to
waypoint legs (axis-aligned, yaw ±90 turns, per-leg ticks from walk speed
4.317 b/s + buffer 📐) → `movement/waypoints` (≤64 legs, within contract) →
after each leg, `queryPlayers` asserts horizontal distance to the leg target
< 0.75 (stop-on-stuck replanning is the runner's job, spec §16 — on this
flat maze no replan should ever trigger; log if it does) → at each
checkpoint junction, `setCamera` re-seats the exact pose before capture.

**Checkpoints:**

| Name | Pose |
| --- | --- |
| `maze-entrance` | start cell, facing first corridor 📐 |
| `maze-junction-a`, `maze-junction-b` | two mid-route junctions 📐 |
| `maze-exit` | exit cell, facing out 📐 |
| `maze-map` | top-down: `(-512+11.5, 100, 11.5) pitch 90 yaw 0` — the whole maze as a map shot; outstanding diff target, fully static |

**Structured assertions:** per-leg position table (expected vs actual, all
< 0.75), receipt `completedLegs == legs`, receipt start/end boundaries
monotonic; final position within 1 block of E; entity charter re-check.

**Risks:** client-tick alignment of long holds (the receipt boundary fields
exist precisely for this; assertions use positions, not tick counts);
leaves' fancy/fast graphics differ by profile — profile pins one mode.

---

### 5.5 Scenario 5 — The Furnace Assay

**Intent.** Typed-NBT and snapshot engine showcase: block entities mutated
by `/data`, simulation advanced by exact ticks, contents asserted through
`queryBlock`'s typed NBT (spec §11) and snapshot diffs — with screenshots
bookending a fully *unlit* (hence static) furnace row.

**Stage:** center `(0,512)`. Four furnaces at `(0..3, 65, 512)`, `facing=south`,
on a stone bench; camera south of them at `(1.5, 67.5, 519) yaw 180
pitch≈-12` 📐.

**Sequence:**

1. Freeze ticks (lease). Place furnaces; `/data merge block` each with
   `{Items:[{id:"minecraft:iron_ore",count:1,Slot:0b},
             {id:"minecraft:coal",count:1,Slot:1b}]}` ⚙ NBT field names and
   typed-byte encoding verify-26.2 — this scenario exists to pin them.
2. Checkpoint `furnace-loaded` (time 6000; unlit → static).
3. `ticks/step {ticks:199}` → assert via `queryBlock`: `BurnTime=1401`±pinned
   offset, `CookTime=199` ⚙ exact off-by-one semantics verify-26.2 then 📐.
4. `ticks/step {ticks:1}` → smelt completes: output slot
   `{id:"minecraft:iron_ingot",count:1}`, `CookTime=0`.
5. `ticks/step {ticks:1400}` → fuel fully spent, furnace **unlit** again.
   Checkpoint `furnace-complete` (static again).
6. Mid-burn visual evidence: between steps 3 and 4, `/time set 18000`,
   checkpoint `furnace-midnight-glow` — front view, **mask** the four
   furnace-front rectangles (fire texture is wall-clock animated); the
   emitted light pool on the floor is deterministic and stays unmasked.
   Restore `/time set 6000` afterwards (absolute, §1.1).

**Structured assertions:**
- `queryBlock` typed NBT at each boundary: exact `Items` list, `BurnTime`,
  `CookTime` with typed-byte slots preserved (the §11 wire representation,
  not JSON-inferred).
- `snapshot-diffs` t_loaded→t_complete: changed paths ⊆
  `{Items, BurnTime, CookTime}` for exactly the 4 furnace positions;
  `truncated=false`; no unrelated records.
- Step jobs SUCCEEDED with `requestedSteps == completedSteps == 1600` total.

**Risks:** exact burn/cook counter semantics (⚙, then pinned assertions);
the mask rectangles (📐, documented per checkpoint); light-propagation timing
under freeze — settled by the settle protocol.

---

### 5.6 Scenario 6 — The Terraformer (player-faithful break & place)

**Intent.** Real gameplay interaction: the player breaks a doorway through a
wall and builds a lit lamp post **through the normal keybinding path** —
attack/use rebound to keyboard keys in the E2E profile (§3.1), driven by
`hold-key` (tick-aware keybinding behavior, spec §3.5). No `/setblock` for
the mutations themselves: the world changes because the *player* acted, and
the API proves it structurally and visually.

**Stage:** center `(1024,0)`. A stone-brick wall 7 wide × 4 high at
`x∈[-3..3], y∈[65..68], z=0`; player starts at `(0.5, 65, 4.5)` facing north
(yaw 180), within creative reach of every target block.

**Sequence:**

1. Checkpoint `wall-before` (baseline; also kept in memory as pair-A).
2. Break a 1×2 doorway at `(0,65,0)` and `(0,66,0)`: aim via `setCamera`
   per block (pitch pinned per target 📐), hold the rebound attack key for
   pinned ticks per block (creative insta-break has an inter-block cooldown
   ⚙ verify-26.2; pinned spacing absorbs it). After each block,
   `queryBlock` must report `air` before continuing.
3. Checkpoint `wall-doorway`: **pair-diff** vs `wall-before` —
   `assertChanged` inside the doorway rectangle **and** `assertUnchanged`
   everywhere outside it (change happened, and only where intended) — then
   baseline-compare.
4. Lamp post at `(2,65..66,1)`: give + hotbar-select `waxed_copper_bulb`
   (waxed so oxidation is impossible even conceptually), place two via the
   rebound use key; place a `lever` on top; flick it with the use key.
5. Checkpoint `lamp-lit` at time 18000 (midnight — the glow is the photo);
   then `/time set 6000` (absolute restore, §1.1).

**Checkpoints:** `wall-before`, `wall-doorway`, `lamp-post-unlit`,
`lamp-lit` (midnight). Masks: none. Tolerance: strict.

**Structured assertions:**
- `queryBlock` for every broken (`air`) and placed position.
- `copper_bulb` `lit=true` after the lever flick ⚙ blockstate verify-26.2.
- `snapshot-diffs` `wall-before` → `lamp-lit`: changed set == exactly the
  expected positions (doorway + post + lever), `truncated=false`.
- Player inventory (server view) shows the expected remaining counts.

**Risks:** aim precision — poses are pinned and every break is verified
before proceeding; creative break cooldown — pinned spacing; keybind remap
format (⚙, then pinned).

---

### 5.7 Scenario 7 — The Garden (tool use and planting)

**Intent.** A second gameplay verb: *using* an item on a block (not
attacking). Tilling and planting through the rebound use key exercises the
"player-faithful at the gameplay level" claim (spec §3.1) on a completely
different mechanic — and leans on `random_tick_speed 0` for persistence.

**Stage:** center `(-1024,0)`. A 7×5 dirt rectangle at `y=64`
(`/fill -1027 64 -2 -1021 64 2 dirt` over the stage floor).

**Sequence:**

1. Checkpoint `garden-dirt`.
2. Give `iron_hoe` + `wheat_seeds`; hotbar-select the hoe; till all 35
   cells via the use key over a pinned aim grid 📐 (dry farmland persists
   indefinitely because `random_tick_speed 0` — no hydration needed, and no
   animated water enters the frame).
3. Checkpoint `garden-tilled`; pair-diff vs `garden-dirt` must confine all
   change to the field rectangle.
4. Select seeds; plant stage-0 wheat on every cell.
5. Checkpoint `garden-planted`; pair-diff vs `garden-tilled` confined to the
   field; baseline-compare.

**Structured assertions:**
- `queryBlock` per cell: `dirt` → `farmland` → `wheat[age=0]`
  (⚙ blockstate names verify-26.2).
- `snapshot-diffs` across both mutations: exactly 70 changed records
  (35 + 35), `truncated=false`.
- Entity charter re-check: no item entities anywhere (planting was
  player-faithful; nothing was thrown).

**Risks:** farmland reversion and trampling — neutralized by
`random_tick_speed 0` and no jumping; seeds never advance past stage 0 under
`random_tick_speed 0`, so the final frame is stable forever.

---

### 5.8 Scenario 8 — The Sundial (the lighting contract, tested)

**Intent.** Turn §1.1 itself into a test, with a negative and a positive
control — the scenario that fails first and loudly if "shades" ever drift
when they shouldn't.

**Stage:** center `(0,-1024)`. Diorama: a 5-block stone pillar at the
center of a 9×9 checkerboard of white and black concrete (long shadow,
high-albedo contrast).

**Sequence:**

1. `/time set 6000`; checkpoint `sun-noon-a`.
2. Negative control: step 100 ticks (daylight cycle is off; time must not
   move), wait 30 wall-clock seconds, checkpoint `sun-noon-b` →
   `assertUnchanged(a, b)` at threshold 0, and the daytime assertion (§3.3
   step 2) must read exactly 6000 for both. Any drift fails the run as
   NOT-REPEATABLE.
3. Positive control: `/time add 200` (the one sanctioned relative beat) →
   daytime query must have advanced by exactly 200 ⚙ verify-26.2 →
   checkpoint `sun-noon-plus200` → `assertChanged(a, c)` concentrated in
   the shadow region (documented rectangle 📐).
4. Restore: `/time set 6000` → checkpoint `sun-noon-c` → `assertUnchanged
   (a, c)` — shading is a pure function of pinned time.
5. Mood series (deliberate lighting tour, baselines diffed): the same pose
   at 6000, 12000, 18000, 23000 — `sun-noon`, `sun-sunset`, `sun-midnight`,
   `sun-predawn`, each recording its `expectedDayTime` in the report.
   Transitional times are allowed **here only**, because lighting is the
   subject.

**Checkpoints/masks:** single fixed pose 📐 for all captures; no masks;
strict tolerance.

**Structured assertions:** daytime query results at every beat (6000, 6000,
6200, 6000, then the four moods); step-job receipt for the 100 idle ticks
(`requestedSteps == completedSteps`).

**Risks:** none beyond the charter — this scenario *is* the charter's
canary. It is also cheap enough to run as a preflight before the others.

---

## 6. Reporting, pass/fail, CI

- `out/<runId>/report.json`: env fingerprint (`/api/v1/info` versions,
  loader, OS), world session id, profile hash, per-scenario assertion
  results, per-checkpoint `{name, baseline, rms, maxDelta, changedFraction,
  masks, frames, expectedDayTime, actualDayTime, verdict}`. Exit code 0 iff
  every assertion and diff passes.
- Per-loader runs (Fabric + NeoForge) execute the identical scenario files;
  baselines are keyed per loader-env. This is the examples slice of the
  loader-parity gate (spec §18) and lands with execution-plan chunk 7.5
  ("Examples execute in CI"); the Java runner's `VisualDiff` remains the
  reference implementation for the Java stack.
- Runtime budget: all eight scenarios target ≤ 8 min per loader on the
  reference worker (world create + 8 stages + ~30 checkpoints), well inside
  the 15-minute smoke-suite gate (spec §18).
- Reliability: these scenarios are designated "critical scenarios" for the
  §18 deterministic-baseline campaign (300 consecutive runs, zero
  unexplained failures) once they exist.

## 7. Implementation order and open decisions

1. Harness skeleton + `png.ts`/`diff.ts` unit tests (synthetic PNGs, no game
   needed) → scenario 1 (canary) → 3 (GUI stability proof) → 8 (lighting
   canary; thereafter run as preflight) → 2 and 5 (tick machinery) →
   6 and 7 (player-faithful verbs; depend on the profile key remaps) →
   4 (movement) → CI wiring.
2. **Operator decisions to record before implementation:**
   - baseline storage: decided — committed CI reference PNGs (§3.4);
   - whether the optional `worlds/create` preset field (§4) is wanted as a
     separate chunk — not required by this plan;
   - adding `e2e/` run dirs, tokens, and `out/` to the never-commit list is
     already covered by existing gitignore patterns — verify during
     implementation (`AGENTS.md` §7).
3. Out of scope for these examples, per spec §2 / `AGENTS.md` §7: no new
   endpoints beyond the documented surface, no source editing/shell/
   reflection over the API, no EULA acceptance on the operator's behalf.
