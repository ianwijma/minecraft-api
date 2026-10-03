/**
 * Scenario 8 — The Sundial (plan §5.8): the lighting contract as a test.
 * Negative control (pinned time ⇒ pixel-identical frames), positive control
 * (/time add 200 ⇒ shading moves), absolute restore ⇒ identity again, then a
 * four-mood lighting tour. Run cheap; run early.
 */
import type { Ctx, Scenario } from '../harness/context.ts';
import { Visual } from '../harness/visual.ts';
import type { Mask } from '../harness/diff.ts';
import {
    prepareStage, setCamera, hideHud, setTimeAbsolute, assertDayTime,
    NOON, MIDNIGHT,
} from '../harness/stage.ts';
import { sleep } from '../harness/report.ts';

// 📐 pin-once values — tuned on the first live run, then frozen.
const PINS = {
    pose: { x: 0.5, y: 66, z: -1013.5, yaw: 180, pitch: -12 },
    wallWaitMs: 30_000,
    shadowRegion: null as Mask | null, // 📐 shadow rectangle for the +200 check
};

const CZ = -1024; // stage center z

export const scenario: Scenario = {
    name: 'sundial',
    async run(ctx) {
        const { h, report } = ctx;
        const visual = new Visual(h, report, {
            scenario: 'sundial',
            baselinesDir: ctx.baselinesDirFor('sundial'),
            outDir: ctx.outDirFor('sundial'),
            updateBaselines: ctx.updateBaselines,
        });

        await prepareStage(h, 0, CZ, ctx.tick);
        for (let x = -4; x <= 4; x++) {
            for (let z = -4; z <= 4; z++) {
                const block = (x + z) % 2 === 0 ? 'white_concrete' : 'black_concrete';
                await h.command(`setblock ${x} 64 ${CZ + z} ${block}`);
            }
        }
        await h.command(`fill 0 65 ${CZ} 0 69 ${CZ} stone`);
        await h.command('kill @e[type=!minecraft:player]').catch(() => {});
        await sleep(6000); // chunk-mesh convergence before the first capture

        await setTimeAbsolute(h, NOON);
        await hideHud(h);
        await setCamera(h, PINS.pose);

        // Negative control: pinned time + wall time must not move a single
        // pixel while the world idles under the frozen session lease.
        // (No tick stepping here: stepping simulation advances world time
        // when advance_time is true — that is correct vanilla behavior,
        // not drift.)
        const a = await visual.capture({ name: 'sun-noon-a', expectedDayTime: NOON });
        await sleep(PINS.wallWaitMs);
        await assertDayTime(h, NOON);
        const b = await visual.capture({ name: 'sun-noon-b', expectedDayTime: NOON });
        visual.pairDiff(a, b, 'sun-noon stable while pinned', {
            expect: 'unchanged', pixelThreshold: 4,
        });

        // Positive control: a deliberate relative beat must move the shading.
        // (+200 ticks at noon is below the shimmer floor; +1200 = 1 hour.)
        await h.command('time add 1200');
        await assertDayTime(h, NOON + 1200);
        const c = await visual.capture({
            name: 'sun-noon-plus1200', expectedDayTime: NOON + 1200,
        });
        if (PINS.shadowRegion === null) {
            report.advisory('shadowRegion unpinned: +1200 change check runs unmasked');
        }
        visual.pairDiff(a, c, 'time +1200 moves shading', {
            expect: 'changed', minChangedFraction: 0.0005,
            region: PINS.shadowRegion ?? undefined,
        });

        // Absolute restore: shading is a pure function of pinned time.
        await setTimeAbsolute(h, NOON);
        const c2 = await visual.capture({ name: 'sun-noon-c', expectedDayTime: NOON });
        visual.pairDiff(a, c2, 'absolute restore reproduces shading', {
            expect: 'unchanged', pixelThreshold: 4,
        });

        // Mood tour: transitional times are allowed here only (§1.1 rule 4).
        for (const [t, name] of [
            [NOON, 'sun-noon'], [12000, 'sun-sunset'],
            [MIDNIGHT, 'sun-midnight'], [23000, 'sun-predawn'],
        ] as const) {
            await setTimeAbsolute(h, t);
            await visual.capture({ name, expectedDayTime: t });
        }
    },
};
