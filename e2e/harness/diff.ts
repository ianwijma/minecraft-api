/**
 * Pixel diffing for MAPI visual checks. Same metric family as the Java
 * reference runner (runner/.../VisualDiff.java): per-pixel max channel
 * delta, changed-pixel fraction, and normalized RMS. A size mismatch is a
 * divergence, never a silent pass.
 */
import type { RgbaImage } from './png.ts';

export interface Mask { x: number; y: number; width: number; height: number }

export interface DiffOptions {
    /** Per-pixel max channel delta at/below which a pixel counts as same. */
    pixelThreshold?: number;
    /** Rectangles excluded from comparison (e.g. wall-clock animated fire). */
    masks?: Mask[];
}

export interface DiffMetrics {
    sizeMismatch: boolean;
    width: number;
    height: number;
    comparedPixels: number;
    maskedPixels: number;
    changedPixels: number;
    changedFraction: number;
    maxDelta: number;
    /** Normalized per-channel RMS delta, 0..1 (comparable to Java VisualDiff). */
    rms: number;
}

function inMask(x: number, y: number, masks: Mask[]): boolean {
    for (const m of masks) {
        if (x >= m.x && x < m.x + m.width && y >= m.y && y < m.y + m.height) {
            return true;
        }
    }
    return false;
}

/** Shared core: returns metrics plus a per-pixel "changed" flag array. */
export function analyzeDiff(a: RgbaImage, b: RgbaImage, opts: DiffOptions = {}):
        { metrics: DiffMetrics; changed: Uint8Array } {
    const threshold = opts.pixelThreshold ?? 4;
    const masks = opts.masks ?? [];
    if (a.width !== b.width || a.height !== b.height) {
        return {
            metrics: {
                sizeMismatch: true, width: a.width, height: a.height,
                comparedPixels: 0, maskedPixels: 0, changedPixels: 0,
                changedFraction: 1, maxDelta: 255, rms: 1,
            },
            changed: new Uint8Array(0),
        };
    }
    const count = a.width * a.height;
    const changed = new Uint8Array(count);
    let compared = 0;
    let masked = 0;
    let changedPixels = 0;
    let maxDelta = 0;
    let sumSq = 0;
    for (let y = 0; y < a.height; y++) {
        for (let x = 0; x < a.width; x++) {
            const p = y * a.width + x;
            if (inMask(x, y, masks)) {
                masked++;
                continue;
            }
            compared++;
            const i = p * 4;
            const d0 = Math.abs(a.data[i] - b.data[i]);
            const d1 = Math.abs(a.data[i + 1] - b.data[i + 1]);
            const d2 = Math.abs(a.data[i + 2] - b.data[i + 2]);
            const delta = Math.max(d0, d1, d2);
            if (delta > maxDelta) maxDelta = delta;
            sumSq += d0 * d0 + d1 * d1 + d2 * d2;
            if (delta > threshold) {
                changed[p] = 1;
                changedPixels++;
            }
        }
    }
    const channels = compared * 3;
    const rms = channels === 0 ? 0 : Math.sqrt(sumSq / channels) / 255;
    return {
        metrics: {
            sizeMismatch: false, width: a.width, height: a.height,
            comparedPixels: compared, maskedPixels: masked,
            changedPixels,
            changedFraction: compared === 0 ? 0 : changedPixels / compared,
            maxDelta, rms,
        },
        changed,
    };
}

export function diffImages(a: RgbaImage, b: RgbaImage,
                           opts: DiffOptions = {}): DiffMetrics {
    return analyzeDiff(a, b, opts).metrics;
}

/**
 * Failure artifact for humans: changed pixels in red, masked regions in
 * blue, everything else dimmed. Never read by the pass/fail logic.
 */
export function renderDiffImage(a: RgbaImage, b: RgbaImage,
                                opts: DiffOptions = {}): RgbaImage {
    const { changed } = analyzeDiff(a, b, opts);
    const masks = opts.masks ?? [];
    const out: RgbaImage = {
        width: b.width, height: b.height, data: Buffer.from(b.data),
    };
    for (let y = 0; y < b.height; y++) {
        for (let x = 0; x < b.width; x++) {
            const p = y * b.width + x;
            const i = p * 4;
            if (inMask(x, y, masks)) {
                out.data[i] = (out.data[i] / 2) | 0;
                out.data[i + 1] = (out.data[i + 1] / 2) | 0;
                out.data[i + 2] = 255;
            } else if (changed[p] === 1) {
                out.data[i] = 255;
                out.data[i + 1] = 0;
                out.data[i + 2] = 0;
            } else {
                out.data[i] = (out.data[i] * 0.75) | 0;
                out.data[i + 1] = (out.data[i + 1] * 0.75) | 0;
                out.data[i + 2] = (out.data[i + 2] * 0.75) | 0;
            }
        }
    }
    return out;
}
