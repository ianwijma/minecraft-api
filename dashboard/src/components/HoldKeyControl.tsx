"use client";

import { useState } from "react";
import { characterToKeyCode, keyCodeToCharacter } from "../lib/key-codes";

export function HoldKeyControl({ value, onChange, label }: { value: unknown; onChange: (next: unknown) => void; label: string }) {
  const [mode, setMode] = useState("code");
  const [character, setCharacter] = useState(() => keyCodeToCharacter(value));
  const characterCode = characterToKeyCode(character);
  const hintId = "hold-key-character-hint";
  return <div>
    <div className="key-input-toggle" role="group" aria-label="Key input mode">
      <button type="button" aria-pressed={mode === "code"} onClick={() => setMode("code")}>Key code</button>
      <button type="button" aria-pressed={mode === "character"} onClick={() => {
        if (mode === "character") return;
        const next = keyCodeToCharacter(value);
        setCharacter(next); setMode("character"); onChange(characterToKeyCode(next) ?? "");
      }}>Character</button>
    </div>
    {mode === "code" ? <input type="number" aria-label={label} value={typeof value === "number" ? value : ""} step={1} onChange={(event) => onChange(event.target.value === "" ? "" : Number(event.target.value))} />
      : <><input type="text" aria-label="Key character" aria-describedby={hintId} aria-invalid={characterCode === undefined} value={character} autoCapitalize="off" autoComplete="off" spellCheck={false} placeholder="e.g. w" onChange={(event) => {
        const next = event.target.value; setCharacter(next); onChange(characterToKeyCode(next) ?? "");
      }} />
      <small id={hintId} className="schema-description" role="status">{characterCode === undefined ? "Enter one letter (A–Z), digit, space, or unshifted punctuation key: ' , - . / ; = [ \\ ] `." : `Key code: ${characterCode}${character === " " ? " (Space)" : ""}`}</small>
      <small className="schema-description">Letters are case-insensitive. This holds a key without Shift; use key code mode for special keys.</small></>}
  </div>;
}
