import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { inventory, validateResponse, type Environment, type Evidence, type Operation } from './coverage.ts';
import type { Adapter, Result, Subscription } from './adapters.ts';
import { manifest } from './manifest.ts';

export interface Trace {
    id: number;
    operationId: string;
    environment: Environment;
    status?: number;
    error?: string;
    parameters?: any;
    body?: any;
    response?: any;
    expectedCondition?: string;
}

export class Suite {
    readonly trace: Trace[] = [];
    readonly evidence: Evidence[] = [];
    readonly operations: Map<string, Operation>;
    environment: Environment;
    selectedOperations: string[] = [];
    private active?: Evidence;
    constructor(adapter: Adapter, contract: any, environment: Environment, outDir: string) {
        this.adapter = adapter;
        this.contract = contract;
        this.environment = environment;
        this.outDir = outDir;
        this.operations = new Map(inventory(contract).map(operation => [operation.operationId, operation]));
    }
    readonly adapter: Adapter;
    readonly contract: any;
    readonly outDir: string;
    async request(id: string, parameters: Record<string, any> = {}, body: any = {}, expected?: number): Promise<any> {
        const operation = this.operations.get(id);
        assert.ok(operation, `unknown operation ${id}`);
        const trace: Trace = { id: this.trace.length + 1, operationId: id, environment: this.environment, parameters, body };
        this.trace.push(trace);
        try {
            const result = await this.adapter.request(operation, parameters, body);
            trace.status = result.status;
            trace.response = result.body?.pngBase64 ? { ...result.body, pngBase64: '[stored separately]' } : result.body;
            if (result.status >= 200 && result.status < 300 && this.active?.operationId === id) this.active.requestIds.push(trace.id);
            if (expected !== undefined) assert.equal(result.status, expected, `${id}: ${JSON.stringify(result.body)}`);
            else assert.ok(result.status >= 200 && result.status < 300, `${id}: HTTP ${result.status} ${JSON.stringify(result.body)}`);
            if (result.status < 300) validateResponse(this.contract, operation, result.status, result.body);
            return result.body;
        } catch (error) { trace.error = String(error); throw error; }
    }
    async outcome(id: string, parameters: Record<string, any> = {}, body: any = {}): Promise<Result> {
        const operation = this.operations.get(id)!;
        const trace: Trace = { id: this.trace.length + 1, operationId: id, environment: this.environment, parameters, body };
        this.trace.push(trace);
        try {
            const result = await this.adapter.request(operation, parameters, body);
            trace.status = result.status;
            trace.response = result.body?.pngBase64 ? { ...result.body, pngBase64: '[stored separately]' } : result.body;
            if (result.status >= 200 && result.status < 300) {
                validateResponse(this.contract, operation, result.status, result.body);
                if (this.active?.operationId === id) this.active.requestIds.push(trace.id);
            }
            return result;
        } catch (error) { trace.error = String(error); throw error; }
    }
    expect(condition: boolean, detail: string): void {
        assert.ok(condition, detail);
        this.active?.assertions.push(detail);
    }
    async case(id: string, run: () => Promise<void>, caseId = 'success'): Promise<void> {
        assert.ok(manifest[id]?.environments.includes(this.environment), `${id} is not applicable in ${this.environment}`);
        const evidence: Evidence = { operationId: id, environment: this.environment,
            caseId, outcome: 'failed', assertions: [], requestIds: [] };
        if (!this.selectedOperations.length || this.selectedOperations.includes(id)) this.evidence.push(evidence);
        this.active = evidence;
        console.log(`  ${this.environment}: ${id}`);
        try {
            await run();
            assert.ok(evidence.assertions.length && evidence.requestIds.length, `${id} needs an observed assertion and its own request`);
            evidence.outcome = 'passed';
        } catch (error) { evidence.detail = String(error); throw error; }
        finally { this.active = undefined; }
    }
    async poll<T>(read: () => Promise<T>, ready: (value: T) => boolean, label: string, timeoutMs = 30_000): Promise<T> {
        const deadline = Date.now() + timeoutMs;
        let latest: T | undefined;
        while (Date.now() < deadline) {
            latest = await read();
            if (ready(latest)) return latest;
            await new Promise(resolve => setTimeout(resolve, 100));
        }
        throw new Error(`${label} exceeded deadline; last=${JSON.stringify(latest)}`);
    }
    async pollRead(id: string, parameters: Record<string, any>, ready: (value: any) => boolean,
        label: string, timeoutMs = 30_000): Promise<any> {
        assert.equal(this.operations.get(id)?.method, 'GET', 'progress polling must never repeat mutations');
        return this.poll(async () => {
            const result = await this.outcome(id, parameters);
            if (result.status === 503 && result.body.error?.code === 'SERVER_BUSY') {
                this.trace.at(-1)!.expectedCondition = `server busy while awaiting ${label}`;
                return undefined;
            }
            assert.equal(result.status, 200, `${id} progress probe: ${JSON.stringify(result)}`);
            return result.body;
        }, value => value !== undefined && ready(value), label, timeoutMs);
    }
    async subscribe(cursor: number, types: string[], world?: string): Promise<Subscription> {
        const trace: Trace = { id: this.trace.length + 1, operationId: 'streamEvents', environment: this.environment, parameters: { cursor, types, world } };
        this.trace.push(trace);
        const subscription = await this.adapter.subscribe({ cursor, types, world });
        trace.status = 200;
        trace.response = { events: subscription.events, errors: subscription.errors };
        if (this.active?.operationId === 'streamEvents') this.active.requestIds.push(trace.id);
        return subscription;
    }
    savePng(body: any, name: string): Buffer {
        const png = Buffer.from(String(body.pngBase64), 'base64');
        fs.mkdirSync(this.outDir, { recursive: true });
        fs.writeFileSync(path.join(this.outDir, `${name}.png`), png);
        return png;
    }
    async command(command: string, requireSuccess = true): Promise<any> {
        const result = await this.request('dispatchCommand', {}, { command });
        if (requireSuccess) assert.equal(result.success, true, `fixture command failed: ${command}: ${JSON.stringify(result)}`);
        return result;
    }
}
