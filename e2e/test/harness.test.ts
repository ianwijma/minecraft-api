/**
 * Offline unit tests for the visual harness core (plan §7 step 1): PNG
 * codec and diff engine, synthetic images only — no game required.
 * Run: cd e2e && npm test
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';
import { decodePng, encodePng, cropImage, type RgbaImage } from '../harness/png.ts';
import { diffImages, renderDiffImage } from '../harness/diff.ts';
import { MAP, bfsPath, legsFromPath } from '../scenarios/04-hedge-maze.spec.ts';
import { HOUSE_EXTERIOR_MASKS } from '../scenarios/01-house.spec.ts';
import { Harness } from '../harness/client.ts';
import { Visual } from '../harness/visual.ts';
import { Report } from '../harness/report.ts';
import { MapiError } from '../../sdk/typescript/src/mapi-client.ts';
import { createRunRoot } from '../harness/supervisor.ts';
import { applyCharter, clearNonPlayerEntities, constructStageFloor,
    holdStageChunkTickets, releaseStageChunkTickets, settleNearbyEntities }
    from '../harness/stage.ts';
import type { SessionTick } from '../harness/context.ts';

test('both CI loaders ship all five house reference PNGs at the profile dimensions', () => {
    const root = fileURLToPath(new URL('../baselines/', import.meta.url));
    for (const loader of ['fabric', 'neoforge']) {
        for (const checkpoint of ['house-se', 'house-sw', 'house-nw', 'house-ne', 'house-interior']) {
            const png = decodePng(fs.readFileSync(path.join(root, `linux-ci-${loader}`, 'house', `${checkpoint}.png`)));
            assert.equal(png.width, 1280, `${loader}/${checkpoint} width`);
            assert.equal(png.height, 720, `${loader}/${checkpoint} height`);
        }
    }
});

test('house masks ignore distant terrain while retaining sensitivity to house changes', () => {
    const file = fileURLToPath(new URL('../baselines/linux-ci-fabric/house/house-se.png', import.meta.url));
    const expected = decodePng(fs.readFileSync(file));
    const repaint = (x: number, y: number) => {
        const changed = { ...expected, data: Buffer.from(expected.data) };
        for (let row = y; row < y + 40; row++) {
            for (let col = x; col < x + 40; col++) {
                changed.data.set([255, 0, 255, 255], 4 * (row * changed.width + col));
            }
        }
        return diffImages(expected, changed, { pixelThreshold: 4, masks: HOUSE_EXTERIOR_MASKS });
    };
    assert.equal(repaint(10, 470).changedFraction, 0);
    assert.equal(repaint(1000, 470).changedFraction, 0);
    assert.ok(repaint(600, 500).changedFraction > 0.001);
});

test('visual baselines require explicit updates and remain unchanged on divergence', async t => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mapi-baselines-'));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    const baselinesDir = path.join(root, 'baselines');
    const outDir = path.join(root, 'out');
    const baseline = path.join(baselinesDir, 'sample.png');
    const png = encodePng({ width: 1, height: 1, data: Buffer.from([10, 20, 30, 255]) });
    const changed = encodePng({ width: 1, height: 1, data: Buffer.from([255, 255, 255, 255]) });
    const h = new Harness('http://127.0.0.1:1', 'test-token');
    const compareReport = new Report('compare', {});
    const visual = new Visual(h, compareReport, {
        scenario: 'sample', baselinesDir, outDir, updateBaselines: false,
    });
    await visual.submitPng({ name: 'sample' }, png);
    assert.equal(compareReport.failed, 1);
    assert.equal(fs.existsSync(baseline), false);
    assert.deepEqual(fs.readFileSync(path.join(outDir, 'sample.actual.png')), png);

    const updateReport = new Report('update', {});
    const update = new Visual(h, updateReport, {
        scenario: 'sample', baselinesDir, outDir, updateBaselines: true,
    });
    await update.submitPng({ name: 'sample' }, png);
    assert.equal(updateReport.failed, 0);
    assert.deepEqual(fs.readFileSync(baseline), png);
    await visual.submitPng({ name: 'sample' }, png);
    assert.equal(compareReport.passed, 1);
    await visual.submitPng({ name: 'sample' }, changed);
    assert.equal(compareReport.failed, 2);
    assert.deepEqual(fs.readFileSync(baseline), png);
    assert.deepEqual(fs.readFileSync(path.join(outDir, 'sample.actual.png')), changed);
    assert.ok(fs.existsSync(path.join(outDir, 'sample.diff.png')));

    await update.submitPng({ name: 'sample' }, changed);
    assert.deepEqual(fs.readFileSync(baseline), changed);
});

test('tick stepping rejects successful jobs with incomplete simulation progress', async () => {
    const originalFetch = globalThis.fetch;
    let completed = 0;
    globalThis.fetch = (async (input: RequestInfo | URL) => {
        const isStep = new URL(String(input)).pathname.endsWith('/ticks/step');
        return new Response(JSON.stringify(isStep ? { jobId: 'step-test' } : {
            state: 'SUCCEEDED', result: { requested: 21, completed, boundary: 99 },
        }), { status: isStep ? 202 : 200,
            headers: { 'Content-Type': 'application/json' } });
    }) as typeof fetch;
    try {
        const h = new Harness('http://127.0.0.1:1', 'test-token');
        await assert.rejects(h.stepTicks('lease', 21), /did not complete 21 simulation ticks/);
        completed = 21;
        const job = await h.stepTicks('lease', 21);
        assert.equal(job.result.completed, 21);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('supervisor creates its isolated run root from a clean build directory', () => {
    const repoRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'mapi-e2e-root-'));
    try {
        const runRoot = createRunRoot(repoRoot, 'fabric');
        assert.equal(path.dirname(runRoot), path.join(repoRoot, 'build', 'e2e'));
        assert.ok(fs.statSync(runRoot).isDirectory());
    } finally {
        fs.rmSync(repoRoot, { recursive: true, force: true });
    }
});

test('HTTP errors retain their route context and MapiError type', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = (async () => new Response(JSON.stringify({
        error: { code: 'SERVER_BUSY', message: 'client thread busy' },
    }), { status: 503, headers: { 'Content-Type': 'application/json' } })) as typeof fetch;
    try {
        const h = new Harness('http://127.0.0.1:1', 'test-token');
        await assert.rejects(h.get('/api/v1/client/screen'), (error: unknown) => {
            assert.ok(error instanceof MapiError);
            assert.equal(error.code, 'SERVER_BUSY');
            assert.match(error.message, /GET \/api\/v1\/client\/screen/);
            return true;
        });
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('GET retries transient fetch failures but POST mutations remain single-shot', async () => {
    const originalFetch = globalThis.fetch;
    let getAttempts = 0;
    let postAttempts = 0;
    globalThis.fetch = (async (_input: RequestInfo | URL, init?: RequestInit) => {
        if (init?.method === 'POST') {
            postAttempts++;
            throw new TypeError('fetch failed');
        }
        getAttempts++;
        if (getAttempts < 3) throw new TypeError('fetch failed');
        return new Response(JSON.stringify({ frame: 3 }), {
            status: 200, headers: { 'Content-Type': 'application/json' },
        });
    }) as typeof fetch;
    try {
        const h = new Harness('http://127.0.0.1:1', 'test-token');
        assert.deepEqual(await h.get('/api/v1/client/screenshots'), { frame: 3 });
        assert.equal(getAttempts, 3);
        await assert.rejects(h.post('/api/v1/server/commands', { command: 'time set noon' }),
            /fetch failed/);
        assert.equal(postAttempts, 1);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('stage cleanup waits for death removal then clears drops and XP', async () => {
    const calls: string[] = [];
    let deathTicks = 0;
    let corpse = false;
    let drops = 0;
    let killCount = 0;
    const h = {
        command: async (command: string) => {
            calls.push(command);
            killCount++;
            if (killCount === 1) corpse = true;
            else drops = 0;
        },
    } as unknown as Harness;
    const tick = {
        stepTicks: async (ticks: number) => {
            calls.push(`step:${ticks}`);
            for (let i = 0; i < ticks; i++) {
                if (corpse && ++deathTicks >= 20) {
                    corpse = false;
                    drops++;
                }
            }
        },
    } as SessionTick;

    await clearNonPlayerEntities(h, tick);

    assert.deepEqual(calls, [
        'kill @e[type=!minecraft:player]', 'step:21',
        'kill @e[type=!minecraft:player]', 'step:1',
    ]);
    assert.ok(deathTicks >= 20, 'vanilla death timer completed');
    assert.equal(corpse, false);
    assert.equal(drops, 0);
});

test('stage chunk tickets preserve prior tickets and release only fixture-owned tickets', async () => {
    const forced = new Set(['0,0']);
    const added: string[] = [];
    const removed: string[] = [];
    const keyAt = (x: number, z: number) => `${Math.floor(x / 16)},${Math.floor(z / 16)}`;
    const h = {
        command: async (command: string) => {
            const [verb, action, xText, zText] = command.split(' ');
            assert.equal(verb, 'forceload');
            const key = keyAt(Number(xText), Number(zText));
            if (action === 'query') {
                if (!forced.has(key)) throw new Error('chunk is not forced');
                return { resultCode: 1 };
            }
            if (action === 'add') {
                forced.add(key);
                added.push(key);
                return { resultCode: 1 };
            }
            if (action === 'remove') {
                forced.delete(key);
                removed.push(key);
                return { resultCode: 1 };
            }
            throw new Error(`unexpected fixture command ${command}`);
        },
    } as unknown as Harness;

    const scope = await holdStageChunkTickets(h, 0, 0);
    assert.deepEqual(scope, { added: 48, preexisting: 1 });
    assert.equal(added.length, 48);
    await releaseStageChunkTickets(h);
    assert.deepEqual(forced, new Set(['0,0']));
    assert.equal(removed.length, 48);
});

test('stage ticket acquisition removes tickets already added when a later add fails', async () => {
    const forced = new Set<string>();
    const keyAt = (x: number, z: number) => `${Math.floor(x / 16)},${Math.floor(z / 16)}`;
    let successfulAdds = 0;
    const removed: string[] = [];
    const h = {
        command: async (command: string) => {
            const [verb, action, xText, zText] = command.split(' ');
            assert.equal(verb, 'forceload');
            const key = keyAt(Number(xText), Number(zText));
            if (action === 'query') {
                if (!forced.has(key)) throw new Error('chunk is not forced');
                return { resultCode: 1 };
            }
            if (action === 'add') {
                if (successfulAdds === 2) throw new Error('ticket cap reached');
                successfulAdds++;
                forced.add(key);
                return { resultCode: 1 };
            }
            if (action === 'remove') {
                forced.delete(key);
                removed.push(key);
                return { resultCode: 1 };
            }
            throw new Error(`unexpected fixture command ${command}`);
        },
    } as unknown as Harness;

    await assert.rejects(holdStageChunkTickets(h, 0, 0), /ticket cap reached/);
    assert.equal(forced.size, 0);
    assert.equal(removed.length, 2);
});

test('stage floor replacement never relies on a no-change fill result', async () => {
    const layers = new Map<number, string>([[62, 'air']]);
    const commands: string[] = [];
    const h = {
        command: async (command: string) => {
            commands.push(command);
            const match = command.match(/^fill -48 (-?\d+) -48 48 -?\d+ 48 (air|stone)$/);
            assert.ok(match, `unexpected floor command: ${command}`);
            const y = Number(match[1]);
            const block = match[2];
            if (block === 'air' && layers.get(y) === 'air') {
                throw new Error('vanilla fill changed no blocks');
            }
            if (block === 'stone') assert.equal(layers.get(y), 'air');
            layers.set(y, block);
            return { success: true, resultCode: 1 };
        },
    } as unknown as Harness;

    await constructStageFloor(h, 0, 0);

    assert.deepEqual([...layers.entries()].sort((a, b) => a[0] - b[0]),
        [[60, 'stone'], [61, 'stone'], [62, 'stone'], [63, 'stone']]);
    assert.equal(commands.length, 8);
});

test('house entity settling cleans late arrivals and requires stable empty reads', async () => {
    const seen: string[][] = [
        ['minecraft:squid'],
        ['minecraft:nautilus'],
        ['minecraft:squid'],
        [], [], [],
    ];
    let queries = 0;
    let killCommands = 0;
    const h = {
        entitiesAround: async () => (seen[queries++] ?? []).map(typeId => ({ typeId })),
        command: async () => { killCommands++; return { resultCode: 1 }; },
    } as unknown as Harness;
    const tick = { stepTicks: async () => ({ milestones: [
        { name: 'stepped', details: { requested: 21, completed: 21 } },
    ] }) } as SessionTick;

    const result = await settleNearbyEntities(h, tick, 0, 64, 0, 32, {
        maxCleanupPasses: 2, stablePolls: 3, pollMs: 0,
    });

    assert.equal(result.stable, true);
    assert.deepEqual(result.entities, []);
    assert.equal(result.cleanupPasses, 2);
    assert.equal(killCommands, 3, 'observed arrivals trigger bounded cleanup commands');
    assert.equal(queries, 7, 'a late arrival resets the consecutive empty observations');
});

test('house entity settling remains failed when entities persist through bounded cleanup', async () => {
    const h = {
        entitiesAround: async () => [{ typeId: 'minecraft:squid' }],
        command: async () => ({ resultCode: 1 }),
    } as unknown as Harness;
    const tick = { stepTicks: async () => ({ milestones: [
        { name: 'stepped', details: { requested: 21, completed: 21 } },
    ] }) } as SessionTick;

    const result = await settleNearbyEntities(h, tick, 0, 64, 0, 32, {
        maxCleanupPasses: 1, stablePolls: 2, pollMs: 0,
    });

    assert.equal(result.stable, false);
    assert.deepEqual(result.entities, [{ typeId: 'minecraft:squid' }]);
    assert.equal(result.cleanupPasses, 1);
});

test('charter requires gamerule application but tolerates idempotent no-ops', async () => {
    const rejectedCommands: string[] = [];
    const rejectedHarness = {
        command: async (command: string) => {
            rejectedCommands.push(command);
            if (command.startsWith('gamerule ')) throw new Error('invalid gamerule');
        },
    } as unknown as Harness;
    await assert.rejects(applyCharter(rejectedHarness, () => {}), /invalid gamerule/);
    assert.ok(rejectedCommands.at(-1)?.startsWith('gamerule '));

    const advisories: string[] = [];
    const idempotentHarness = {
        command: async (command: string) => {
            if (command === 'difficulty peaceful'
                || command === 'kill @e[type=!minecraft:player]') {
                throw new Error('already in requested state');
            }
        },
        dayTime: async () => 6000,
    } as unknown as Harness;
    await applyCharter(idempotentHarness, message => advisories.push(message));
    assert.equal(advisories.length, 2);
});

function image(width: number, height: number,
               fill: [number, number, number, number]): RgbaImage {
    const data = Buffer.alloc(width * height * 4);
    for (let i = 0; i < width * height; i++) {
        data.set(fill, i * 4);
    }
    return { width, height, data };
}

/** Hand-build a color-type-2 (RGB) PNG row to prove the decoder handles it. */
function tinyRgbPng(): Buffer {
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(2, 0);
    ihdr.writeUInt32BE(1, 4);
    ihdr[8] = 8;
    ihdr[9] = 2;
    const raw = Buffer.from([0, 10, 20, 30, 40, 50, 60]); // filter 0, 2 RGB px
    const sig = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
    const crcTable = (() => {
        const t = new Int32Array(256);
        for (let n = 0; n < 256; n++) {
            let c = n;
            for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
            t[n] = c;
        }
        return t;
    })();
    const crc32 = (buf: Buffer) => {
        let crc = 0xffffffff;
        for (const byte of buf) crc = crcTable[(crc ^ byte) & 0xff] ^ (crc >>> 8);
        return (crc ^ 0xffffffff) >>> 0;
    };
    const chunk = (type: string, data: Buffer) => {
        const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
        const out = Buffer.alloc(12 + data.length);
        out.writeUInt32BE(data.length, 0);
        body.copy(out, 4);
        out.writeUInt32BE(crc32(body), 8 + data.length);
        return out;
    };
    return Buffer.concat([
        sig, chunk('IHDR', ihdr),
        chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0)),
    ]);
}

test('png: RGBA encode/decode roundtrip', () => {
    const img = image(3, 2, [10, 128, 250, 255]);
    img.data.set([1, 2, 3, 4], 4); // one distinct pixel
    const decoded = decodePng(encodePng(img));
    assert.equal(decoded.width, 3);
    assert.equal(decoded.height, 2);
    assert.deepEqual(decoded.data, img.data);
});

test('png: decodes RGB (color type 2) into RGBA with alpha 255', () => {
    const decoded = decodePng(tinyRgbPng());
    assert.equal(decoded.width, 2);
    assert.deepEqual([...decoded.data.subarray(0, 8)],
        [10, 20, 30, 255, 40, 50, 60, 255]);
});

test('png: rejects a non-PNG buffer', () => {
    assert.throws(() => decodePng(Buffer.from('not a png at all')), /not a PNG/);
});

test('diff: identical images have zero metrics', () => {
    const a = image(4, 4, [0, 0, 0, 255]);
    const m = diffImages(a, image(4, 4, [0, 0, 0, 255]));
    assert.equal(m.changedPixels, 0);
    assert.equal(m.changedFraction, 0);
    assert.equal(m.maxDelta, 0);
    assert.equal(m.rms, 0);
    assert.equal(m.sizeMismatch, false);
});

test('diff: one pixel above threshold is measured exactly', () => {
    const a = image(4, 4, [100, 100, 100, 255]);
    const b = image(4, 4, [100, 100, 100, 255]);
    b.data.set([110, 100, 100, 255], 4 * (1 * 4 + 2)); // pixel (2,1), delta 10
    const m = diffImages(a, b, { pixelThreshold: 4 });
    assert.equal(m.changedPixels, 1);
    assert.equal(m.changedFraction, 1 / 16);
    assert.equal(m.maxDelta, 10);
    assert.ok(m.rms > 0 && m.rms < 0.02);
});

test('diff: delta at threshold counts as unchanged', () => {
    const a = image(2, 2, [100, 100, 100, 255]);
    const b = image(2, 2, [104, 96, 100, 255]);
    assert.equal(diffImages(a, b, { pixelThreshold: 4 }).changedPixels, 0);
});

test('diff: masked pixels are excluded from comparison', () => {
    const a = image(4, 4, [0, 0, 0, 255]);
    const b = image(4, 4, [255, 255, 255, 255]);
    const m = diffImages(a, b, { masks: [{ x: 0, y: 0, width: 4, height: 4 }] });
    assert.equal(m.maskedPixels, 16);
    assert.equal(m.comparedPixels, 0);
    assert.equal(m.changedPixels, 0);
});

test('diff: size mismatch is a divergence, never silent', () => {
    const m = diffImages(image(4, 4, [0, 0, 0, 255]), image(5, 4, [0, 0, 0, 255]));
    assert.equal(m.sizeMismatch, true);
    assert.equal(m.changedFraction, 1);
});

test('diff: renderDiffImage marks changed red and masked blue', () => {
    const a = image(2, 1, [10, 10, 10, 255]);
    const b = image(2, 1, [200, 10, 10, 255]);
    const out = renderDiffImage(a, b, {
        pixelThreshold: 4, masks: [{ x: 1, y: 0, width: 1, height: 1 }],
    });
    assert.deepEqual([...out.data.subarray(0, 3)], [255, 0, 0]);      // changed
    assert.equal(out.data[4 + 2], 255);                                // masked: blue
});

test('png: cropImage extracts a clamped rectangle', () => {
    const img = image(4, 4, [0, 0, 0, 255]);
    img.data.set([9, 9, 9, 255], 4 * (2 * 4 + 3)); // pixel (3,2)
    const crop = cropImage(img, 2, 1, 4, 3); // clamped to 2x3
    assert.equal(crop.width, 2);
    assert.equal(crop.height, 3);
    assert.deepEqual([...crop.data.subarray(4 * (1 * 2 + 1), 4 * (1 * 2 + 1) + 4)],
        [9, 9, 9, 255]);
});

test('maze: ASCII map is rectangular and has S and E', () => {
    for (const row of MAP) assert.equal(row.length, 11);
    assert.ok(MAP.some(r => r.includes('S')));
    assert.ok(MAP.some(r => r.includes('E')));
});

test('maze: BFS finds a contiguous wall-free path from S to E', () => {
    const path = bfsPath(MAP);
    assert.equal(MAP[path[0].r][path[0].c], 'S');
    assert.equal(MAP[path[path.length - 1].r][path[path.length - 1].c], 'E');
    for (let i = 1; i < path.length; i++) {
        const dr = Math.abs(path[i].r - path[i - 1].r);
        const dc = Math.abs(path[i].c - path[i - 1].c);
        assert.equal(dr + dc, 1, `path not contiguous at ${i}`);
        assert.notEqual(MAP[path[i].r][path[i].c], '#');
    }
});

test('maze: legs group into same-yaw runs within the 64-leg contract', () => {
    const legs = legsFromPath(bfsPath(MAP));
    assert.ok(legs.length > 0 && legs.length <= 64);
    for (let i = 1; i < legs.length; i++) {
        assert.notEqual(legs[i].yaw, legs[i - 1].yaw, 'consecutive legs must turn');
    }
    const cells = legs.reduce((s, l) => s + l.cells, 0);
    assert.equal(cells, bfsPath(MAP).length - 1);
});

test('input mutations acquire and reuse a 300-second control lease', async () => {
    const originalFetch = globalThis.fetch;
    const requests: { path: string; body: any }[] = [];
    let acquisitions = 0;
    globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = new URL(String(input));
        const body = init?.body ? JSON.parse(String(init.body)) : undefined;
        requests.push({ path: url.pathname, body });
        let result: any;
        let status = 200;
        if (url.pathname === '/api/v1/client/control/lease' && body.leaseId) {
            status = 409;
            result = { error: { code: 'LEASE_REQUIRED', message: 'lease expired' } };
        } else if (url.pathname === '/api/v1/client/control/lease') {
            acquisitions++;
            result = {
                leaseId: `input-lease-${acquisitions}`,
                expiresAtEpochMs: Date.now() + (acquisitions === 1 ? 5_000 : 300_000),
            };
        } else {
            result = { accepted: true };
        }
        return new Response(JSON.stringify(result), {
            status, headers: { 'Content-Type': 'application/json' },
        });
    }) as typeof fetch;
    try {
        const h = new Harness('http://127.0.0.1:1', 'test-token');
        await h.holdKey(69, 1);
        await h.clickScreen(10, 20);
        await h.post('/api/v1/client/worlds/load', { levelId: 'world' });
        await h.clickInventory(3);
        await h.moveWaypoints([{ yaw: 90, pitch: 0, ticks: 20 }]);
        await h.captureRenderedTooltip(4);

        const leases = requests.filter(r => r.path === '/api/v1/client/control/lease');
        assert.equal(leases.length, 5);
        assert.deepEqual(leases[0].body, { ttlSeconds: 300 });
        assert.deepEqual(leases[1].body, { ttlSeconds: 300, leaseId: 'input-lease-1' });
        assert.deepEqual(leases[2].body, { ttlSeconds: 300 });
        assert.deepEqual(leases[3].body, { ttlSeconds: 300, leaseId: 'input-lease-2' });
        assert.deepEqual(leases[4].body, { ttlSeconds: 300 });
        const mutations = requests.filter(r => ['/api/v1/client/actions/hold-key',
            '/api/v1/client/actions/click', '/api/v1/client/inventory/click',
            '/api/v1/client/movement/waypoints',
            '/api/v1/client/inventory/tooltip-rendered'].includes(r.path));
        assert.equal(mutations.length, 5);
        assert.deepEqual(mutations.map(r => r.body.leaseId), [
            'input-lease-1', 'input-lease-2', 'input-lease-3', 'input-lease-3', 'input-lease-3',
        ]);
        assert.equal(mutations.filter(r => r.path.endsWith('/hold-key')).length, 1,
            'a failed lease renewal must not replay the input mutation');
        assert.equal(mutations.find(r => r.path.endsWith('/inventory/click'))?.body.executionMode,
            'client-logic');
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('world transitions revalidate and reuse a still-held input lease', async () => {
    const originalFetch = globalThis.fetch;
    const requests: { path: string; body: any }[] = [];
    globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = new URL(String(input));
        const body = init?.body ? JSON.parse(String(init.body)) : undefined;
        requests.push({ path: url.pathname, body });
        let result: any;
        if (url.pathname === '/api/v1/client/control/lease') {
            result = body.leaseId
                ? { leaseId: body.leaseId, expiresAtEpochMs: Date.now() + 300_000 }
                : { leaseId: 'kept-lease', expiresAtEpochMs: Date.now() + 300_000 };
        } else {
            result = { accepted: true };
        }
        return new Response(JSON.stringify(result), {
            status: 200, headers: { 'Content-Type': 'application/json' },
        });
    }) as typeof fetch;
    try {
        const h = new Harness('http://127.0.0.1:1', 'test-token');
        await h.holdKey(69, 1);
        await h.post('/api/v1/client/worlds/load', { levelId: 'world' });
        await h.clickInventory(3);
        const leases = requests.filter(r => r.path === '/api/v1/client/control/lease');
        assert.deepEqual(leases.map(r => r.body), [
            { ttlSeconds: 300 },
            { ttlSeconds: 300, leaseId: 'kept-lease' },
        ]);
        const actions = requests.filter(r => r.path.endsWith('/hold-key')
            || r.path.endsWith('/inventory/click'));
        assert.deepEqual(actions.map(r => r.body.leaseId), ['kept-lease', 'kept-lease']);
    } finally {
        globalThis.fetch = originalFetch;
    }
});
