"use client";

import { useEffect, useRef } from "react";
import type { ConnectionProfile } from "@/lib/types";

export default function RemovalDialog({ profile, onCancel, onConfirm }: { profile: ConnectionProfile | null; onCancel: () => void; onConfirm: () => void }) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (profile && !dialog.open) {
      dialog.showModal();
      requestAnimationFrame(() => cancelRef.current?.focus());
    } else if (!profile && dialog.open) dialog.close();
  }, [profile?.id]);

  return <dialog ref={dialogRef} className="remove-dialog" aria-labelledby="remove-title" aria-describedby="remove-description" data-testid="remove-dialog" onCancel={(event) => { event.preventDefault(); onCancel(); }} onClose={onCancel}>
    <div className="eyebrow">Connection settings</div>
    <h2 id="remove-title" style={{ margin: "6px 0 9px", fontSize: 20 }}>Remove connection?</h2>
    <p id="remove-description">Remove “{profile?.name ?? ""}”? This clears its saved credentials, presets, history, and active requests from this browser. Minecraft will keep running.</p>
    <div className="form-actions">
      <button ref={cancelRef} className="button" type="button" data-testid="cancel-remove" onClick={onCancel}>Cancel</button>
      <button className="button primary" type="button" data-testid="confirm-remove" onClick={onConfirm}>Remove connection</button>
    </div>
  </dialog>;
}
