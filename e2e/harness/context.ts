/** Shared per-run context handed to every scenario. */
import type { Harness } from './client.ts';
import type { Report } from './report.ts';

/**
 * The one tick-control lease of the whole session (spec §5: exclusive).
 * The runner acquires it once and freezes everything; scenarios that
 * simulate (2, 5, 8) unfreeze/step through this and MUST re-freeze on exit.
 */
export interface SessionTick {
    leaseId: string;
    /** Total ticks stepped through this lease (daytime advances by this
     *  much under a true doDaylightCycle — use for expectedDayTime math). */
    readonly stepped: number;
    freeze(): Promise<void>;
    unfreeze(): Promise<void>;
    stepTicks(ticks: number): Promise<any>;
}

export interface Ctx {
    h: Harness;
    report: Report;
    envId: string;
    updateBaselines: boolean;
    tick: SessionTick;
    baselinesDirFor(scenario: string): string;
    outDirFor(scenario: string): string;
}

export interface Scenario {
    name: string;
    run(ctx: Ctx): Promise<void>;
}
