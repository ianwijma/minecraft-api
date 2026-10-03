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
import { deflateSync } from 'node:zlib';
import { decodePng, encodePng, cropImage, type RgbaImage } from '../harness/png.ts';
import { diffImages, renderDiffImage } from '../harness/diff.ts';
import { MAP, bfsPath, legsFromPath } from '../scenarios/04-hedge-maze.spec.ts';
import { Harness } from '../harness/client.ts';
import { createRunRoot } from '../harness/supervisor.ts';

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
