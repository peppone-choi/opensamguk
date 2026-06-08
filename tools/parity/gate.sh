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
    tasks=( ":common:test" ":logic:test" ":infra:test" ":app:game-engine:test" ":app:game-api:test" )
    xml_roots=( "common" "logic" "infra" "app/game-engine" "app/game-api" )
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
