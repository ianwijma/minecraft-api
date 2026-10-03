/**
 * Scenario 1 — The House (plan §5.1): static command-built structure on a
 * flat stage, mobs off, diffed from four exterior + one interior fixed pose.
 */
import type { Ctx, Scenario } from '../harness/context.ts';
import { Visual } from '../harness/visual.ts';
import { prepareStage, setCamera, hideHud, NOON, type Pose }
    from '../harness/stage.ts';

// 📐 pin-once values — tuned on the first live run, then frozen.
const PINS = {
    poses: {
        se: { x: 12.5, y: 72, z: 12.5, yaw: 135, pitch: -20 },
        sw: { x: -12.5, y: 72, z: 12.5, yaw: 225, pitch: -20 },
        nw: { x: -12.5, y: 72, z: -12.5, yaw: 315, pitch: -20 },
        ne: { x: 12.5, y: 72, z: -12.5, yaw: 45, pitch: -20 },
        interior: { x: 0.5, y: 65, z: 0.5, yaw: 180, pitch: 15 },
    } satisfies Record<string, Pose>,
    // Bounded snapshots watch players/entities, not arbitrary blocks (spec
    // §12) — a build produces 0 changed records by design; block queries
    // below carry the structural proof. Pinned at 0.
    snapshotChangedCount: 0 as number | null,
};

const BUILD: string[] = [
    'fill -4 64 -3 4 64 3 stone_bricks',
    'fill -4 65 -3 4 68 3 oak_planks hollow',
    // glass_pane ripples frame-to-frame under fancy graphics; full blocks
    // of the same material are pixel-static (plan §1 animated/noise sources).
    'fill -4 67 -1 -4 67 1 glass',
    'fill 4 67 -1 4 67 1 glass',
    'setblock 0 65 3 oak_door[half=lower,facing=south]',
    'setblock 0 66 3 oak_door[half=upper,facing=south]',
    'fill -5 69 -4 5 69 4 oak_planks',
    'setblock -5 69 -4 stone_bricks',
    'setblock 5 69 -4 stone_bricks',
    'setblock -5 69 4 stone_bricks',
    'setblock 5 69 4 stone_bricks',
    'setblock 0 68 0 glowstone',
];

async function expectBlock(ctx: Ctx, x: number, y: number, z: number,
                           id: string): Promise<void> {
    const body = await ctx.h.blockAt(x, y, z);
    ctx.report.expect(ctx.h.bodyMentions(body, id),
        `block ${id} @${x},${y},${z}`,
        JSON.stringify(body).slice(0, 160));
}

export const scenario: Scenario = {
    name: 'house',
    async run(ctx) {
        const { h, report } = ctx;
        const visual = new Visual(h, report, {
            scenario: 'house',
            baselinesDir: ctx.baselinesDirFor('house'),
            outDir: ctx.outDirFor('house'),
            updateBaselines: ctx.updateBaselines,
        });

        await prepareStage(h, 0, 0, ctx.tick);
        await hideHud(h);

        const pre = await h.snapshot('house-pre');
        for (const cmd of BUILD) await h.command(cmd);
        const post = await h.snapshot('house-post');

        const diff = await h.snapshotDiff(pre, post);
        const changed = (diff.changed as unknown[]) ?? [];
        report.expect(diff.truncated !== true, 'snapshot diff untruncated',
            `${changed.length} changed records`);
        report.expectPinned('house snapshot changed count',
            PINS.snapshotChangedCount, changed.length);

        await expectBlock(ctx, -4, 65, -3, 'oak_planks');
        await expectBlock(ctx, 4, 65, 3, 'oak_planks');
        await expectBlock(ctx, 0, 65, 3, 'oak_door');
        await expectBlock(ctx, 0, 68, 0, 'glowstone');
        await expectBlock(ctx, -4, 67, 0, 'glass');

        const entities = await h.entitiesAround(0, 64, 0, 32);
        const nonPlayer = entities.filter(e => e.typeId !== 'minecraft:player');
        report.expect(nonPlayer.length === 0, 'no entities near house',
            `${nonPlayer.length} non-player entities in radius 32: `
            + nonPlayer.map(e => e.typeId).join(', '));

        for (const [name, pose] of Object.entries(PINS.poses)) {
            await setCamera(h, pose);
            await visual.capture({ name: `house-${name}`, expectedDayTime: NOON });
        }
    },
};
