const printableKeys = " ',-./0123456789;=ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]`";

export function characterToKeyCode(character: string): number | undefined {
  if (character.length !== 1 || !/^[\x20-\x7e]$/.test(character)) return undefined;
  const key = character.toUpperCase();
  return printableKeys.includes(key) ? key.charCodeAt(0) : undefined;
}

export function keyCodeToCharacter(keyCode: unknown): string {
  if (typeof keyCode !== "number" || !Number.isInteger(keyCode)) return "";
  return [...printableKeys].find((key) => key.charCodeAt(0) === keyCode)?.toLowerCase() ?? "";
}
