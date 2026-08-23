#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

if [[ $# -eq 0 ]]; then
  target="backend"
else
  target="$1"
fi

case "$target" in
  backend)
    # Every JVM service has a v2-isolation gate, including gateway-api.
    # Keep tasks and xml_roots aligned so every executed task is also evaluated.
    tasks=( ":common:test" ":logic:test" ":infra:test" ":app:game-engine:test" ":app:game-api:test" ":app:gateway-api:test" ":app:board-api:test" )
    xml_roots=( "common" "logic" "infra" "app/game-engine" "app/game-api" "app/gateway-api" "app/board-api" )
    ;;
  common)
    tasks=( ":common:test" )
    xml_roots=( "common" )
    ;;
  logic)
    tasks=( ":logic:test" )
    xml_roots=( "logic" )
    ;;
  infra)
    tasks=( ":infra:test" )
    xml_roots=( "infra" )
    ;;
  engine|game-engine)
    tasks=( ":app:game-engine:test" )
    xml_roots=( "app/game-engine" )
    ;;
  api|game-api)
    tasks=( ":app:game-api:test" )
    xml_roots=( "app/game-api" )
    ;;
  gateway|gateway-api)
    tasks=( ":app:gateway-api:test" )
    xml_roots=( "app/gateway-api" )
    ;;
  board|board-api)
    tasks=( ":app:board-api:test" )
    xml_roots=( "app/board-api" )
    ;;
  *)
    echo "Unknown gate target: $target" >&2
    echo "Usage: tools/parity/gate.sh [backend|common|logic|infra|engine|api|gateway|board]" >&2
    exit 64
    ;;
esac

if [[ ${#tasks[@]} -ne ${#xml_roots[@]} ]]; then
  echo "Gate task/XML root count mismatch: ${#tasks[@]} tasks, ${#xml_roots[@]} roots" >&2
  exit 1
fi

for index in "${!tasks[@]}"; do
  expected_root="${tasks[$index]#:}"
  expected_root="${expected_root%:test}"
  expected_root="${expected_root//:/\/}"
  if [[ "${xml_roots[$index]}" != "$expected_root" ]]; then
    echo "Gate task/XML root mismatch: ${tasks[$index]} expects $expected_root, got ${xml_roots[$index]}" >&2
    exit 1
  fi
done

if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  if ! JAVA_HOME="$(/usr/libexec/java_home -v 21)"; then
    echo "Gate requires Java 21, but macOS could not resolve it with /usr/libexec/java_home -v 21" >&2
    exit 1
  fi
  export JAVA_HOME
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
  java_bin="$JAVA_HOME/bin/java"
else
  java_bin="$(command -v java || true)"
fi

if [[ ! -x "$java_bin" ]]; then
  echo "Gate requires Java 21, but no executable Java was found at ${java_bin:-PATH}" >&2
  exit 1
fi

if ! java_version="$("$java_bin" -version 2>&1)"; then
  echo "Gate could not execute Java at $java_bin" >&2
  exit 1
fi

if [[ ! "$java_version" =~ version[[:space:]]+\"([0-9]+) ]]; then
  echo "Gate could not determine the Java major version from $java_bin:" >&2
  echo "$java_version" >&2
  exit 1
fi

java_major="${BASH_REMATCH[1]}"
if [[ "$java_major" != "21" ]]; then
  echo "Gate requires Java 21, but $java_bin reports Java $java_major:" >&2
  echo "$java_version" >&2
  exit 1
fi

log_file="${TMPDIR:-/tmp}/opensamguk-gradle-${target}-$(date +%Y%m%d%H%M%S).log"

echo "Running gate '$target' with Java $java_major at $java_bin (JAVA_HOME=${JAVA_HOME:-PATH})"
./gradlew --no-daemon --console=plain --rerun-tasks "${tasks[@]}" 2>&1 | tee "$log_file"

if ! grep -q "BUILD SUCCESSFUL" "$log_file"; then
  echo "Gradle output did not contain BUILD SUCCESSFUL: $log_file" >&2
  exit 1
fi

python3 "$ROOT/tools/agent-system/check_test_xml.py" --repo-root "$ROOT" "${xml_roots[@]}"
