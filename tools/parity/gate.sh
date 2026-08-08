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
    # gateway-api는 OPENSAM-35에서 합류했다. v2 격리 게이트(production 컨텍스트 v2 빈 0)가
    # 세 JVM 서비스 전부에 있는데 backend 게이트가 둘만 돌면, gateway 쪽 게이트는 이 리포의
    # acceptance 기준으로는 영원히 실행되지 않는다(CI `./gradlew build`만 덮음).
    # tasks와 xml_roots는 반드시 함께 늘린다 — 태스크만 늘리면 돌기만 하고 채점되지 않는다.
    tasks=( ":common:test" ":logic:test" ":infra:test" ":app:game-engine:test" ":app:game-api:test" ":app:gateway-api:test" )
    xml_roots=( "common" "logic" "infra" "app/game-engine" "app/game-api" "app/gateway-api" )
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
  *)
    echo "Unknown gate target: $target" >&2
    echo "Usage: tools/parity/gate.sh [backend|common|logic|infra|engine|api]" >&2
    exit 64
    ;;
esac

if [[ -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
fi

log_file="${TMPDIR:-/tmp}/opensamguk-gradle-${target}-$(date +%Y%m%d%H%M%S).log"

echo "Running gate '$target' with JAVA_HOME=${JAVA_HOME:-unset}"
./gradlew --no-daemon --console=plain "${tasks[@]}" 2>&1 | tee "$log_file"

if ! grep -q "BUILD SUCCESSFUL" "$log_file"; then
  echo "Gradle output did not contain BUILD SUCCESSFUL: $log_file" >&2
  exit 1
fi

python3 - "$ROOT" "${xml_roots[@]}" <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(sys.argv[1])
module_roots = [root / rel for rel in sys.argv[2:]]
files = []
for module_root in module_roots:
    files.extend(module_root.glob("build/test-results/test/TEST-*.xml"))
files = sorted(files)
if not files:
    print("No Gradle test XML files found for selected modules", file=sys.stderr)
    sys.exit(1)

bad = []
total_tests = 0
for path in files:
    tree = ET.parse(path)
    suite = tree.getroot()
    tests = int(float(suite.attrib.get("tests", "0")))
    failures = int(float(suite.attrib.get("failures", "0")))
    errors = int(float(suite.attrib.get("errors", "0")))
    skipped = int(float(suite.attrib.get("skipped", "0")))
    total_tests += tests
    if failures or errors:
        bad.append((path, tests, failures, errors, skipped))

if bad:
    for path, tests, failures, errors, skipped in bad:
        print(f"RED {path}: tests={tests} failures={failures} errors={errors} skipped={skipped}", file=sys.stderr)
    sys.exit(1)

print(f"XML gate green: {len(files)} suites, {total_tests} tests")
PY
