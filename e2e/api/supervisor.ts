import * as fs from 'node:fs';
import * as path from 'node:path';
import * as net from 'node:net';
import * as crypto from 'node:crypto';
import { spawn, type ChildProcess } from 'node:child_process';
import { startClient, stopClient, createRunRoot, type SupervisedClient } from '../harness/supervisor.ts';

export interface GameProcess {
    proc: ChildProcess;
    runDir: string;
    logFile: string;
    fixtureReport: string;
    base: string;
    token: string;
    gamePort?: number;
}

export async function freePort(): Promise<number> {
    const server = net.createServer();
    await new Promise<void>((resolve, reject) => {
        server.once('error', reject);
        server.listen(0, '127.0.0.1', resolve);
    });
    const port = (server.address() as net.AddressInfo).port;
    await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
    return port;
}

export async function startDedicated(repoRoot: string, loader: 'fabric' | 'neoforge', outDir: string): Promise<GameProcess> {
    if (process.env.MAPI_ACCEPT_EULA !== 'true') throw new Error('MAPI_ACCEPT_EULA=true must be supplied by the operator');
    const runDir = path.join(createRunRoot(repoRoot, loader), 'server');
    fs.mkdirSync(runDir, { recursive: true });
    fs.mkdirSync(outDir, { recursive: true });
    const port = await freePort();
    const gamePort = await freePort();
    const token = crypto.randomBytes(32).toString('base64url');
    const fixtureReport = path.join(outDir, 'java-api.json');
    const logFile = path.join(outDir, 'server.log');
    const profile = {
        'server-ip': '127.0.0.1', 'server-port': gamePort, 'online-mode': false,
        'enforce-secure-profile': false, 'level-seed': 20260919, 'level-type': 'minecraft:flat',
        'gamemode': 'creative', 'difficulty': 'peaceful', 'view-distance': 4, 'simulation-distance': 4,
        'pause-when-empty-seconds': 0, 'max-players': 2,
    };
    fs.writeFileSync(path.join(runDir, 'server.properties'), Object.entries(profile).map(([key, value]) => `${key}=${value}`).join('\n') + '\n');
    fs.writeFileSync(path.join(runDir, 'eula.txt'), 'eula=true\n');
    fs.writeFileSync(path.join(outDir, 'profile-requested.json'), JSON.stringify({
        profileId: 'mapi-api-server', version: 1, settings: profile,
        authentication: 'isolated offline test server; loopback only',
    }, null, 2));
    const log = fs.openSync(logFile, 'w');
    const proc = spawn('./gradlew', [`:${loader}:runReleaseServer`, `-PmapiServerRunDir=${runDir}`,
        '-PmapiFixture=true', '--console=plain', '--no-daemon'], {
        cwd: repoRoot, detached: true, stdio: ['ignore', log, log],
        env: { ...process.env, MAPI_HTTP_ENABLED: 'true', MAPI_HTTP_TOKEN: token,
            MAPI_SERVER_LAN_ENABLED: 'true', MAPI_HTTP_SCOPES: '', MAPI_HTTP_PORT: String(port), MAPI_HTTP_RATE_LIMIT_PER_MINUTE: '100000', MAPI_FIXTURE_REPORT: fixtureReport },
    });
    fs.closeSync(log);
    return { proc, runDir, logFile, fixtureReport, base: `http://127.0.0.1:${port}`, token, gamePort };
}

export async function startApiClient(repoRoot: string, loader: 'fabric' | 'neoforge', outDir: string,
    server: GameProcess): Promise<GameProcess> {
    const port = await freePort();
    const token = crypto.randomBytes(32).toString('base64url');
    const fixtureReport = path.join(outDir, 'java-api.json');
    const prior = process.env.MAPI_HTTP_PORT;
    process.env.MAPI_HTTP_PORT = String(port);
    try {
        const client = await startClient({ repoRoot, loader, token, outDir, mode: 'release', fixture: true,
            profilePath: path.join(repoRoot, 'e2e/profiles/client/window-smoke.json'),
            options: { maxFps: 30, enableVsync: false, renderDistance: 2, simulationDistance: 5, pauseOnLostFocus: false },
            env: { MAPI_HTTP_RATE_LIMIT_PER_MINUTE: '100000', MAPI_FIXTURE_REPORT: fixtureReport, MAPI_HTTP_SCOPES: '', MAPI_SERVER_LAN_ENABLED: 'true',
                MAPI_CLIENT_CONNECT_ALLOWLIST: `127.0.0.1:${server.gamePort}` },
        });
        return { ...client, fixtureReport, token, base: `http://127.0.0.1:${port}` };
    } finally {
        if (prior === undefined) delete process.env.MAPI_HTTP_PORT;
        else process.env.MAPI_HTTP_PORT = prior;
    }
}

export async function cleanup(game: GameProcess, record: (value: any) => void = () => {}): Promise<void> {
    if (game.proc.exitCode !== null || game.proc.signalCode !== null) return;
    await stopClient({ post: async () => {
        try {
            const response = await fetch(`${game.base}/api/v1/process/shutdown`, {
                method: 'POST', body: '{}', headers: { Authorization: `Bearer ${game.token}` },
                signal: AbortSignal.timeout(5000),
            });
            record({ status: response.status, response: await response.json() });
        } catch (error) { record({ error: String(error) }); throw error; }
    } } as any, { ...game, mode: 'release' } as SupervisedClient);
}

export async function waitForExit(game: GameProcess, timeoutMs = 60_000): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    while (game.proc.exitCode === null && game.proc.signalCode === null && Date.now() < deadline) {
        await new Promise(resolve => setTimeout(resolve, 100));
    }
    if (game.proc.exitCode !== 0 || game.proc.signalCode !== null) {
        throw new Error(`game did not exit cleanly within deadline; code=${game.proc.exitCode} signal=${game.proc.signalCode}`);
    }
}

export function artifactHash(repoRoot: string, loader: 'fabric' | 'neoforge'): string {
    const directory = path.join(repoRoot, loader, 'build/libs');
    const artifacts = fs.readdirSync(directory).filter(name => name.startsWith(`minecraft-api-${loader}-`)
        && name.endsWith('.jar') && !name.endsWith('-sources.jar') && !name.endsWith('-javadoc.jar'));
    if (artifacts.length !== 1) throw new Error(`expected one ${loader} release JAR, found ${artifacts.length}`);
    return crypto.createHash('sha256').update(fs.readFileSync(path.join(directory, artifacts[0]))).digest('hex');
}

export async function socketAccepts(port: number): Promise<boolean> {
    return new Promise(resolve => {
        const socket = net.createConnection({ host: '127.0.0.1', port });
        const finish = (accepted: boolean) => { socket.destroy(); resolve(accepted); };
        socket.setTimeout(1000);
        socket.once('connect', () => finish(true));
        socket.once('error', () => finish(false));
        socket.once('timeout', () => finish(false));
    });
}
