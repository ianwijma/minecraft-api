/**
 * Thin typed wrapper over the generated MapiClient. The generic get/post
 * helpers keep scenario paths readable while generated methods cover the
 * contract for SDK consumers.
 */
import { MapiClient, MapiError } from '../../sdk/typescript/src/mapi-client.ts';
import { sleep, assert } from './report.ts';

/** GLFW key codes consumed by /api/v1/client/actions/hold-key. ⚙ verify-26.2. */
export const KEYS = {
    F1: 290,
    ESCAPE: 256,
    INVENTORY: 69,          // E
    ATTACK: 71,             // G (rebound in the E2E profile from mouse left)
    USE: 72,                // H (rebound from mouse right)
    PICK: 74,               // J (rebound from mouse middle)
    HOTBAR_1: 49,           // '1'
    HOTBAR_2: 50,           // '2'
    HOTBAR_3: 51,           // '3'
};

export interface Capture {
    png: Buffer;
    width: number;
    height: number;
    frame: number;
    screenId: string;
    guiScale: number;
    capturedAtEpochMs: number;
}

export class Harness {
    readonly api: MapiClient;
    readonly base: string;
    private inputLeaseId: string | null = null;
    private inputLeaseExpiresAt = 0;
    private inputLeaseNeedsValidation = false;

    constructor(base: string, token: string) {
        this.base = base;
        this.api = new MapiClient(base, token);
    }

    async get(path: string): Promise<any> {
        const r = await this.withRequestContext('GET', path, () => this.api.get(path));
        if (!r.ok) throw new Error(`GET ${path} -> ${r.status}: ${JSON.stringify(r.body)}`);
        return r.body;
    }

    async post(path: string, body: unknown): Promise<any> {
        const r = await this.withRequestContext('POST', path, () => this.api.post(path, body));
        if (!r.ok) throw new Error(`POST ${path} -> ${r.status}: ${JSON.stringify(r.body)}`);
        if (/^\/api\/v1\/client\/worlds\/(create|delete|load)$/.test(path)) {
            this.inputLeaseNeedsValidation = true;
        }
        return r.body;
    }

    private async withRequestContext<T>(method: 'GET' | 'POST', path: string,
                                        request: () => Promise<T>): Promise<T> {
        try {
            return await request();
        } catch (e) {
            if (e instanceof MapiError) {
                throw new MapiError(e.status, e.code, `${e.message} (${method} ${path})`);
            }
            throw e;
        }
    }

    async healthWait(timeoutMs = 480_000, pollMs = 2_000): Promise<void> {
        const deadline = Date.now() + timeoutMs;
        while (Date.now() < deadline) {
            try {
                const r = await this.api.getHealth();
                if (r.ok) return;
            } catch { /* not up yet */ }
            await sleep(pollMs);
        }
        throw new Error(`MAPI unreachable after ${timeoutMs / 1000}s at ${this.base}`);
    }

    async info(): Promise<any> {
        return this.get('/api/v1/info');
    }

    /**
     * SERVER_BUSY is a documented "retry shortly" answer (docs/http-api.md);
     * world creation and first terrain generation saturate the server thread
     * well past the API's bounded waits. Retry with bounded backoff here so
     * scenarios never see boot-window transients.
     */
    private async busyRetry<T>(fn: () => Promise<T>, attempts = 8): Promise<T> {
        let lastErr: unknown;
        for (let i = 0; i < attempts; i++) {
            try {
                return await fn();
            } catch (e) {
                if (e instanceof MapiError && e.code === 'SERVER_BUSY') {
                    lastErr = e;
                    await sleep(1000 * Math.min(i + 1, 3));
                    continue;
                }
                throw e;
            }
        }
        throw lastErr;
    }

    /**
     * Post-create boot window: phase ACTIVE does not mean the server thread
     * can answer within the API's bounded waits yet (spawn chunks still
     * generating). Poll a trivial command until it succeeds; absorb the
     * documented busy/not-loaded boot codes up to the deadline.
     */
    async waitForServerReady(deadlineMs = 180_000): Promise<void> {
        const deadline = Date.now() + deadlineMs;
        while (Date.now() < deadline) {
            try {
                const r = await this.api.post('/api/v1/server/commands',
                    { command: 'time query daytime' });
                if (r.ok) return;
            } catch (e) {
                if (e instanceof MapiError
                    && (e.code === 'SERVER_BUSY' || e.code === 'WORLD_NOT_LOADED')) {
                    await sleep(2_000);
                    continue;
                }
                throw e;
            }
        }
        throw new Error('server thread not responsive within deadline');
    }

    /** Dispatch a console-context command; throws unless it reported success. */
    async command(cmd: string): Promise<any> {
        const body = await this.busyRetry(() =>
            this.post('/api/v1/server/commands', { command: cmd }));
        if (body.success !== true) {
            throw new Error(`command failed: ${cmd} -> ${JSON.stringify(body)}`);
        }
        return body;
    }

    /**
     * Current daytime. Variant `day` vs `daytime` differs across loaders:
     * try `daytime` first, fall back to `day`. Either is fine for the
     * daylight contract, which only ever asserts pinned-ness.
     */
    async dayTime(): Promise<number> {
        const variants = ['time query day', 'time query daytime'];
        let last: unknown;
        for (const variant of variants) {
            try {
                const body = await this.busyRetry(() =>
                    this.post('/api/v1/server/commands', { command: variant }));
                if (body.success === true) {
                    return Number(body.resultCode);
                }
                last = new Error(`variant ${variant} reported failure: ${body.failure}`);
            } catch (e) {
                last = e;
            }
        }
        throw last;
    }

    async blockAt(x: number, y: number, z: number,
                  dimension = 'minecraft:overworld'): Promise<any> {
        return this.busyRetry(() =>
            this.get(`/api/v1/server/queries/block?dimension=${dimension}` +
                `&x=${x}&y=${y}&z=${z}`));
    }

    /** Schema-tolerant block check (exact response shape pinned on first run). */
    bodyMentions(body: unknown, id: string): boolean {
        return JSON.stringify(body).includes(id);
    }

    async players(max = 8): Promise<any[]> {
        const body = await this.get(`/api/v1/server/queries/players?max=${max}`);
        return (body.players as any[]) ?? [];
    }

    async playerPos(): Promise<{ name: string; x: number; y: number; z: number }> {
        const players = await this.players(1);
        assert(players.length > 0, 'no players online');
        const p = players[0];
        return { name: p.name, x: p.x, y: p.y, z: p.z };
    }

    async entitiesAround(x: number, y: number, z: number, radius: number,
                         max = 64): Promise<any[]> {
        const body = await this.get('/api/v1/server/queries/entities?' +
            `dimension=minecraft:overworld&x=${x}&y=${y}&z=${z}` +
            `&radius=${radius}&max=${max}`);
        return (body.entities as any[]) ?? [];
    }

    async worldPhase(): Promise<string> {
        const body = await this.get('/api/v1/server/world');
        return String(body.phase ?? 'NONE');
    }

    async waitForPhase(target: string, timeoutMs: number): Promise<void> {
        const deadline = Date.now() + timeoutMs;
        while (Date.now() < deadline) {
            const phase = await this.worldPhase();
            if (phase === target) return;
            await sleep(500);
        }
        throw new Error(`world phase did not reach ${target} within ${timeoutMs}ms`);
    }

    async waitForJob(jobId: string, timeoutMs = 120_000): Promise<any> {
        const deadline = Date.now() + timeoutMs;
        while (Date.now() < deadline) {
            const body = await this.get(`/api/v1/jobs/${jobId}`);
            const state = String(body.state);
            if (state === 'SUCCEEDED') return body;
            if (state === 'FAILED' || state === 'CANCELLED') {
                throw new Error(`job ${jobId} ${state}: ${body.failureCode} ${body.failureMessage ?? ''}`);
            }
            await sleep(200);
        }
        throw new Error(`job ${jobId} not finished within ${timeoutMs}ms`);
    }

    async screenshot(): Promise<Capture> {
        const body = await this.get('/api/v1/client/screenshots');
        return {
            png: Buffer.from(String(body.pngBase64), 'base64'),
            width: Number(body.width),
            height: Number(body.height),
            frame: Number(body.frame),
            screenId: String(body.screenId ?? ''),
            guiScale: Number(body.guiScale),
            capturedAtEpochMs: Number(body.capturedAtEpochMs),
        };
    }

    // -- tick control -------------------------------------------------------

    async acquireTickLease(ttlSeconds = 120): Promise<string> {
        const body = await this.post('/api/v1/server/ticks/lease', { ttlSeconds });
        return String(body.leaseId);
    }

    async freezeTicks(leaseId: string): Promise<void> {
        await this.post('/api/v1/server/ticks/freeze', { leaseId });
    }

    async unfreezeTicks(leaseId: string): Promise<void> {
        await this.post('/api/v1/server/ticks/unfreeze', { leaseId });
    }

    /** Step exact ticks and await the job; returns the job view. */
    async stepTicks(leaseId: string, ticks: number): Promise<any> {
        const r = await this.api.post('/api/v1/server/ticks/step', { leaseId, ticks });
        if (r.status !== 202) {
            throw new Error(`step -> ${r.status}: ${JSON.stringify(r.body)}`);
        }
        return this.waitForJob(String((r.body as any).jobId));
    }

    // -- client bridge --------------------------------------------------------

    private async inputLease(): Promise<string> {
        const acquire = async () => {
            const body = await this.post('/api/v1/client/control/lease', { ttlSeconds: 300 });
            this.inputLeaseId = String(body.leaseId);
            this.inputLeaseExpiresAt = Number(body.expiresAtEpochMs);
            this.inputLeaseNeedsValidation = false;
        };
        if (!this.inputLeaseId) {
            await acquire();
        } else if (this.inputLeaseNeedsValidation || Date.now() + 15_000 >= this.inputLeaseExpiresAt) {
            try {
                const body = await this.post('/api/v1/client/control/lease',
                    { ttlSeconds: 300, leaseId: this.inputLeaseId });
                this.inputLeaseExpiresAt = Number(body.expiresAtEpochMs);
                this.inputLeaseNeedsValidation = false;
            } catch (error) {
                if (!(error instanceof MapiError) || error.code !== 'LEASE_REQUIRED') throw error;
                this.inputLeaseId = null;
                this.inputLeaseExpiresAt = 0;
                this.inputLeaseNeedsValidation = false;
                await acquire();
            }
        }
        return this.inputLeaseId;
    }

    async holdKey(keyCode: number, ticks: number): Promise<any> {
        return this.post('/api/v1/client/actions/hold-key',
            { keyCode, ticks, leaseId: await this.inputLease() });
    }

    async clickScreen(x: number, y: number): Promise<any> {
        return this.post('/api/v1/client/actions/click',
            { x, y, leaseId: await this.inputLease() });
    }

    async moveWaypoints(waypoints: { yaw: number; pitch: number; ticks: number }[]): Promise<any> {
        return this.post('/api/v1/client/movement/waypoints',
            { waypoints, leaseId: await this.inputLease() });
    }

    async captureRenderedTooltip(slot: number): Promise<any> {
        return this.post('/api/v1/client/inventory/tooltip-rendered',
            { slot, leaseId: await this.inputLease() });
    }

    async inventory(): Promise<any> {
        return this.get('/api/v1/client/inventory');
    }

    async clickInventory(slot: number, button = 0, containerInput = 'PICKUP'): Promise<any> {
        return this.post('/api/v1/client/inventory/click',
            { slot, button, containerInput, executionMode: 'client-logic',
                leaseId: await this.inputLease() });
    }

    async snapshot(label: string): Promise<string> {
        const body = await this.busyRetry(() =>
            this.post('/api/v1/server/snapshots', { label }));
        return String(body.snapshotId);
    }

    async snapshotDiff(firstId: string, secondId: string): Promise<any> {
        return this.busyRetry(() =>
            this.post('/api/v1/server/snapshot-diffs',
                { firstId, secondId, maxChanges: 10000 }));
    }
}
