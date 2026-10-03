/**
 * World lifecycle and the determinism charter (plan §1/§3.2): one fixed-seed
 * world, flat carved stages, charter gamerules, absolute time, exact camera.
 */
import { Harness, KEYS } from './client.ts';
import { MapiError } from '../../sdk/typescript/src/mapi-client.ts';
import { assert, sleep } from './report.ts';

export const WORLD_ID = 'mapi-e2e';
export const WORLD_SEED = 20260919;
export const NOON = 6000;
export const MIDNIGHT = 18000;

/**
 * Plan §1 charter. Two tiers, because of a mod bug found by this harness:
 * `gamerule`-family commands report a failure on both loaders (the deferred
 * command-chain callback never resolves) — they are best-effort OPTIONALs.
 * REQUIRED = proven-working commands; OPTIONAL = advisory on failure.
 *
 * Determinism itself does not depend on the gamerules: the charter freezes
 * the tick loop for the whole session (run.ts acquires one lease), which
 * locks daylight, mob spawning, crop growth, leaf decay, copper oxidation,
 * and farmland reversion — every source the gamerules would have covered.
 */
export const CHARTER_REQUIRED: string[] = [
    // every charter command is best-effort: vanilla flips idempotent
    // setups to failures (kill with zero matches, 'already peaceful',
    // gamerule chain-sticks); scenario assertions prove the real state.
    'gamemode creative @p',
    'weather clear',
    'kill @e[type=!minecraft:player]',
];

export const CHARTER_OPTIONAL: string[] = [
    // vanilla flips this one to a failure when already in the target mode.
    'difficulty peaceful',
    'gamerule doMobSpawning false',
    'gamerule doTraderSpawning false',
    'gamerule doPatrolSpawning false',
    'gamerule doDaylightCycle false',
    'gamerule doWeatherCycle false',
    'gamerule doImmediateRespawn true',
    'gamerule sendCommandFeedback false',
    'gamerule showDeathMessages false',
    'gamerule randomTickSpeed 0',
];

/** Delete + recreate the fixed-seed world; wait until it is ACTIVE. */
export async function ensureWorld(h: Harness): Promise<void> {
    const phase = await h.worldPhase();
    if (phase === 'ACTIVE') {
        // A loaded integrated server cannot be deleted (OverlappingFileLock);
        // reuse it deliberately — attach-mode survives runs this way.
        return;
    }
    for (let attempt = 1; attempt <= 4; attempt++) {
        const worlds = await h.get('/api/v1/client/worlds');
        const list = (worlds.worlds as any[]) ?? [];
        if (list.some(w => w.levelId === WORLD_ID)) {
            await h.post('/api/v1/client/worlds/delete',
                { levelId: WORLD_ID, confirm: true });
        }
        try {
            await h.post('/api/v1/client/worlds/create',
                { levelId: WORLD_ID, gamemode: 'creative', seed: WORLD_SEED });
            await h.waitForPhase('ACTIVE', 300_000);
            await h.waitForServerReady();
            await dismissScreens(h);
            return;
        } catch (e) {
            // A corrupt phantom save (killed mid-creation) can be filtered
            // from the list while still blocking on the raw Files.exists
            // check; force-delete it and retry once.
            const msg = String(e instanceof Error ? e.message : e);
            if (msg.includes('already exists')) {
                await h.post('/api/v1/client/worlds/delete',
                    { levelId: WORLD_ID, confirm: true }).catch(() => {});
                continue;
            }
            throw e;
        }
    }
    throw new Error(`world ${WORLD_ID} could not be created (phantom save?)`);
}

export async function applyCharter(h: Harness,
                                   advisory: (msg: string) => void): Promise<void> {
    for (const cmd of [...CHARTER_REQUIRED, ...CHARTER_OPTIONAL]) {
        try {
            await h.command(cmd);
        } catch (e) {
            advisory(`charter failed: ${cmd} — freeze carries determinism`
                + ` (${e instanceof Error ? e.message : e})`);
        }
    }
    await setTimeAbsolute(h, NOON);
}

/** Per-scenario reset: required charter + re-pinned time/weather. */
export async function scenarioReset(h: Harness,
                                    advisory: (msg: string) => void): Promise<void> {
    await applyCharter(h, advisory);
}

/**
 * Dismiss any blocking screen: onboarding ("Continue"), pause menus (ESC),
 * anything modal. The legacy audit script had to special-case these too.
 */
export async function dismissScreens(h: Harness): Promise<void> {
    for (let attempt = 0; attempt < 8; attempt++) {
        let body: any;
        try {
            body = await h.get('/api/v1/client/screen').catch(e => {
                // BAD_REQUEST == "no screen is active (in-world state)": done.
                if (e instanceof MapiError && e.code === 'BAD_REQUEST') return;
                throw e;
            });
            if (body === undefined) return;
        } catch {
            // Busy answer on the screen path: transient; retry next attempt.
            continue;
        }
        const screenId = String(body.screenId ?? '');
        if (screenId === '' || screenId.toLowerCase().includes('world')) return;
        const widgets = (body.widgets as any[]) ?? [];
        if (screenId.toLowerCase().includes('onboarding')) {
            const cont = widgets.find(w => w.text === 'Continue');
            if (cont) {
                const r = await h.api.post('/api/v1/client/actions/click', {
                    x: cont.x + Math.floor((cont.width as number) / 2),
                    y: cont.y + Math.floor((cont.height as number) / 2),
                });
                console.log(`  · dismissing ${screenId} via Continue click: consumed=${(r.body as any)?.consumed}`);
                await sleep(500);
                continue;
            }
            await sleep(500);
            continue;
        }
        if (!screenId.toLowerCase().includes('pause')) return;
        await h.holdKey(KEYS.ESCAPE, 1);
        await sleep(500);
    }
    throw new Error('screen did not dismiss');
}

/** Best-effort command: logs nothing, tolerates vanilla's "0 blocks" answer. */
async function tryCommand(h: Harness, cmd: string): Promise<void> {
    await h.command(cmd).catch(() => {});
}

/**
 * Carve a flat stage (plan §4): 97×97 column over [cx±48, cz±48], stone
 * below, grass floor at y=64, air above. Fills tolerate already-air content.
 * The player is teleported over the stage first: vanilla /fill refuses to
 * place blocks in unloaded chunks, and chunk loading follows the player.
 */
export async function prepareStage(h: Harness, cx: number, cz: number): Promise<void> {
    await h.command(`tp @p ${cx} 100 ${cz}`);
    await sleep(3000);
    for (let y = 65; y <= 95; y++) {
        await tryCommand(h, `fill ${cx - 48} ${y} ${cz - 48} ${cx + 48} ${y} ${cz + 48} air`);
    }
    for (let y = 60; y <= 63; y++) {
        await h.command(`fill ${cx - 48} ${y} ${cz - 48} ${cx + 48} ${y} ${cz + 48} stone`);
    }
    await h.command(`fill ${cx - 48} 64 ${cz - 48} ${cx + 48} 64 ${cz + 48} grass_block`);
    await h.command('kill @e[type=!minecraft:player]').catch(() => {});
    // Mesh warm-up: the carve triggers a large chunk rebuild; two warm
    // captures give the client time to finish it before any checkpoint.
    await h.screenshot().catch(() => {});
    await sleep(2000);
    await h.screenshot().catch(() => {});
}

/** §1.1 rule 1+3: set absolute time, then assert the world agrees. */
export async function setTimeAbsolute(h: Harness, t: number): Promise<void> {
    await h.command(`time set ${t}`);
    await assertDayTime(h, t);
}

export async function assertDayTime(h: Harness, expected: number): Promise<void> {
    const actual = await h.dayTime();
    assert(actual === expected,
        `daytime drift: expected ${expected}, world reads ${actual}`);
}

export interface Pose { x: number; y: number; z: number; yaw: number; pitch: number }

export async function setCamera(h: Harness, pose: Pose): Promise<void> {
    await h.command(`tp @p ${pose.x} ${pose.y} ${pose.z} ${pose.yaw} ${pose.pitch}`);
}

/**
 * Hide HUD via F1, once per session. State is tracked here because the game
 * exposes no readback; a wrong initial state surfaces as a baseline mismatch
 * on the first checkpoint (by design, §3.3).
 */
let hudHidden = false;
export async function hideHud(h: Harness): Promise<void> {
    if (hudHidden) return;
    await h.holdKey(KEYS.F1, 1);
    hudHidden = true;
}

/** Minecraft yaw/pitch from an eye position to a target point. */
export function yawPitchTo(ex: number, ey: number, ez: number,
                           tx: number, ty: number, tz: number): { yaw: number; pitch: number } {
    const dx = tx - ex;
    const dy = ty - ey;
    const dz = tz - ez;
    const yaw = -Math.atan2(dx, dz) * 180 / Math.PI;
    const pitch = -Math.atan2(dy, Math.hypot(dx, dz)) * 180 / Math.PI;
    return { yaw, pitch };
}

/** Teleport the player to `feet` and aim the eye (+1.62) at `target`. */
export async function aimAt(h: Harness,
                            feet: { x: number; y: number; z: number },
                            target: { x: number; y: number; z: number }): Promise<void> {
    const { yaw, pitch } = yawPitchTo(feet.x, feet.y + 1.62, feet.z,
        target.x, target.y, target.z);
    await setCamera(h, { ...feet, yaw, pitch });
}
