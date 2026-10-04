"use client";

import { useMemo, useState, type ChangeEvent } from "react";
import type { JsonSchema, Operation } from "../lib/catalog";
import { defaultFor, formSchema, initializeFormValues, isRequired, resolvedSchema, validateFormValue, type FormValues } from "../lib/forms";

type Props = { operation: Operation; initialValues?: FormValues; onValuesChange?: (values: FormValues) => void };

function ValueControl({ schema: inputSchema, value, onChange, label }: { schema: JsonSchema; value: unknown; onChange: (next: unknown) => void; label: string }) {
  const [newName, setNewName] = useState("");
  const [newType, setNewType] = useState("string");
  const [dynamicError, setDynamicError] = useState("");
  const schema = resolvedSchema(inputSchema);
  const type = Array.isArray(schema.type) ? schema.type.find((item) => item !== "null") : schema.type;
  if ((type === "object" || schema.properties || schema.additionalProperties !== undefined) && !schema.enum) {
    const properties = schema.properties ?? {};
    const dynamicNames = Object.keys((value && typeof value === "object" ? value : {}) as FormValues).filter((key) => key !== "leaseId" && !(key in properties));
    const objectValue = (value && typeof value === "object" ? value : {}) as FormValues;
    const write = (key: string, next: unknown) => onChange({ ...objectValue, [key]: next });
    return <fieldset className="schema-object"><legend>{schema.title ?? label}</legend>
      {Object.entries(properties).filter(([key]) => key !== "leaseId").map(([key, child]) => {
        const required = isRequired(schema, key);
        const included = required || Object.hasOwn(objectValue, key);
        return <div className="schema-field" key={key}>
          <div className="schema-field-label">{!required && <label><input type="checkbox" aria-label={`Include ${key}`} checked={included} onChange={(event) => { if (event.target.checked) write(key, defaultFor(child)); else { const next = { ...objectValue }; delete next[key]; onChange(next); } }} /> Include</label>}
            <strong>{key}</strong><span>{required ? "Required" : "Optional"}</span></div>
          {included && <ValueControl schema={child} label={`${label}.${key}`} value={objectValue[key] ?? defaultFor(child)} onChange={(next) => write(key, next)} />}
          {child.description && <small className="schema-description">{child.description}</small>}
        </div>;
      })}
      {dynamicNames.map((key) => <div className="schema-dynamic-field" key={key}><label>{key} <small>Added field</small></label>
        <ValueControl schema={typeof schema.additionalProperties === "object" ? schema.additionalProperties : inferSchema(objectValue[key])} label={`${label}.${key}`} value={objectValue[key]} onChange={(next) => write(key, next)} />
        <button type="button" aria-label={`Remove ${key}`} onClick={() => { const next = { ...objectValue }; delete next[key]; onChange(next); }}>Remove</button></div>)}
      {schema.additionalProperties !== false && <div className="schema-add-field"><input aria-label="New field name" placeholder="Field name" value={newName} onChange={(event) => { setNewName(event.target.value); setDynamicError(""); }} />
        <select aria-label="New field type" value={newType} onChange={(event) => setNewType(event.target.value)}>{["string", "number", "boolean", "object", "array"].map((kind) => <option key={kind}>{kind}</option>)}</select>
        <button type="button" onClick={() => { const key = newName.trim(); if (!key) { setDynamicError("Enter a field name."); return; } if (key === "leaseId") { setDynamicError("Lease IDs are managed automatically."); return; } if (key in objectValue) { setDynamicError("Field names must be unique."); return; } write(key, defaultFor({ type: newType, additionalProperties: newType === "object" ? true : undefined, items: {} })); setNewName(""); setDynamicError(""); }}>+ Add field</button>
        {dynamicError && <small className="schema-validation" role="alert">{dynamicError}</small>}</div>}
    </fieldset>;
  }
  if (type === "array") {
    const values = Array.isArray(value) ? value : [];
    return <fieldset className="schema-array"><legend>{schema.title ?? label}</legend>{values.map((item, index) => <div className="schema-array-item" key={index}>
      <strong>Item {index + 1}</strong><ValueControl schema={schema.items ?? {}} label={`${label}[${index + 1}]`} value={item} onChange={(next) => onChange(values.map((entry, i) => i === index ? next : entry))} />
      <button type="button" aria-label={`Remove ${label} item ${index + 1}`} onClick={() => onChange(values.filter((_, i) => i !== index))}>Remove</button></div>)}
      <button type="button" disabled={schema.maxItems !== undefined && values.length >= schema.maxItems} onClick={() => onChange([...values, defaultFor(schema.items ?? {})])}>+ Add item</button>
    </fieldset>;
  }
  const id = `schema-${label.replace(/[^a-z0-9_-]/gi, "-")}`;
  const common = { id, name: label, "aria-label": label, onChange: (event: ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
    const target = event.target as HTMLInputElement;
    if (target.type === "checkbox") onChange(target.checked);
    else if (target.type === "number") onChange(target.value === "" ? "" : Number(target.value));
    else onChange(target.value);
  } };
  if (schema.enum) return <select id={id} name={label} aria-label={label} value={String(value ?? schema.enum[0] ?? "")} onChange={(event) => onChange(schema.enum?.find((entry) => String(entry) === event.target.value))}>{schema.enum.map((entry) => <option key={String(entry)} value={String(entry)}>{String(entry)}</option>)}</select>;
  if (schema.const !== undefined) return <input {...common} value={String(schema.const)} readOnly />;
  if (type === "boolean") return <label className="schema-bool"><input {...common} type="checkbox" checked={Boolean(value)} /> {label}</label>;
  if (type === "integer" || type === "number") return <input {...common} type="number" value={typeof value === "number" ? value : ""} min={schema.minimum} max={schema.maximum} step={type === "integer" ? 1 : "any"} />;
  return <input {...common} type="text" value={String(value ?? "")} minLength={schema.minLength} maxLength={schema.maxLength} placeholder={schema.format === "date-time" ? "RFC 3339 date and time" : schema.description} />;
}

function inferSchema(value: unknown): JsonSchema {
  if (Array.isArray(value)) return { type: "array", items: value.length ? inferSchema(value[0]) : { type: "string" } };
  if (value && typeof value === "object") return { type: "object", properties: Object.fromEntries(Object.entries(value).map(([key, item]) => [key, inferSchema(item)])), additionalProperties: true };
  return { type: typeof value === "number" ? "number" : typeof value === "boolean" ? "boolean" : "string" };
}

export function SchemaFields({ operation, initialValues = {}, onValuesChange }: Props) {
  const schema = useMemo(() => formSchema(operation), [operation]);
  const [values, setValues] = useState<FormValues>(() => initializeFormValues(schema, initialValues));
  const set = (next: unknown) => {
    const normalized = (next && typeof next === "object" && !Array.isArray(next) ? next : {}) as FormValues;
    const initialized = initializeFormValues(schema, normalized);
    setValues(initialized); onValuesChange?.(initialized);
  };
  const errors: string[] = [];
  try { validateFormValue(schema, values, "Request"); } catch (error) { errors.push(error instanceof Error ? error.message : "Check the request fields."); }
  const hasControls = Object.keys(schema.properties ?? {}).length > 0 || schema.additionalProperties !== false;
  return <div className="schema-fields">{hasControls ? <ValueControl schema={schema} label="Request" value={values} onChange={set} /> : <p className="schema-no-fields">No request fields are needed for this operation.</p>}
    {errors.length > 0 && <p className="schema-validation" role="status">{errors[0]}</p>}
  </div>;
}
