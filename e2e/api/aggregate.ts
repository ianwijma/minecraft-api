import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { verifyReport } from './report.ts';
const root = path.resolve(process.argv[2] ?? 'build/api-coverage');
function find(directory: string): string[] {
    return fs.existsSync(directory) ? fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry =>
        entry.isDirectory() ? find(path.join(directory, entry.name)) : entry.name === 'coverage.json' ? [path.join(directory, entry.name)] : []) : [];
}
const expected = ['fabric', 'neoforge'].flatMap(loader => ['typescript', 'python', 'java'].map(sdk => `${loader}-${sdk}`));
let reports: any[] = [];
let failure: string | undefined;
try {
    reports = find(root).map(file => JSON.parse(fs.readFileSync(file, 'utf8')));
    assert.deepEqual(reports.map(report => `${report.loader}-${report.sdk}`).sort(), expected.sort(), 'all six unique SDK/loader runs are required');
    assert.equal(new Set(reports.map(report => report.revision)).size, 1, 'mixed revisions are not coverage');
    assert.equal(new Set(reports.map(report => report.contract)).size, 1, 'mixed contracts are not coverage');
    if (process.env.GITHUB_SHA) for (const report of reports) {
        assert.equal(report.revision, process.env.GITHUB_SHA, 'coverage must belong to the current CI revision');
    }
    for (const report of reports) verifyReport(report);
    console.log('Complete API coverage: all six required corpora passed');
} catch (error) { failure = String(error); console.error(failure); process.exitCode = 1; }
finally {
    fs.mkdirSync(root, { recursive: true });
    fs.writeFileSync(path.join(root, 'matrix.json'), JSON.stringify({ status: failure ? 'failed' : 'passed', failure,
        expectedRevision: process.env.GITHUB_SHA, missing: expected.filter(key => !reports.some(report => `${report.loader}-${report.sdk}` === key)),
        reports: reports.map(({ loader, sdk, status, revision, evidence, artifactSha256 }) =>
            ({ loader, sdk, status, revision, artifactSha256, operations: evidence?.filter((e: any) => e.outcome === 'passed') ?? [] })) }, null, 2));
}
