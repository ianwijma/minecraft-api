/**
 * MAPI E2E visual scenarios — plan: docs/examples/e2e-visual-scenarios.md.
 *
 * Usage:
 *   node --experimental-strip-types e2e/run.ts --scenario all --loader fabric
 *
 * Flags:
 *   --scenario <name|a,b|all>   default: all (house, clockwork,
 *                               inventory-ballet, hedge-maze, furnace-assay,
 *                               terraformer, garden, sundial)
 *   --loader <fabric|neoforge>  default: fabric (baselines are keyed per env)
 *   --env <envId>               baseline env id; default: <platform>-<loader>
 *   --base <url>                default: http://127.0.0.1:25586
 *   --update-baselines          rewrite baselines instead of comparing
 *   --supervised                launch the client via gradle first (dedicated
 *                               isolated run dir + preflighted profile)
 *   --release-jar                supervise an actual production runner task;
 *                               report passes only with JAR provenance
 *   --keep-world                do not delete/recreate the mapi-e2e world
 *
 * Auth: MAPI_HTTP_TOKEN from the environment; a blank-token server accepts
 * any bearer (same convention as scripts/e2e-audit.ts).
 * Unit tests: cd e2e && npm test
 */
import * as path from 'node:path';
import * as fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import * as crypto from 'node:crypto';
import * as net from 'node:net';
import { Harness } from './harness/client.ts';
import { Report } from './harness/report.ts';
import type { Ctx, Scenario, SessionTick } from './harness/context.ts';
import { ensureWorld, applyCharter, scenarioReset, dismissScreens, NOON, setTimeAbsolute }
    from './harness/stage.ts';
import { startClient, stopClient, type SupervisedClient } from './harness/supervisor.ts';
import { scenario as house } from './scenarios/01-house.spec.ts';
import { scenario as clockwork } from './scenarios/02-clockwork.spec.ts';
import { scenario as inventoryBallet } from './scenarios/03-inventory-ballet.spec.ts';
import { scenario as hedgeMaze } from './scenarios/04-hedge-maze.spec.ts';
import { scenario as furnaceAssay } from './scenarios/05-furnace-assay.spec.ts';
import { scenario as terraformer } from './scenarios/06-terraformer.spec.ts';
import { scenario as garden } from './scenarios/07-garden.spec.ts';
import { scenario as sundial } from './scenarios/08-sundial.spec.ts';

const SCENARIOS: Scenario[] = [
    house, clockwork, inventoryBallet, hedgeMaze,
    furnaceAssay, terraformer, garden, sundial,
];

function arg(name: string): string | undefined {
    const eq = process.argv.find(a => a.startsWith(`--${name}=`));
    if (eq) return eq.slice(name.length + 3);
    const i = process.argv.indexOf(`--${name}`);
    return i >= 0 ? process.argv[i + 1] : undefined;
}

async function waitForClient(h: Harness, sup: SupervisedClient | null): Promise<void> {
    if (!sup) return h.healthWait();
    const deadline = Date.now() + 480_000;
    while (Date.now() < deadline) {
        if (sup.proc.exitCode !== null || sup.proc.signalCode !== null) {
            throw new Error(`supervised client exited before API readiness (code=${sup.proc.exitCode}, signal=${sup.proc.signalCode}); see ${sup.logFile}`);
        }
        try {
            const response = await h.api.getHealth();
            if (response.ok) return;
        } catch { /* client still starting */ }
        await new Promise(resolve => setTimeout(resolve, 2_000));
    }
    throw new Error(`MAPI unreachable after 480s at ${h.base}`);
}

async function runConnectionProbe(
    h: Harness, report: Report, mode: 'deny' | 'allow', port: number, clientLog: string,
): Promise<void> {
    const lease = await h.post('/api/v1/client/control/lease', { ttlSeconds: 60 });
    const leaseId = String(lease.leaseId);
    const result = await h.post('/api/v1/client/connect', {
        address: `${mode === 'deny' ? 'localhost' : '127.0.0.1'}:${port}`,
        leaseId,
    });
    report.expect(result.address === `${mode === 'deny' ? 'localhost' : '127.0.0.1'}:${port}`,
        'connect request accepted', 'authorized API request reached the vanilla client connection flow');
    let accepted = false;
    let resolvedAddressRejected = false;
    const deadline = Date.now() + 12_000;
    while (Date.now() < deadline) {
        accepted = Boolean((globalThis as any).__mapiSinkAccepted);
        const logs = fs.existsSync(clientLog) ? fs.readFileSync(clientLog, 'utf8') : '';
        resolvedAddressRejected = logs.includes('MAPI connection policy rejected resolved destination');
        if (accepted || resolvedAddressRejected) break;
        await new Promise(resolve => setTimeout(resolve, 400));
    }
    try {
        const screen = await h.get('/api/v1/client/screen');
        report.advisory(`screen after connect: ${String(screen.screenId ?? 'unknown')}`);
    } catch { /* the connection may be transitioning screens */ }
    if (mode === 'allow') {
        report.expect(accepted, 'explicit IP destination reached sink',
            `loopback sink accepted=${accepted}; explicit 127.0.0.1:${port} was configured`);
    } else {
        report.expect(!accepted, 'hostname-only allowlist blocked resolved IP',
            `loopback sink accepted=${accepted}; only localhost was configured, with no numeric destination pin`);
        report.expect(resolvedAddressRejected, 'final numeric address rejection observed',
            `client log contained resolver policy rejection=${resolvedAddressRejected}`);
    }
}

const isMain = process.argv[1] &&
    fileURLToPath(import.meta.url) === path.resolve(process.argv[1]);

async function main(): Promise<number> {
    const repoRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
    const loader = (arg('loader') ?? 'fabric') as 'fabric' | 'neoforge';
    const apiPort = Number(process.env['MAPI_HTTP_PORT'] ?? 25586);
    const base = arg('base') ?? `http://127.0.0.1:${apiPort}`;
    const releaseJar = process.argv.includes('--release-jar');
    const connectionProbe = arg('connection-probe') as 'deny' | 'allow' | undefined;
    const supervised = process.argv.includes('--supervised') || releaseJar;
    if (supervised && new URL(base).port !== String(apiPort)) {
        throw new Error(`supervised client MAPI_HTTP_PORT (${apiPort}) must match --base (${base})`);
    }
    const token = process.env['MAPI_HTTP_TOKEN']
        ?? (supervised ? crypto.randomBytes(24).toString('base64url') : 'auth-off-dummy-token');
    const envId = arg('env') ?? `${process.platform}-${loader}`;
    const updateBaselines = process.argv.includes('--update-baselines');
    const keepWorld = process.argv.includes('--keep-world');
    const selected = arg('scenario') ?? 'all';
    const names = selected === 'all'
        ? SCENARIOS.map(s => s.name)
        : selected.split(',').map(s => s.trim());
    const runId = new Date().toISOString().replace(/[:.]/g, '-');
    const outDir = arg('out-dir') ?? path.join(repoRoot, 'e2e', 'out', runId);

    console.log('╔══════════════════════════════════════════════╗');
    console.log('║  MAPI E2E visual scenarios                   ║');
    console.log('╚══════════════════════════════════════════════╝');
    console.log(`  base: ${base}  loader: ${loader}  env: ${envId}`);
    console.log(`  scenarios: ${names.join(', ')}`);
    console.log(`  out: ${outDir}`);

    const report = new Report(runId, { base, loader, envId, supervised,
        launchMode: releaseJar ? 'release-jar' : (supervised ? 'development' : 'external'),
        connectionProbe: connectionProbe ?? null });
    const h = new Harness(base, token);
    let sup: SupervisedClient | null = null;
    let sink: net.Server | null = null;
    let sinkPort = 0;
    let tick: SessionTick | null = null;
    let exitCode = 1;
    try {
        if (supervised) {
            if (connectionProbe) {
                sink = net.createServer(socket => {
                    (globalThis as any).__mapiSinkAccepted = true;
                    socket.destroy();
                });
                (globalThis as any).__mapiSinkAccepted = false;
                await new Promise<void>((resolve, reject) => {
                    sink!.once('error', reject);
                    sink!.listen(0, '127.0.0.1', () => resolve());
                });
                sinkPort = (sink.address() as net.AddressInfo).port;
                process.env['MAPI_CLIENT_CONNECT_ALLOWLIST'] = connectionProbe === 'deny'
                    ? 'localhost'
                    : `localhost,127.0.0.1:${sinkPort},[::1]:${sinkPort}`;
            }
            sup = await startClient({
                loader, token, repoRoot, outDir,
                profilePath: path.join(repoRoot, 'e2e', 'profiles', 'client', 'window-smoke.json'),
                mode: releaseJar ? 'release' : 'dev',
            });
            (report as any).meta.clientRunDir = sup.runDir;
            (report as any).meta.sinkPort = sinkPort || undefined;
            (report as any).meta.connectionAllowlist = process.env['MAPI_CLIENT_CONNECT_ALLOWLIST'] ?? '(config file)';
        }
        await waitForClient(h, sup);
        const info = await h.info();
        console.log(`  ✓ mapi ${info.version} on MC ${info.minecraftVersion} (${info.platform})`);
        (report as any).meta.mapi = info;
        if (releaseJar) {
            const identity = info.runtimeArtifact;
            const artifactDir = path.join(repoRoot, loader, 'build', 'libs');
            const artifacts = fs.readdirSync(artifactDir)
                .filter(name => name.startsWith(`minecraft-api-${loader}-`)
                    && name.endsWith('.jar') && !name.includes('-sources') && !name.includes('-javadoc'))
                .map(name => path.join(artifactDir, name));
            if (artifacts.length !== 1) {
                throw new Error(`expected exactly one packaged ${loader} JAR in ${artifactDir}, found ${artifacts.length}`);
            }
            const artifact = artifacts[0];
            const expected = crypto.createHash('sha256').update(fs.readFileSync(artifact)).digest('hex');
            report.expect(identity?.kind === 'jar' && identity?.sha256 === expected,
                'release artifact provenance',
                `expected packaged ${loader} JAR sha256 ${expected}; runtime reported ${JSON.stringify(identity)}`);
        }
        if (supervised) {
            const profile = JSON.parse(fs.readFileSync(path.join(sup!.runDir, 'profile.json'), 'utf8'));
            const applied: Record<string, unknown> = {};
            if (profile.windowWidth && profile.windowHeight) {
                applied.window = await h.post('/api/v1/client/window/set-windowed', {
                    width: profile.windowWidth, height: profile.windowHeight,
                });
            }
            if (typeof profile.guiScale === 'number') {
                applied.guiScale = await h.post('/api/v1/client/window/set-gui-scale', {
                    guiScale: profile.guiScale,
                });
            }
            if (typeof profile.fullscreen === 'boolean') {
                applied.fullscreen = await h.post('/api/v1/client/window/set-fullscreen', {
                    fullscreen: profile.fullscreen,
                });
            }
            fs.writeFileSync(path.join(outDir, 'profile-effective.json'),
                JSON.stringify({ requested: profile, observed: applied }, null, 2));
            (report as any).meta.profile = { requested: profile, observed: applied };
        }

        if (connectionProbe) {
            await runConnectionProbe(h, report, connectionProbe, sinkPort, sup!.logFile);
            exitCode = report.failed === 0 ? 0 : 1;
        } else {

        if (!keepWorld) {
            await dismissScreens(h);
            await ensureWorld(h);
        }
        await h.waitForServerReady();
        // The single session tick lease pauses the world by default; all
        // scenario advancement, including entity cleanup, uses exact step jobs.
        let leaseId: string;
        try {
            leaseId = await h.acquireTickLease(3600);
        } catch (e) {
            report.record('tick lease', false,
                'a previous run still holds the lease on this game (no release '
                + 'endpoint exists — TTL expiry is the release). Wait for it '
                + 'to expire, or use --supervised (fresh game per run).');
            throw e;
        }
        let stepped = 0;
        tick = {
            leaseId,
            get stepped() { return stepped; },
            freeze: () => h.freezeTicks(leaseId),
            unfreeze: () => h.unfreezeTicks(leaseId),
            stepTicks: async (n: number) => {
                const job = await h.stepTicks(leaseId, n);
                stepped += n;
                return job;
            },
        };
        await h.freezeTicks(leaseId);
        await applyCharter(h, msg => report.advisory(msg));

        const ctx: Ctx = {
            h, report, envId, updateBaselines, tick,
            baselinesDirFor: s => path.join(
                repoRoot, 'e2e', 'baselines', envId, s),
            outDirFor: s => path.join(outDir, s),
        };

        for (const name of names) {
            const scenario = SCENARIOS.find(s => s.name === name);
            if (!scenario) {
                report.record(name, false, `unknown scenario (have: ${SCENARIOS.map(s => s.name).join(', ')})`);
                continue;
            }
            console.log(`\n── ${name}`);
            report.setScenario(name);
            try {
                await scenarioReset(h, msg => report.advisory(msg));
                await scenario.run(ctx);
            } catch (e) {
                report.record(`${name} crashed`, false,
                    e instanceof Error ? e.message : String(e));
            } finally {
                await setTimeAbsolute(h, NOON).catch(() => {});
                await tick.freeze().catch(() => {});
            }
        }
        exitCode = report.failed === 0 ? 0 : 1;
        }
    } catch (e) {
        report.record('run', false, e instanceof Error ? e.message : String(e));
        // Failure evidence for the human: current screen + a raw screenshot.
        try {
            const screen = await h.get('/api/v1/client/screen');
            report.advisory(`screen at failure: ${JSON.stringify(screen.screenId)}`);
            const shot = await h.screenshot();
            const file = path.join(outDir, 'failure-screenshot.png');
            fs.writeFileSync(file, shot.png);
            report.advisory(`failure screenshot: ${file}`);
        } catch { /* API may be gone */ }
    } finally {
        if (sink) await new Promise<void>(resolve => sink!.close(() => resolve()));
        // Best-effort: leave the game unpaused regardless of outcome.
        if (tick) await tick.unfreeze().catch(() => {});
        if (sup) await stopClient(h, sup);
    }

    console.log('\n╔══════════════════════════════════════════════╗');
    console.log(`║  Results: ${report.passed} passed, ${report.failed} failed`);
    console.log('╚══════════════════════════════════════════════╝');
    report.write(outDir);
    return exitCode;
}

if (isMain) {
    main().then(code => process.exit(code)).catch(e => {
        console.error('runner crashed:', e);
        process.exit(1);
    });
}
