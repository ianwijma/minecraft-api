import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { spawnSync } from 'node:child_process';
import { reserveOutput } from '../api/output.ts';
import { Suite } from '../api/suite.ts';
import { awaitServerStatusReady, metadata, settleFixtureWorld } from '../api/corpus.ts';
import { manifest } from '../api/manifest.ts';
import { loadContract, missingCoverage, validateSchema } from '../api/coverage.ts';
import { verifyFixture, verifyReport } from '../api/report.ts';

const contract = loadContract();
test('failed transport probes remain in the request trace', async () => {
    const suite = new Suite({ request: async () => { throw new Error('deliberate transport deadline'); },
        close() {}, subscribe: async () => { throw new Error('unused'); } }, contract, 'menu', '/tmp');
    await assert.rejects(suite.outcome('getHealth'), /transport deadline/);
    assert.equal(suite.trace.length, 1);
    assert.equal(suite.trace[0].operationId, 'getHealth');
    assert.match(suite.trace[0].error!, /transport deadline/);
    assert.equal(suite.trace[0].status, undefined);
});
test('GET progress probes record busy conditions without crediting them or repeating mutations', async () => {
    const replies = [{ status: 503, body: { error: { code: 'SERVER_BUSY' } } },
        { status: 200, body: { protocolVersion: 1, status: 'ok' } }];
    let calls = 0;
    const suite = new Suite({ request: async () => { calls++; return replies.shift()!; },
        close() {}, subscribe: async () => { throw new Error('unused'); } }, contract, 'menu', '/tmp');
    await suite.case('getHealth', async () => {
        const result = await suite.pollRead('getHealth', {}, value => value.status === 'ok', 'healthy progress');
        suite.expect(result.status === 'ok', 'actual successful response observed');
    });
    assert.equal(calls, 2);
    assert.deepEqual(suite.evidence[0].requestIds, [2]);
    assert.match(suite.trace[0].expectedCondition!, /server busy/);
    await assert.rejects(suite.pollRead('requestShutdown', {}, () => true, 'invalid mutation poll'), /never repeat mutations/);
    assert.equal(calls, 2);
});
test('progress errors and deadlines remain first-attempt failures', async () => {
    for (const code of ['CAPABILITY_UNAVAILABLE', 'SERVER_BUSY']) {
        const suite = new Suite({ request: async () => ({ status: 503, body: { error: { code } } }),
            close() {}, subscribe: async () => { throw new Error('unused'); } }, contract, 'menu', '/tmp');
        await assert.rejects(suite.case('getHealth', async () => {
            await suite.pollRead('getHealth', {}, () => true, 'uncompleted progress', 10);
            suite.expect(true, 'must not be reached');
        }));
        assert.equal(suite.evidence[0].outcome, 'failed');
        assert.deepEqual(suite.evidence[0].requestIds, []);
        assert.ok(suite.evidence[0].detail?.includes(code === 'SERVER_BUSY' ? 'deadline' : 'progress probe'));
    }
});
test('fixture readiness cannot use busy or stopped clocks as progress', async () => {
    const results = [
        { status: 200, body: { available: true, frozen: false, sprinting: false, tickCount: 10 } },
        { status: 503, body: { error: { code: 'SERVER_BUSY' } } },
        { status: 200, body: { available: true, frozen: false, sprinting: false, tickCount: 49 } },
        { status: 200, body: { available: true, frozen: false, sprinting: false, tickCount: 89 } },
    ];
    let reads = 0;
    const chunks: string[] = [];
    const fake = {
        pollRead: async (id: string, parameters: any) => {
            assert.equal(id, 'queryBlock');
            chunks.push(`${parameters.x},${parameters.z}`);
            return { blockId: 'minecraft:air' };
        },
        outcome: async () => { reads++; return results.shift(); },
        poll: async (read: any, ready: any) => {
            for (let i = 0; i < 10; i++) { const value = await read(); if (ready(value)) return value; }
            throw new Error('readiness deadline');
        },
    };
    await settleFixtureWorld(fake as any);
    assert.equal(new Set(chunks).size, 9);
    assert.equal(reads, 4, 'busy readiness probe resets the normal-tick progress window');
    fake.outcome = async () => ({ status: 200, body: { available: true, frozen: true, tickCount: 1000 } }) as any;
    await assert.rejects(settleFixtureWorld(fake as any), /normal ticking/);
    fake.outcome = async () => ({ status: 503, body: { error: { code: 'CAPABILITY_UNAVAILABLE' } } }) as any;
    await assert.rejects(settleFixtureWorld(fake as any), /fixture readiness/);
});
test('integrated status readiness records busy probes separately from coverage', async () => {
    const replies = [
        { status: 503, body: { error: { code: 'SERVER_BUSY', message: 'busy' } } },
        { status: 200, body: { protocolVersion: 1, running: true, capturedAtEpochMs: 1,
            startedAtEpochMs: 1, uptimeMs: 0, playerCount: 1, maxPlayers: 8, tickCount: 1,
            averageTickTimeMs: 50, motd: '' } },
    ];
    let calls = 0;
    const suite = new Suite({ request: async () => { calls++; return replies.shift()!; },
        close() {}, subscribe: async () => { throw new Error('unused'); } }, contract, 'integrated', '/tmp');
    await awaitServerStatusReady(suite);
    assert.equal(calls, 2);
    assert.equal(suite.trace[0].status, 503);
    assert.match(suite.trace[0].expectedCondition!, /status snapshot readiness/);
    assert.equal(suite.trace[1].status, 200);
    assert.deepEqual(suite.evidence, [], 'readiness probes do not create coverage evidence');
});
test('integrated status readiness fails on non-busy errors and bounded timeout', async () => {
    let calls = 0;
    const forbidden = new Suite({ request: async () => {
        calls++;
        return { status: 401, body: { error: { code: 'UNAUTHORIZED', message: 'bad token' } } };
    }, close() {}, subscribe: async () => { throw new Error('unused'); } }, contract, 'integrated', '/tmp');
    await assert.rejects(awaitServerStatusReady(forbidden, 100), /server status readiness/);
    assert.equal(calls, 1, 'unexpected errors fail immediately');

    calls = 0;
    const busy = new Suite({ request: async () => {
        calls++;
        return { status: 503, body: { error: { code: 'SERVER_BUSY', message: 'busy' } } };
    }, close() {}, subscribe: async () => { throw new Error('unused'); } }, contract, 'integrated', '/tmp');
    await assert.rejects(awaitServerStatusReady(busy, 1), /exceeded deadline/);
    assert.equal(calls, 1, 'busy retries stop at the readiness deadline');
    assert.equal(busy.evidence.length, 0);
});
test('diagnostic reruns preserve first-attempt evidence', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mapi-first-attempt-'));
    const output = path.join(root, 'fabric-java');
    try {
        reserveOutput(output);
        fs.writeFileSync(path.join(output, 'coverage.json'), 'original failure');
        assert.throws(() => reserveOutput(output), /first-attempt/);
        assert.equal(fs.readFileSync(path.join(output, 'coverage.json'), 'utf8'), 'original failure');
    } finally { fs.rmSync(root, { recursive: true, force: true }); }
});
test('missing corpora fail with a diagnostic matrix artifact', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mapi-missing-corpora-'));
    try {
        const result = spawnSync(process.execPath, ['--experimental-strip-types', '--no-warnings',
            new URL('../api/aggregate.ts', import.meta.url).pathname, root], { encoding: 'utf8' });
        assert.equal(result.status, 1);
        const matrix = JSON.parse(fs.readFileSync(path.join(root, 'matrix.json'), 'utf8'));
        assert.equal(matrix.status, 'failed');
        assert.equal(matrix.missing.length, 6);
    } finally { fs.rmSync(root, { recursive: true, force: true }); }
});
test('every operation has an executable live case', () => {
    const sources = ['corpus.ts', 'run.ts'].map(file => fs.readFileSync(new URL(`../api/${file}`, import.meta.url), 'utf8')).join('\n');
    const cases = [...sources.matchAll(/\.case\('([^']+)'/g)].map(match => match[1]);
    assert.deepEqual([...new Set(cases)].sort(), Object.keys(manifest).sort());
});
test('boolean const discriminates the actual server-status union', () => {
    const schema = contract.paths['/api/v1/server/status'].get.responses['200'].content['application/json'].schema;
    validateSchema(contract, schema, { protocolVersion: 1, running: false });
    assert.throws(() => validateSchema(contract, schema, { protocolVersion: 1, running: true }));
});
test('wrong packaged JAR identity fails the corpus before earning info coverage', async () => {
    const repo = fs.mkdtempSync(path.join(os.tmpdir(), 'mapi-jar-fault-'));
    fs.mkdirSync(path.join(repo, 'fabric/build/libs'), { recursive: true });
    fs.writeFileSync(path.join(repo, 'fabric/build/libs/minecraft-api-fabric-test.jar'), 'deliberately wrong artifact');
    const suite = new Suite({ request: async operation => ({ status: 200, body: operation.operationId === 'getHealth'
        ? { protocolVersion: 1, status: 'ok' }
        : { protocolVersion: 1, name: 'mapi', version: 'fixture', apiVersion: 'fixture', platform: 'fabric', platformVersion: 'fixture', minecraftVersion: '26.2', runtimeArtifact: { kind: 'jar', sha256: '0'.repeat(64) } } }), close() {},
        subscribe: async () => { throw new Error('unused'); } }, contract, 'menu', '/tmp');
    try { await assert.rejects(metadata(suite, repo, 'fabric'), /digest matches/); }
    finally { fs.rmSync(repo, { recursive: true, force: true }); }
    assert.equal(suite.evidence.find(e => e.operationId === 'getInfo')?.outcome, 'failed');
    assert.ok(missingCoverage(manifest, suite.evidence).includes('getInfo@menu'));
});
test('a broken observed effect remains failed even if its request succeeded', async () => {
    const suite = new Suite({ request: async () => ({ status: 200, body: { protocolVersion: 1, status: 'ok' } }),
        close() {}, subscribe: async () => { throw new Error('unused'); } }, contract, 'menu', '/tmp');
    await assert.rejects(suite.case('getHealth', async () => {
        await suite.request('getHealth');
        suite.expect(false, 'deliberately broken fixture effect');
    }));
    assert.equal(suite.evidence[0].outcome, 'failed');
    assert.equal(suite.evidence[0].requestIds.length, 1);
});
test('aggregate refuses skipped or filtered reports and missing Java effects', () => {
    assert.throws(() => verifyReport({ version: 1, full: false }));
    assert.throws(() => verifyReport({ version: 1, full: true, status: 'skipped' }));
    assert.throws(() => verifyFixture({ failures: [], checks: [], starts: 1, stops: 1, snapshots: 1 }));
});

test('omitted operation results and error-only requests cannot earn success coverage', async () => {
    const suite = new Suite({ request: async () => ({ status: 401, body: { error: { code: 'UNAUTHORIZED' } } }),
        close() {}, subscribe: async () => { throw new Error('unused'); } }, contract, 'menu', '/tmp');
    await assert.rejects(suite.case('getHealth', async () => suite.expect(true, 'assertion without an API result')));
    await assert.rejects(suite.case('getHealth', async () => {
        await suite.request('getHealth', {}, {}, 401);
        suite.expect(true, 'expected error observed');
    }));
    assert.ok(missingCoverage(manifest, suite.evidence).includes('getHealth@menu'));
});
