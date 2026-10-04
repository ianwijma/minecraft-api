import * as path from 'node:path';
import { TypeScriptAdapter, WorkerAdapter, type Adapter, type Sdk } from './adapters.ts';
import { java25 } from '../harness/supervisor.ts';

export function createAdapter(sdk: Sdk, base: string, token: string, repoRoot: string, timeoutMs = 30_000): Adapter {
    if (sdk === 'typescript') return new TypeScriptAdapter(base, token, timeoutMs);
    if (sdk === 'python') return new WorkerAdapter(process.env.MAPI_PYTHON ?? 'python3',
        [path.join(repoRoot, 'e2e/api/workers/python_worker.py')], base, token, timeoutMs);
    return new WorkerAdapter(java25(repoRoot), ['-jar', path.join(repoRoot, 'sdk/build/libs/mapi-api-worker.jar')],
        base, token, timeoutMs);
}
