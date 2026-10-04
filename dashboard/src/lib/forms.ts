import type { JsonSchema, Operation } from "./catalog";

export type FormValues = Record<string, unknown>;

function omitManagedLease(schemaInput: JsonSchema): JsonSchema {
  const schema = resolvedSchema(schemaInput);
  const result: JsonSchema = { ...schema };
  if (schema.properties) result.properties = Object.fromEntries(Object.entries(schema.properties).filter(([key]) => key !== "leaseId").map(([key, child]) => [key, omitManagedLease(child)]));
  if (schema.required) result.required = schema.required.filter((key) => key !== "leaseId");
  if (schema.items) result.items = omitManagedLease(schema.items);
  return result;
}

export function resolvedSchema(schema: JsonSchema): JsonSchema {
  if (!schema.allOf?.length) return schema;
  const result: JsonSchema = { ...schema, properties: {}, required: [] };
  delete result.allOf;
  for (const part of schema.allOf) {
    const child = resolvedSchema(part);
    result.properties = { ...result.properties, ...child.properties };
    result.required = [...new Set([...(result.required ?? []), ...(child.required ?? [])])];
    for (const [key, value] of Object.entries(child)) {
      if (key !== "properties" && key !== "required") result[key] = value;
    }
  }
  if (!Object.keys(result.properties ?? {}).length) delete result.properties;
  if (!result.required?.length) delete result.required;
  return result;
}

export function defaultFor(schemaInput: JsonSchema): unknown {
  const schema = resolvedSchema(schemaInput);
  if (schema.default !== undefined) return structuredClone(schema.default);
  if (schema.const !== undefined) return schema.const;
  if (schema.enum?.length) return schema.enum[0];
  const type = Array.isArray(schema.type) ? schema.type.find((item) => item !== "null") : schema.type;
  if (type === "object" || schema.properties || schema.additionalProperties) return {};
  if (type === "array") return [];
  if (type === "boolean") return false;
  if (type === "integer" || type === "number") return schema.minimum ?? 0;
  return "";
}

export function isRequired(schema: JsonSchema, name: string): boolean {
  return Boolean(resolvedSchema(schema).required?.includes(name));
}

export function initializeFormValues(schemaInput: JsonSchema, input: unknown = {}): FormValues {
  return (initializeValue(schemaInput, input, true) ?? {}) as FormValues;
}

function initializeValue(schemaInput: JsonSchema, input: unknown, include: boolean): unknown {
  const schema = resolvedSchema(schemaInput);
  const types = Array.isArray(schema.type) ? schema.type : schema.type ? [schema.type] : [];
  if ((types.includes("object") || schema.properties || schema.additionalProperties !== undefined) && !schema.enum) {
    if (!include) return undefined;
    const supplied = input && typeof input === "object" && !Array.isArray(input) ? input as FormValues : {};
    const output: FormValues = {};
    for (const [key, child] of Object.entries(schema.properties ?? {})) {
      if (key === "leaseId") continue;
      if (Object.hasOwn(supplied, key)) output[key] = initializeValue(child, supplied[key], true);
      else if (schema.required?.includes(key)) output[key] = initializeValue(child, undefined, true);
    }
    for (const [key, value] of Object.entries(supplied)) {
      if (key === "leaseId" || Object.hasOwn(schema.properties ?? {}, key)) continue;
      if (schema.additionalProperties === false) continue;
      const child = typeof schema.additionalProperties === "object" ? schema.additionalProperties : inferValueSchema(value);
      output[key] = initializeValue(child, value, true);
    }
    return output;
  }
  if (types.includes("array")) {
    if (!include) return undefined;
    const entries = Array.isArray(input) ? input : Array.from({ length: schema.minItems ?? 0 }, () => undefined);
    return entries.map((value) => initializeValue(schema.items ?? {}, value, true));
  }
  if (!include) return undefined;
  return input === undefined ? defaultFor(schema) : structuredClone(input);
}

function inferValueSchema(value: unknown): JsonSchema {
  if (Array.isArray(value)) return { type: "array", items: value.length ? inferValueSchema(value[0]) : { type: "string" } };
  if (value && typeof value === "object") return { type: "object", properties: Object.fromEntries(Object.entries(value).map(([key, item]) => [key, inferValueSchema(item)])), additionalProperties: true };
  return { type: typeof value === "number" ? "number" : typeof value === "boolean" ? "boolean" : "string" };
}

export function formSchema(operation: Operation): JsonSchema {
  const properties: Record<string, JsonSchema> = {};
  const required: string[] = [];
  for (const parameter of operation.parameters) {
    if (parameter.name === "leaseId") continue;
    properties[parameter.name] = omitManagedLease(parameter.schema);
    if (parameter.required) required.push(parameter.name);
  }
  if (operation.bodySchema) {
    const body = omitManagedLease(operation.bodySchema);
    Object.assign(properties, body.properties ?? {});
    required.push(...(body.required ?? []));
    return { ...body, properties, required: [...new Set(required)].filter((key) => key !== "leaseId") };
  }
  return { type: "object", properties, required: [...new Set(required)], additionalProperties: false };
}

export function requestPreview(operation: Operation, values: FormValues): Record<string, unknown> {
  let path = operation.path;
  const pathParameters: Record<string, unknown> = {};
  const query: Record<string, unknown> = {};
  for (const parameter of operation.parameters) {
    const value = values[parameter.name];
    if (value === undefined || parameter.name === "leaseId") continue;
    if (parameter.in === "path") {
      pathParameters[parameter.name] = value;
      path = path.replace(`{${parameter.name}}`, encodeURIComponent(String(value)));
    } else query[parameter.name] = value;
  }
  const bodyKeys = new Set(Object.keys(resolvedSchema(operation.bodySchema ?? {}).properties ?? {}));
  const parameterNames = new Set(operation.parameters.map((parameter) => parameter.name));
  for (const key of Object.keys(values)) if (!parameterNames.has(key) && key !== "leaseId") bodyKeys.add(key);
  const body = Object.fromEntries([...bodyKeys].filter((key) => values[key] !== undefined && key !== "leaseId").map((key) => [key, values[key]]));
  return {
    method: operation.method,
    path,
    ...(Object.keys(pathParameters).length ? { pathParameters } : {}),
    ...(Object.keys(query).length ? { query } : {}),
    ...(operation.bodySchema ? { body } : {}),
  };
}

export function validateFormValue(schemaInput: JsonSchema, input: unknown, label = "Value"): void {
  const schema = resolvedSchema(schemaInput);
  const types = Array.isArray(schema.type) ? schema.type : schema.type ? [schema.type] : [];
  if (input === null && schema.nullable) return;
  if (schema.const !== undefined && input !== schema.const) throw new Error(`${label} must equal ${String(schema.const)}.`);
  if ((types.includes("number") || types.includes("integer")) && typeof input === "number" && !Number.isFinite(input)) throw new Error(`${label} must be a finite number.`);
  if (types.length) {
    const matches = types.some((type) => type === "null" ? input === null
      : type === "object" ? Boolean(input && typeof input === "object" && !Array.isArray(input))
        : type === "array" ? Array.isArray(input)
          : type === "integer" ? typeof input === "number" && Number.isInteger(input)
            : type === "number" ? typeof input === "number" && Number.isFinite(input)
              : type === "string" ? typeof input === "string"
                : type === "boolean" ? typeof input === "boolean" : true);
    if (!matches) throw new Error(`${label} has an invalid type.`);
  }
  if (types.includes("object") || schema.properties) {
    if (!input || typeof input !== "object" || Array.isArray(input)) throw new Error(`${label} must be an object.`);
    const object = input as Record<string, unknown>;
    for (const key of schema.required ?? []) {
      if (!(key in object)) throw new Error(`Fill in ${key}.`);
      if (typeof object[key] === "string" && !object[key].trim()) throw new Error(`Fill in ${key}.`);
    }
    for (const [key, value] of Object.entries(object)) {
      if (key === "leaseId") throw new Error("Lease IDs are managed automatically.");
      const child = schema.properties?.[key] ?? (typeof schema.additionalProperties === "object" ? schema.additionalProperties : undefined);
      if (child) validateFormValue(child, value, key);
      else if (schema.additionalProperties === false) throw new Error(`Unknown field ${key}.`);
    }
    return;
  }
  if (types.includes("array")) {
    if (!Array.isArray(input)) throw new Error(`${label} must be a list.`);
    if (schema.minItems !== undefined && input.length < schema.minItems) throw new Error(`${label} needs at least ${schema.minItems} item(s).`);
    if (schema.maxItems !== undefined && input.length > schema.maxItems) throw new Error(`${label} allows at most ${schema.maxItems} item(s).`);
    input.forEach((item, index) => validateFormValue(schema.items ?? {}, item, `${label} ${index + 1}`));
    return;
  }
  if (types.includes("boolean") && typeof input !== "boolean") throw new Error(`${label} must be true or false.`);
  if ((types.includes("integer") || types.includes("number")) && typeof input !== "number") throw new Error(`${label} must be a number.`);
  if (types.includes("integer") && !Number.isInteger(input)) throw new Error(`${label} must be a whole number.`);
  if (typeof input === "number") {
    if (!Number.isFinite(input)) throw new Error(`${label} must be a finite number.`);
    if (schema.minimum !== undefined && input < schema.minimum) throw new Error(`${label} must be at least ${schema.minimum}.`);
    if (schema.maximum !== undefined && input > schema.maximum) throw new Error(`${label} must be at most ${schema.maximum}.`);
    if (schema.exclusiveMinimum !== undefined && input <= schema.exclusiveMinimum) throw new Error(`${label} must be greater than ${schema.exclusiveMinimum}.`);
    if (schema.exclusiveMaximum !== undefined && input >= schema.exclusiveMaximum) throw new Error(`${label} must be less than ${schema.exclusiveMaximum}.`);
  }
  if (typeof input === "string") {
    if (schema.minLength !== undefined && input.length < schema.minLength) throw new Error(`${label} is too short.`);
    if (schema.maxLength !== undefined && input.length > schema.maxLength) throw new Error(`${label} is too long.`);
    if (schema.pattern !== undefined && !new RegExp(schema.pattern).test(input)) throw new Error(`${label} has an invalid format.`);
  }
  if (schema.enum && !schema.enum.some((choice) => choice === input)) throw new Error(`${label} must be one of the listed options.`);
}
