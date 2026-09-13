# MAPI TypeScript SDK

Zero-dependency TypeScript client for the MAPI local HTTP API (Node 22+,
fetch-based) with the §10 helper layer: token-file auth, discovery parsing
(untrusted-input hardened), task polling with idempotency + stale-session
preconditions, and event cursors with gap reporting.

## Usage

```ts
import { MapiClient } from './src/client.ts';

const client = MapiClient.fromGameDir('/path/to/instance/run');
console.log(await client.ready());
console.log(await client.players(['name', 'id']));

const task = await client.createTask('wait-for-tick', { targetTick: 1000 },
  { idempotencyKey: 'my-op-1' });
const done = await client.waitTask(String(task.id));

for await (const event of client.followEvents(0)) {
  console.log(event);
}
```

## Tests

```bash
node --experimental-strip-types --test test/client.test.ts
# or: ./gradlew sdkTypeScriptTest
```

No build step required: node 22.6+ runs the `.ts` sources directly via
type stripping (`--experimental-strip-types`).
