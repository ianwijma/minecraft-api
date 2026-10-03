import { inventory, loadContract, validateManifest } from './coverage.ts';
import { manifest } from './manifest.ts';

const operations = inventory(loadContract());
validateManifest(operations, manifest);
console.log(`API coverage definitions: ${operations.length} operations`);
