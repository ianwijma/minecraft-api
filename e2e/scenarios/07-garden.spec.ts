/**
 * Scenario 7 — The Garden (plan §5.7): tilling and planting a 7×5 field via
 * the use verb (not attack). Dry farmland persists under randomTickSpeed 0;
 * stage-0 wheat never advances, so the final frame is stable forever.
 */
import type { Ctx, Scenario } from '../harness/context.ts';
import { Visual } from '../harness/visual.ts';
import { KEYS } from '../harness/client.ts';
import { prepareStage, setCamera, hideHud, NOON } from '../harness/stage.ts';
import { sleep } from '../harness/report.ts';

const CX = -1024;
const FIELD = { x1: CX - 3, x2: CX + 3, z1: -2, z2: 2 }; // 7×5 at y=64

// 📐 pin-once.
const PINS = {
    pose: { x: CX + 0.5, y: 68, z: 8.5, yaw: 180, pitch: -30 },
    tilledChangedCount: null as number | null,  // expected 35
    plantedChangedCount: null as number | null, // expected 70
};

function* cells(): Generator<{ x: number; z: number }> {
    for (let x = FIELD.x1; x <= FIELD.x2; x++) {
        for (let z = FIELD.z1; z <= FIELD.z2; z++) {
            yield { x, z };
        }
    }
}

async function useOnCell(ctx: Ctx, x: number, z: number): Promise<void> {
    const { h } = ctx;
    // Overhead pose, straight down: hits the top face of the cell underfoot.
    await setCamera(h, { x: x + 0.5, y: 65, z: z + 0.5, yaw: 0, pitch: 90 });
    await h.holdKey(KEYS.USE, 4);
    // Use-effects apply server-side; the frozen server must step to process.
    await ctx.tick.stepTicks(3);
    await sleep(100);
}

export const scenario: Scenario = {
    name: 'garden',
    async run(ctx) {
        const { h, report } = ctx;
        const visual = new Visual(h, report, {
            scenario: 'garden',
            baselinesDir: ctx.baselinesDirFor('garden'),
            outDir: ctx.outDirFor('garden'),
            updateBaselines: ctx.updateBaselines,
        });

        await prepareStage(h, CX, 0);
        await h.command(
            `fill ${FIELD.x1} 64 ${FIELD.z1} ${FIELD.x2} 64 ${FIELD.z2} dirt`);
        await hideHud(h);
        await setCamera(h, PINS.pose);
        const dirt = await visual.capture({ name: 'garden-dirt', expectedDayTime: NOON });

        const snapPre = await h.snapshot('garden-pre');
        await h.command('give @p iron_hoe');
        await h.command('give @p wheat_seeds 35');
        await h.holdKey(KEYS.HOTBAR_1, 1);
        let failures = 0;
        for (const { x, z } of cells()) {
            await useOnCell(ctx, x, z);
            if (!h.bodyMentions(await h.blockAt(x, 64, z), 'farmland')) failures++;
        }
        report.expect(failures === 0, 'all 35 cells tilled',
            `${failures} cells still not farmland`);

        await setCamera(h, PINS.pose);
        const tilled = await visual.capture({ name: 'garden-tilled', expectedDayTime: NOON });
        visual.pairDiff(dirt, tilled, 'field visibly tilled',
            { expect: 'changed', minChangedFraction: 0.0005 });

        await h.holdKey(KEYS.HOTBAR_2, 1);
        failures = 0;
        for (const { x, z } of cells()) {
            await useOnCell(ctx, x, z);
            if (!h.bodyMentions(await h.blockAt(x, 65, z), 'wheat')) failures++;
        }
        report.expect(failures === 0, 'all 35 cells planted',
            `${failures} cells without stage-0 wheat`);

        await setCamera(h, PINS.pose);
        const planted = await visual.capture({ name: 'garden-planted', expectedDayTime: NOON });
        visual.pairDiff(tilled, planted, 'field visibly planted',
            { expect: 'changed', minChangedFraction: 0.0005 });

        const snapPost = await h.snapshot('garden-post');
        const diff = await h.snapshotDiff(snapPre, snapPost);
        const changed = (diff.changed as unknown[]) ?? [];
        report.expect(diff.truncated !== true, 'garden diff untruncated',
            `${changed.length} changed records`);
        report.expectPinned('garden changed set size (till+plant)',
            PINS.plantedChangedCount, changed.length);

        const entities = await h.entitiesAround(CX, 65, 0, 32);
        const items = entities.filter(e => JSON.stringify(e).includes('item'));
        report.expect(items.length === 0, 'no stray item entities',
            `${items.length} item entities in field area`);
    },
};
