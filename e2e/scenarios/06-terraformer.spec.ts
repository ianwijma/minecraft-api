/**
 * Scenario 6 — The Terraformer (plan §5.6): the player breaks a doorway and
 * builds a lit lamp post through the normal keybinding path (attack/use
 * rebound to keyboard). No /setblock for the mutations themselves.
 */
import type { Ctx, Scenario } from '../harness/context.ts';
import { Visual } from '../harness/visual.ts';
import { KEYS } from '../harness/client.ts';
import type { Mask } from '../harness/diff.ts';
import {
    prepareStage, aimAt, hideHud, setTimeAbsolute, NOON, MIDNIGHT,
} from '../harness/stage.ts';
import { sleep } from '../harness/report.ts';

const CX = 1024;
const FEET = { x: CX + 0.5, y: 65, z: 4.5 };

// 📐 pin-once: creative break spacing (cooldown ⚙) and the doorway mask.
const PINS = {
    attackTicks: 20,
    doorwayRect: null as Mask | null,
    snapshotChangedCount: null as number | null,
};

async function breakBlock(ctx: Ctx, target: { x: number; y: number; z: number }): Promise<void> {
    const { h } = ctx;
    const center = { x: target.x + 0.5, y: target.y + 0.5, z: target.z + 0.5 };
    for (let attempt = 1; attempt <= 3; attempt++) {
        await aimAt(h, FEET, center);
        await h.holdKey(KEYS.ATTACK, PINS.attackTicks);
        // Creative break applies server-side: the frozen server must step to
        // process it (spec §5 freeze excludes nothing we rely on here).
        await ctx.tick.stepTicks(10);
        await sleep(150);
        if (h.bodyMentions(await h.blockAt(target.x, target.y, target.z), 'air')) {
            return;
        }
    }
    throw new Error(`failed to break ${JSON.stringify(target)} in 3 attempts`);
}

async function useAt(ctx: Ctx, point: { x: number; y: number; z: number },
                     verify: { x: number; y: number; z: number },
                     idPart: string): Promise<void> {
    const { h } = ctx;
    for (let attempt = 1; attempt <= 3; attempt++) {
        await aimAt(h, FEET, point);
        await h.holdKey(KEYS.USE, 4);
        await ctx.tick.stepTicks(5);
        await sleep(150);
        if (h.bodyMentions(await h.blockAt(verify.x, verify.y, verify.z), idPart)) {
            return;
        }
    }
    throw new Error(`placement of ${idPart} @${JSON.stringify(verify)} failed`);
}

export const scenario: Scenario = {
    name: 'terraformer',
    async run(ctx) {
        const { h, report } = ctx;
        const visual = new Visual(h, report, {
            scenario: 'terraformer',
            baselinesDir: ctx.baselinesDirFor('terraformer'),
            outDir: ctx.outDirFor('terraformer'),
            updateBaselines: ctx.updateBaselines,
        });

        await prepareStage(h, CX, 0);
        await h.command(`fill ${CX - 3} 65 0 ${CX + 3} 68 0 stone_bricks`);
        await hideHud(h);

        const snapPre = await h.snapshot('tf-pre');
        await aimAt(h, FEET, { x: CX + 0.5, y: 66.5, z: 0 });
        const before = await visual.capture({ name: 'wall-before', expectedDayTime: NOON });

        await breakBlock(ctx, { x: CX, y: 65, z: 0 });
        await breakBlock(ctx, { x: CX, y: 66, z: 0 });
        for (const pos of [{ x: CX, y: 65, z: 0 }, { x: CX, y: 66, z: 0 }]) {
            const body = await h.blockAt(pos.x, pos.y, pos.z);
            report.expect(h.bodyMentions(body, 'air'),
                `doorway open @${pos.y}`, JSON.stringify(body).slice(0, 120));
        }
        await aimAt(h, FEET, { x: CX + 0.5, y: 66.5, z: 0 });
        const doorway = await visual.capture({ name: 'wall-doorway', expectedDayTime: NOON });
        visual.pairDiff(before, doorway, 'doorway appeared',
            { expect: 'changed', minChangedFraction: 0.0005 });
        if (PINS.doorwayRect !== null) {
            visual.pairDiff(before, doorway, 'nothing but the doorway moved', {
                expect: 'unchanged', masks: [PINS.doorwayRect], pixelThreshold: 0,
            });
        } else {
            report.advisory('doorwayRect unpinned: outside-doorway check skipped');
        }

        // Lamp post: two waxed bulbs and a lever, all placed by the player.
        await h.command('give @p waxed_copper_bulb 2');
        await h.command('give @p lever');
        await h.holdKey(KEYS.HOTBAR_1, 1);
        await useAt(ctx, { x: CX + 2.5, y: 65, z: 1.5 },
            { x: CX + 2, y: 65, z: 1 }, 'copper_bulb');
        await useAt(ctx, { x: CX + 2.5, y: 66, z: 1.5 },
            { x: CX + 2, y: 66, z: 1 }, 'copper_bulb');
        await h.holdKey(KEYS.HOTBAR_2, 1);
        await useAt(ctx, { x: CX + 2.5, y: 67, z: 1.5 },
            { x: CX + 2, y: 67, z: 1 }, 'lever');

        await aimAt(h, FEET, { x: CX + 2.5, y: 67.4, z: 1.5 });
        await h.holdKey(KEYS.USE, 4);
        await sleep(250);
        const bulb = await h.blockAt(CX + 2, 66, 1);
        const bulbJson = JSON.stringify(bulb);
        report.expect(
            bulbJson.includes('"lit":true') || bulbJson.includes('lit=true'),
            'bulb lit after lever flick', bulbJson.slice(0, 200));

        const snapPost = await h.snapshot('tf-post');
        await setTimeAbsolute(h, MIDNIGHT);
        await aimAt(h, FEET, { x: CX + 1.5, y: 66.5, z: 0.5 });
        await visual.capture({ name: 'lamp-lit', expectedDayTime: MIDNIGHT });
        await setTimeAbsolute(h, NOON);

        const diff = await h.snapshotDiff(snapPre, snapPost);
        const changed = (diff.changed as unknown[]) ?? [];
        report.expect(diff.truncated !== true, 'terraformer diff untruncated',
            `${changed.length} changed records`);
        report.expectPinned('terraformer changed set size',
            PINS.snapshotChangedCount, changed.length);
    },
};
