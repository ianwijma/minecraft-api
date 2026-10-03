/**
 * Machine-readable run report (plan §6): the single artifact an agent reads
 * to know pass/fail. Console output mirrors it for humans.
 */
import * as fs from 'node:fs';
import * as path from 'node:path';

export interface CheckResult {
    scenario: string;
    check: string;
    ok: boolean;
    detail: string;
}

export class Report {
    readonly runId: string;
    private readonly checks: CheckResult[] = [];
    private readonly checkpoints: Record<string, unknown>[] = [];
    private readonly advisories: string[] = [];
    private scenario = '';
    private readonly meta: Record<string, unknown>;

    constructor(runId: string, meta: Record<string, unknown>) {
        this.runId = runId;
        this.meta = meta;
    }

    setScenario(name: string): void {
        this.scenario = name;
    }

    record(check: string, ok: boolean, detail: string): boolean {
        this.checks.push({ scenario: this.scenario, check, ok, detail });
        console.log(`  ${ok ? '✓' : '✗'} ${check}: ${detail}`);
        return ok;
    }

    checkpoint(entry: Record<string, unknown>): void {
        this.checkpoints.push({ scenario: this.scenario, ...entry });
    }

    /** Non-fatal finding, e.g. an unpinned 📐 value observed but not asserted. */
    advisory(message: string): void {
        this.advisories.push(this.scenario ? `${this.scenario}: ${message}` : message);
        console.log(`  ! advisory: ${message}`);
    }

    get passed(): number {
        return this.checks.filter(c => c.ok).length;
    }

    get failed(): number {
        return this.checks.filter(c => !c.ok).length;
    }

    /** Assert helper: throws on failure after recording. */
    expect(condition: boolean, check: string, detail: string): void {
        this.record(check, condition, detail);
        if (!condition) throw new Error(`${check}: ${detail}`);
    }

    /** Assert only when the expected value has been pinned; otherwise advisory. */
    expectPinned(check: string, pinned: number | null, actual: number): void {
        if (pinned === null) {
            this.advisory(`${check}: unpinned, observed ${actual} — pin after review`);
            return;
        }
        this.expect(pinned === actual, check, `expected ${pinned}, got ${actual}`);
    }

    write(outDir: string): string {
        fs.mkdirSync(outDir, { recursive: true });
        const file = path.join(outDir, 'report.json');
        const body = {
            runId: this.runId,
            timestamp: new Date().toISOString(),
            ...this.meta,
            total: this.checks.length,
            passed: this.passed,
            failed: this.failed,
            advisories: this.advisories,
            checkpoints: this.checkpoints,
            firstFailure: this.checks.find(check => !check.ok) ?? null,
            checks: this.checks,
        };
        fs.writeFileSync(file, JSON.stringify(body, null, 2));
        console.log(`\n  report: ${file}`);
        return file;
    }
}

export function assert(condition: boolean, message: string): void {
    if (!condition) throw new Error(message);
}

export const sleep = (ms: number): Promise<void> =>
    new Promise<void>(r => setTimeout(r, ms));
