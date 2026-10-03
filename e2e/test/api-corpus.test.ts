import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { Suite } from '../api/suite.ts';
import { metadata } from '../api/corpus.ts';
import { manifest } from '../api/manifest.ts';
import { loadContract, missingCoverage, validateSchema } from '../api/coverage.ts';
import { verifyFixture, verifyReport } from '../api/report.ts';

const contract = loadContract();
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
