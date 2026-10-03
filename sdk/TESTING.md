# SDK and API acceptance adapters

`./gradlew apiHarnessTest` builds the Java JSON-lines worker and runs the
shared offline operation serialization, authentication/error, fragmented SSE,
and cleanup tests for TypeScript, Python, and Java. Java SSE has a bounded
lifetime and explicit `close()`; TypeScript accepts an AbortSignal and releases
its reader; Python exposes optional open/gap callbacks for supervisor cleanup.
The worker sources are acceptance helpers and are excluded from published SDKs.

The live runner invokes generated TypeScript methods and the existing generic
Python/Java SDK request methods through those adapters. See
[complete API coverage](../docs/api-coverage.md) for the live corpus and CI gate.
