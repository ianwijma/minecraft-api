#!/usr/bin/env bash
# MAPI environment doctor. Deterministic, read-only; exits non-zero on blockers.
set -u

PASS=0; FAIL=0; WARN=0
ok()   { printf '  PASS  %s\n' "$1"; PASS=$((PASS+1)); }
bad()  { printf '  FAIL  %s\n' "$1"; FAIL=$((FAIL+1)); }
warn() { printf '  WARN  %s\n' "$1"; WARN=$((WARN+1)); }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "== minecraft-api doctor =="
echo "repository: $ROOT"

# --- java ---
JAVA_OK=0
if command -v java >/dev/null 2>&1; then
  JVER="$(java -version 2>&1 | head -1 | grep -oE '[0-9]+\.[0-9]+' | head -1)"
  JMAJOR="${JVER%%.*}"
  if [ "${JMAJOR:-0}" -ge 25 ]; then
    ok "java: $(java -version 2>&1 | head -1)"
    JAVA_OK=1
  else
    warn "java version ${JVER:-unknown} detected; JDK 25 required (build will auto-download via foojay resolver)"
    JAVA_OK=1
  fi
else
  warn "no java on PATH; the Gradle build will auto-download JDK 25 (needs network)"
fi
if command -v javac >/dev/null 2>&1; then
  ok "javac present: $(javac -version 2>&1)"
else
  warn "javac not on PATH (a JRE is not enough for compilation; JDK 25 recommended)"
fi
[ -n "${JAVA_HOME:-}" ] && ok "JAVA_HOME set: ${JAVA_HOME}" || warn "JAVA_HOME not set (Gradle will pick java from PATH)"

# --- gradle wrapper ---
if [ -f gradlew ] && [ -f gradle/wrapper/gradle-wrapper.jar ] && [ -f gradle/wrapper/gradle-wrapper.properties ]; then
  ok "gradle wrapper present (gradlew + wrapper jar + properties)"
  grep -q "distributionSha256Sum" gradle/wrapper/gradle-wrapper.properties \
    && ok "wrapper distribution checksum pinned" \
    || warn "wrapper has no distributionSha256Sum pinned"
  grep -oE "gradle-[0-9.]+-bin.zip" gradle/wrapper/gradle-wrapper.properties | head -1 | xargs -I{} echo "        pinned distribution: {}"
else
  bad "gradle wrapper files missing"
fi

# --- network (toolchain sources) ---
if command -v curl >/dev/null 2>&1; then
  probe() { curl -sS -m 10 -o /dev/null -w '%{http_code}' "$1" 2>/dev/null; }
  check_url() {
    code="$(probe "$1")"
    case "$code" in
      200|302|404) ok "network: $1 (HTTP $code)" ;;
      *) warn "network: $1 unreachable (code=${code:-none})" ;;
    esac
  }
  check_url "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
  check_url "https://meta.fabricmc.net/v2/versions/loader"
  check_url "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml"
  check_url "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml"
  check_url "https://services.gradle.org/versions/current"
  check_url "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/maven-metadata.xml"
else
  warn "curl not found; cannot probe network"
fi

# --- tools ---
command -v python3 >/dev/null 2>&1 && ok "python3 present (client example + token generation)" || warn "python3 missing (scripts/mapi-client.py unavailable)"
command -v git >/dev/null 2>&1 && ok "git present" || warn "git missing"

# --- disk ---
if command -v df >/dev/null 2>&1; then
  FREE_KB="$(df -Pk . | awk 'NR==2 {print $4}')"
  FREE_MB=$((FREE_KB / 1024))
  if [ "$FREE_MB" -ge 4000 ]; then ok "disk: ${FREE_MB} MB free (first build needs several GB in ~/.gradle)"; else warn "disk: ${FREE_MB} MB free; first build needs several GB"; fi
fi

# --- secrets hygiene spot check (never prints contents) ---
if ls *.token 2>/dev/null | grep -q .; then warn "a *.token file exists in repo root (gitignored, but clean it up)"; fi
[ -f eula.txt ] && warn "eula.txt present in repo root (never commit)" || true

echo
echo "summary: PASS=$PASS WARN=$WARN FAIL=$FAIL"
if [ "$FAIL" -gt 0 ]; then
  echo "doctor: FAIL — fix the items above before building"
  exit 1
fi
echo "doctor: environment acceptable (warnings are non-blocking)"
exit 0