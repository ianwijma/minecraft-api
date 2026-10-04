import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import { Suite } from './suite.ts';
import { decodePng } from '../harness/png.ts';
import { artifactHash, freePort, socketAccepts, waitForExit, type GameProcess } from './supervisor.ts';

const DIMENSION = 'minecraft:overworld';
const WORLD_ID = 'mapi-api-coverage';
const blockParameters = { dimension: DIMENSION, x: 2, y: 65, z: 2 };

export async function readiness(suite: Suite, game: GameProcess): Promise<void> {
    const deadline = Date.now() + 480_000;
    while (Date.now() < deadline) {
        if (game.proc.exitCode !== null || game.proc.signalCode !== null) throw new Error('game exited before API readiness');
        try {
            const result = await suite.outcome('getHealth');
            if (result.status === 200 && result.body.status === 'ok') return;
        } catch { /* readiness probes precede the asserted corpus */ }
        await new Promise(resolve => setTimeout(resolve, 1000));
    }
    throw new Error('API did not become ready within 480 seconds');
}

export async function metadata(suite: Suite, repoRoot: string, loader: 'fabric' | 'neoforge'): Promise<void> {
    await suite.case('getHealth', async () => {
        const body = await suite.request('getHealth');
        suite.expect(body.status === 'ok' && body.protocolVersion === 1, 'healthy protocol v1 listener');
    });
    await suite.case('getInfo', async () => {
        const info = await suite.request('getInfo');
        suite.expect(info.platform === loader && info.minecraftVersion === '26.2', 'expected loader and Minecraft target');
        suite.expect(info.runtimeArtifact?.kind === 'jar'
            && info.runtimeArtifact.sha256 === artifactHash(repoRoot, loader), 'loaded release JAR digest matches distributable');
        fs.writeFileSync(`${suite.outDir}/provenance.json`, JSON.stringify(info, null, 2));
    });
    await suite.case('listOperations', async () => {
        const registry = await suite.request('listOperations');
        const descriptors = new Map<string, any>(registry.operations.map((operation: any) => [operation.id, operation]));
        for (const operation of suite.operations.values()) {
            if (operation.method !== 'POST') continue;
            const key = operation.definition['x-mapi-operation'];
            const actual = descriptors.get(key);
            suite.expect(Boolean(actual), `${operation.operationId} has authorization metadata`);
            for (const [field, expected] of Object.entries(operation.definition['x-mapi-security'])) {
                assert.deepEqual(actual[field], expected, `${operation.operationId}.${field}`);
            }
        }
        suite.expect(descriptors.size > 0, 'POST descriptors match canonical contract');
    });
    await suite.case('readLogs', async () => {
        const first = await suite.request('readLogs', { cursor: 0, limit: 1 });
        suite.expect(Array.isArray(first.entries) && first.entries.length <= 1, 'log read respects bound');
        suite.expect(Number.isInteger(first.cursor), 'log read exposes resume cursor');
        const next = await suite.request('readLogs', { cursor: first.cursor, limit: 1 });
        suite.expect(next.entries.every((entry: any) => entry.seq > first.cursor), 'log resume does not replay prior entries');
    });
}

export async function prepareWorld(suite: Suite, player: boolean): Promise<void> {
    for (const command of ['gamerule spawn_mobs false', 'gamerule advance_time false',
        'gamerule advance_weather false', 'gamerule random_tick_speed 0', 'weather clear', 'time set 6000']) {
        await suite.command(command);
    }
    await suite.command('forceload add -16 -16 16 16');
    await suite.command('fill -8 64 -8 8 64 16 air', false);
    await suite.command('fill -8 64 -8 8 64 16 stone');
    await suite.command('fill -8 65 -8 8 80 16 air', false);
    await suite.command('setblock 2 65 2 chest');
    if (player) {
        await suite.command('gamemode survival @p');
        await suite.command('tp @p 0.5 65 4.5 0 0');
        await suite.command('clear @p', false);
        await suite.command('give @p minecraft:diamond 3');
        await suite.poll(() => suite.request('queryEntities', { dimension: DIMENSION, x: 0, y: 65, z: 0, radius: 8, max: 20 }),
            result => result.entities.some((entity: any) => entity.typeId === 'minecraft:player'), 'fixture lane entity section loaded');
    }
    await suite.command('summon pig 0 65 0 {NoAI:1b,Tags:["mapi-api-fixture"]}');
    await settleFixtureWorld(suite);
}

export async function settleFixtureWorld(suite: Suite): Promise<void> {
    for (const x of [-16, 0, 16]) for (const z of [-16, 0, 16]) {
        await suite.pollRead('queryBlock', { dimension: DIMENSION, x, y: 65, z },
            block => typeof block.blockId === 'string' && !block.unloaded, `fixture chunk ${x},${z} readable`, 120_000);
    }
    let firstTick: number | undefined;
    await suite.poll(async () => suite.outcome('getTickState'), result => {
        if (result.status === 503 && result.body.error?.code === 'SERVER_BUSY') {
            firstTick = undefined;
            return false;
        }
        assert.equal(result.status, 200, `fixture readiness: ${JSON.stringify(result)}`);
        assert.ok(result.body.available && !result.body.frozen && !result.body.sprinting, 'fixture needs normal ticking');
        firstTick ??= result.body.tickCount;
        return result.body.tickCount >= firstTick! + 40;
    }, 'fixture completes 40 normal server ticks after chunk readiness', 120_000);
}

async function completedJob(suite: Suite, jobId: string): Promise<any> {
    return suite.poll(() => suite.request('getJob', { id: jobId }), job => {
        if (['FAILED', 'CANCELLED'].includes(job.state)) throw new Error(`job ${jobId} ${job.state}: ${JSON.stringify(job)}`);
        return job.state === 'SUCCEEDED';
    }, `job ${jobId}`, 65_000);
}

export async function serverCorpus(suite: Suite, player: boolean): Promise<string> {
    await suite.case('getWorldInfo', async () => {
        const world = await suite.request('getWorldInfo');
        suite.expect(world.phase === 'ACTIVE' && typeof world.worldSessionId === 'string', 'active world has a session identity');
        suite.expect(world.tickControl && world.worldQueries && world.commands, 'required server bridge capabilities exist');
        suite.expect(world.clocks.clocks.length === 5, 'five named clocks are exposed');
    });
    await suite.case('getServerStatus', async () => {
        const status = await suite.request('getServerStatus');
        suite.expect(status.running && status.tickCount >= 0 && status.maxPlayers > 0, 'running server status has valid metrics');
        suite.expect(status.playerCount === (player ? 1 : 0), 'server player count matches isolated fixture');
    });
    await suite.case('queryPlayers', async () => {
        const result = await suite.request('queryPlayers', { max: 1 });
        suite.expect(result.players.length === (player ? 1 : 0), 'bounded player query matches fixture');
        if (player) suite.expect(result.players[0].inventory.some((item: any) => item.itemId === 'minecraft:diamond' && item.count === 3),
            'server inventory contains the prepared diamond stack');
    });
    let pig: any;
    await suite.case('queryEntities', async () => {
        const fixture = (entity: any) => entity.typeId === 'minecraft:pig' && Math.abs(entity.x - 0.5) < 0.01 && Math.abs(entity.z - 0.5) < 0.01;
        const result = await suite.poll(() => suite.request('queryEntities', { dimension: DIMENSION, x: 0, y: 65, z: 0, radius: 8, max: 20 }),
            result => result.entities.some(fixture), 'summoned entity becomes observable');
        pig = result.entities.find(fixture);
        suite.expect(Boolean(pig?.uuid) && result.entities.length <= 20, 'known entity appears in bounded query');
    });
    await suite.case('queryBlock', async () => {
        const block = await suite.request('queryBlock', blockParameters);
        suite.expect(block.blockId === 'minecraft:chest' && block.blockEntityTypeId === 'minecraft:chest', 'known fixture block entity is returned');
        suite.expect(block.data?.type === 'compound', 'block entity uses typed NBT encoding');
    });
    await suite.case('listRegistries', async () => {
        const body = await suite.request('listRegistries');
        suite.expect(body.registries.some((registry: any) => registry.id === 'minecraft:block' && registry.size > 0), 'block registry is populated');
    });
    await suite.case('queryRegistryEntries', async () => {
        const body = await suite.request('queryRegistryEntries', { registryId: 'minecraft:block', max: 1000 });
        suite.expect(body.entries.includes('minecraft:air') && body.entries.length <= 1000, 'bounded registry entries contain known block');
        suite.expect(JSON.stringify(body.entries) === JSON.stringify([...body.entries].sort()), 'registry entries are sorted');
    });
    await suite.case('dispatchCommand', async () => {
        const body = await suite.request('dispatchCommand', {}, { command: 'setblock 3 65 3 gold_block' });
        suite.expect(body.dispatched && body.success, 'command dispatch succeeded');
        const block = await suite.request('queryBlock', { dimension: DIMENSION, x: 3, y: 65, z: 3 });
        suite.expect(block.blockId === 'minecraft:gold_block', 'command effect independently observed');
    });
    await suite.case('streamEvents', async () => {
        const history = await suite.subscribe(0, ['command.dispatched']);
        let cursor: number;
        try {
            await suite.poll(async () => history.events, events => events.some(event => !event.gap), 'retained command event');
            cursor = Math.max(...history.events.filter(event => !event.gap).map(event => Number(event.id)));
        } finally { await history.close(); }
        const stream = await suite.subscribe(cursor!, ['command.dispatched']);
        try {
            await suite.command('time set 6001');
            await suite.poll(async () => stream.events, events => events.some(event => !event.gap && Number(event.id) > cursor!), 'new command event');
            const event = stream.events.find(event => !event.gap && Number(event.id) > cursor!);
            suite.expect(event.event === 'command.dispatched' && event.data.payload.success, 'post-cursor command event has successful dispatch evidence');
            suite.expect(stream.events.every(item => item.gap || item.event === 'command.dispatched'), 'event-type filter excludes other events');
            suite.expect(stream.errors.length === 0, 'event stream has no transport errors');
            const resumed = await suite.subscribe(Number(event.id), ['command.dispatched']);
            try {
                await suite.command('time set 6002');
                await suite.poll(async () => resumed.events, events => events.some(item => !item.gap), 'resumed command event');
                suite.expect(resumed.events.every(item => item.gap || Number(item.id) > Number(event.id)), 'resume excludes previously consumed event');
            } finally { await resumed.close(); }
        } finally { await stream.close(); }
    });
    let leaseId = '';
    await suite.case('acquireTickLease', async () => {
        const lease = await suite.request('acquireTickLease', {}, { ttlSeconds: 600 });
        leaseId = lease.leaseId;
        const invalid = await suite.outcome('acquireTickLease', {}, { ttlSeconds: 0 });
        suite.expect(invalid.status === 400 && invalid.body.error.code === 'BAD_REQUEST', 'invalid tick lease TTL rejected');
        suite.expect(Boolean(leaseId) && lease.expiresAtEpochMs > Date.now(), 'tick lease has future expiry and identity');
        const conflict = await suite.outcome('acquireTickLease', {}, { ttlSeconds: 600 });
        suite.expect(conflict.status === 409 && conflict.body.error.code === 'LEASE_HELD', 'second tick controller is rejected');
    });
    await suite.case('freezeTicks', async () => {
        await suite.request('freezeTicks', {}, { leaseId });
        const state = await suite.request('getTickState');
        suite.expect(state.frozen === true, 'frozen state confirmed by independent readback');
    });
    await suite.case('setTickRate', async () => {
        const result = await suite.request('setTickRate', {}, { leaseId, rate: 40 });
        const state = await suite.request('getTickState');
        suite.expect(result.appliedTickRate === 40 && state.tickRate === 40, 'effective rate matches request');
        await suite.request('setTickRate', {}, { leaseId, rate: 20 });
    });
    let stepJob = '';
    await suite.case('stepTicks', async () => {
        const accepted = await suite.request('stepTicks', {}, { leaseId, ticks: 3 }, 202);
        stepJob = accepted.jobId;
        const job = await completedJob(suite, stepJob);
        suite.expect(job.result.requested === 3 && job.result.completed === 3, 'all three simulation steps completed');
    });
    await suite.case('getJob', async () => {
        const job = await suite.request('getJob', { id: stepJob });
        suite.expect(job.state === 'SUCCEEDED' && job.kind === 'ticks.step', 'job identity and terminal state match step');
        suite.expect(job.milestones.some((milestone: any) => milestone.name === 'stepped' && milestone.details.completed === 3), 'job exposes completed-step milestone');
    });
    await suite.case('stepAndObserve', async () => {
        const accepted = await suite.request('stepAndObserve', {}, { leaseId, ticks: 2, label: 'api-step-observation' }, 202);
        const job = await completedJob(suite, accepted.jobId);
        suite.expect(job.result.completed === 2 && typeof job.result.snapshotId === 'string', 'step completion retains an observation');
        suite.expect(job.result.snapshotBoundary === job.result.boundary, 'snapshot is captured at the reported completion boundary');
    });
    await suite.case('getTickState', async () => {
        const state = await suite.request('getTickState');
        suite.expect(state.available && state.frozen && state.tickRate === 20 && state.leaseId === leaseId, 'tick state reflects control ownership and effective settings');
    });
    await suite.case('unfreezeTicks', async () => {
        await suite.request('unfreezeTicks', {}, { leaseId });
        const state = await suite.request('getTickState');
        suite.expect(!state.frozen, 'unfreeze confirmed by readback');
    });
    await suite.case('sprintTicks', async () => {
        const before = await suite.request('getTickState');
        await suite.request('sprintTicks', {}, { leaseId, ticks: 5 });
        const state = await suite.pollRead('getTickState', {}, state => !state.sprinting && state.tickCount > before.tickCount, 'bounded sprint completion');
        suite.expect(state.tickCount >= before.tickCount + 5, 'sprint advances at least the requested simulation ticks');
    });
    await suite.case('stopTickWork', async () => {
        await suite.request('sprintTicks', {}, { leaseId, ticks: 10_000 });
        const stopped = await suite.request('stopTickWork', {}, { leaseId });
        const state = await suite.request('getTickState');
        suite.expect(stopped.stopped === true && !state.sprinting, 'active tick work was stopped');
    });
    let firstId = '';
    await suite.case('captureSnapshot', async () => {
        const first = await suite.request('captureSnapshot', {}, { label: 'api-before', maxPlayers: 2, maxEntities: 50 });
        firstId = first.snapshotId;
        suite.expect(Boolean(firstId) && Number.isInteger(first.boundary) && first.worldSessionId, 'snapshot has retained identity, session, and boundary');
        const diff = await suite.request('diffSnapshots', {}, { firstId, secondId: firstId });
        suite.expect(diff.records.length === 0 && !diff.truncated, 'retained snapshot can be compared to itself');
    });
    await suite.case('diffSnapshots', async () => {
        await suite.command('tp @e[tag=mapi-api-fixture,limit=1] 3 65 0');
        const second = await suite.request('captureSnapshot', {}, { label: 'api-after', maxPlayers: 2, maxEntities: 50 });
        const diff = await suite.request('diffSnapshots', {}, { firstId, secondId: second.snapshotId, includePaths: ['entities.*'], maxChanges: 50 });
        const change = diff.records.find((record: any) => record.path === `entities.${pig.uuid}.x`);
        suite.expect(change?.kind === 'changed' && JSON.stringify(change.before) !== JSON.stringify(change.after), 'intentional entity movement appears in structural diff');
        suite.expect(!diff.truncated && diff.records.length <= 50, 'diff respects requested bounds');
    });
    for (const [id, parameters, body, status, code] of [
        ['queryBlock', { ...blockParameters, dimension: 'invalid:missing' }, {}, 400, 'BAD_REQUEST'],
        ['queryEntities', { dimension: DIMENSION, radius: 129 }, {}, 400, 'BAD_REQUEST'],
        ['queryRegistryEntries', { registryId: 'minecraft:block', max: 1001 }, {}, 400, 'BAD_REQUEST'],
        ['getJob', { id: 'job-missing' }, {}, 404, 'NOT_FOUND'],
        ['diffSnapshots', {}, { firstId: 'snapshot-missing', secondId: firstId }, 404, 'NOT_FOUND'],
        ['setTickRate', {}, { leaseId, rate: 101 }, 400, 'BAD_REQUEST'],
    ] as const) {
        const result = await suite.outcome(id, parameters, body);
        assert.equal(result.status, status, `${id}: negative contract`);
        assert.equal(result.body.error.code, code);
    }
    return leaseId;
}

async function inputLease(suite: Suite, current?: string): Promise<string> {
    const body = await suite.request('acquireClientControlLease', {}, { ttlSeconds: 300, ...(current ? { leaseId: current } : {}) });
    return body.leaseId;
}

async function screen(suite: Suite): Promise<any> {
    const result = await suite.outcome('inspectScreen');
    if (result.status === 400 && result.body.error?.code === 'BAD_REQUEST'
        && result.body.error.message.includes('no screen is active')) return { screenId: '', widgets: [] };
    assert.equal(result.status, 200, JSON.stringify(result));
    return result.body;
}

async function clickText(suite: Suite, text: RegExp, leaseId: string): Promise<void> {
    const current = await screen(suite);
    const widget = current.widgets.find((widget: any) => widget.active && widget.visible && text.test(widget.text));
    assert.ok(widget, `screen ${current.screenId} has no actionable ${text}`);
    await suite.request('clickScreen', {}, { x: widget.x + Math.floor(widget.width / 2),
        y: widget.y + Math.floor(widget.height / 2), leaseId });
}

async function titleScreen(suite: Suite, leaseId: string): Promise<any> {
    return suite.poll(async () => {
        const current = await screen(suite);
        const continueWidget = current.widgets.find((widget: any) => widget.active && /^Continue$|^Done$|^Cancel$|^Back$|^Back to Title$|^Back to Server List$/.test(widget.text));
        if (!current.screenId.includes('Title') && continueWidget) {
            await suite.request('clickScreen', {}, { x: continueWidget.x + Math.floor(continueWidget.width / 2),
                y: continueWidget.y + Math.floor(continueWidget.height / 2), leaseId });
        }
        return current;
    }, current => current.screenId.includes('Title'), 'title screen', 120_000);
}

async function worldReady(suite: Suite): Promise<any> {
    await suite.poll(() => suite.request('getWorldInfo'), world => world.phase === 'ACTIVE', 'active world', 300_000);
    return suite.poll(async () => suite.outcome('listRegistries'), result => {
        if (result.status === 503 && result.body.error?.code === 'SERVER_BUSY') return false;
        assert.equal(result.status, 200, JSON.stringify(result));
        return result.body.registries.length > 0;
    }, 'responsive world', 180_000);
}

async function unload(suite: Suite, leaseId: string): Promise<void> {
    await suite.poll(() => screen(suite), current => !current.screenId, 'client finished joining world', 120_000);
    await suite.request('holdKey', {}, { keyCode: 256, ticks: 1, leaseId });
    await suite.poll(() => screen(suite), current => current.screenId.includes('Pause'), 'pause screen');
    await clickText(suite, /Save and Quit to Title/, leaseId);
    await suite.poll(() => suite.request('getWorldInfo'), world => world.phase === 'NONE', 'integrated world unloaded', 60_000);
}

export async function clientCorpus(suite: Suite, dedicated: Suite, client: GameProcess,
    server: GameProcess, checkFixture: (game: GameProcess) => Promise<void>): Promise<void> {
    suite.environment = 'menu';
    let leaseId = await inputLease(suite);
    await titleScreen(suite, leaseId);
    await suite.case('acquireClientControlLease', async () => {
        const invalid = await suite.outcome('acquireClientControlLease', {}, { ttlSeconds: 0 });
        suite.expect(invalid.status === 400 && invalid.body.error.code === 'BAD_REQUEST', 'invalid input lease TTL rejected');
        const renewed = await inputLease(suite, leaseId);
        suite.expect(renewed === leaseId, 'renewal preserves input lease identity');
        const conflict = await suite.outcome('acquireClientControlLease', {}, { ttlSeconds: 300 });
        suite.expect(conflict.status === 409 && conflict.body.error.code === 'LEASE_HELD', 'second input controller rejected');
        const stale = await suite.outcome('acquireClientControlLease', {}, { leaseId: 'lease-missing' });
        suite.expect(stale.status === 409 && stale.body.error.code === 'LEASE_REQUIRED', 'unknown renewal never falls back to acquisition');
    });
    await suite.case('getClientInfo', async () => {
        const clientInfo = await suite.request('getClientInfo');
        suite.expect(['client.input', 'client.screenshots', 'client.window', 'client.ui', 'client.worlds', 'client.connect', 'client.inventory']
            .every(capability => clientInfo.capabilities.includes(capability)), 'required client capabilities are advertised');
    });
    await suite.case('setWindowed', async () => {
        await suite.request('setWindowed', {}, { width: 1024, height: 768 });
        const state = await suite.poll(() => suite.request('getWindowState'), state => state.width === 1024 && state.height === 768, 'window resize');
        suite.expect(!state.fullscreen, 'effective windowed dimensions match request');
    });
    await suite.case('setFullscreen', async () => {
        for (const fullscreen of [true, false]) {
            await suite.request('setFullscreen', {}, { fullscreen });
            const state = await suite.poll(() => suite.request('getWindowState'), state => state.fullscreen === fullscreen, 'fullscreen transition');
            suite.expect(state.fullscreen === fullscreen, `fullscreen ${fullscreen} observed`);
        }
    });
    await suite.case('setGuiScale', async () => {
        await suite.request('setGuiScale', {}, { guiScale: 2 });
        const state = await suite.poll(() => suite.request('getWindowState'), state => state.guiScale === 2, 'GUI scale');
        suite.expect(state.guiScale === 2, 'effective GUI scale is two');
    });
    await suite.case('getWindowState', async () => {
        const state = await suite.request('getWindowState');
        suite.expect(state.width > 0 && state.height > 0 && state.framebufferWidth > 0 && state.framebufferHeight > 0,
            'logical and framebuffer dimensions are independently positive');
        suite.expect(state.guiScale === 2 && !state.fullscreen, 'window readback retains effective settings');
        fs.writeFileSync(`${suite.outDir}/profile-effective.json`, JSON.stringify(state, null, 2));
    });
    await suite.case('captureScreenshot', async () => {
        const body = await suite.request('captureScreenshot');
        const decoded = decodePng(suite.savePng(body, 'title-screen'));
        suite.expect(decoded.width === body.width && decoded.height === body.height, 'decoded PNG matches capture dimensions');
        suite.expect(body.frame >= 0 && body.guiScale === 2, 'capture exposes frame and scale metadata');
    });
    await suite.case('inspectScreen', async () => {
        const current = await screen(suite);
        suite.expect(current.screenId.includes('Title') && current.widgets.some((widget: any) => widget.active && widget.text === 'Options...'),
            'title screen exposes actionable options widget');
    });
    await suite.case('clickScreen', async () => {
        await clickText(suite, /^Options\.\.\.$/, leaseId);
        const current = await suite.poll(() => screen(suite), current => current.screenId.includes('Options'), 'options screen');
        suite.expect(current.screenId.includes('Options'), 'click changes the semantic screen');
        await clickText(suite, /^Done$/, leaseId);
        await titleScreen(suite, leaseId);
    });
    suite.environment = 'integrated';
    await suite.case('createWorld', async () => {
        await suite.request('createWorld', {}, { levelId: WORLD_ID, gamemode: 'creative', seed: 20260919 }, 202);
        await worldReady(suite);
        await suite.pollRead('queryPlayers', { max: 1 }, result => result.players.length === 1, 'integrated player joined', 120_000);
        suite.expect((await suite.request('getWorldInfo')).phase === 'ACTIVE', 'creation admission reaches active world');
    });
    leaseId = await inputLease(suite, leaseId);
    const current = await screen(suite);
    if (current.screenId && !current.screenId.includes('Title')) {
        const continueWidget = current.widgets.find((widget: any) => /^Continue$|^Proceed$|^Done$/.test(widget.text) && widget.active);
        if (continueWidget) await clickText(suite, /^Continue$|^Proceed$|^Done$/, leaseId);
        else await suite.request('holdKey', {}, { keyCode: 256, ticks: 1, leaseId });
    }
    await prepareWorld(suite, true);
    const tickLease = await serverCorpus(suite, true);
    leaseId = await inputLease(suite, leaseId);
    await suite.case('holdKey', async () => {
        const receipt = await suite.request('holdKey', {}, { keyCode: 69, ticks: 1, leaseId });
        const current = await suite.poll(() => screen(suite), current => current.screenId.includes('Inventory'), 'inventory key effect');
        suite.expect(receipt.actualMode === 'raw-input' && current.screenId.includes('Inventory'), 'raw key dispatch opens inventory');
    });
    await suite.case('inspectInventory', async () => {
        const inventory = await suite.request('inspectInventory');
        suite.expect(inventory.containerId === 0 && inventory.slots.some((slot: any) => slot.slot === 36 && slot.itemId === 'minecraft:diamond' && slot.count === 3),
            'prepared stack is visible in player menu slot 36');
    });
    let computed: any;
    await suite.case('getTooltip', async () => {
        computed = await suite.request('getTooltip', { slot: 36 });
        suite.expect(computed.lines.some((line: string) => line.includes('Diamond')), 'computed tooltip contains fixture item name');
    });
    await suite.case('captureRenderedTooltip', async () => {
        const baseline = decodePng(suite.savePng(await suite.request('captureScreenshot'), 'inventory-before-hover'));
        const rendered = await suite.request('captureRenderedTooltip', {}, { slot: 36, leaseId });
        const decoded = decodePng(suite.savePng(rendered, 'rendered-tooltip'));
        suite.expect(JSON.stringify(rendered.lines) === JSON.stringify(computed.lines), 'rendered capture and computed tooltip lines agree');
        suite.expect(decoded.width === rendered.width && decoded.height === rendered.height && rendered.frame > 0,
            'rendered tooltip has valid PNG and frame metadata');
        const scale = rendered.guiScale;
        const x0 = Math.floor((decoded.width / scale - 176) / 2 + 16 + 12) * scale;
        const y0 = Math.floor((decoded.height / scale - 166) / 2 + 150 - 12) * scale;
        let tooltipPixels = 0;
        for (let y = y0; y < Math.min(decoded.height, y0 + 24 * scale); y++) {
            for (let x = x0; x < Math.min(decoded.width, x0 + 64 * scale); x++) {
                const i = (y * decoded.width + x) * 4;
                const [r, g, b] = decoded.data.subarray(i, i + 3);
                const changed = Math.max(...[0, 1, 2].map(channel => Math.abs(decoded.data[i + channel] - baseline.data[i + channel]))) > 25;
                if (changed && r > g + 3 && b > g + 3 && r < 70 && g < 40 && b < 90) tooltipPixels++;
            }
        }
        suite.expect(tooltipPixels > 40, 'hover adds visible tooltip background pixels near the known slot');
    });
    await suite.case('clickInventory', async () => {
        const pickup = await suite.request('clickInventory', {}, { slot: 36, button: 0, containerInput: 'PICKUP', executionMode: 'client-logic', leaseId });
        suite.expect(pickup.dispatched && pickup.effectVerified === false, 'receipt distinguishes dispatch from server confirmation');
        await suite.request('clickInventory', {}, { slot: 37, button: 0, containerInput: 'PICKUP', executionMode: 'client-logic', leaseId });
        const players = await suite.pollRead('queryPlayers', { max: 1 }, result => result.players[0].inventory.some((item: any) => item.slot === 1 && item.itemId === 'minecraft:diamond' && item.count === 3), 'server inventory movement');
        suite.expect(players.players[0].inventory.some((item: any) => item.slot === 1 && item.itemId === 'minecraft:diamond'), 'server confirms moved stack in inventory slot 1');
        suite.expect((await suite.request('inspectInventory')).carriedCount === 0, 'cursor stack is empty after placement');
    });
    await suite.request('holdKey', {}, { keyCode: 256, ticks: 1, leaseId });
    await suite.poll(() => screen(suite), current => !current.screenId, 'inventory closed');
    await suite.command('tp @p 0.5 65 4.5 0 0');
    await suite.case('moveWaypoints', async () => {
        const before = (await suite.request('queryPlayers', { max: 1 })).players[0];
        const receipt = await suite.request('moveWaypoints', {}, { leaseId, waypoints: [{ yaw: 0, pitch: 0, ticks: 5 }] });
        const after = (await suite.pollRead('queryPlayers', { max: 1 }, result => result.players[0].z > before.z + 0.1, 'raw movement effect')).players[0];
        suite.expect(after.z > before.z + 0.1 && after.z < before.z + 4, 'player moved forward within bounded lane');
        suite.expect(receipt.actualMode === 'raw-input', 'movement reports raw execution without teleport fallback');
    });
    const lanPort = await freePort();
    await suite.case('publishLan', async () => {
        const published = await suite.request('publishLan', {}, { port: lanPort, gamemode: 'survival', cheats: true, leaseId: tickLease });
        await suite.poll(() => socketAccepts(lanPort), value => value, 'LAN socket publication');
        suite.expect(published.published === true, 'LAN publication accepted and socket is reachable');
    });
    await suite.case('unpublishLan', async () => {
        const unpublished = await suite.request('unpublishLan', {}, { leaseId: tickLease });
        await suite.poll(() => socketAccepts(lanPort), value => !value, 'LAN socket closure');
        suite.expect(unpublished.unpublished === true, 'LAN unpublication accepted and new connections refused');
    });
    const sessionBefore = (await suite.request('getWorldInfo')).worldSessionId;
    await unload(suite, leaseId);
    suite.environment = 'menu';
    leaseId = await inputLease(suite, leaseId);
    await titleScreen(suite, leaseId);
    await suite.case('listWorlds', async () => {
        const worlds = await suite.request('listWorlds');
        suite.expect(worlds.worlds.some((world: any) => world.levelId === WORLD_ID), 'created save appears in world list');
    });
    suite.environment = 'integrated';
    await suite.case('loadWorld', async () => {
        await suite.request('loadWorld', {}, { levelId: WORLD_ID }, 202);
        await worldReady(suite);
        await suite.pollRead('queryPlayers', { max: 1 }, result => result.players.length === 1, 'reloaded player joined', 120_000);
        const world = await suite.request('getWorldInfo');
        const block = await suite.request('queryBlock', { dimension: DIMENSION, x: 3, y: 65, z: 3 });
        suite.expect(world.worldSessionId !== sessionBefore && block.blockId === 'minecraft:gold_block', 'reload has new session and persists fixture block');
    });
    await unload(suite, leaseId);
    suite.environment = 'menu';
    leaseId = await inputLease(suite, leaseId);
    await titleScreen(suite, leaseId);
    await suite.case('deleteWorld', async () => {
        const denied = await suite.outcome('deleteWorld', {}, { levelId: WORLD_ID });
        suite.expect(denied.status === 428 && denied.body.error.code === 'DESTRUCTIVE_INTENT_REQUIRED', 'world deletion requires explicit intent');
        await suite.request('deleteWorld', {}, { levelId: WORLD_ID, confirm: true });
        const worlds = await suite.request('listWorlds');
        suite.expect(!worlds.worlds.some((world: any) => world.levelId === WORLD_ID), 'only disposable test save has been removed');
    });
    suite.environment = 'multiplayer';
    await suite.case('connectServer', async () => {
        const address = `127.0.0.1:${server.gamePort}`;
        await suite.request('connectServer', {}, { address, leaseId }, 202);
        const result = await dedicated.pollRead('queryPlayers', { max: 2 }, result => result.players.length === 1, 'authoritative multiplayer join', 120_000);
        const player = result.players[0];
        suite.expect(Boolean(player.uuid) && Boolean(player.name), 'dedicated server observes authoritative player identity');
        await dedicated.command(`give ${player.name} minecraft:emerald 7`);
        const clientInventory = await suite.poll(() => suite.request('inspectInventory'), inventory => inventory.slots.some((item: any) => item.itemId === 'minecraft:emerald' && item.count === 7), 'client observes server-assigned inventory marker');
        suite.expect(clientInventory.slots.some((item: any) => item.itemId === 'minecraft:emerald' && item.count === 7), 'both APIs correlate the joined player using an exclusive fixture marker');
        fs.writeFileSync(`${suite.outDir}/participant.json`, JSON.stringify({ address, player,
            verification: 'observed through server identity and exclusive client inventory marker' }, null, 2));
    });
    leaseId = await inputLease(suite, leaseId);
    await suite.poll(() => screen(suite), current => !current.screenId, 'client finished multiplayer join', 120_000);
    await suite.request('holdKey', {}, { keyCode: 256, ticks: 1, leaseId });
    await suite.poll(() => screen(suite), current => current.screenId.includes('Pause'), 'multiplayer pause screen');
    await clickText(suite, /Disconnect/, leaseId);
    await titleScreen(suite, leaseId);
    suite.environment = 'menu';
    await suite.case('requestShutdown', async () => {
        const shutdown = await suite.request('requestShutdown');
        await waitForExit(client);
        await checkFixture(client);
        suite.expect(shutdown.accepted === true, 'client shutdown exits cleanly with complete Java API fixture evidence');
    });
}
