/**
 * Scenario 3 — Inventory Ballet (plan §5.3): 2×2 crafting chain through
 * client-logic container clicks with server-confirmed postconditions after
 * every beat, ending in computed-vs-rendered tooltip comparison. GUI frames
 * are the most byte-stable shots the game produces.
 */
import type { Ctx, Scenario } from '../harness/context.ts';
import { Visual } from '../harness/visual.ts';
import { KEYS } from '../harness/client.ts';
import { hideHud, setCamera, NOON } from '../harness/stage.ts';
import { sleep } from '../harness/report.ts';

// Player-inventory menu slots (docs/openapi.yaml): 0=result, 1-4=craft grid,
// 9-44=main, 36-44=hotbar.
const SLOT = {
    RESULT: 0, CRAFT1: 1, CRAFT2: 2, CRAFT3: 3, CRAFT4: 4,
    LOG_A: 36, LOG_B: 37, PLANKS_A: 36, PLANKS_B: 37,
    STICKS: 38, TABLE: 39,
};

async function serverCount(ctx: Ctx, itemId: string): Promise<number> {
    const players = await ctx.h.players(1);
    const inv = (players[0]?.inventory as any[]) ?? [];
    return inv.filter(i => i.itemId === itemId)
        .reduce((s, i) => s + Number(i.count), 0);
}

async function expectCarriedEmpty(ctx: Ctx, beat: string): Promise<void> {
    const inv = await ctx.h.inventory();
    ctx.report.expect(Number(inv.carriedCount) === 0,
        `carried empty after ${beat}`, `carriedCount=${inv.carriedCount}`);
}

async function expectServerCount(ctx: Ctx, itemId: string,
                                 expected: number, beat: string): Promise<void> {
    const actual = await serverCount(ctx, itemId);
    ctx.report.expect(actual === expected, `server has ${expected} ${itemId} after ${beat}`,
        `actual ${actual}`);
}

export const scenario: Scenario = {
    name: 'inventory-ballet',
    async run(ctx) {
        const { h, report } = ctx;
        const visual = new Visual(h, report, {
            scenario: 'inventory-ballet',
            baselinesDir: ctx.baselinesDirFor('inventory-ballet'),
            outDir: ctx.outDirFor('inventory-ballet'),
            updateBaselines: ctx.updateBaselines,
        });

        await setCamera(h, { x: 8.5, y: 65, z: 8.5, yaw: 0, pitch: 0 });
        await hideHud(h);
        await h.command('give @p oak_log 2');
        await sleep(4000); // recipe-unlock toasts must fade before any capture
        await h.holdKey(KEYS.INVENTORY, 1);
        await sleep(1500); // inventory open animation must finish before settle
        await visual.capture({ name: 'inv-given', expectedDayTime: NOON });

        // Craft A: 1 log -> 4 planks.
        await h.clickInventory(SLOT.LOG_A, 0);          // carry 2 logs
        await h.clickInventory(SLOT.CRAFT1, 1);         // 1 log into grid
        await h.clickInventory(SLOT.LOG_B, 0);          // park remaining log
        await h.clickInventory(SLOT.RESULT, 0);         // take 4 planks
        await h.clickInventory(SLOT.PLANKS_A, 0);       // planks home
        await expectCarriedEmpty(ctx, 'craft A');
        await expectServerCount(ctx, 'minecraft:oak_planks', 4, 'craft A');

        // Sticks: 2 planks (vertical) -> 4 sticks.
        await h.clickInventory(SLOT.PLANKS_A, 0);
        await h.clickInventory(SLOT.CRAFT1, 1);
        await h.clickInventory(SLOT.CRAFT3, 1);
        await h.clickInventory(SLOT.PLANKS_A, 0);       // 2 planks back
        await h.clickInventory(SLOT.RESULT, 0);         // 4 sticks
        await h.clickInventory(SLOT.STICKS, 0);
        await expectCarriedEmpty(ctx, 'sticks');
        await expectServerCount(ctx, 'minecraft:stick', 4, 'sticks');

        // Craft B: second log -> 4 planks (lands on the empty log slot).
        await h.clickInventory(SLOT.LOG_B, 0);
        await h.clickInventory(SLOT.CRAFT1, 1);
        await h.clickInventory(SLOT.RESULT, 0);
        await h.clickInventory(SLOT.PLANKS_B, 0);
        await expectCarriedEmpty(ctx, 'craft B');
        await expectServerCount(ctx, 'minecraft:oak_planks', 6, 'craft B');

        // Crafting table: one plank in each of the four grid slots.
        await h.clickInventory(SLOT.PLANKS_A, 0);       // carry 2
        await h.clickInventory(SLOT.CRAFT1, 1);
        await h.clickInventory(SLOT.CRAFT2, 1);         // cursor empty
        await h.clickInventory(SLOT.PLANKS_B, 0);       // carry 4
        await h.clickInventory(SLOT.CRAFT3, 1);
        await h.clickInventory(SLOT.CRAFT4, 1);
        await h.clickInventory(SLOT.PLANKS_B, 0);       // 2 planks back
        await h.clickInventory(SLOT.RESULT, 0);         // the table
        await h.clickInventory(SLOT.TABLE, 0);
        await expectCarriedEmpty(ctx, 'table');
        await expectServerCount(ctx, 'minecraft:crafting_table', 1, 'table');
        await expectServerCount(ctx, 'minecraft:oak_planks', 2, 'table');

        await visual.capture({ name: 'inv-crafted', expectedDayTime: NOON });

        // Computed tooltip, then the rendered capture of the same slot —
        // the two paths must agree (spec §10.2).
        const computed = await h.get(
            `/api/v1/client/inventory/tooltip?slot=${SLOT.TABLE}`);
        const rendered = await h.post(
            '/api/v1/client/inventory/tooltip-rendered', { slot: SLOT.TABLE });
        report.expect(
            JSON.stringify(computed.lines) === JSON.stringify(rendered.lines),
            'computed tooltip == rendered tooltip',
            `computed ${JSON.stringify(computed.lines)} vs ${JSON.stringify(rendered.lines)}`);
        await visual.submitPng({ name: 'tooltip-rendered' },
            Buffer.from(String(rendered.pngBase64), 'base64'),
            { frame: rendered.frame });

        await h.clickInventory(SLOT.TABLE, 0, 'QUICK_MOVE');
        await expectCarriedEmpty(ctx, 'quick-move');
        await h.holdKey(KEYS.INVENTORY, 1);             // close
        await sleep(500);
        await visual.capture({ name: 'inv-closed', expectedDayTime: NOON });
    },
};
