"use client";

import { useMemo } from "react";
import type { CapabilityContext, RunRecord, RuntimeEvent } from "@/lib/types";

function pretty(value: unknown): string {
  try { return JSON.stringify(value ?? null, null, 2); } catch { return String(value); }
}

export default function ResponseInspector({ latest, events, capabilities, onClearEvents, streamActive = false, onToggleEvents, onCopyResponse }: { latest?: RunRecord; events: RuntimeEvent[]; capabilities: CapabilityContext; onClearEvents: () => void; streamActive?: boolean; onToggleEvents?: () => void; onCopyResponse?: () => void }) {
  const responseText = useMemo(() => {
    if (!latest) return "Connect to MAPI to inspect its latest response.";
    if (latest.status === 202 && latest.error && latest.response !== undefined) return pretty({ accepted: latest.response, followUpError: latest.error });
    return pretty(latest.error ?? latest.response);
  }, [latest]);
  const responseHeaders = latest?.responseHeaders;
  const errorHeaders = latest?.error?.headers;
  const modeLabel = capabilities.mode === "unknown" ? "Capabilities unverified" : capabilities.mode === "integrated" ? "Client + integrated server" : capabilities.mode === "dedicated" ? "Dedicated server" : capabilities.mode === "remote" ? "Client on remote server" : "Client menu";

  return <aside className="inspector" data-testid="activity-inspector" aria-label="Latest response and activity">
    <div className="inspector-title"><h2>Latest response</h2><div style={{ display: "flex", alignItems: "center", gap: 7 }}><span>JSON · redacted</span><button className="button small" type="button" disabled={!latest} onClick={onCopyResponse}>Copy</button></div></div>
    <div className="response-block">
      <div className="response-meta"><span>{latest?.status === 202 && latest.error ? `202 accepted · follow-up error ${latest.error.code}` : latest?.error ? `Error · ${latest.error.code}` : latest?.status ? `${latest.status} response` : latest?.response !== undefined ? "Local result" : "No request yet"}</span><span>{latest?.durationMs !== undefined ? `${Math.round(latest.durationMs)} ms` : ""}</span></div>
      <pre className="json-view" data-testid="latest-response">{responseText}</pre>
      {responseHeaders && <details className="response-headers"><summary>Response headers · {Object.keys(responseHeaders).length}</summary><pre>{pretty(responseHeaders)}</pre></details>}
      {errorHeaders && <details className="response-headers"><summary>{latest?.status === 202 ? "Completion check headers" : "Error response headers"} · {Object.keys(errorHeaders).length}</summary><pre>{pretty(errorHeaders)}</pre></details>}
    </div>
    <div className="lease-info"><strong>Capabilities</strong><span style={{ display: "block", marginTop: 3 }}>{modeLabel} · {capabilities.capabilities.length ? capabilities.capabilities.join(", ") : "No capability metadata"}</span></div>
    <section className="activity" aria-label="Activity and events">
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 8, padding: "12px 14px", borderBottom: "1px solid var(--line)" }}><h2>Activity &amp; events</h2><div style={{ display: "flex", gap: 6 }}><button className="button small" type="button" onClick={onToggleEvents} aria-pressed={streamActive}>{streamActive ? "Stop stream" : "Start stream"}</button><button className="button small" type="button" onClick={onClearEvents} aria-label="Clear activity events">Clear</button></div></div>
      <div className="event-list" data-testid="activity-list" aria-live="polite" aria-relevant="additions text">
        {events.length === 0 ? <p style={{ paddingTop: 12, fontSize: 11 }}>Events from this connection will appear here.</p> : events.map((event, index) => {
          const eventId = (event as RuntimeEvent & { id?: string }).id ?? `${event.seq ?? "event"}-${event.atEpochMs ?? index}-${index}`;
          return <div className="event-row" key={eventId} data-event-id={eventId}>
          {event.gap ? "Event stream gap detected" : event.type ?? "MAPI event"}
          <small>{event.atEpochMs ? new Date(event.atEpochMs).toLocaleTimeString() : "Live event"}{event.seq !== undefined ? ` · #${event.seq}` : ""}</small>
        </div>;
        })}
      </div>
    </section>
  </aside>;
}
