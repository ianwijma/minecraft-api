import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as http from 'node:http';
import { fileURLToPath } from 'node:url';
import { createAdapter } from '../api/factory.ts';
import { inventory, loadContract } from '../api/coverage.ts';
import { requestPath, type Sdk } from '../api/adapters.ts';
import { MapiClient } from '../../sdk/typescript/src/mapi-client.ts';

const root = fileURLToPath(new URL('../../', import.meta.url));

test('TypeScript cancellation closes an SSE reader waiting for its first event', async () => {
    let opened!: () => void;
    let closed!: () => void;
    const ready = new Promise<void>(resolve => { opened = resolve; });
    const disconnected = new Promise<void>(resolve => { closed = resolve; });
    const server = http.createServer((request, response) => {
        assert.equal(request.headers.authorization, 'Bearer cancellation-test');
        response.writeHead(200, { 'Content-Type': 'text/event-stream' });
        response.write(':ready\n\n');
        response.on('close', closed);
        opened();
    });
    await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
    const controller = new AbortController();
    const stream = new MapiClient(`http://127.0.0.1:${(server.address() as any).port}`, 'cancellation-test', 3000)
        .streamEvents(undefined, undefined, undefined, 1, controller.signal);
    const pending = stream.next();
    try {
        await ready;
        controller.abort();
        await assert.rejects(pending, error => (error as Error).name === 'AbortError');
        await Promise.race([disconnected, new Promise((_, reject) => setTimeout(() => reject(new Error('SSE connection was not released')), 3000))]);
    } finally {
        controller.abort();
        server.closeAllConnections();
        await new Promise<void>(resolve => server.close(() => resolve()));
    }
});

for (const sdk of ['typescript', 'python', 'java'] as Sdk[]) {
    test(`${sdk}: every operation serializes through the SDK and streams are bounded`, async () => {
        const calls: { method: string; path: string; body: any }[] = [];
        const server = http.createServer(async (request, response) => {
            if (request.headers.authorization !== 'Bearer adapter-test-token') {
                response.writeHead(401, { 'Content-Type': 'application/json' });
                response.end(JSON.stringify({ error: { code: 'UNAUTHORIZED', message: 'rejected' } }));
                return;
            }
            if (request.url?.startsWith('/api/v1/events/stream')) {
                response.writeHead(200, { 'Content-Type': 'text/event-stream' });
                const text = ':event-gap droppedUpTo=4\r\n\r\nid: 5\r\nevent: changed\r\n'
                    + 'data: {"text":"café",\r\ndata: "ok":true}\r\n\r\n';
                for (const byte of Buffer.from(text)) response.write(Buffer.from([byte]));
                response.end();
                return;
            }
            let raw = '';
            for await (const chunk of request) raw += chunk;
            calls.push({ method: request.method!, path: request.url!, body: raw ? JSON.parse(raw) : undefined });
            response.writeHead(200, { 'Content-Type': 'application/json' });
            response.end(JSON.stringify({ ok: true }));
        });
        await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
        const base = `http://127.0.0.1:${(server.address() as any).port}`;
        const adapter = createAdapter(sdk, base, 'adapter-test-token', root, 3000);
        const bad = createAdapter(sdk, base, 'wrong-token', root, 3000);
        try {
            const operations = inventory(loadContract());
            for (const operation of operations.filter(o => o.operationId !== 'streamEvents')) {
                const parameters: Record<string, any> = {};
                for (const parameter of operation.definition.parameters ?? []) {
                    parameters[parameter.name] = parameter.schema?.type === 'integer' ? 7
                        : parameter.schema?.type === 'number' ? 1.5 : parameter.schema?.type === 'boolean' ? true : 'test a/é';
                }
                const body = { label: 'test "quoted" café', nested: { count: 7 },
                    ...(operation.operationId === 'diffSnapshots' ? { firstId: 'first', secondId: 'second', maxChanges: 7 } : {}) };
                assert.equal((await adapter.request(operation, parameters, body)).status, 200);
                const recorded = calls.at(-1)!;
                assert.equal(recorded.method, operation.method);
                const expected = new URL(requestPath(operation, parameters), base);
                const actual = new URL(recorded.path, base);
                assert.equal(actual.pathname, expected.pathname);
                assert.deepEqual([...actual.searchParams].sort(), [...expected.searchParams].sort());
                if (operation.method === 'POST') assert.deepEqual(recorded.body, body);
            }
            const health = operations.find(o => o.operationId === 'getHealth')!;
            const rejected = await bad.request(health);
            assert.equal(rejected.status, 401);
            assert.equal(rejected.body.error.code, 'UNAUTHORIZED');
            const stream = await adapter.subscribe({ cursor: 7, types: ['changed'] });
            const deadline = Date.now() + 3000;
            while (stream.events.length < 2 && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 10));
            assert.deepEqual(stream.errors, []);
            assert.equal(stream.events[0].gap, true);
            assert.equal(stream.events[0].droppedUpToSeq, 4);
            assert.equal(stream.events[1].id, '5');
            assert.deepEqual(stream.events[1].data, { text: 'café', ok: true });
            await stream.close();
        } finally {
            adapter.close(); bad.close();
            server.closeAllConnections();
            await new Promise<void>(resolve => server.close(() => resolve()));
        }
    });
}
