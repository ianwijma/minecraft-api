/** JSON Schema subset emitted by the OpenAPI catalog generator. */
export interface JsonSchema {
  $schema?: string;
  type?: string | string[];
  title?: string;
  description?: string;
  format?: string;
  properties?: Record<string, JsonSchema>;
  required?: string[];
  enum?: unknown[];
  const?: unknown;
  default?: unknown;
  items?: JsonSchema;
  additionalProperties?: boolean | JsonSchema;
  minimum?: number;
  maximum?: number;
  exclusiveMinimum?: number;
  exclusiveMaximum?: number;
  minLength?: number;
  maxLength?: number;
  minItems?: number;
  maxItems?: number;
  pattern?: string;
  nullable?: boolean;
  oneOf?: JsonSchema[];
  anyOf?: JsonSchema[];
  allOf?: JsonSchema[];
  [keyword: string]: unknown;
}

export interface OperationParameter {
  name: string;
  in: "query" | "path";
  required: boolean;
  schema: JsonSchema;
}

export interface OperationSecurity {
  requiredScopes: string[];
  destructive: boolean;
  sideEffectClass: string;
  requiresLease: boolean;
  supportedExecutionModes: string[];
}

export interface Operation {
  id: string;
  label: string;
  group: string;
  method: "GET" | "POST";
  path: string;
  summary: string;
  parameters: OperationParameter[];
  bodySchema?: JsonSchema;
  security: OperationSecurity;
  responses?: Record<string, JsonSchema>;
}
