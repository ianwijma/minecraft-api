#!/usr/bin/env bash
# Publishes the common API artifact to ~/.m2 and compiles the consumer example
# against the published artifact (validates the real publishing path).
set -euo pipefail

if [ ! -f gradlew ]; then
  echo "run from the repository root" >&2
  exit 2
fi

./gradlew publishLocal --console=plain
./gradlew :example-consumer:build -PmapiConsumerUseMavenLocal=true --console=plain

echo "published-api verification: PASS"
echo "artifact: $(ls ~/.m2/repository/dev/example/mapi/minecraft-api-common/*/minecraft-api-common-*.jar | head -1)"