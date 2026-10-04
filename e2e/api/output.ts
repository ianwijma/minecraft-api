import assert from 'node:assert/strict';
import * as fs from 'node:fs';

export function reserveOutput(directory: string): void {
    assert.ok(!fs.existsSync(directory), `refusing to overwrite first-attempt evidence: ${directory}; choose a fresh --out directory`);
    fs.mkdirSync(directory, { recursive: true });
}
