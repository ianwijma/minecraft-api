"use client";

import { useEffect, useId, useRef, useState } from "react";
import type { ConnectionProfile, ProbeResult } from "@/lib/types";

export type ConnectOutcome = { ok: boolean; probe?: ProbeResult; error?: string };

export interface ConnectionDialogProps {
  open?: boolean;
  inline?: boolean;
  initialProfile?: ConnectionProfile;
  onClose?: () => void;
  onTest: (profile: ConnectionProfile) => Promise<ConnectOutcome>;
  onConnect: (profile: ConnectionProfile) => Promise<ConnectOutcome>;
  onRemove?: () => void;
}

export default function ConnectionDialog({ open = false, inline = false, initialProfile, onClose, onTest, onConnect, onRemove }: ConnectionDialogProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const idPrefix = useId();
  const nameId = `${idPrefix}-name`;
  const addressId = `${idPrefix}-address`;
  const tokenId = `${idPrefix}-token`;
  const generation = useRef(0);
  const [name, setName] = useState("");
  const [baseUrl, setBaseUrl] = useState("http://127.0.0.1:25586");
  const [token, setToken] = useState("");
  const [rememberToken, setRememberToken] = useState(true);
  const [busy, setBusy] = useState<"test" | "connect" | null>(null);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState(false);

  useEffect(() => {
    generation.current++;
    setName(initialProfile?.name ?? "");
    setBaseUrl(initialProfile?.baseUrl ?? "http://127.0.0.1:25586");
    setToken("");
    setRememberToken(initialProfile?.rememberToken ?? true);
    setBusy(null);
    setNotice("");
    setError(false);
  }, [initialProfile?.id, open, inline]);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog || inline) return;
    if (open && !dialog.open) dialog.showModal();
    else if (!open && dialog.open) dialog.close();
  }, [open, inline]);

  function buildProfile(): ConnectionProfile {
    const trimmed = baseUrl.trim().replace(/\/$/, "");
    const parsed = new URL(trimmed);
    if (!/^https?:$/.test(parsed.protocol)) throw new Error("Use an HTTP or HTTPS MAPI address.");
    if (parsed.username || parsed.password || parsed.search || parsed.hash) throw new Error("Do not include credentials, query values, or fragments in the MAPI address.");
    return {
      id: initialProfile?.id ?? crypto.randomUUID(),
      name: name.trim(),
      baseUrl: trimmed,
      token: token || initialProfile?.token || "",
      rememberToken,
      context: initialProfile?.context,
    };
  }

  const dismiss = () => { generation.current++; onClose?.(); };

  async function submit(action: "test" | "connect") {
    const submittedGeneration = generation.current;
    setNotice(""); setError(false);
    try {
      const profile = buildProfile();
      if (!profile.name) throw new Error("Give this connection a name.");
      if (!profile.token) throw new Error("Enter the bearer token from your MAPI configuration.");
      setBusy(action);
      const result = await (action === "test" ? onTest(profile) : onConnect(profile));
      if (submittedGeneration !== generation.current) return;
      if (!result.ok) {
        setError(true);
        setNotice(result.error ?? "Connection failed. Check the address, token, and browser origin settings.");
      } else if (action === "test") {
        const version = result.probe?.protocolVersion;
        setNotice(`Connection verified${version ? ` · protocol v${version}` : ""}. Connect to add this workspace.`);
      } else {
        setNotice("Connection verified.");
      }
    } catch (cause) {
      if (submittedGeneration !== generation.current) return;
      setError(true);
      setNotice(cause instanceof Error ? cause.message : "Could not validate this connection.");
    } finally {
      if (submittedGeneration === generation.current) setBusy(null);
    }
  }

  const form = <div className="connect-layout">
    <div className="form-card" data-testid="connect-form">
      <div className="eyebrow">{initialProfile ? "Edit connection" : "New connection"}</div>
      <h1>{initialProfile ? "Update this workspace." : "Your next test starts here."}</h1>
      <p>Connect to a Minecraft client or server running MAPI.</p>
      <form onSubmit={(event) => { event.preventDefault(); void submit("connect"); }}>
        <label className="field-label" htmlFor={nameId}>Connection name</label>
        <input className="text-input" id={nameId} data-testid="connection-name" value={name} onChange={(event) => setName(event.target.value)} autoComplete="off" required />
        <label className="field-label" htmlFor={addressId}>MAPI address</label>
        <input className="text-input" id={addressId} data-testid="connection-address" type="url" value={baseUrl} onChange={(event) => setBaseUrl(event.target.value)} required />
        <div className="hint">The MAPI HTTP port, separate from Minecraft’s multiplayer port. 127.0.0.1 refers to the device running this browser; reaching MAPI on another computer requires separately configured network access.</div>
        <label className="field-label" htmlFor={tokenId}>Bearer token</label>
        <input className="text-input" id={tokenId} data-testid="connection-token" type="password" value={token} onChange={(event) => setToken(event.target.value)} placeholder={initialProfile?.token ? "Leave blank to keep the saved token" : "Paste from your MAPI configuration"} autoComplete="new-password" />
        <label className="check-row"><input type="checkbox" data-testid="remember-token" checked={rememberToken} onChange={(event) => setRememberToken(event.target.checked)} /><span><strong>Remember this connection and token on this device</strong><span className="hint">Browser storage is not encrypted. On a shared device, turn this off and re-enter the token after reload.</span></span></label>
        <details className="hint"><summary>Browser access and privacy</summary><p>MAPI must be enabled, and its exact-origin browser allowlist must include this dashboard. The dashboard sends requests directly from your browser to MAPI; tokens stay in this browser and are never placed in URLs.</p></details>
        <div className="form-actions">
          <button className="button primary" type="submit" data-testid="connect-submit" disabled={busy !== null}>{busy === "connect" ? "Connecting…" : initialProfile ? "Save and reconnect" : "Connect →"}</button>
          <button className="button" type="button" data-testid="test-connection" onClick={() => void submit("test")} disabled={busy !== null}>{busy === "test" ? "Testing…" : "Test connection"}</button>
          {!inline && <button className="button" type="button" onClick={dismiss}>Cancel</button>}
          {initialProfile && onRemove && <button className="button danger" type="button" data-testid="remove-from-setup" onClick={onRemove}>Remove connection</button>}
        </div>
        {notice && <div role={error ? "alert" : "status"} className={`notice${error ? " error" : ""}`} data-testid="connection-notice">{notice}</div>}
      </form>
    </div>
    <div className="explain">
      <div className="eyebrow">One workspace. Every operation.</div>
      <h1>Less setup.<br />More Minecraft.</h1>
      <p>Observe the world, test an input, step the simulation. Keep each instance in its own connection tab.</p>
      <div className="steps">
        <div className="step"><b>01</b><div><strong>Enable MAPI in your mod config</strong><p>Turn on the HTTP API and copy the generated token.</p></div></div>
        <div className="step"><b>02</b><div><strong>Allow this dashboard’s origin</strong><p>Use MAPI’s opt-in exact-origin browser access setting.</p></div></div>
        <div className="step"><b>03</b><div><strong>Connect and discover capabilities</strong><p>Client and server controls adapt to the running instance.</p></div></div>
      </div>
      <div className="note">Browser network errors can hide the cause. Check MAPI’s origin allowlist and your browser’s local-network permission if the test cannot reach the server.</div>
      {initialProfile && onRemove && inline && <button className="button danger" type="button" data-testid="remove-disconnected" style={{ marginTop: 15 }} onClick={onRemove}>Remove this connection</button>}
    </div>
  </div>;

  if (inline) return form;
  return <dialog ref={dialogRef} className="connection-dialog" aria-label={initialProfile ? `Edit ${initialProfile.name}` : "Add connection"} data-testid="connection-dialog" onCancel={(event) => { event.preventDefault(); dismiss(); }} onClose={dismiss}>
    <div className="dialog-top"><span className="eyebrow">{initialProfile ? "Edit connection" : "New connection"}</span><button className="button small" type="button" aria-label="Close connection dialog" onClick={dismiss}>×</button></div>
    {form}
  </dialog>;
}
