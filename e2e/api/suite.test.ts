import assert from 'node:assert/strict';
import test from 'node:test';
import type { Adapter, Result, Subscription } from './adapters.ts';
import { loadContract } from './coverage.ts';
import { Suite } from './suite.ts';

function adapterFor(request: () => Promise<Result>): Adapter {
    return {
        request,
        async subscribe(): Promise<Subscription> {
            return { events: [], errors: [], async close() {} };
        },
        close() {},
    };
}

test('retries transient SERVER_BUSY responses for safe GET requests', async () => {
    let attempts = 0;
    const suite = new Suite(adapterFor(async () => {
        attempts++;
        return attempts < 3
            ? { status: 503, body: { error: { code: 'SERVER_BUSY', message: 'busy' } } }
            : { status: 200, body: { status: 'ok', protocolVersion: 1 } };
    }), loadContract(), 'dedicated', '/tmp/mapi-api-suite-test');

    const result = await suite.request('getHealth');

    assert.deepEqual(result, { status: 'ok', protocolVersion: 1 });
    assert.equal(attempts, 3);
    assert.equal(suite.trace.length, 3);
    assert.ok(suite.trace.slice(0, 2).every(trace => trace.expectedCondition?.includes('retrying')));
});

test('does not retry SERVER_BUSY responses for mutations', async () => {
    let attempts = 0;
    const suite = new Suite(adapterFor(async () => {
        attempts++;
        return { status: 503, body: { error: { code: 'SERVER_BUSY', message: 'busy' } } };
    }), loadContract(), 'dedicated', '/tmp/mapi-api-suite-test');

    await assert.rejects(suite.request('dispatchCommand', {}, { command: 'say test' }), /HTTP 503/);
    assert.equal(attempts, 1);
    assert.equal(suite.trace.length, 1);
});
