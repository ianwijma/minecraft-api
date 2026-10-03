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
 *                               run dir + E2E profile), stop it afterwards
 *   --keep-world                do not delete/recreate the mapi-e2e world
 *
 * Auth: MAPI_HTTP_TOKEN from the environment; a blank-token server accepts
 * any bearer (same convention as scripts/e2e-audit.ts).
 * Unit tests: node --experimental-strip-types --no-warnings --test 'e2e/test/*.test.ts'
 */
import * as path from 'node:path';
import * as fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import * as crypto from 'node:crypto';
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

const isMain = process.argv[1] &&
    fileURLToPath(import.meta.url) === path.resolve(process.argv[1]);

async function main(): Promise<number> {
    const repoRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
    const loader = (arg('loader') ?? 'fabric') as 'fabric' | 'neoforge';
    const base = arg('base') ?? 'http://127.0.0.1:25586';
    const supervised = process.argv.includes('--supervised');
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
    const outDir = path.join(repoRoot, 'e2e', 'out', runId);

    console.log('╔══════════════════════════════════════════════╗');
    console.log('║  MAPI E2E visual scenarios                   ║');
    console.log('╚══════════════════════════════════════════════╝');
    console.log(`  base: ${base}  loader: ${loader}  env: ${envId}`);
    console.log(`  scenarios: ${names.join(', ')}`);
    console.log(`  out: ${outDir}`);

    const report = new Report(runId, { base, loader, envId, supervised });
    const h = new Harness(base, token);
    let sup: SupervisedClient | null = null;
    let tick: SessionTick | null = null;
    let exitCode = 1;
    try {
        if (supervised) {
            sup = await startClient({
                loader, token, repoRoot, outDir,
                profilePath: path.join(
                    repoRoot, 'e2e', 'profiles', 'client', 'options.txt'),
            });
        }
        await h.healthWait();
        const info = await h.info();
        console.log(`  ✓ mapi ${info.version} on MC ${info.minecraftVersion} (${info.platform})`);
        (report as any).meta.mapi = info;

        if (!keepWorld) {
            await dismissScreens(h);
            await ensureWorld(h);
        }
        await h.waitForServerReady();
        // The single session tick lease: frozen for the entire run — the
        // determinism charter, independent of broken gamerule commands.
        // Acquired FIRST so a true doDaylightCycle can't drift while the
        // ~50s of best-effort optional commands are attempted.
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
