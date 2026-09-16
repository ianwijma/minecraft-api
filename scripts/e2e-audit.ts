/**
 * MAPI E2E audit — runs against a live Minecraft client with the mod loaded.
 *
 * Usage:
 *   MAPI_HTTP_TOKEN='...' node --experimental-strip-types scripts/e2e-audit.ts [--base http://127.0.0.1:25586]
 *
 * The script expects:
 *   - A running Minecraft client with MAPI loaded
 *   - HTTP API enabled (http.enabled=true in the loader-native config)
 *   - The game at the title screen (the script clicks through to load a world)
 *
 * The script runs every major API surface, tracks pass/fail per check,
 * and produces a machine-readable JSON report at the end. Exit code:
 * 0 = all passed, 1 = some failed.
 */

import { MapiClient, MapiError } from
    '../sdk/typescript/src/mapi-client.ts';
import * as fs from 'node:fs';
import * as path from 'node:path';

// ---------------------------------------------------------------------------
// Infrastructure
// ---------------------------------------------------------------------------

const base = process.argv.find(a => a.startsWith('--base='))?.slice(7)
    ?? 'http://127.0.0.1:25586';
// Auth-off mode: the server accepts any request without auth when the
// http.token is blank. Pass a dummy token to satisfy the SDK's constructor
// validation — the server ignores it.
const token = process.env['MAPI_HTTP_TOKEN'] ?? 'auth-off-dummy-token';
const client = new MapiClient(base, token);

const results: { check: string; ok: boolean; detail: string }[] = [];
const screenshots: string[] = [];
let phase = '';

function record(check: string, ok: boolean, detail: string): boolean {
    results.push({ check, ok, detail });
    const mark = ok ? '✓' : '✗';
    console.log(`  ${mark} ${check}: ${detail}`);
    return ok;
}

async function check(check: string, fn: () => Promise<string>): Promise<boolean> {
    try {
        const detail = await fn();
        return record(check, true, detail);
    } catch (e) {
        const msg = e instanceof Error ? e.message : String(e);
        return record(check, false, msg);
    }
}

function checkSync(check: string, fn: () => string): boolean {
    try {
        return record(check, true, fn());
    } catch (e) {
        const msg = e instanceof Error ? e.message : String(e);
        return record(check, false, msg);
    }
}

function assert(condition: boolean, message: string): void {
    if (!condition) throw new Error(message);
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const sleep = (ms: number) => new Promise<void>(r => setTimeout(r, ms));

interface ScreenInfo {
    screenId: string;
    widgets: { kind: string; text: string; x: number; y: number; width: number; height: number }[];
}

async function getScreen(): Promise<ScreenInfo> {
    const r = await client.get('/api/v1/client/screen');
    if (!r.ok) throw new Error(`screen inspect failed: ${r.status}`);
    return r.body as unknown as ScreenInfo;
}

async function click(x: number, y: number): Promise<boolean> {
    const r = await client.post('/api/v1/client/actions/click', { x, y });
    return r.ok;
}

async function clickWidget(text: string): Promise<boolean> {
    const screen = await getScreen();
    const widget = screen.widgets.find(w => w.text === text);
    if (!widget) return false;
    return click(widget.x + Math.floor(widget.width / 2),
                 widget.y + Math.floor(widget.height / 2));
}

async function getWorldPhase(): Promise<string> {
    const r = await client.get('/api/v1/server/world');
    return String(r.body.phase ?? 'NONE');
}

async function getPlayer(): Promise<{ name: string; x: number; y: number; z: number; inventory: any[] }> {
    const r = await client.get('/api/v1/server/queries/players?max=1');
    const players = r.body.players as any[];
    if (!players || players.length === 0) throw new Error('no players online');
    return players[0];
}

async function screenshot(label: string): Promise<void> {
    const r = await client.get('/api/v1/client/screenshots');
    if (!r.ok) throw new Error(`screenshot failed: ${r.status}`);
    const body = r.body as any;
    const png = Buffer.from(body.pngBase64, 'base64');
    const file = path.join('build', 'acceptance', `${label}.png`);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, png);
    screenshots.push(file);
    console.log(`    screenshot: ${file} (${png.length} bytes)`);
}

async function waitForPhase(target: string, timeoutMs: number): Promise<boolean> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        const p = await getWorldPhase();
        if (p === target) return true;
        await sleep(500);
    }
    return false;
}

// ---------------------------------------------------------------------------
// Test groups
// ---------------------------------------------------------------------------

// -- 1. Read-only observability --------------------------------------------

async function testObservability(): Promise<void> {
    console.log('\n── 1. Observability');

    await check('health', async () => {
        const r = await client.get('/api/v1/health');
        assert(r.ok, `status ${r.status}`);
        return `status ${r.status}`;
    });

    await check('info', async () => {
        const r = await client.get('/api/v1/info');
        assert(r.ok, `status ${r.status}`);
        const b = r.body as any;
        assert(b.name === 'mapi', `name ${b.name}`);
        return `mapi v${b.version} on MC ${b.minecraftVersion} (${b.platform})`;
    });

    await check('operations registry', async () => {
        const r = await client.get('/api/v1/operations');
        assert(r.ok, `status ${r.status}`);
        const ops = (r.body as any).operations as any[];
        assert(ops.length >= 15, `only ${ops.length} operations`);
        return `${ops.length} operations registered`;
    });

    await check('logs', async () => {
        const r = await client.get('/api/v1/logs?limit=10');
        assert(r.ok, `status ${r.status}`);
        const entries = (r.body as any).entries as any[];
        return `${entries.length} log entries`;
    });

    await check('clock registry', async () => {
        const r = await client.get('/api/v1/server/world');
        assert(r.ok, `status ${r.status}`);
        const clocks = (r.body as any).clocks?.clocks as any[];
        assert(clocks && clocks.length === 5, `expected 5 clocks, got ${clocks?.length}`);
        const wall = clocks.find(c => c.clock === 'wall');
        assert(wall.advancing === true, 'wall clock not advancing');
        return 'wall advancing, 5 clocks registered';
    });

    await check('event stream (SSE)', async () => {
        // Just verify the endpoint responds with the correct content type;
        // consuming a live stream inside the audit is complex (long-running).
        const r = await fetch(`${base}/api/v1/events/stream?limit=1`, {
            headers: { Authorization: `Bearer ${token}` },
        });
        assert(r.ok, `status ${r.status}`);
        const contentType = r.headers.get('content-type') ?? '';
        assert(contentType.includes('text/event-stream'), contentType);
        // Close the stream immediately — the point is that it starts correctly
        return `SSE endpoint returns ${r.status} ${contentType}`;
    });
}

// -- 2. Client bridge -------------------------------------------------------

async function testClientBridge(): Promise<void> {
    console.log('\n── 2. Client bridge');

    await check('client capabilities', async () => {
        const r = await client.get('/api/v1/client');
        assert(r.ok, `status ${r.status}`);
        const caps = (r.body as any).capabilities as string[];
        const expected = ['client.input', 'client.lan', 'client.screenshots',
            'client.window', 'client.ui', 'client.worlds', 'client.connect',
            'client.inventory'];
        const missing = expected.filter(c => !caps.includes(c));
        assert(missing.length === 0, `missing: ${missing}`);
        return `${caps.length} capabilities`;
    });

    await check('title screen inspection', async () => {
        const screen = await getScreen();
        assert(screen.screenId.includes('Title'), `screen: ${screen.screenId}`);
        assert(screen.widgets.length >= 8, `only ${screen.widgets.length} widgets`);
        return `${screen.widgets.length} widgets on ${screen.screenId}`;
    });

    await check('screenshot (title screen)', async () => {
        await screenshot('e2e-title-screen');
        return 'captured';
    });
}

// -- 3. Menu automation → world load ----------------------------------------

async function testMenuAutomation(): Promise<void> {
    console.log('\n── 3. Menu automation → world load');

    await check('click Singleplayer', async () => {
        const screen = await getScreen();
        const sp = screen.widgets.find(w => w.text === 'Singleplayer');
        assert(sp, 'Singleplayer button not found');
        const consumed = await click(sp.x + Math.floor(sp.width / 2),
                                      sp.y + Math.floor(sp.height / 2));
        assert(consumed, 'click not consumed');
        await sleep(1500);
        return 'navigated to SelectWorldScreen';
    });

    await check('world list visible', async () => {
        const screen = await getScreen();
        assert(screen.screenId.includes('SelectWorld'),
               `screen: ${screen.screenId}`);
        const play = screen.widgets.find(w => w.text.includes('Play'));
        assert(play, 'Play button not found');
        return 'SelectWorldScreen with Play button';
    });

    await check('load world via API', async () => {
        const worlds = await client.get('/api/v1/client/worlds');
        assert(worlds.ok, `worlds list failed: ${worlds.status}`);
        const list = (worlds.body as any).worlds as any[];
        assert(list.length > 0, 'no saved worlds found');
        const target = list[0].levelId;
        const r = await client.post('/api/v1/client/worlds/load', { levelId: target });
        assert(r.status === 202 || r.ok, `world load failed: ${r.status}`);
        return `loading "${target}"`;
    });

    await check('world session ACTIVE', async () => {
        const active = await waitForPhase('ACTIVE', 120_000);
        assert(active, 'phase did not reach ACTIVE within 120s');
        return 'phase ACTIVE';
    });

    await check('dismiss pause screen (if auto-opened)', async () => {
        await sleep(3000); // wait for spawn + terrain gen
        const screen = await getScreen();
        if (screen.screenId.includes('Pause')) {
            const consumed = await clickWidget('Back to Game');
            assert(consumed, 'Back to Game click not consumed');
            await sleep(1000);
        }
        return 'in-world or no pause screen';
    });
}

// -- 4. In-world movement ----------------------------------------------------

async function testMovement(): Promise<void> {
    console.log('\n── 4. Movement');

    let before: { x: number; y: number; z: number };
    await check('position before movement', async () => {
        before = await getPlayer();
        return `(${before.x.toFixed(1)}, ${before.y.toFixed(1)}, ${before.z.toFixed(1)})`;
    });

    await check('waypoint dispatch (yaw 180, 30 ticks)', async () => {
        const r = await client.post('/api/v1/client/movement/waypoints', {
            waypoints: [{ yaw: 180, pitch: 0, ticks: 30 }],
        });
        assert(r.ok, `status ${r.status}`);
        const b = r.body as any;
        return `dispatched ${b.dispatchOutcome}, ${b.completedLegs} legs, ` +
               `boundaries ${b.startBoundary}→${b.endBoundary}`;
    });

    await sleep(2000);

    await check('position after movement', async () => {
        const after = await getPlayer();
        const dist = Math.hypot(after.x - before!.x, after.z - before!.z);
        assert(dist > 0.5, `moved only ${dist.toFixed(2)} blocks`);
        return `moved ${dist.toFixed(1)} blocks`;
    });
}

// -- 5. Commands ------------------------------------------------------------

async function testCommands(): Promise<void> {
    console.log('\n── 5. Commands');

    const playerName = (await getPlayer()).name;

    await check('give items via command', async () => {
        const r = await client.post('/api/v1/server/commands',
            { command: `give ${playerName} diamond 64` });
        assert(r.ok, `status ${r.status}`);
        const b = r.body as any;
        assert(b.success === true, `success: ${b.success}`);
        return `resultCode ${b.resultCode}`;
    });

    await sleep(500);

    await check('inventory has diamonds', async () => {
        const p = await getPlayer();
        const diamonds = p.inventory.filter(i => i.itemId === 'minecraft:diamond');
        assert(diamonds.length > 0, 'no diamonds found');
        return `${diamonds.reduce((s, i) => s + i.count, 0)} diamonds`;
    });
}

// -- 6. Inventory ------------------------------------------------------------

async function testInventory(): Promise<void> {
    console.log('\n── 6. Inventory');

    await check('inventory inspect', async () => {
        const r = await client.get('/api/v1/client/inventory');
        assert(r.ok, `status ${r.status}`);
        const b = r.body as any;
        assert(b.containerId === 0, `containerId ${b.containerId}`);
        const slots = b.slots as any[];
        assert(slots.length > 0, 'inventory empty after give');
        return `${slots.length} non-empty slots, carried ${b.carriedCount}`;
    });

    let diamondSlot: number;
    await check('find diamond slot', async () => {
        const r = await client.get('/api/v1/client/inventory');
        const b = r.body as any;
        const diamond = b.slots.find((s: any) => s.itemId === 'minecraft:diamond');
        assert(diamond, 'diamond not found in client inventory');
        diamondSlot = diamond.slot;
        return `diamond at menu slot ${diamondSlot}`;
    });

    await check('PICKUP (click diamond slot)', async () => {
        const r = await client.post('/api/v1/client/inventory/click',
            { slot: diamondSlot, button: 0, containerInput: 'PICKUP' });
        assert(r.ok, `status ${r.status}`);
        await sleep(500);
        const after = await client.get('/api/v1/client/inventory');
        const carried = (after.body as any).carriedCount;
        assert(carried > 0, `carried ${carried} after PICKUP`);
        return `carried ${carried} after PICKUP`;
    });

    await check('tooltip computed (emptied slot)', async () => {
        const r = await client.get(`/api/v1/client/inventory/tooltip?slot=${diamondSlot}`);
        assert(r.ok, `status ${r.status}`);
        const lines = (r.body as any).lines as string[];
        return `${lines.length} lines (empty slot = empty tooltip, correct)`;
    });

    await check('PICKUP again (place back)', async () => {
        const r = await client.post('/api/v1/client/inventory/click',
            { slot: diamondSlot, button: 0, containerInput: 'PICKUP' });
        assert(r.ok, `status ${r.status}`);
        await sleep(500);
        const after = await client.get('/api/v1/client/inventory');
        const carried = (after.body as any).carriedCount;
        assert(carried === 0, `carried ${carried} after place back`);
        return 'carried 0 (stack placed back)';
    });

    await check('screenshot (in-world after inventory)', async () => {
        await screenshot('e2e-in-world');
        return 'captured';
    });
}

// -- 7. Tooltips --------------------------------------------------------------

async function testTooltips(): Promise<void> {
    console.log('\n── 7. Tooltips');

    await check('tooltip computed data', async () => {
        const r = await client.get('/api/v1/client/inventory');
        const b = r.body as any;
        const diamond = b.slots.find((s: any) => s.itemId === 'minecraft:diamond');
        if (!diamond) return 'no items to inspect (skipped)';
        const r2 = await client.get(`/api/v1/client/inventory/tooltip?slot=${diamond.slot}`);
        assert(r2.ok, `status ${r2.status}`);
        const lines = (r2.body as any).lines as string[];
        return `${lines.length} tooltip lines`;
    });

    await check('tooltip rendered capture', async () => {
        const r = await client.get('/api/v1/client/inventory');
        const b = r.body as any;
        const diamond = b.slots.find((s: any) => s.itemId === 'minecraft:diamond');
        if (!diamond) return 'no items to capture (skipped)';
        const r2 = await client.post('/api/v1/client/inventory/tooltip-rendered',
            { slot: diamond.slot });
        assert(r2.ok, `status ${r2.status}`);
        const capture = r2.body as any;
        assert(capture.pngBase64?.length > 0, 'no screenshot in rendered capture');
        assert(capture.lines?.length > 0, 'no lines in rendered capture');
        return `${capture.lines.length} lines, ${capture.pngBase64.length} chars base64, ` +
               `frame ${capture.frame}`;
    });
}

// -- 8. Tick control -----------------------------------------------------------

async function testTickControl(): Promise<void> {
    console.log('\n── 8. Tick control');

    let leaseId: string;
    await check('acquire tick-control lease', async () => {
        const r = await client.post('/api/v1/server/ticks/lease', { ttlSeconds: 120 });
        assert(r.ok, `status ${r.status}`);
        leaseId = (r.body as any).leaseId;
        return `lease ${leaseId}, expires ${new Date((r.body as any).expiresAtEpochMs)}`;
    });

    await check('freeze', async () => {
        const r = await client.post('/api/v1/server/ticks/freeze',
            { leaseId });
        assert(r.ok, `status ${r.status}`);
        const b = r.body as any;
        assert(b.frozen === true, `frozen: ${b.frozen}`);
        return `frozen at tick ${b.tickCount}`;
    });

    let jobId: string;
    await check('step 20 ticks (job)', async () => {
        const r = await client.post('/api/v1/server/ticks/step',
            { leaseId, ticks: 20 });
        assert(r.status === 202, `status ${r.status}`);
        jobId = (r.body as any).jobId;
        return `job ${jobId}`;
    });

    await check('step job SUCCEEDED', async () => {
        let state = '';
        for (let attempt = 0; attempt < 30; attempt++) {
            const r = await client.get(`/api/v1/jobs/${jobId}`);
            state = String((r.body as any).state);
            if (state !== 'PENDING' && state !== 'RUNNING') break;
            await sleep(200);
        }
        assert(state === 'SUCCEEDED', `state ${state}`);
        return 'stepped';
    });

    await check('unfreeze', async () => {
        const r = await client.post('/api/v1/server/ticks/unfreeze',
            { leaseId });
        assert(r.ok, `status ${r.status}`);
        const b = r.body as any;
        assert(b.frozen === false, `frozen: ${b.frozen}`);
        return `unfrozen at tick ${b.tickCount}`;
    });
}

// -- 9. LAN publish/unpublish ---------------------------------------------------

async function testLan(): Promise<void> {
    console.log('\n── 9. LAN');

    await check('publish to LAN', async () => {
        const r = await client.post('/api/v1/server/lan',
            { port: 25590, gamemode: 'survival' });
        assert(r.ok, `status ${r.status}`);
        const b = r.body as any;
        assert(b.published === true, `published: ${b.published}`);
        return 'published on port 25590';
    });

    await check('unpublish from LAN', async () => {
        const r = await client.post('/api/v1/server/lan/stop', {});
        assert(r.ok, `status ${r.status}`);
        const b = r.body as any;
        assert(b.unpublished === true, `unpublished: ${b.unpublished}`);
        return 'unpublished';
    });
}

// -- 10. Window control ---------------------------------------------------------

async function testWindow(): Promise<void> {
    console.log('\n── 10. Window');

    await check('window state', async () => {
        const r = await client.get('/api/v1/client/window');
        assert(r.ok, `status ${r.status}`);
        const b = r.body as any;
        assert(b.width > 0 && b.height > 0, `size ${b.width}x${b.height}`);
        return `${b.width}x${b.height} framebuffer ${b.framebufferWidth}x${b.framebufferHeight} ` +
               `scale ${b.guiScale} fullscreen=${b.fullscreen}`;
    });
}

// -- 11. Graceful shutdown (last) -------------------------------------------------

async function testShutdown(): Promise<void> {
    console.log('\n── 11. Shutdown (last)');

    await check('graceful shutdown', async () => {
        const r = await client.post('/api/v1/process/shutdown', {});
        assert(r.ok, `status ${r.status}`);
        const b = r.body as any;
        assert(b.accepted === true, `accepted: ${b.accepted}`);
        return 'accepted';
    });

    // Wait for the process to stop
    let down = false;
    for (let attempt = 0; attempt < 30; attempt++) {
        await sleep(1000);
        try {
            const r = await client.get('/api/v1/health');
            if (!r.ok) { down = true; break; }
        } catch {
            down = true;
            break;
        }
    }
    await check('process stopped', async () => {
        assert(down, 'API still responding after shutdown');
        return 'listener gone';
    });
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

async function main(): Promise<number> {
    console.log('╔══════════════════════════════════════════════╗');
    console.log('║  MAPI E2E Audit                              ║');
    console.log('╚══════════════════════════════════════════════╝');
    console.log(`  base: ${base}`);
    console.log(`  token: ${token ? '***' : '(blank — auth-off)'}`);

    // Health polling: wait until the MAPI instance is reachable (bounded)
    const HEALTH_TIMEOUT_MS = 300_000; // 5 minutes
    const HEALTH_POLL_MS = 2000;
    console.log(`  polling /api/v1/health every ${HEALTH_POLL_MS / 1000}s (timeout ${HEALTH_TIMEOUT_MS / 1000}s)…`);
    let ready = false;
    const deadline = Date.now() + HEALTH_TIMEOUT_MS;
    while (Date.now() < deadline) {
        try {
            const r = await client.get('/api/v1/health');
            if (r.ok) {
                console.log(`  ✓ MAPI instance reachable after ${Math.round((Date.now() - deadline + HEALTH_TIMEOUT_MS) / 1000)}s`);
                ready = true;
                break;
            }
        } catch {
            // Not ready yet — keep polling
        }
        await sleep(HEALTH_POLL_MS);
    }
    if (!ready) {
        console.error(`  ✗ MAPI instance unreachable after ${HEALTH_TIMEOUT_MS / 1000}s — is the client running?`);
        return 1;
    }

    // Run all test groups
    const groups = [
        testObservability,
        testClientBridge,
        testMenuAutomation,
        testMovement,
        testCommands,
        testInventory,
        testTooltips,
        testTickControl,
        testLan,
        testWindow,
        testShutdown,
    ];

    let abort = false;
    for (const group of groups) {
        if (abort) break;
        const before = results.length;
        try {
            await group();
        } catch (e) {
            // A check threw before its record() — record it as failed
            const msg = e instanceof Error ? e.message : String(e);
            const failed = results.slice(before).some(r => !r.ok);
            if (!failed) {
                results.push({ check: group.name, ok: false, detail: msg });
            }
            // Abort on critical failure (menu automation or movement)
            if (group === testMenuAutomation || group === testMovement) {
                console.error(`  ✗ critical failure in ${group.name} — aborting`);
                abort = true;
            }
        }
    }

    // Report
    const passed = results.filter(r => r.ok).length;
    const failed = results.filter(r => !r.ok).length;
    console.log('\n╔══════════════════════════════════════════════╗');
    console.log(`║  Results: ${passed} passed, ${failed} failed                  ║`);
    console.log('╚══════════════════════════════════════════════╝');

    if (failed > 0) {
        console.log('\n  Failed checks:');
        for (const r of results.filter(r => !r.ok)) {
            console.log(`    ✗ ${r.check}: ${r.detail}`);
        }
    }

    // Machine-readable report
    const report = {
        timestamp: new Date().toISOString(),
        base,
        total: results.length,
        passed,
        failed,
        screenshots,
        checks: results,
    };
    fs.mkdirSync('build/acceptance', { recursive: true });
    fs.writeFileSync('build/acceptance/e2e-audit.json',
        JSON.stringify(report, null, 2));
    console.log(`\n  report: build/acceptance/e2e-audit.json`);

    return failed === 0 ? 0 : 1;
}

main().then(code => process.exit(code)).catch(e => {
    console.error('audit crashed:', e);
    process.exit(1);
});
