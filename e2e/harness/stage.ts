/**
 * World lifecycle and the determinism charter (plan §1/§3.2): one fixed-seed
 * world, flat carved stages, charter gamerules, absolute time, exact camera.
 */
import { Harness, KEYS } from './client.ts';
import type { SessionTick } from './context.ts';
import { MapiError } from '../../sdk/typescript/src/mapi-client.ts';
import { assert, sleep } from './report.ts';

export const WORLD_ID = 'mapi-e2e';
export const WORLD_SEED = 20260919;
export const NOON = 6000;
export const MIDNIGHT = 18000;

interface StageChunkTickets {
    added: { x: number; z: number }[];
    preexisting: number;
}

const stageChunkTickets = new WeakMap<Harness, StageChunkTickets>();

/**
 * Plan §1 charter. REQUIRED commands establish the scenario state;
 * OPTIONAL commands are idempotent setup. The tick lease pauses simulation
 * during setup/building; controlled ticks settle entity deaths/removals while
 * required mob-spawning rules prevent replacements.
 */
export const CHARTER_REQUIRED: string[] = [
    'gamemode creative @p',
    'weather clear',
    'gamerule spawn_mobs false',
    'gamerule spawn_wandering_traders false',
    'gamerule spawn_patrols false',
    'gamerule advance_time false',
    'gamerule advance_weather false',
    'gamerule immediate_respawn true',
    'gamerule send_command_feedback false',
    'gamerule show_death_messages false',
    'gamerule random_tick_speed 0',
];

export const CHARTER_OPTIONAL: string[] = [
    // Vanilla reports a failed command when the world is already peaceful.
    'difficulty peaceful',
    // No matching entities is a normal no-op; stage cleanup verifies state.
    'kill @e[type=!minecraft:player]',
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
    for (const cmd of CHARTER_REQUIRED) {
        await h.command(cmd);
    }
    for (const cmd of CHARTER_OPTIONAL) {
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
                const body = await h.clickScreen(
                    cont.x + Math.floor((cont.width as number) / 2),
                    cont.y + Math.floor((cont.height as number) / 2));
                console.log(`  · dismissing ${screenId} via Continue click: consumed=${body.consumed}`);
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
export async function prepareStage(
    h: Harness, cx: number, cz: number, tick: SessionTick,
): Promise<void> {
    await holdStageChunkTickets(h, cx, cz);
    await h.command(`tp @p ${cx} 100 ${cz}`);
    await sleep(3000);
    for (let y = 65; y <= 95; y++) {
        await tryCommand(h, `fill ${cx - 48} ${y} ${cz - 48} ${cx + 48} ${y} ${cz + 48} air`);
    }
    await constructStageFloor(h, cx, cz);
    await h.command(`fill ${cx - 48} 64 ${cz - 48} ${cx + 48} 64 ${cz + 48} grass_block`);
    await clearNonPlayerEntities(h, tick);
    // Mesh warm-up: the carve triggers a large chunk rebuild; two warm
    // captures give the client time to finish it before any checkpoint.
    await h.screenshot().catch(() => {});
    await sleep(2000);
    await h.screenshot().catch(() => {});
}

/**
 * Settle entity cleanup while the required spawn gamerules are active. The
 * first batch advances through vanilla's 20-tick living-entity death timer;
 * the second kill removes drops/XP and one tick finalizes those removals.
 */
export async function clearNonPlayerEntities(
    h: Harness, tick: SessionTick,
): Promise<void> {
    await h.command('kill @e[type=!minecraft:player]').catch(() => {});
    await tick.stepTicks(21);
    await h.command('kill @e[type=!minecraft:player]').catch(() => {});
    await tick.stepTicks(1);
}

/** Replace each under-floor layer with air before filling it with stone. */
export async function constructStageFloor(h: Harness, cx: number, cz: number): Promise<void> {
    for (let y = 60; y <= 63; y++) {
        await tryCommand(h, `fill ${cx - 48} ${y} ${cz - 48} ${cx + 48} ${y} ${cz + 48} air`);
        await h.command(`fill ${cx - 48} ${y} ${cz - 48} ${cx + 48} ${y} ${cz + 48} stone`);
    }
}

/** Force-load the bounded stage in entity-ticking mode, preserving prior tickets. */
export async function holdStageChunkTickets(
    h: Harness, cx: number, cz: number,
): Promise<{ added: number; preexisting: number }> {
    if (stageChunkTickets.has(h)) {
        throw new Error('stage chunk tickets are already held for this harness');
    }
    const scope: StageChunkTickets = { added: [], preexisting: 0 };
    try {
        const minChunkX = Math.floor((cx - 48) / 16);
        const maxChunkX = Math.floor((cx + 48) / 16);
        const minChunkZ = Math.floor((cz - 48) / 16);
        const maxChunkZ = Math.floor((cz + 48) / 16);
        for (let chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (let chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                // ForceLoadCommand takes block-column positions and rounds
                // them to a chunk; use the center of each desired chunk.
                const x = chunkX * 16 + 8;
                const z = chunkZ * 16 + 8;
                try {
                    const query = await h.command(`forceload query ${x} ${z}`);
                    if (Number(query.resultCode) < 1) {
                        throw new Error(`forceload query ${x} ${z} did not confirm a ticket`);
                    }
                    scope.preexisting++;
                } catch (error) {
                    if (error instanceof MapiError) throw error;
                    const result = await h.command(`forceload add ${x} ${z}`);
                    if (Number(result.resultCode) < 1) {
                        throw new Error(`forceload add ${x} ${z} did not add a ticket`);
                    }
                    scope.added.push({ x, z });
                }
            }
        }
        stageChunkTickets.set(h, scope);
        return { added: scope.added.length, preexisting: scope.preexisting };
    } catch (error) {
        const cleanupFailures: string[] = [];
        for (const { x, z } of scope.added) {
            try {
                await h.command(`forceload remove ${x} ${z}`);
            } catch (cleanupError) {
                cleanupFailures.push(`${x} ${z}: ${String(cleanupError)}`);
            }
        }
        if (cleanupFailures.length > 0) {
            throw new AggregateError([error, ...cleanupFailures],
                `stage ticket acquisition failed; cleanup also failed: ${cleanupFailures.join('; ')}`);
        }
        throw error;
    }
}

/** Release only the force-load tickets acquired by `holdStageChunkTickets`. */
export async function releaseStageChunkTickets(h: Harness): Promise<void> {
    const scope = stageChunkTickets.get(h);
    if (!scope) return;
    const failures: string[] = [];
    const remaining: { x: number; z: number }[] = [];
    for (const { x, z } of scope.added) {
        try {
            await h.command(`forceload remove ${x} ${z}`);
        } catch (error) {
            failures.push(`${x} ${z}: ${String(error)}`);
            remaining.push({ x, z });
        }
    }
    if (remaining.length > 0) {
        stageChunkTickets.set(h, { added: remaining, preexisting: scope.preexisting });
    } else {
        stageChunkTickets.delete(h);
    }
    if (failures.length > 0) {
        throw new Error(`could not release ${failures.length} stage chunk tickets: `
            + failures.join('; '));
    }
}

/**
 * Wait for a stable empty observation around the stage. Chunk entity lists
 * can arrive after initial cleanup, so each bounded non-empty pass repeats
 * cleanup and requires several empty reads before accepting the result.
 */
export async function settleNearbyEntities(
    h: Harness, tick: SessionTick, x: number, y: number, z: number, radius: number,
    options: { maxCleanupPasses?: number; stablePolls?: number; pollMs?: number } = {},
): Promise<{ entities: any[]; stable: boolean; cleanupPasses: number;
             diagnostics: string[]; tickets: { added: number; preexisting: number } }> {
    const maxCleanupPasses = options.maxCleanupPasses ?? 3;
    const stablePolls = options.stablePolls ?? 3;
    const pollMs = options.pollMs ?? 500;
    const maxPolls = maxCleanupPasses * stablePolls + stablePolls;
    let cleanupPasses = 0;
    let consecutiveEmpty = 0;
    let nonPlayer: any[] = [];
    const diagnostics: string[] = [];
    const heldTickets = stageChunkTickets.get(h);
    const tickets = { added: heldTickets?.added.length ?? 0,
        preexisting: heldTickets?.preexisting ?? 0 };

    for (let poll = 0; poll < maxPolls; poll++) {
        await sleep(pollMs);
        const entities = await h.entitiesAround(x, y, z, radius);
        nonPlayer = entities.filter(entity => entity.typeId !== 'minecraft:player');
        if (nonPlayer.length === 0) {
            consecutiveEmpty++;
            if (consecutiveEmpty >= stablePolls) {
                return { entities: nonPlayer, stable: true, cleanupPasses, diagnostics, tickets };
            }
            continue;
        }

        consecutiveEmpty = 0;
        if (cleanupPasses >= maxCleanupPasses) {
            return { entities: nonPlayer, stable: false, cleanupPasses, diagnostics, tickets };
        }
        cleanupPasses++;
        try {
            diagnostics.push(await clearNearbyEntities(h, tick, x, y, z, radius, nonPlayer));
        } catch (error) {
            diagnostics.push(`cleanup failed: ${String(error)}`);
            return { entities: nonPlayer, stable: false, cleanupPasses, diagnostics, tickets };
        }
    }
    return { entities: nonPlayer, stable: false, cleanupPasses, diagnostics, tickets };
}

async function clearNearbyEntities(
    h: Harness, tick: SessionTick, x: number, y: number, z: number, radius: number,
    observed: any[],
): Promise<string> {
    const selector = `execute positioned ${x} ${y} ${z} run kill `
        + `@e[type=!minecraft:player,distance=..${radius}]`;
    const initial = describeEntities(observed);
    const first = await h.command(selector);
    const firstKillCount = Number(first.resultCode ?? 0);
    if (firstKillCount < observed.length) {
        throw new Error(`kill selected ${firstKillCount} of ${observed.length} observed: ${initial}`);
    }
    const deathStep = await tick.stepTicks(21);
    const deathTicksCompleted = completedTicks(deathStep);
    if (deathTicksCompleted !== null && deathTicksCompleted < 21) {
        throw new Error(`death step completed ${deathTicksCompleted} of 21 ticks; observed ${initial}`);
    }
    const afterDeath = (await h.entitiesAround(x, y, z, radius))
        .filter(entity => entity.typeId !== 'minecraft:player');
    let secondKillCount: number | null = null;
    if (afterDeath.length > 0) {
        const second = await h.command(selector);
        secondKillCount = Number(second.resultCode ?? 0);
        if (secondKillCount < afterDeath.length) {
            throw new Error(`second kill selected ${secondKillCount} of `
                + `${afterDeath.length} observed: ${describeEntities(afterDeath)}`);
        }
    }
    const finalStep = await tick.stepTicks(1);
    const finalTicksCompleted = completedTicks(finalStep);
    if (finalTicksCompleted !== null && finalTicksCompleted < 1) {
        throw new Error(`final cleanup step completed ${finalTicksCompleted} of 1 ticks`);
    }
    return `observed=${initial}; kill=${firstKillCount}; death-step=${deathTicksCompleted ?? 'unknown'}/21; `
        + `after-death=${describeEntities(afterDeath)}; second-kill=${secondKillCount ?? 'skipped'}; `
        + `final-step=${finalTicksCompleted ?? 'unknown'}/1`;
}

function completedTicks(job: any): number | null {
    const stepped = Array.isArray(job?.milestones)
        ? job.milestones.find((milestone: any) => milestone.name === 'stepped')?.data?.completed
        : undefined;
    return Number.isInteger(stepped) ? Number(stepped) : null;
}

function describeEntities(entities: any[]): string {
    if (entities.length === 0) return 'none';
    return entities.slice(0, 16).map(entity => {
        const position = [entity.x, entity.y, entity.z]
            .map(value => Number(value).toFixed(1)).join(',');
        return `${entity.typeId}@${position}`;
    }).join('|') + (entities.length > 16 ? `|…+${entities.length - 16}` : '');
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
