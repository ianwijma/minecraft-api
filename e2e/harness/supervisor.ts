/**
 * Process lifecycle for a supervised E2E client (plan §3.5): dedicated
 * loader run dir, E2E profile applied, token/rate-limit via env, health
 * poll, graceful shutdown with the watchdog's DevLaunch kill fallback.
 * Never touches a user's normal game directory (spec §8 preamble).
 */
import { spawn, type ChildProcess } from 'node:child_process';
import * as fs from 'node:fs';
import * as net from 'node:net';
import * as path from 'node:path';
import { Harness } from './client.ts';
import { sleep } from './report.ts';

export interface SupervisedClient {
    proc: ChildProcess;
    runDir: string;
    logFile: string;
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
}): Promise<SupervisedClient> {
    await assertPortFree(25586);
    const runDir = path.join(opts.repoRoot, opts.loader, 'run', 'client');
    fs.mkdirSync(runDir, { recursive: true });
    fs.copyFileSync(opts.profilePath, path.join(runDir, 'options.txt'));
    fs.mkdirSync(opts.outDir, { recursive: true });
    const logFile = path.join(opts.outDir, 'client.log');
    const log = fs.openSync(logFile, 'w');
    const proc = spawn('./gradlew', [`:${opts.loader}:runClient`, '--console=plain'], {
        cwd: opts.repoRoot,
        env: {
            ...process.env,
            // The API is disabled by default (docs/http-api.md); E2E needs it.
            MAPI_HTTP_ENABLED: 'true',
            MAPI_HTTP_TOKEN: opts.token,
            MAPI_HTTP_RATE_LIMIT_PER_MINUTE: '3600',
        },
        stdio: ['ignore', log, log],
        detached: true,
    });
    return { proc, runDir, logFile };
}

export async function stopClient(h: Harness, sup: SupervisedClient): Promise<void> {
    try {
        await h.post('/api/v1/process/shutdown', {});
    } catch { /* already gone */ }
    const exited = await Promise.race([
        new Promise<boolean>(resolve => sup.proc.once('exit', () => resolve(true))),
        sleep(45_000).then(() => false),
    ]);
    if (!exited) {
        // The watchdog pattern: gradle may die without the game process.
        sup.proc.kill('SIGKILL');
        spawn('pkill', ['-9', '-f', '[D]evLaunch'], { stdio: 'ignore' });
        await sleep(2_000);
    }
}
