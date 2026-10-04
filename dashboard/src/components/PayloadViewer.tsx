"use client";

import type { Operation } from "../lib/catalog";
import type { RunRecord } from "../lib/types";

function printable(value: unknown): string {
  try { return JSON.stringify(value, null, 2) ?? String(value); }
  catch { return String(value); }
}

function screenshotData(value: unknown): string | undefined {
  if (!value || typeof value !== "object") return undefined;
  const candidate = value as Record<string, unknown>;
  return typeof candidate.pngBase64 === "string" && candidate.pngBase64.length
    ? `data:image/png;base64,${candidate.pngBase64}`
    : screenshotData(candidate.data ?? candidate.body ?? candidate.result);
}

export function PayloadViewer({ record, operation }: { record: RunRecord; operation: Operation }) {
  const image = screenshotData(record.response);
  const request = record.request;
  const dispatchOnly = /dispatch|does not verify|verified separately/i.test(operation.summary) || operation.id === "dispatchCommand";
  const responseHeaders = record.responseHeaders;
  const errorHeaders = record.error?.headers;
  const hasOutcome = record.response !== undefined || record.error !== undefined || record.status !== undefined;
  const isPreset = !hasOutcome;
  const responseLabel = record.error ? record.status === 202 ? "Accepted · 202 · completion unverified" : `Error · ${record.error.code}` : record.status !== undefined ? `${record.status}${record.durationMs === undefined ? "" : ` · ${Math.round(record.durationMs)} ms`}` : hasOutcome ? "Local result" : "Not run yet";
  return <div className="payload-viewer">
    <details className="payload-section" open>
      <summary>{isPreset ? "Preset inputs · not sent yet" : `Request · ${request.method} ${request.path}`}</summary>
      <pre>{isPreset ? printable(record.values) : printable(request)}</pre>
    </details>
    {responseHeaders && Object.keys(responseHeaders).length > 0 && <details className="payload-section payload-headers">
      <summary>Response headers · {Object.keys(responseHeaders).length}</summary>
      <pre>{printable(responseHeaders)}</pre>
    </details>}
    {errorHeaders && Object.keys(errorHeaders).length > 0 && <details className="payload-section payload-headers">
      <summary>{record.status === 202 ? "Completion check error headers" : "Error response headers"} · {Object.keys(errorHeaders).length}</summary>
      <pre>{printable(errorHeaders)}</pre>
    </details>}
    <details className="payload-section" open>
      <summary>Response · {responseLabel}</summary>
      {record.error && <pre className="payload-error">{printable(record.error)}</pre>}
      {!hasOutcome
        ? <p className="payload-empty">Not run yet. Save a preset, then run it to capture a response.</p>
        : <>
          {record.status === 202 && <p className="payload-outcome">{record.error ? "Accepted (202), but the follow-up check failed. The response preserves the admission and marks completion unverified; see the error above." : "Accepted for asynchronous processing. HTTP 202 records admission; use job or world completion details in this response to assess the outcome."}</p>}
          {dispatchOnly && record.status !== 202 && <p className="payload-outcome">Dispatch receipt shown. A successful dispatch does not verify the resulting game state.</p>}
          {record.response !== undefined && image && <figure className="payload-image"><img src={image} alt="Screenshot response" />
            <a href={image} download={`mapi-screenshot-${record.id}.png`}>Download PNG</a></figure>}
          {record.response !== undefined && <pre>{printable(record.response)}</pre>}
        </>}
    </details>
  </div>;
}
