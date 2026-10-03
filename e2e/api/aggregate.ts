import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { verifyReport } from './report.ts';
const root = path.resolve(process.argv[2] ?? 'build/api-coverage');
function find(directory: string): string[] {
    return fs.existsSync(directory) ? fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry =>
        entry.isDirectory() ? find(path.join(directory, entry.name)) : entry.name === 'coverage.json' ? [path.join(directory, entry.name)] : []) : [];
}
try {
    const reports = find(root).map(file => JSON.parse(fs.readFileSync(file, 'utf8')));
    const expected = ['fabric', 'neoforge'].flatMap(loader => ['typescript', 'python', 'java'].map(sdk => `${loader}-${sdk}`));
    assert.deepEqual(reports.map(report => `${report.loader}-${report.sdk}`).sort(), expected.sort(), 'all six unique SDK/loader runs are required');
    assert.equal(new Set(reports.map(report => report.revision)).size, 1, 'mixed revisions are not coverage');
    assert.equal(new Set(reports.map(report => report.contract)).size, 1, 'mixed contracts are not coverage');
    for (const report of reports) verifyReport(report);
    fs.writeFileSync(path.join(root, 'matrix.json'), JSON.stringify(reports.map(({ loader, sdk, evidence, artifactSha256 }) =>
        ({ loader, sdk, artifactSha256, operations: evidence.filter((e: any) => e.outcome === 'passed') })), null, 2));
    console.log('Complete API coverage: all six required corpora passed');
} catch (error) { console.error(String(error)); process.exitCode = 1; }
