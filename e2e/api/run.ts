import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { loadContract, missingCoverage, assertComplete } from './coverage.ts';
import { manifest } from './manifest.ts';
import { createAdapter } from './factory.ts';
import { Suite } from './suite.ts';
import { metadata, readiness, prepareWorld, serverCorpus, clientCorpus } from './corpus.ts';
import { startDedicated, startApiClient, cleanup, waitForExit, artifactHash, type GameProcess } from './supervisor.ts';
import { verifyFixture, verifyReport, junit } from './report.ts';
import type { Sdk } from './adapters.ts';
import { reserveOutput } from './output.ts';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
function option(name: string, fallback: string): string {
    const index = process.argv.indexOf(`--${name}`);
    return index < 0 ? fallback : process.argv[index + 1];
}
const loaders = option('loader', 'all') === 'all' ? ['fabric', 'neoforge'] : [option('loader', 'all')];
const sdks = option('sdk', 'all') === 'all' ? ['typescript', 'python', 'java'] : [option('sdk', 'all')];
const selected = option('operation', '').split(',').filter(Boolean);
assert.ok(loaders.every(loader => ['fabric', 'neoforge'].includes(loader)), 'invalid loader');
assert.ok(sdks.every(sdk => ['typescript', 'python', 'java'].includes(sdk)), 'invalid SDK');
assert.ok(selected.every(id => id in manifest), 'unknown operation filter');
const output = path.resolve(option('out', 'build/api-coverage'));
const contract = loadContract();
let failed = false;

for (const loader of loaders as ('fabric' | 'neoforge')[]) for (const sdk of sdks as Sdk[]) {
    const outDir = path.join(output, `${loader}-${sdk}`);
    reserveOutput(outDir);
    const suites: Suite[] = [];
    const games: GameProcess[] = [];
    const fixtures: Record<string, any> = {};
    const report: any = { version: 1, full: selected.length === 0, loader, sdk, status: 'failed',
        revision: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repoRoot, encoding: 'utf8' }).trim(),
        contract: JSON.parse(fs.readFileSync(new URL('./contract.json', import.meta.url), 'utf8')).sourceSha256,
        selectedOperations: selected, startedAt: new Date().toISOString(), evidence: [], trace: [], fixtures };
    const cleanupRequests: any[] = [];
    const collectEvidence = () => {
        let offset = 0;
        report.evidence = suites.flatMap(suite => {
            const result = suite.evidence.map(e => ({ ...e, requestIds: e.requestIds.map(id => id + offset) }));
            offset += suite.trace.length;
            return result;
        });
        offset = 0;
        report.trace = suites.flatMap(suite => {
            const result = suite.trace.map(trace => ({ ...trace, id: trace.id + offset, process: suite.outDir }));
            offset += suite.trace.length;
            return result;
        });
        report.trace.push(...cleanupRequests.map((trace, index) => ({ ...trace, id: offset + index + 1 })));
    };
    const checkFixture = async (game: GameProcess) => {
        const fixture = JSON.parse(fs.readFileSync(game.fixtureReport, 'utf8'));
        const kind = game.gamePort ? 'server' : 'client';
        verifyFixture(fixture, kind === 'client' ? 2 : 1);
        fixtures[kind] = fixture;
    };
    console.log(`API corpus: ${loader} / ${sdk}`);
    try {
        if (process.env.MAPI_ACCEPT_EULA !== 'true') throw new Error('Operator prerequisite missing: MAPI_ACCEPT_EULA=true');
        report.artifactSha256 = artifactHash(repoRoot, loader);
        const server = await startDedicated(repoRoot, loader, path.join(outDir, 'server'));
        games.push(server);
        const dedicated = new Suite(createAdapter(sdk, server.base, server.token, repoRoot), contract, 'dedicated', path.join(outDir, 'server'));
        dedicated.selectedOperations = selected;
        suites.push(dedicated);
        await readiness(dedicated, server);
        await dedicated.poll(() => dedicated.request('getWorldInfo'), world => world.phase === 'ACTIVE', 'dedicated world ready', 300_000);
        await metadata(dedicated, repoRoot, loader);
        await prepareWorld(dedicated, false);
        const tickLease = await serverCorpus(dedicated, false);
        const input = await dedicated.request('acquireClientControlLease', {}, { ttlSeconds: 300 });
        const inputLease = input.leaseId;
        const unsupported: [string, any, any][] = [
            ...['getWindowState', 'captureScreenshot', 'inspectInventory', 'inspectScreen', 'listWorlds'].map(id => [id, {}, {}] as [string, any, any]),
            ['getTooltip', { slot: 0 }, {}],
            ['holdKey', {}, { keyCode: 69, ticks: 1, leaseId: inputLease }],
            ['setWindowed', {}, { width: 800, height: 600 }],
            ['setFullscreen', {}, { fullscreen: true }],
            ['setGuiScale', {}, { guiScale: 2 }],
            ['moveWaypoints', {}, { leaseId: inputLease, waypoints: [{ yaw: 0, pitch: 0, ticks: 1 }] }],
            ['captureRenderedTooltip', {}, { slot: 0, leaseId: inputLease }],
            ['clickInventory', {}, { slot: 0, executionMode: 'client-logic', leaseId: inputLease }],
            ['clickScreen', {}, { x: 0, y: 0, leaseId: inputLease }],
            ['createWorld', {}, { levelId: 'unsupported-fixture', seed: 20260919 }],
            ['loadWorld', {}, { levelId: 'unsupported-fixture' }],
            ['deleteWorld', {}, { levelId: 'unsupported-fixture', confirm: true }],
            ['connectServer', {}, { address: '127.0.0.1:25565', leaseId: inputLease }],
            ['publishLan', {}, { port: 25565, leaseId: tickLease }],
            ['unpublishLan', {}, { leaseId: tickLease }],
        ];
        for (const [id, parameters, body] of unsupported) {
            const result = await dedicated.request(id, parameters, body, 503);
            assert.equal(result.error.code, 'CAPABILITY_UNAVAILABLE');
            dedicated.evidence.push({ operationId: id, environment: 'dedicated', caseId: 'client-capability-rejection',
                outcome: 'capability-rejected', assertions: ['dedicated process rejects client capability'], requestIds: [dedicated.trace.length] });
        }
        const client = await startApiClient(repoRoot, loader, path.join(outDir, 'client'), server);
        games.push(client);
        const consumer = new Suite(createAdapter(sdk, client.base, client.token, repoRoot), contract, 'menu', path.join(outDir, 'client'));
        consumer.selectedOperations = selected;
        suites.push(consumer);
        await readiness(consumer, client);
        await metadata(consumer, repoRoot, loader);
        await clientCorpus(consumer, dedicated, client, server, checkFixture);
        await dedicated.case('requestShutdown', async () => {
            const response = await dedicated.request('requestShutdown');
            await waitForExit(server);
            await checkFixture(server);
            dedicated.expect(response.accepted === true, 'dedicated shutdown exits gracefully and balances Java consumer callbacks');
        });
        collectEvidence();
        assertComplete(selected.length ? Object.fromEntries(Object.entries(manifest).filter(([id]) => selected.includes(id))) : manifest, report.evidence);
        report.status = 'passed';
        if (report.full) verifyReport(report);
    } catch (error) {
        report.status = 'failed';
        report.firstFailure = String(error);
        failed = true;
        console.error(report.firstFailure);
        collectEvidence();
        const client = suites.find(suite => suite.environment !== 'dedicated');
        if (client) try {
            const capture = await client.outcome('captureScreenshot');
            if (capture.status === 200) client.savePng(capture.body, 'failure');
        } catch { /* diagnostic capture cannot replace the original failure */ }
    } finally {
        for (const suite of suites) suite.adapter.close();
        for (const game of games) {
            try { await cleanup(game, result => cleanupRequests.push({ ...result, operationId: 'requestShutdown',
                environment: suites.find(suite => suite.outDir === path.dirname(game.logFile))?.environment ?? 'menu',
                transport: 'supervisor-cleanup', body: {}, process: path.dirname(game.logFile) })); } catch (error) {
                report.cleanupFailure = String(error); report.status = 'failed'; failed = true;
            }
        }
        collectEvidence();
        report.finishedAt = new Date().toISOString();
        report.missing = missingCoverage(manifest, report.evidence);
        let serialized = JSON.stringify(report, null, 2);
        for (const game of games) {
            serialized = serialized.replaceAll(game.token, '[REDACTED]');
            for (const filename of [game.logFile, path.join(game.runDir, 'logs/latest.log')]) {
                if (fs.existsSync(filename)) {
                    const redacted = fs.readFileSync(filename, 'utf8').replaceAll(game.token, '[REDACTED]');
                    fs.writeFileSync(filename, redacted);
                    if (filename !== game.logFile) fs.writeFileSync(path.join(path.dirname(game.logFile), 'latest.log'), redacted);
                }
            }
        }
        fs.writeFileSync(path.join(outDir, 'coverage.json'), serialized + '\n');
        fs.writeFileSync(path.join(outDir, 'junit.xml'), junit(JSON.parse(serialized)));
    }
}
if (failed) process.exitCode = 1;
