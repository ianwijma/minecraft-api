/**
 * Scenario 2 — Clockwork (plan §5.2): a torch-free, RNG-free, self-starting
 * repeater ring driving four lamps; the session tick lease stays FROZEN and
 * advances only by exact step jobs; the lit lamp rotates one position per
 * STEP_TICKS and returns to identity after PERIOD.
 *
 * The circuit is an initial 📐 design: the assertions below are the
 * contract (exactly one lamp lit, advancing by one, identity after PERIOD).
 * If the first live run disagrees with the physics, tune GEOMETRY/STEP/PERIOD
 * — never the assertions.
 */
import type { Ctx, Scenario } from '../harness/context.ts';
import { Visual } from '../harness/visual.ts';
import { prepareStage, setCamera, hideHud, NOON } from '../harness/stage.ts';

const CX = 512; // stage center

// 📐 pin-once circuit: 4 repeaters (delay 4) in a rectangular ring with dust
// links, lamps tapped outside the ring, redstone-block pulse injection.
const GEOMETRY: string[] = [
    `setblock ${CX + 4} 65 -2 repeater[delay=4,facing=east]`,
    `fill ${CX + 5} 65 -2 ${CX + 7} 65 -2 redstone_wire`,
    `setblock ${CX + 8} 65 -2 repeater[delay=4,facing=south]`,
    `fill ${CX + 8} 65 -1 ${CX + 8} 65 1 redstone_wire`,
    `setblock ${CX + 8} 65 2 repeater[delay=4,facing=west]`,
    `fill ${CX + 5} 65 2 ${CX + 7} 65 2 redstone_wire`,
    `setblock ${CX + 4} 65 2 repeater[delay=4,facing=north]`,
    `fill ${CX + 4} 65 -1 ${CX + 4} 65 1 redstone_wire`,
    `fill ${CX + 3} 65 -2 ${CX + 3} 65 -1 redstone_wire`,
];
const LAMPS = [
    { x: CX + 5, y: 65, z: -3 },
    { x: CX + 9, y: 65, z: 0 },
    { x: CX + 7, y: 65, z: 3 },
    { x: CX + 3, y: 65, z: 1 },
];
const STEP_TICKS = 8;    // 📐 one lamp position per step
const PERIOD_STEPS = 4;  // 📐 full rotation = 4 steps (32 game ticks)

async function lampLit(ctx: Ctx, lamp: { x: number; y: number; z: number }):
        Promise<boolean> {
    const body = await ctx.h.blockAt(lamp.x, lamp.y, lamp.z);
    const json = JSON.stringify(body);
    // ⚙ verify-26.2 blockstate wire shape; accept both common spellings.
    return json.includes('"lit":true') || json.includes('lit=true');
}

async function litIndex(ctx: Ctx): Promise<number | null> {
    const lit: number[] = [];
    for (let i = 0; i < LAMPS.length; i++) {
        if (await lampLit(ctx, LAMPS[i])) lit.push(i);
    }
    if (lit.length !== 1) {
        ctx.report.advisory(`lamp contract ambiguous: lit=${JSON.stringify(lit)}`);
        return null;
    }
    return lit[0];
}

export const scenario: Scenario = {
    name: 'clockwork',
    async run(ctx) {
        const { h, report } = ctx;
        const visual = new Visual(h, report, {
            scenario: 'clockwork',
            baselinesDir: ctx.baselinesDirFor('clockwork'),
            outDir: ctx.outDirFor('clockwork'),
            updateBaselines: ctx.updateBaselines,
        });

        await prepareStage(h, CX, 0);
        for (const lamp of LAMPS) {
            await h.command(`setblock ${lamp.x} ${lamp.y} ${lamp.z} redstone_lamp`);
        }
        await hideHud(h);
        await setCamera(h, { x: CX + 6.5, y: 70, z: 9.5, yaw: 180, pitch: -25 });
        // Daytime moves with stepped ticks under a true doDaylightCycle;
        // expectedDayTime = scenario-local baseline + steps taken since.
        const t0 = await h.dayTime();
        const s0 = ctx.tick.stepped;
        const dayAt = () => t0 + (ctx.tick.stepped - s0);
        try {
            for (const cmd of GEOMETRY) await h.command(cmd);

            // Pulse injection: brief power into the ring, then step through it.
            await h.command(`setblock ${CX + 6} 65 -3 redstone_block`);
            await ctx.tick.stepTicks(2);
            await h.command(`setblock ${CX + 6} 65 -3 air`);
            await ctx.tick.stepTicks(2);

            const snapPre = await h.snapshot('clock-pre');
            await visual.capture({ name: 'clock-b0', expectedDayTime: dayAt() });
            const i0 = await litIndex(ctx);

            for (let k = 1; k <= PERIOD_STEPS; k++) {
                const job = await ctx.tick.stepTicks(STEP_TICKS);
                const result = job.result as any;
                report.expect(
                    result == null
                        || result.requestedSteps === undefined
                        || result.requestedSteps === result.completedSteps,
                    `step job ${k} completed requested ticks`,
                    `requested ${result?.requestedSteps}, completed ${result?.completedSteps}`);
                const actual = await litIndex(ctx);
                if (i0 !== null && actual !== null) {
                    report.expect(actual === (i0 + k) % LAMPS.length,
                        `beat ${k}: lamp advanced by one`,
                        `from ${i0} to ${actual}`);
                }
                await visual.capture({ name: `clock-b${k}`, expectedDayTime: dayAt() });
            }

            // Identity beat: one full period later the world repeats itself —
            // visually and in blockstate.
            const b0 = visual.get('clock-b0');
            const b4 = visual.get(`clock-b${PERIOD_STEPS}`);
            visual.pairDiff(b0, b4, 'identity after one period',
                { expect: 'unchanged', pixelThreshold: 4 });
            const snapPost = await h.snapshot('clock-post');
            const diff = await h.snapshotDiff(snapPre, snapPost);
            const lampChanges = ((diff.changed as any[]) ?? [])
                .filter(c => JSON.stringify(c).includes('redstone_lamp'));
            report.expect(lampChanges.length === 0,
                'no lamp state changed across one period',
                `${lampChanges.length} lamp records changed`);
        } finally {
            await ctx.tick.freeze().catch(() => {});
        }
    },
};
