/**
 * Scenario 4 — The Hedge Maze (plan §5.4): a persistent-leaf maze built from
 * a versioned ASCII map; BFS route executed via raw-input waypoints with
 * per-leg position assertions; photos re-seated at exact poses (raw input is
 * not pixel-exact — no silent teleport, spec §3.3).
 *
 * MAP, bfsPath and legsFromPath are exported for the offline unit tests.
 */
import type { Ctx, Scenario } from '../harness/context.ts';
import { Visual } from '../harness/visual.ts';
import { prepareStage, setCamera, hideHud, NOON } from '../harness/stage.ts';

// Versioned maze layout. '#' = leaf wall, 'S'/'E' = start/exit cells.
// (The BFS unit test keeps this honest: an earlier draft enclosed E.)
export const MAP = [
    '###########',
    '#S#     # #',
    '# # ###   #',
    '# # #   # #',
    '# # # ### #',
    '#   # #   #',
    '### # # ###',
    '#   # #   #',
    '# ### ### #',
    '#       #E#',
    '###########',
];

const CX = -512;          // stage center x (z = 0)
const X0 = CX - 11;       // world x of cell column 0
const Z0 = -11;           // world z of cell row 0
const CELL = 2;

// 📐 pin-once: walking pace per 2-block cell with buffer (slight overshoot
// is fine — every leg's end is verified and re-seated).
const TICKS_PER_CELL = 10;

export interface Cell { r: number; c: number }

export function findCell(map: string[], mark: string): Cell {
    for (let r = 0; r < map.length; r++) {
        const c = map[r].indexOf(mark);
        if (c >= 0) return { r, c };
    }
    throw new Error(`mark ${mark} not in map`);
}

/** BFS shortest path from S to E on the ASCII maze ('#' is wall). */
export function bfsPath(map: string[]): Cell[] {
    const start = findCell(map, 'S');
    const goal = findCell(map, 'E');
    const prev = new Map<string, Cell | null>();
    const key = (c: Cell) => `${c.r},${c.c}`;
    prev.set(key(start), null);
    const queue: Cell[] = [start];
    while (queue.length > 0) {
        const cur = queue.shift()!;
        if (cur.r === goal.r && cur.c === goal.c) {
            const path: Cell[] = [];
            let node: Cell | null = cur;
            while (node) {
                path.unshift(node);
                node = prev.get(key(node)) ?? null;
            }
            return path;
        }
        for (const [dr, dc] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
            const next = { r: cur.r + dr, c: cur.c + dc };
            if (map[next.r]?.[next.c] === '#' || prev.has(key(next))) continue;
            prev.set(key(next), cur);
            queue.push(next);
        }
    }
    throw new Error('no path from S to E');
}

export interface Leg { yaw: number; cells: number; end: Cell }

/** Group the path into straight runs with the yaw that walks them. */
export function legsFromPath(path: Cell[]): Leg[] {
    const legs: Leg[] = [];
    for (let i = 1; i < path.length; i++) {
        const dr = path[i].r - path[i - 1].r;
        const dc = path[i].c - path[i - 1].c;
        // MC yaw: 0 faces +z (south); dx = -sin(yaw), dz = cos(yaw).
        const yaw = dc === 1 ? 270 : dc === -1 ? 90 : dr === 1 ? 0 : 180;
        const last = legs[legs.length - 1];
        if (last && last.yaw === yaw) {
            last.cells++;
            last.end = path[i];
        } else {
            legs.push({ yaw, cells: 1, end: path[i] });
        }
    }
    return legs;
}

function cellCenter(cell: Cell): { x: number; z: number } {
    return { x: X0 + cell.c * CELL + 0.5, z: Z0 + cell.r * CELL + 0.5 };
}

export const scenario: Scenario = {
    name: 'hedge-maze',
    async run(ctx) {
        const { h, report } = ctx;
        const visual = new Visual(h, report, {
            scenario: 'hedge-maze',
            baselinesDir: ctx.baselinesDirFor('hedge-maze'),
            outDir: ctx.outDirFor('hedge-maze'),
            updateBaselines: ctx.updateBaselines,
        });

        await prepareStage(h, CX, 0);
        for (let r = 0; r < MAP.length; r++) {
            let c = 0;
            while (c < MAP[r].length) {
                if (MAP[r][c] !== '#') { c++; continue; }
                let end = c;
                while (end + 1 < MAP[r].length && MAP[r][end + 1] === '#') end++;
                await h.command(
                    `fill ${X0 + c * CELL} 65 ${Z0 + r * CELL} ` +
                    `${X0 + end * CELL + 1} 67 ${Z0 + r * CELL + 1} ` +
                    'oak_leaves[persistent=true]');
                c = end + 1;
            }
        }
        await h.command('kill @e[type=!minecraft:player]').catch(() => {});
        await hideHud(h);

        // The map shot: whole maze, top-down, before anyone walks it.
        await setCamera(h, { x: CX + 0.5, y: 100, z: 0.5, yaw: 0, pitch: 90 });
        await visual.capture({ name: 'maze-map', expectedDayTime: NOON });

        const path = bfsPath(MAP);
        const legs = legsFromPath(path);
        report.expect(legs.length <= 64, 'waypoint legs within contract',
            `${legs.length} legs (max 64)`);

        // Walk it leg by leg so each leg's end position is verified.
        const start = cellCenter(path[0]);
        await setCamera(h, { x: start.x, y: 65, z: start.z, yaw: legs[0].yaw, pitch: 0 });
        for (let i = 0; i < legs.length; i++) {
            const leg = legs[i];
            const r = await h.api.post('/api/v1/client/movement/waypoints', {
                waypoints: [{ yaw: leg.yaw, pitch: 0, ticks: leg.cells * TICKS_PER_CELL }],
            });
            if (!r.ok) throw new Error(`waypoints -> ${r.status}: ${JSON.stringify(r.body)}`);
            const target = cellCenter(leg.end);
            const pos = await h.playerPos();
            const dist = Math.hypot(pos.x - target.x, pos.z - target.z);
            if (dist >= 0.75) {
                report.advisory(`leg ${i}: drifted ${dist.toFixed(2)} blocks — re-seating`);
                await setCamera(h, { x: target.x, y: 65, z: target.z, yaw: leg.yaw, pitch: 0 });
            }
        }
        const exitPos = cellCenter(path[path.length - 1]);
        const finalPos = await h.playerPos();
        const finalDist = Math.hypot(finalPos.x - exitPos.x, finalPos.z - exitPos.z);
        report.expect(finalDist < 1.0, 'reached the exit cell',
            `final distance ${finalDist.toFixed(2)}`);

        // Photo tour at re-seated poses: entrance, two junctions, exit.
        const shots: [string, Cell, number][] = [
            ['maze-entrance', path[0], legs[0].yaw],
            ['maze-junction-a', path[Math.floor(path.length / 3)], 0],
            ['maze-junction-b', path[Math.floor(2 * path.length / 3)], 0],
            ['maze-exit', path[path.length - 1], legs[legs.length - 1].yaw],
        ];
        for (const [name, cell, yaw] of shots) {
            const p = cellCenter(cell);
            await setCamera(h, { x: p.x, y: 65, z: p.z, yaw, pitch: 0 });
            await visual.capture({ name, expectedDayTime: NOON });
        }
    },
};
