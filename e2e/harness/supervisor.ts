/**
 * Process lifecycle for a supervised E2E client: isolated run directory,
 * profile preflight, token/rate-limit via env, and owned process-group cleanup.
 * Never touches a user's normal game directory (spec §8 preamble).
 */
import { spawn, spawnSync, type ChildProcess } from 'node:child_process';
import * as fs from 'node:fs';
import * as net from 'node:net';
import * as os from 'node:os';
import * as path from 'node:path';
import { Harness } from './client.ts';
import { sleep } from './report.ts';

export interface SupervisedClient {
    proc: ChildProcess;
    runDir: string;
    logFile: string;
    mode: 'dev' | 'release';
}

export function createRunRoot(repoRoot: string, loader: 'fabric' | 'neoforge'): string {
    const runRootParent = path.join(repoRoot, 'build', 'e2e');
    fs.mkdirSync(runRootParent, { recursive: true });
    return fs.mkdtempSync(path.join(runRootParent, `${Date.now()}-${loader}-`));
}

/** Fail fast instead of letting the mod log a bind conflict hours later. */
async function assertPortFree(port: number): Promise<void> {
    await new Promise<void>((resolve, reject) => {
        const probe = net.createConnection({ host: '127.0.0.1', port });
        probe.once('connect', () => {
            probe.destroy();
            reject(new Error(
                `port ${port} already in use — a stale Minecraft process is `
                + 'holding the MAPI listener; kill it and retry'));
        });
        probe.once('error', () => resolve());
    });
}

export async function startClient(opts: {
    loader: 'fabric' | 'neoforge';
    token: string;
    repoRoot: string;
    profilePath: string;
    outDir: string;
    mode?: 'dev' | 'release';
    fixture?: boolean;
    env?: Record<string, string>;
}): Promise<SupervisedClient> {
    const mode = opts.mode ?? 'dev';
    const runRoot = createRunRoot(opts.repoRoot, opts.loader);
    const runDir = path.join(runRoot, 'client');
    const port = Number(process.env['MAPI_HTTP_PORT'] ?? 25586);
    await assertPortFree(port);
    fs.mkdirSync(opts.outDir, { recursive: true });
    fs.mkdirSync(runDir, { recursive: true });
    // Exclude animated sky geometry from static checkpoint settlement.
    // This directory belongs to this disposable supervised launch.
    fs.writeFileSync(path.join(runDir, 'options.txt'), 'renderClouds:"false"\n');
    fs.copyFileSync(opts.profilePath, path.join(runDir, 'profile.json'));
    const profileReport = path.join(opts.outDir, 'profile-preflight.json');
    const preflight = spawnSync('./gradlew', [':runner:runnerJar', '--console=plain', '--no-daemon'], {
        cwd: opts.repoRoot, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
    });
    fs.writeFileSync(path.join(opts.outDir, 'profile-preflight-build.log'),
        `${preflight.stdout ?? ''}\n${preflight.stderr ?? ''}`);
    if (preflight.status !== 0) {
        throw new Error(`profile preflight runner build failed (exit ${preflight.status}); see Gradle build output`);
    }
    const runnerJar = path.join(opts.repoRoot, 'runner', 'build', 'libs',
        `minecraft-api-runner-${readVersion(opts.repoRoot)}-all.jar`);
    const checked = spawnSync(java25(opts.repoRoot), ['-jar', runnerJar, 'preflight', '--profile', path.join(runDir, 'profile.json')], {
        cwd: opts.repoRoot, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
    });
    fs.writeFileSync(path.join(opts.outDir, 'profile-preflight-cli.log'),
        `${checked.stdout ?? ''}\n${checked.stderr ?? ''}`);
    fs.writeFileSync(profileReport, checked.stdout ?? '');
    if (checked.status !== 0) throw new Error(`client profile rejected; details in ${profileReport}`);
    const checkedJson = JSON.parse(checked.stdout ?? '{}');
    if (checkedJson.ok !== true) throw new Error(`client profile rejected; details in ${profileReport}`);
    fs.mkdirSync(opts.outDir, { recursive: true });
    const logFile = path.join(opts.outDir, 'client.log');
    const log = fs.openSync(logFile, 'w');
    const task = mode === 'release' ? `:${opts.loader}:runReleaseClient` : `:${opts.loader}:runClient`;
    const launchArgs = [task, `-PmapiClientRunDir=${runDir}`];
    if (opts.fixture) launchArgs.push('-PmapiFixture=true');
    if (mode === 'release' && process.env['MAPI_USE_XVFB'] === 'true') {
        launchArgs.push('-PmapiUseXvfb=true');
    }
    launchArgs.push('--console=plain', '--no-daemon');
    const useXvfb = process.env['MAPI_USE_XVFB'] === 'true';
    const executable = useXvfb && !(mode === 'release' && opts.loader === 'fabric')
        ? 'xvfb-run' : './gradlew';
    const args = executable === 'xvfb-run'
        ? ['-a', './gradlew', ...launchArgs] : launchArgs;
    const proc = spawn(executable, args, {
        cwd: opts.repoRoot,
        env: {
            ...process.env,
            // The API is disabled by default (docs/http-api.md); E2E needs it.
            MAPI_HTTP_ENABLED: 'true',
            MAPI_HTTP_TOKEN: opts.token,
            MAPI_HTTP_RATE_LIMIT_PER_MINUTE: '3600',
            ...opts.env,
            ...(!opts.env?.MAPI_CLIENT_CONNECT_ALLOWLIST && process.env['MAPI_CLIENT_CONNECT_ALLOWLIST']
                ? { MAPI_CLIENT_CONNECT_ALLOWLIST: process.env['MAPI_CLIENT_CONNECT_ALLOWLIST'] }
                : {}),
        },
        stdio: ['ignore', log, log],
        detached: true,
    });
    fs.closeSync(log);
    return { proc, runDir, logFile, mode };
}

export async function stopClient(h: Harness, sup: SupervisedClient): Promise<void> {
    try {
        await h.post('/api/v1/process/shutdown', {});
    } catch { /* already gone */ }
    const exited = sup.proc.exitCode !== null || sup.proc.signalCode !== null || await Promise.race([
        new Promise<boolean>(resolve => sup.proc.once('exit', () => resolve(true))),
        sleep(45_000).then(() => false),
    ]);
    if (!exited) {
        // detached=true gives this launch an owned process group. Terminate
        // only that group; never match or kill unrelated Minecraft processes.
        try { process.kill(-sup.proc.pid!, 'SIGTERM'); } catch { sup.proc.kill('SIGTERM'); }
        await sleep(5_000);
        try { process.kill(-sup.proc.pid!, 'SIGKILL'); } catch { sup.proc.kill('SIGKILL'); }
        await sleep(2_000);
    }
}

function readVersion(repoRoot: string): string {
    const props = fs.readFileSync(path.join(repoRoot, 'gradle.properties'), 'utf8');
    const match = props.match(/^mapiVersion=(.+)$/m);
    if (!match) throw new Error('mapiVersion missing from gradle.properties');
    return match[1].trim();
}

export function java25(repoRoot: string): string {
    const candidates = [
        process.env['JAVA_HOME'] ? path.join(process.env['JAVA_HOME'], 'bin', 'java') : '',
        ...(fs.existsSync(path.join(os.homedir(), '.gradle', 'jdks'))
            ? fs.readdirSync(path.join(os.homedir(), '.gradle', 'jdks'), { withFileTypes: true })
            .filter(entry => entry.isDirectory())
                .map(entry => path.join(os.homedir(), '.gradle', 'jdks', entry.name, 'bin', 'java'))
            : []),
    ].filter(Boolean);
    for (const candidate of candidates) {
        if (!fs.existsSync(candidate)) continue;
        const version = spawnSync(candidate, ['-version'], { encoding: 'utf8' });
        if (`${version.stdout ?? ''}${version.stderr ?? ''}`.includes('25.')) return candidate;
    }
    throw new Error('Java 25 runtime not found for test-profile preflight');
}
