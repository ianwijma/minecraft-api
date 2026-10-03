import { test } from 'node:test';
import assert from 'node:assert/strict';
import { assertComplete, inventory, loadContract, missingCoverage, validateManifest,
    validateResponse, validateSchema, type Evidence } from '../api/coverage.ts';
import { manifest } from '../api/manifest.ts';

test('canonical contract and behavior manifest agree in both directions', () => {
    validateManifest(inventory(loadContract()), manifest);
});

test('adding or removing an operation without updating tests fails', () => {
    const operations = inventory(loadContract());
    assert.throws(() => validateManifest([...operations,
        { operationId: 'newOperation', method: 'GET', path: '/new', definition: {} }], manifest));
    assert.throws(() => validateManifest(operations.slice(1), manifest));
});

test('only asserted successful requests earn environment coverage', () => {
    const definition = { sample: { environments: ['menu' as const], prerequisites: [],
        assertion: 'observed effect', negativeCases: [] } };
    const evidence: Evidence[] = [{ operationId: 'sample', environment: 'menu', caseId: 'sample',
        outcome: 'capability-rejected', assertions: ['rejection observed'], requestIds: [1] }];
    assert.deepEqual(missingCoverage(definition, evidence), ['sample@menu']);
    evidence[0].outcome = 'passed';
    evidence[0].assertions = [];
    assert.throws(() => assertComplete(definition, evidence));
    evidence[0].assertions = ['observed effect'];
    assertComplete(definition, evidence);
    evidence.push({ ...evidence[0], outcome: 'failed' });
    assert.throws(() => assertComplete(definition, evidence), /first-attempt/);
    assert.throws(() => assertComplete(definition, []), /missing/);
});

test('schema validation rejects missing fields, invalid types, bounds, and unions', () => {
    const contract = { components: { schemas: { sample: { type: 'object', required: ['count'],
        properties: { count: { type: 'integer', minimum: 1 } } } } } };
    const schema = { $ref: '#/components/schemas/sample' };
    validateSchema(contract, schema, { count: 2 });
    for (const value of [{}, { count: '2' }, { count: 0 }, { count: 1.5 }]) {
        assert.throws(() => validateSchema(contract, schema, value));
    }
    assert.throws(() => validateSchema({}, { oneOf: [{ type: 'number' }, { type: 'integer' }] }, 2));
    const health = inventory(loadContract()).find(o => o.operationId === 'getHealth')!;
    assert.throws(() => validateResponse(loadContract(), health, 599, {}), /undocumented/);
});
