import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import { assertComplete, loadContract, inventory, validateManifest } from './coverage.ts';
import { manifest } from './manifest.ts';

export function verifyFixture(report: any, minimumCycles = 1): void {
    const members = JSON.parse(fs.readFileSync(new URL('./java-members.json', import.meta.url), 'utf8'));
    assert.deepEqual(report.failures, [], 'Java consumer retained a failure');
    for (const [member, kind] of Object.entries(members)) {
        if (kind === 'live') assert.ok(report.checks.includes(member), `Java consumer missed ${member}`);
    }
    assert.ok(report.starts >= minimumCycles && report.snapshots > 0, 'Java consumer missed world lifecycle');
    assert.equal(report.stops, report.starts, 'Java consumer lifecycle callbacks must balance after exit');
}

export function verifyReport(report: any): void {
    validateManifest(inventory(loadContract()), manifest);
    assert.equal(report.version, 1);
    const canonical = JSON.parse(fs.readFileSync(new URL('./contract.json', import.meta.url), 'utf8'));
    assert.equal(report.contract, canonical.sourceSha256, 'report uses a stale contract');
    assert.equal(report.full, true, 'filtered runs cannot satisfy the CI gate');
    assert.equal(report.status, 'passed', report.firstFailure ?? 'run did not pass');
    assertComplete(manifest, report.evidence);
    assert.ok(report.artifactSha256?.match(/^[a-f0-9]{64}$/), 'missing packaged artifact digest');
    for (const evidence of report.evidence) {
        if (evidence.outcome !== 'passed') continue;
        assert.ok(evidence.requestIds.some((id: number) => report.trace.some((trace: any) =>
            trace.id === id && trace.operationId === evidence.operationId && trace.environment === evidence.environment
            && trace.status >= 200 && trace.status < 300 && !trace.error)), 'evidence has no successful matching request');
    }
    for (const environment of ['client', 'server']) {
        verifyFixture(report.fixtures[environment], environment === 'client' ? 2 : 1);
    }
}

export function junit(report: any): string {
    const escape = (value: any) => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&apos;');
    const cases = report.evidence.map((e: any) => `<testcase classname="${escape(report.loader + '.' + report.sdk + '.' + e.environment)}" name="${escape(e.operationId + '.' + e.caseId)}">`
        + (e.outcome === 'failed' ? `<failure message="${escape(e.detail)}"/>` : '') + '</testcase>');
    if (report.status !== 'passed') cases.push(`<testcase name="complete-coverage"><failure message="${escape(report.firstFailure)}"/></testcase>`);
    return `<?xml version="1.0" encoding="UTF-8"?><testsuite name="api-coverage" tests="${cases.length}" failures="${report.evidence.filter((e: any) => e.outcome === 'failed').length + (report.status === 'passed' ? 0 : 1)}">${cases.join('')}</testsuite>\n`;
}
