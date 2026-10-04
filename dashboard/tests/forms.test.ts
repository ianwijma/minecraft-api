import assert from "node:assert/strict";
import test from "node:test";
import { operations } from "../src/generated/operations";
import type { JsonSchema, Operation } from "../src/lib/catalog";
import { defaultFor, formSchema, initializeFormValues, requestPreview, resolvedSchema, validateFormValue } from "../src/lib/forms";

test("all catalog operations have valid form schemas with managed lease IDs hidden", () => {
  assert.equal(operations.length, 48);
  const containsLease = (schema: JsonSchema): boolean => {
    const current = resolvedSchema(schema);
    return Boolean(current.properties?.leaseId || Object.values(current.properties ?? {}).some(containsLease) || (current.items && containsLease(current.items)));
  };
  for (const operation of operations) {
    const schema = formSchema(operation);
    assert.ok(!schema.required?.includes("leaseId"), `${operation.id} must not require a managed lease ID`);
    assert.ok(!containsLease(schema), `${operation.id} must not expose leaseId in its form`);
  }
});

test("defaults and validation preserve optional omission, nested required values, and array bounds", () => {
  const schema: JsonSchema = { type: "object", required: ["name", "meta", "steps"], properties: {
    name: { type: "string", minLength: 2, pattern: "^[a-z]+$" },
    optional: { type: "boolean", default: false },
    meta: { type: "object", required: ["count"], properties: { count: { type: "integer", minimum: 1, maximum: 3 } } },
    steps: { type: "array", minItems: 1, maxItems: 2, items: { type: "number", minimum: 0 } },
  } };
  assert.equal(defaultFor(schema.properties!.optional), false);
  assert.throws(() => validateFormValue(schema, { name: "", meta: { count: 2 }, steps: [1] }), /Fill in name/);
  assert.throws(() => validateFormValue(schema, { name: "A1", meta: { count: 2 }, steps: [1] }), /invalid format/);
  assert.throws(() => validateFormValue(schema, { name: "okay", meta: { count: 4 }, steps: [1] }), /at most 3/);
  assert.throws(() => validateFormValue(schema, { name: "okay", meta: { count: 2 }, steps: [1, 2, 3] }), /at most 2/);
  assert.throws(() => validateFormValue({ type: "number" }, Infinity), /finite number/);
  assert.doesNotThrow(() => validateFormValue(schema, { name: "okay", meta: { count: 2 }, steps: [1] }));
});

test("required controls start with included schema defaults while optional controls remain omitted", () => {
  const schema: JsonSchema = { type: "object", required: ["enabled", "settings", "steps"], properties: {
    enabled: { type: "boolean", default: false },
    optional: { type: "string", default: "seed" },
    settings: { type: "object", required: ["count"], properties: { count: { type: "integer", minimum: 2 } } },
    steps: { type: "array", minItems: 1, items: { type: "string", enum: ["walk", "turn"] } },
  } };
  const values = initializeFormValues(schema);
  assert.deepEqual(values, { enabled: false, settings: { count: 2 }, steps: ["walk"] });
  assert.doesNotThrow(() => validateFormValue(schema, values));
  assert.ok(!("optional" in values));
});

test("request previews keep path, query, body, and dynamic open-object fields distinct", () => {
  const operation: Operation = {
    id: "sample", label: "Sample", group: "Test", method: "POST", path: "/items/{itemId}", summary: "",
    parameters: [
      { name: "itemId", in: "path", required: true, schema: { type: "string" } },
      { name: "verbose", in: "query", required: false, schema: { type: "boolean" } },
    ],
    bodySchema: { type: "object", additionalProperties: true, properties: { title: { type: "string" } } },
    security: { requiredScopes: [], destructive: false, sideEffectClass: "none", requiresLease: true, supportedExecutionModes: ["safe"] },
  };
  assert.deepEqual(requestPreview(operation, { itemId: "a b", verbose: false, title: "hi", custom: { nested: [1] }, leaseId: "secret" }), {
    method: "POST", path: "/items/a%20b", pathParameters: { itemId: "a b" }, query: { verbose: false }, body: { title: "hi", custom: { nested: [1] } },
  });
  const schema = formSchema(operation);
  assert.ok(!schema.required?.includes("leaseId"));
  assert.ok(!schema.properties?.leaseId);
  assert.equal(schema.properties?.title.type, "string");
  assert.equal(schema.properties?.verbose.type, "boolean");
});
