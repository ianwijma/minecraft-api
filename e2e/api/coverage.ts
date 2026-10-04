import * as fs from 'node:fs';
import * as crypto from 'node:crypto';
import assert from 'node:assert/strict';

export type Environment = 'menu' | 'integrated' | 'dedicated' | 'multiplayer';
export interface Operation {
    operationId: string;
    method: string;
    path: string;
    definition: any;
}
export interface CaseDefinition {
    environments: Environment[];
    prerequisites: string[];
    assertion: string;
    negativeCases: string[];
}
export interface Evidence {
    operationId: string;
    environment: Environment;
    caseId: string;
    outcome: 'passed' | 'failed' | 'capability-rejected';
    assertions: string[];
    requestIds: number[];
    detail?: string;
}

export function loadContract(): any {
    const snapshot = JSON.parse(fs.readFileSync(new URL('./contract.json', import.meta.url), 'utf8'));
    const hash = crypto.createHash('sha256').update(
        fs.readFileSync(new URL('../../docs/openapi.yaml', import.meta.url))).digest('hex');
    assert.equal(snapshot.sourceSha256, hash, 'E2E contract drift: regenerate the contract snapshot');
    return snapshot.openapi;
}

export function inventory(contract: any): Operation[] {
    const result: Operation[] = [];
    const ids = new Set<string>();
    for (const [path, methods] of Object.entries<any>(contract.paths)) {
        for (const [method, definition] of Object.entries<any>(methods)) {
            if (!['get', 'post', 'put', 'patch', 'delete', 'head', 'options', 'trace'].includes(method)) continue;
            assert.ok(definition.operationId, `${method} ${path} needs operationId`);
            assert.ok(!ids.has(definition.operationId), `duplicate operationId ${definition.operationId}`);
            ids.add(definition.operationId);
            result.push({ operationId: definition.operationId, method: method.toUpperCase(), path, definition });
        }
    }
    return result;
}

export function validateManifest(operations: Operation[], manifest: Record<string, CaseDefinition>): void {
    assert.deepEqual(Object.keys(manifest).sort(), operations.map(o => o.operationId).sort(),
        'every operation must have exactly one behavior definition');
    for (const [id, entry] of Object.entries(manifest)) {
        assert.ok(entry.assertion.trim().length > 0, `${id} needs a behavior assertion`);
        assert.ok(entry.environments.length > 0, `${id} needs an applicable environment`);
        assert.equal(new Set(entry.environments).size, entry.environments.length);
        assert.ok(Array.isArray(entry.prerequisites) && Array.isArray(entry.negativeCases));
    }
}

export function missingCoverage(manifest: Record<string, CaseDefinition>, evidence: Evidence[]): string[] {
    return Object.entries(manifest).flatMap(([id, entry]) => entry.environments
        .filter(environment => !evidence.some(e => e.operationId === id && e.environment === environment
            && e.caseId === 'success' && e.outcome === 'passed' && e.assertions.length > 0 && e.requestIds.length > 0))
        .map(environment => `${id}@${environment}`));
}

export function assertComplete(manifest: Record<string, CaseDefinition>, evidence: Evidence[]): void {
    assert.deepEqual(missingCoverage(manifest, evidence), [], 'missing successful behavior evidence');
    assert.deepEqual(evidence.filter(e => e.outcome === 'failed'), [], 'first-attempt failures remain failures');
}

function resolve(contract: any, schema: any): any {
    if (!schema?.$ref) return schema;
    assert.ok(schema.$ref.startsWith('#/'), 'only local contract references are supported');
    return resolve(contract, schema.$ref.slice(2).split('/').reduce((value: any, key: string) => {
        assert.ok(value && key in value, `unresolved contract reference ${schema.$ref}`);
        return value[key];
    }, contract));
}

export function validateSchema(contract: any, schema: any, value: any, location = '$'): void {
    schema = resolve(contract, schema);
    if (!schema) return;
    if ('const' in schema) assert.deepEqual(value, schema.const, `${location}: const mismatch`);
    if (schema.nullable && value === null) return;
    for (const sub of schema.allOf ?? []) validateSchema(contract, sub, value, location);
    if (schema.oneOf || schema.anyOf) {
        const matches = (schema.oneOf ?? schema.anyOf).filter((sub: any) => {
            try { validateSchema(contract, sub, value, location); return true; } catch { return false; }
        }).length;
        assert.ok(schema.oneOf ? matches === 1 : matches >= 1, `${location}: union schema mismatch`);
    }
    if (schema.enum) assert.ok(schema.enum.includes(value), `${location}: unexpected enum value`);
    if (schema.type === 'object') {
        assert.ok(value !== null && typeof value === 'object' && !Array.isArray(value), `${location}: expected object`);
        for (const key of schema.required ?? []) assert.ok(key in value, `${location}.${key}: required`);
        for (const [key, item] of Object.entries(value)) {
            if (schema.properties?.[key]) validateSchema(contract, schema.properties[key], item, `${location}.${key}`);
            else if (schema.additionalProperties === false) assert.fail(`${location}.${key}: unknown property`);
            else if (typeof schema.additionalProperties === 'object') validateSchema(contract, schema.additionalProperties, item, `${location}.${key}`);
        }
    } else if (schema.type === 'array') {
        assert.ok(Array.isArray(value), `${location}: expected array`);
        if (schema.minItems !== undefined) assert.ok(value.length >= schema.minItems, `${location}: too few items`);
        if (schema.maxItems !== undefined) assert.ok(value.length <= schema.maxItems, `${location}: too many items`);
        value.forEach((item: any, index: number) => validateSchema(contract, schema.items, item, `${location}[${index}]`));
    } else if (schema.type === 'string') {
        assert.equal(typeof value, 'string', `${location}: expected string`);
        if (schema.minLength !== undefined) assert.ok(value.length >= schema.minLength);
        if (schema.maxLength !== undefined) assert.ok(value.length <= schema.maxLength);
        if (schema.pattern) assert.match(value, new RegExp(schema.pattern));
    } else if (schema.type === 'integer' || schema.type === 'number') {
        assert.ok(typeof value === 'number' && Number.isFinite(value), `${location}: expected finite number`);
        if (schema.type === 'integer') assert.ok(Number.isInteger(value), `${location}: expected integer`);
        if (schema.minimum !== undefined) assert.ok(value >= schema.minimum);
        if (schema.maximum !== undefined) assert.ok(value <= schema.maximum);
    } else if (schema.type === 'boolean') assert.equal(typeof value, 'boolean', `${location}: expected boolean`);
}

export function validateResponse(contract: any, operation: Operation, status: number, body: any): void {
    const response = resolve(contract, operation.definition.responses[String(status)] ?? operation.definition.responses.default);
    assert.ok(response, `${operation.operationId}: undocumented HTTP ${status}`);
    validateSchema(contract, response.content?.['application/json']?.schema, body);
}
