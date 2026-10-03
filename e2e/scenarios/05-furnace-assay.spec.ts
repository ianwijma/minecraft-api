/**
 * Scenario 5 — The Furnace Assay (plan §5.5): furnaces loaded via /data,
 * exactly one smelt stepped under tick freeze, typed-NBT assertions at every
 * boundary, and an exactly-1600-tick burn so the final frame is unlit again.
 */
import type { Ctx, Scenario } from '../harness/context.ts';
import { Visual } from '../harness/visual.ts';
import type { Mask } from '../harness/diff.ts';
import {
    prepareStage, setCamera, hideHud, setTimeAbsolute, NOON, MIDNIGHT,
} from '../harness/stage.ts';

const CZ = 512; // stage center z
const FURNACES = [0, 1, 2, 3].map(x => ({ x, y: 65, z: CZ }));

// 📐 pin-once: exact counter semantics and the midnight furnace-front mask
// rectangles. 26.2 furnace NBT (live-verified): lit_total_time (short) =
// total burn time of the current fuel; cooking_time_spent (short) = smelt
// progress 0..200; cooking_total_time (short) = recipe time.
const PINS = {
    litAfter199: null as number | null,   // expected 1600 (coal total)
    cookAfter199: null as number | null,  // expected 199
    litAfter200: null as number | null,   // expected 1600
    frontMasks: null as Mask[] | null,
};

/** Schema-tolerant numeric read from a typed-NBT queryBlock response. ⚙ */
function nbtNumber(body: unknown, key: string): number | null {
    const json = JSON.stringify(body);
    const typed = new RegExp(`"${key}"\\s*:\\s*\\{[^{}]*?"value"\\s*:\\s*(-?\\d+)`)
        .exec(json);
    if (typed) return Number(typed[1]);
    const plain = new RegExp(`"${key}"\\s*:\\s*(-?\\d+)`).exec(json);
    return plain ? Number(plain[1]) : null;
}

export const scenario: Scenario = {
    name: 'furnace-assay',
    async run(ctx) {
        const { h, report } = ctx;
        const visual = new Visual(h, report, {
            scenario: 'furnace-assay',
            baselinesDir: ctx.baselinesDirFor('furnace-assay'),
            outDir: ctx.outDirFor('furnace-assay'),
            updateBaselines: ctx.updateBaselines,
        });

        await prepareStage(h, 0, CZ);
        await hideHud(h);
        await setCamera(h, { x: 1.5, y: 67.5, z: CZ + 7, yaw: 180, pitch: -12 });
        const t0 = await h.dayTime();
        const s0 = ctx.tick.stepped;
        const dayAt = () => t0 + (ctx.tick.stepped - s0);

        try {
            for (const f of FURNACES) {
                await h.command(`setblock ${f.x} ${f.y} ${f.z} furnace[facing=south]`);
                await h.command(`data merge block ${f.x} ${f.y} ${f.z} ` +
                    `{Items:[{Slot:0b,id:"minecraft:iron_ore",count:1},` +
                    `{Slot:1b,id:"minecraft:coal",count:1}]}`);
            }
            await setTimeAbsolute(h, NOON);
            const snapLoaded = await h.snapshot('furnace-loaded');
            await visual.capture({ name: 'furnace-loaded', expectedDayTime: dayAt() });

            await ctx.tick.stepTicks(199);
            for (const f of FURNACES) {
                const body = await h.blockAt(f.x, f.y, f.z);
                report.expectPinned(`lit_total_time after 199 @${f.x}`,
                    PINS.litAfter199, nbtNumber(body, 'lit_total_time') ?? -1);
                report.expectPinned(`cooking_time_spent after 199 @${f.x}`,
                    PINS.cookAfter199, nbtNumber(body, 'cooking_time_spent') ?? -1);
            }

            await ctx.tick.stepTicks(1);
            for (const f of FURNACES) {
                const body = await h.blockAt(f.x, f.y, f.z);
                report.expect(h.bodyMentions(body, 'iron_ingot'),
                    `smelt completed @${f.x} after 200 ticks`,
                    JSON.stringify(body).slice(0, 300));
                report.expectPinned(`lit_total_time after 200 @${f.x}`,
                    PINS.litAfter200, nbtNumber(body, 'lit_total_time') ?? -1);
            }
            const snapSmelted = await h.snapshot('furnace-smelted');

            // Midnight glow: lit fronts are wall-clock animated → masked; the
            // light pool on the floor is deterministic and stays visible.
            await setTimeAbsolute(h, MIDNIGHT);
            if (PINS.frontMasks === null) {
                report.advisory('frontMasks unpinned: glow shot compared unmasked');
            }
            await visual.capture({
                name: 'furnace-midnight-glow', expectedDayTime: MIDNIGHT,
                masks: PINS.frontMasks ?? [],
            });
            await setTimeAbsolute(h, NOON);

            // Burn out the remaining fuel: 1400 more ticks, furnace unlit.
            await ctx.tick.stepTicks(1400);
            for (const f of FURNACES) {
                const body = await h.blockAt(f.x, f.y, f.z);
                report.expect(nbtNumber(body, 'cooking_time_spent') === 0,
                    `fuel spent @${f.x} after 1600 total ticks`,
                    `cooking_time_spent=${nbtNumber(body, 'cooking_time_spent')}`);
                report.expect(!h.bodyMentions(body, 'coal'),
                    `coal consumed @${f.x}`, JSON.stringify(body).slice(0, 200));
            }
            const snapDone = await h.snapshot('furnace-done');
            await visual.capture({ name: 'furnace-complete', expectedDayTime: dayAt() });

            const diff = await h.snapshotDiff(snapLoaded, snapDone);
            const changed = (diff.changed as any[]) ?? [];
            report.expect(diff.truncated !== true,
                'loaded→done diff untruncated', `${changed.length} records`);
            const stray = changed.filter(c => {
                const s = JSON.stringify(c);
                return !['Items', 'lit_total_time', 'cooking_time_spent',
                    'cooking_total_time'].some(k => s.includes(k));
            });
            report.expect(stray.length === 0,
                'only furnace NBT fields changed',
                `${stray.length} stray records: ${JSON.stringify(stray).slice(0, 200)}`);

            const smeltDiff = await h.snapshotDiff(snapLoaded, snapSmelted);
            const smeltChanged = (smeltDiff.changed as any[]) ?? [];
            report.expect(smeltChanged.length > 0,
                'smelt boundary is observable in snapshot diff',
                `${smeltChanged.length} records loaded→smelted`);
        } finally {
            await ctx.tick.freeze().catch(() => {});
        }
    },
};
