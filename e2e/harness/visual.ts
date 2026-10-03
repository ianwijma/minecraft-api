/**
 * The checkpoint protocol (plan §3.3) and baseline/pair diffing (§3.4).
 * All pass/fail ends up in the Report; PNG artifacts are for humans only.
 */
import * as fs from 'node:fs';
import * as path from 'node:path';
import { Harness } from './client.ts';
import type { Capture } from './client.ts';
import { Report, sleep } from './report.ts';
import { decodePng, encodePng, cropImage, type RgbaImage } from './png.ts';
import { diffImages, renderDiffImage, type Mask } from './diff.ts';

export interface CheckpointSpec {
    name: string;
    masks?: Mask[];
    pixelThreshold?: number;
    maxChangedFraction?: number;
    maxRms?: number;
    /** §1.1 rule 3: capture fails before settling unless daytime matches. */
    expectedDayTime?: number;
}

export interface VisualOptions {
    scenario: string;
    baselinesDir: string;
    outDir: string;
    updateBaselines: boolean;
}

export class Visual {
    private readonly h: Harness;
    private readonly report: Report;
    private readonly opts: VisualOptions;
    private readonly captures = new Map<string, Capture>();
    private lastFrame = -1;

    constructor(h: Harness, report: Report, opts: VisualOptions) {
        this.h = h;
        this.report = report;
        this.opts = opts;
    }

    /**
     * §3.3 step 3: consecutive captures must agree within the renderer's own
     * shimmer budget — measured live: ≤0.05% of pixels and maxDelta ≤24/255.
     * Stricter fails on sub-visual AA/lighting shimmer; looser hides real
     * scene changes (chunk pop-in, toasts, animations).
     */
    async settle(): Promise<Capture> {
        let last: { a: Capture; b: Capture; m: import('./diff.ts').DiffMetrics } | null = null;
        for (let attempt = 1; attempt <= 6; attempt++) {
            const a = await this.h.screenshot();
            await sleep(250);
            const b = await this.h.screenshot();
            const m = diffImages(decodePng(a.png), decodePng(b.png),
                { pixelThreshold: 4 });
            if (m.changedFraction <= 0.0005 && m.maxDelta <= 24) return b;
            last = { a, b, m };
            await sleep(1000);
        }
        if (last) {
            fs.mkdirSync(this.opts.outDir, { recursive: true });
            for (const [suffix, cap] of [['unstable-a', last.a], ['unstable-b', last.b]] as const) {
                fs.writeFileSync(path.join(this.opts.outDir, `${suffix}.png`), cap.png);
            }
            this.report.advisory(
                `settle drift: ${last.m.changedPixels} px (${(last.m.changedFraction * 100).toFixed(4)}%), `
                + `maxDelta ${last.m.maxDelta}, rms ${last.m.rms.toFixed(5)} — frames dumped to outDir`);
        }
        throw new Error('scene not stable: consecutive captures differ');
    }

    async capture(spec: CheckpointSpec): Promise<Capture> {
        if (spec.expectedDayTime !== undefined) {
            const actual = await this.h.dayTime();
            this.report.expect(actual === spec.expectedDayTime,
                `daytime pinned @${spec.name}`,
                `expected ${spec.expectedDayTime}, world reads ${actual}`);
        }
        const c = await this.settle();
        this.lastFrame = c.frame;
        this.captures.set(spec.name, c);
        await this.comparePng(spec, c.png, {
            frame: c.frame, screenId: c.screenId, guiScale: c.guiScale,
        });
        return c;
    }

    /** Baseline-compare a PNG produced elsewhere (e.g. tooltip-rendered). */
    async submitPng(spec: CheckpointSpec, png: Buffer,
                    meta: Record<string, unknown> = {}): Promise<void> {
        await this.comparePng(spec, png, meta);
    }

    get(name: string): Capture {
        const c = this.captures.get(name);
        if (!c) throw new Error(`no capture named ${name}`);
        return c;
    }

    private async comparePng(spec: CheckpointSpec, png: Buffer,
                             meta: Record<string, unknown>): Promise<void> {
        const dir = this.opts.baselinesDir;
        const file = path.join(dir, `${spec.name}.png`);
        const entry = {
            name: spec.name, baseline: file, ...meta,
            masks: spec.masks ?? [],
        };
        if (this.opts.updateBaselines || !fs.existsSync(file)) {
            fs.mkdirSync(dir, { recursive: true });
            fs.writeFileSync(file, png);
            this.report.advisory(
                `baseline written: ${file} (${this.opts.updateBaselines ? 'update' : 'first run'})`);
            this.report.checkpoint({ ...entry, verdict: 'BASELINED' });
            return;
        }
        const expected = decodePng(fs.readFileSync(file));
        const actual = decodePng(png);
        const opts = {
            pixelThreshold: spec.pixelThreshold ?? 4,
            masks: spec.masks ?? [],
        };
        const m = diffImages(expected, actual, opts);
        const maxFrac = spec.maxChangedFraction ?? 0.001;
        const maxRms = spec.maxRms ?? 0.01;
        const ok = !m.sizeMismatch && m.changedFraction <= maxFrac && m.rms <= maxRms;
        this.report.checkpoint({
            ...entry, verdict: ok ? 'PASS' : 'FAIL',
            rms: m.rms, maxDelta: m.maxDelta,
            changedFraction: m.changedFraction, changedPixels: m.changedPixels,
        });
        if (!ok) {
            fs.mkdirSync(this.opts.outDir, { recursive: true });
            const diffFile = path.join(this.opts.outDir, `${spec.name}.diff.png`);
            fs.writeFileSync(diffFile,
                encodePng(renderDiffImage(expected, actual, opts)));
        }
        this.report.record(`checkpoint ${spec.name}`, ok, m.sizeMismatch
            ? `size mismatch: baseline ${expected.width}x${expected.height} vs ${actual.width}x${actual.height}`
            : `rms ${m.rms.toFixed(5)} (max ${maxRms}), changed ` +
              `${(m.changedFraction * 100).toFixed(3)}% (max ${(maxFrac * 100).toFixed(3)}%), ` +
              `maxDelta ${m.maxDelta}`);
    }

    /**
     * In-run change detection (plan §3.4): diff two captures of this run.
     * expect 'changed'   → at least minChangedFraction of pixels moved;
     * expect 'unchanged' → nothing moved beyond threshold.
     * region crops both images first (checks confined to a screen rectangle);
     * masks exclude rectangles instead (e.g. "everything except the doorway").
     */
    pairDiff(before: Capture, after: Capture, label: string,
             opts: { region?: Mask; masks?: Mask[]; expect: 'changed' | 'unchanged';
                     minChangedFraction?: number; pixelThreshold?: number }): boolean {
        let a = decodePng(before.png);
        let b = decodePng(after.png);
        if (opts.region) {
            const r = opts.region;
            a = cropImage(a, r.x, r.y, r.width, r.height);
            b = cropImage(b, r.x, r.y, r.width, r.height);
        }
        const m = diffImages(a, b, {
            pixelThreshold: opts.pixelThreshold ?? 4,
            masks: opts.masks ?? [],
        });
        const ok = opts.expect === 'changed'
            ? m.changedFraction >= (opts.minChangedFraction ?? 0.001)
            : (!m.sizeMismatch && m.changedFraction <= 0.0005 && m.maxDelta <= 24);
        this.report.checkpoint({
            name: label, verdict: ok ? 'PASS' : 'FAIL', pair: true,
            expect: opts.expect, rms: m.rms, maxDelta: m.maxDelta,
            changedFraction: m.changedFraction, region: opts.region ?? null,
        });
        return this.report.record(`pairDiff ${label}`, ok,
            `${opts.expect}: changed ${(m.changedFraction * 100).toFixed(3)}%, ` +
            `maxDelta ${m.maxDelta}, rms ${m.rms.toFixed(5)}`);
    }
}
