"use client";

import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { JsonSchema, Operation } from "../lib/catalog";
import type { RunRecord } from "../lib/types";
import { formSchema, initializeFormValues, requestPreview as makeRequestPreview, validateFormValue, type FormValues } from "../lib/forms";
import { SchemaFields } from "./SchemaFields";
import { PayloadViewer } from "./PayloadViewer";

export type ActionModalProps = {
  operation: Operation;
  connectionId: string;
  records: RunRecord[];
  onClose: () => void;
  onRun: (values: FormValues) => Promise<void | RunRecord>;
  onSavePreset: (values: FormValues) => void | Promise<void>;
  onToggleSaved: (recordId: string) => void;
  onClearRecent: () => void;
  onClearSaved: () => void;
  leaseStatus?: string;
};

const actionDrafts = new Map<string, FormValues>();

function draftKey(connectionId: string, operationId: string): string {
  return JSON.stringify([connectionId, operationId]);
}

function restoreDraft(connectionId: string, operation: Operation, schema: JsonSchema): FormValues {
  const stored = actionDrafts.get(draftKey(connectionId, operation.id)) ?? {};
  const values = initializeFormValues(schema, stored);
  delete values.leaseId;
  if (operation.security.destructive && Object.hasOwn(schema.properties ?? {}, "confirm")) values.confirm = false;
  return values;
}

function storeDraft(connectionId: string, operation: Operation, schema: ReturnType<typeof formSchema>, input: FormValues): void {
  const values = initializeFormValues(schema, input);
  delete values.leaseId;
  if (operation.security.destructive) delete values.confirm;
  actionDrafts.set(draftKey(connectionId, operation.id), structuredClone(values));
}

export function clearActionDrafts(connectionId: string): void {
  for (const key of actionDrafts.keys()) {
    try { if ((JSON.parse(key) as [string, string])[0] === connectionId) actionDrafts.delete(key); }
    catch { actionDrafts.delete(key); }
  }
}

export function ActionModal({ operation, connectionId, records, onClose, onRun, onSavePreset, onToggleSaved, onClearRecent, onClearSaved, leaseStatus }: ActionModalProps) {
  const listRef = useRef<HTMLDivElement>(null);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const closeCallbackRef = useRef(onClose);
  closeCallbackRef.current = onClose;
  const anchorRef = useRef<{ id: string; top: number } | null>(null);
  const schema = useMemo(() => formSchema(operation), [operation]);
  const [values, setValues] = useState<FormValues>(() => restoreDraft(connectionId, operation, schema));
  const [formVersion, setFormVersion] = useState(0);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [copyMessage, setCopyMessage] = useState("");
  const ordered = useMemo(() => [...records].sort((a, b) => Number(b.saved) - Number(a.saved) || (Date.parse(b.at) || 0) - (Date.parse(a.at) || 0)), [records]);
  const dispatchOnly = /dispatch|does not verify|verified separately/i.test(operation.summary) || operation.id === "dispatchCommand";

  useEffect(() => { const restored = restoreDraft(connectionId, operation, schema); setValues(restored); setMessage(""); setCopyMessage(""); setFormVersion((n) => n + 1); }, [connectionId, operation.id, schema]);
  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (!dialog.open) dialog.showModal();
    const handleCancel = (event: Event) => { event.preventDefault(); closeCallbackRef.current(); };
    dialog.addEventListener("cancel", handleCancel);
    return () => { dialog.removeEventListener("cancel", handleCancel); if (dialog.open) dialog.close(); };
  }, []);
  useLayoutEffect(() => {
    const anchor = anchorRef.current;
    const list = listRef.current;
    if (!anchor || !list) return;
    const item = [...list.querySelectorAll<HTMLElement>("[data-record-id]")].find((element) => element.dataset.recordId === anchor.id);
    if (item) list.scrollTop += item.getBoundingClientRect().top - anchor.top;
    anchorRef.current = null;
  }, [ordered]);

  const rememberAnchor = () => {
    const list = listRef.current;
    if (!list) return;
    const bounds = list.getBoundingClientRect();
    const visible = [...list.querySelectorAll<HTMLElement>("[data-record-id]")].find((element) => element.getBoundingClientRect().bottom > bounds.top);
    if (visible) anchorRef.current = { id: visible.dataset.recordId ?? "", top: visible.getBoundingClientRect().top };
  };
  const safeValues = (input: FormValues) => {
    const copy = initializeFormValues(schema, input);
    delete copy.leaseId;
    return copy;
  };
  const updateDraft = (input: FormValues) => {
    const normalized = safeValues(input);
    setValues(normalized);
    storeDraft(connectionId, operation, schema, normalized);
  };
  const copyRequest = async (record: RunRecord) => {
    setCopyMessage("");
    try {
      await navigator.clipboard.writeText(JSON.stringify(record.request, null, 2));
      setCopyMessage("Request copied.");
    } catch {
      setCopyMessage("Clipboard unavailable. Expand the Request section to select its redacted JSON.");
    }
  };
  const generatedRequest = makeRequestPreview(operation, values);
  const performRun = async (input = values) => {
    if (operation.security.destructive && input.confirm !== true) { setMessage("Enable confirm to express destructive intent again."); return; }
    try { validateFormValue(schema, input, "Request"); }
    catch (error) { setMessage(error instanceof Error ? error.message : "Check the request fields."); return; }
    setBusy(true); setMessage(""); setCopyMessage(""); rememberAnchor();
    try {
      const record = await onRun(safeValues(input));
      if (record) setMessage(operation.id === "streamEvents" ? "Event stream started. New events appear in the Activity panel." : record.error ? `${record.error.code}: ${record.error.message}` : `${record.status === 202 ? "Request accepted" : dispatchOnly ? "Dispatch completed" : "Request completed"}${record.status ? ` · ${record.status}` : ""}${record.durationMs !== undefined ? ` · ${Math.round(record.durationMs)} ms` : ""}.`);
      else setMessage("Request submitted.");
    } catch (error) {
      const text = error instanceof Error ? error.message : "The request failed.";
      setMessage(text);
    } finally { setBusy(false); }
  };
  const loadRecord = (record: RunRecord) => {
    const next = safeValues(record.values);
    if (operation.security.destructive && "confirm" in next) next.confirm = false;
    updateDraft(next); setFormVersion((n) => n + 1); setMessage(operation.security.destructive ? "Confirm destructive intent again before running." : "Fields loaded.");
  };
  const savePreset = async () => {
    setBusy(true); setMessage(""); setCopyMessage("");
    try { const next = safeValues(values); if (operation.security.destructive && "confirm" in next) next.confirm = false; rememberAnchor(); await onSavePreset(next); setMessage("Preset saved."); }
    catch (error) { setMessage(error instanceof Error ? error.message : "Could not save preset."); }
    finally { setBusy(false); }
  };

  return <dialog ref={dialogRef} className="action-modal action-modal-backdrop" aria-labelledby="action-modal-title" onClick={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <header className="action-modal-heading">
        <div><p className="action-modal-kicker">{operation.group}</p><h2 id="action-modal-title">{operation.label}</h2><p>{operation.summary}</p>
          <code>{operation.method} {operation.path}</code></div>
        <button type="button" aria-label="Close action" onClick={onClose}>×</button>
      </header>
      <div className="action-modal-layout">
        <section className="action-form-pane" aria-label="Action fields">
          <div className="action-pane-scroll">
            <div className="action-metadata"><strong>Execution modes</strong><span>{operation.security.supportedExecutionModes.join(", ") || "Any supported mode"}</span>
              <strong>Scopes</strong><span>{operation.security.requiredScopes.join(", ") || "None"}</span>
              <strong>Intent</strong><span>{operation.security.destructive ? "Destructive action · explicit confirmation required" : operation.security.sideEffectClass}</span></div>
            {operation.security.requiresLease && <p className="lease-notice">Lease IDs are managed automatically. {leaseStatus ?? "The dashboard will acquire or reuse a lease when required."}</p>}
            <SchemaFields key={`${operation.id}-${connectionId}-${formVersion}`} operation={operation} initialValues={values} onValuesChange={updateDraft} />
            <details className="generated-request"><summary>Generated request · read only</summary><pre>{JSON.stringify({ ...generatedRequest, ...(operation.security.requiresLease ? { leaseId: "[managed automatically]" } : {}) }, null, 2)}</pre></details>
          </div>
          <footer className="action-form-footer">
            <button type="button" onClick={() => void savePreset()} disabled={busy}>Save preset</button>
            <button className="action-run-button" type="button" disabled={busy} onClick={() => void performRun()}>{busy ? "Running…" : "Run action"}</button>
          </footer>
        </section>
        <section className="action-history-pane" aria-label="Saved and recent calls">
          <header className="action-history-heading"><div><h3>Saved &amp; recent</h3><p>{ordered.length} item{ordered.length === 1 ? "" : "s"} · this connection and operation</p></div>
            <div className="action-history-clears"><button type="button" onClick={() => { rememberAnchor(); onClearRecent(); }}>Clear recent</button><button type="button" onClick={() => { rememberAnchor(); onClearSaved(); }}>Clear saved</button></div></header>
          <div className="action-history-list" ref={listRef}>
            {ordered.length === 0 ? <p className="action-history-empty">No saved presets or recent calls for this action.</p> : ordered.map((record) => <article className={`action-history-card${record.saved ? " is-saved" : ""}`} key={record.id} data-record-id={record.id}>
              <header><strong>{record.saved ? "★ Saved" : "Recent"}</strong><time>{new Date(record.at).toLocaleString()}</time><span>{record.error ? "Failed" : record.status !== undefined ? `${record.status}${record.durationMs === undefined ? "" : ` · ${Math.round(record.durationMs)} ms`}` : record.response !== undefined ? "Local result" : "Not run yet"}</span></header>
              <PayloadViewer record={record} operation={operation} />
              <div className="action-history-buttons"><button type="button" aria-label={`Load fields for ${operation.label} from ${new Date(record.at).toLocaleString()}`} onClick={() => loadRecord(record)}>Load</button>
                <button type="button" aria-label={`Rerun ${operation.label} from ${new Date(record.at).toLocaleString()}`} onClick={() => { const next = safeValues(record.values); if (operation.security.destructive && "confirm" in next) next.confirm = false; updateDraft(next); setFormVersion((n) => n + 1); if (operation.security.destructive) setMessage("Confirm destructive intent again before rerunning."); else void performRun(next); }}>Rerun</button>
                <button type="button" aria-label={`Copy redacted request for ${operation.label} from ${new Date(record.at).toLocaleString()}`} onClick={() => void copyRequest(record)}>Copy request</button>
                <button type="button" aria-label={`${record.saved ? "Unsave" : "Save"} ${operation.label} from ${new Date(record.at).toLocaleString()}`} onClick={() => { rememberAnchor(); onToggleSaved(record.id); }}>{record.saved ? "Unsave" : "Save"}</button></div>
            </article>)}
          </div>
        </section>
      </div>
      <div className="action-modal-live" aria-live="polite">{copyMessage || message}</div>
  </dialog>;
}
