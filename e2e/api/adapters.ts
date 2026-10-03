import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import * as readline from 'node:readline';
import { MapiClient, MapiError } from '../../sdk/typescript/src/mapi-client.ts';
import type { Operation } from './coverage.ts';

export type Sdk = 'typescript' | 'python' | 'java';
export interface Result { status: number; body: any; }
export interface Subscription {
    events: any[];
    errors: string[];
    close(): Promise<void>;
}
export interface Adapter {
    request(operation: Operation, parameters?: Record<string, any>, body?: any): Promise<Result>;
    subscribe(options: { cursor?: number; types?: string[]; world?: string }): Promise<Subscription>;
    close(): void;
}

export function requestPath(operation: Operation, parameters: Record<string, any>): string {
    let path = operation.path;
    const query = new URLSearchParams();
    for (const parameter of operation.definition.parameters ?? []) {
        const value = parameters[parameter.name];
        if (value === undefined) continue;
        if (parameter.in === 'path') path = path.replace(`{${parameter.name}}`, encodeURIComponent(String(value)));
        if (parameter.in === 'query') query.set(parameter.name, Array.isArray(value) ? value.join(',') : String(value));
    }
    if (/\{[^}]+\}/.test(path)) throw new Error(`unresolved path parameter in ${path}`);
    return path + (query.size ? `?${query}` : '');
}

export class TypeScriptAdapter implements Adapter {
    private readonly client: MapiClient;
    private readonly streams = new Set<AbortController>();
    constructor(base: string, token: string, timeoutMs = 30_000) {
        this.client = new MapiClient(base, token, timeoutMs);
    }
    async request(operation: Operation, parameters: Record<string, any> = {}, body: any = {}): Promise<Result> {
        try {
            const method = (this.client as any)[operation.operationId];
            if (typeof method !== 'function') throw new Error(`missing generated SDK method ${operation.operationId}`);
            const declared = operation.definition.parameters ?? [];
            const pathParameters = declared.filter((p: any) => p.in === 'path')
                .sort((a: any, b: any) => a.name.localeCompare(b.name));
            const queryParameters = declared.filter((p: any) => p.in === 'query')
                .sort((a: any, b: any) => Number(b.required === true) - Number(a.required === true)
                    || a.name.localeCompare(b.name));
            const args = operation.method === 'POST' ? [body]
                : [...pathParameters, ...queryParameters].map((parameter: any) => parameters[parameter.name]);
            return await method.apply(this.client, args);
        } catch (error) {
            if (error instanceof MapiError) return { status: error.status,
                body: { error: { code: error.code, message: error.message } } };
            throw error;
        }
    }
    async subscribe(options: { cursor?: number; types?: string[]; world?: string }): Promise<Subscription> {
        const controller = new AbortController();
        this.streams.add(controller);
        const events: any[] = [];
        const errors: string[] = [];
        const iterator = this.client.streamEvents(options.cursor, options.types, options.world, 1, controller.signal);
        const done = (async () => {
            try {
                for await (const event of iterator) events.push(event);
            } catch (error) {
                if (!controller.signal.aborted) errors.push(String(error));
            } finally {
                this.streams.delete(controller);
            }
        })();
        return { events, errors, close: async () => { controller.abort(); await done; } };
    }
    close(): void { for (const controller of this.streams) controller.abort(); }
}

export class WorkerAdapter implements Adapter {
    private readonly process: ChildProcessWithoutNullStreams;
    private sequence = 0;
    private pending = new Map<number, { resolve(value: Result): void; reject(error: Error): void; timer: ReturnType<typeof setTimeout> }>();
    private streams = new Map<string, { events: any[]; errors: string[] }>();
    private stderr = '';
    private readonly base: string;
    private readonly token: string;
    private readonly timeoutMs: number;
    constructor(command: string, args: string[], base: string, token: string, timeoutMs = 30_000) {
        this.base = base;
        this.token = token;
        this.timeoutMs = timeoutMs;
        this.process = spawn(command, args, { stdio: ['pipe', 'pipe', 'pipe'] });
        this.process.stderr.on('data', chunk => { this.stderr = (this.stderr + chunk).slice(-4000); });
        const fail = (error: Error) => {
            for (const entry of this.pending.values()) { clearTimeout(entry.timer); entry.reject(error); }
            this.pending.clear();
            for (const stream of this.streams.values()) stream.errors.push(error.message);
        };
        this.process.on('error', fail);
        this.process.on('exit', code => fail(new Error(`SDK worker exited (${code}): ${this.stderr.replaceAll(this.token, '[REDACTED]')}`)));
        readline.createInterface({ input: this.process.stdout }).on('line', line => {
            try {
                const value = JSON.parse(line);
                if (value.id !== undefined) {
                    const entry = this.pending.get(value.id);
                    if (!entry) return;
                    clearTimeout(entry.timer);
                    this.pending.delete(value.id);
                    if (value.error) entry.reject(new Error(value.error));
                    else entry.resolve({ status: value.status, body: value.body });
                } else if (value.streamId) {
                    const stream = this.streams.get(value.streamId);
                    if (value.event) stream?.events.push(value.event);
                    if (value.error) stream?.errors.push(value.error);
                }
            } catch (error) { fail(new Error(`invalid SDK worker protocol: ${String(error)}`)); }
        });
    }
    private send(payload: any): Promise<Result> {
        const id = ++this.sequence;
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
                this.pending.delete(id);
                reject(new Error(`SDK worker request ${id} exceeded deadline`));
                this.process.kill();
            }, this.timeoutMs + 5000);
            this.pending.set(id, { resolve, reject, timer });
            this.process.stdin.write(JSON.stringify({ ...payload, id, base: this.base,
                token: this.token, timeoutMs: this.timeoutMs }) + '\n');
        });
    }
    request(operation: Operation, parameters: Record<string, any> = {}, body: any = {}): Promise<Result> {
        return this.send({ command: 'request', operationId: operation.operationId,
            method: operation.method, path: requestPath(operation, parameters), body });
    }
    async subscribe(options: { cursor?: number; types?: string[]; world?: string }): Promise<Subscription> {
        const streamId = `stream-${this.sequence + 1}`;
        const state = { events: [] as any[], errors: [] as string[] };
        this.streams.set(streamId, state);
        const result = await this.send({ command: 'subscribe', streamId, ...options });
        if (result.status !== 200) throw new Error(`SSE subscribe rejected: ${JSON.stringify(result)}`);
        return { ...state, close: async () => {
            await this.send({ command: 'close', streamId });
            this.streams.delete(streamId);
        } };
    }
    close(): void { this.process.stdin.end(); this.process.kill(); }
}
