#!/usr/bin/env bash
# CI-only actual-gate mutation. Baseline XML and golden gates remain untouched.
set -euo pipefail
[[ "${CI:-}" == "true" && "${GITHUB_ACTIONS:-}" == "true" ]] || { echo "This mutation check requires isolated GitHub CI" >&2; exit 2; }
source_path='app/game-engine/src/main/kotlin/opensamguk/engine/status/StatusController.kt'
evidence_root="$PWD/build/pause-gate-mutation"
mkdir -p "$evidence_root"
backup_path="$(mktemp "${TMPDIR:-/tmp}/c8-pause-source.XXXXXX")"
cp "$source_path" "$backup_path"
cleanup() {
  cp "$backup_path" "$source_path"
  rm -f "$backup_path"
}
trap cleanup EXIT
python3 - "$source_path" <<'PY'
import sys
from pathlib import Path
p = Path(sys.argv[1])
s = p.read_text()
needle = 'val paused = pauseGate.isPaused()'
if s.count(needle) != 1:
    raise SystemExit('Actual pause gate read marker is not unique')
p.write_text(s.replace(needle, 'val paused = false', 1))
PY
mutant_exit=0
./gradlew :app:game-engine:test --tests opensamguk.engine.status.StatusControllerTest --no-daemon \
  -I tools/ci/pause-gate-mutation/report.init.gradle \
  "-Dopensamguk.pauseMutation.output=$evidence_root/mutant" > "$evidence_root/mutant-gradle.log" 2>&1 || mutant_exit=$?
cp "$backup_path" "$source_path"
cmp "$backup_path" "$source_path"
./gradlew :app:game-engine:test --tests opensamguk.engine.status.StatusControllerTest --no-daemon \
  -I tools/ci/pause-gate-mutation/report.init.gradle \
  "-Dopensamguk.pauseMutation.output=$evidence_root/restored" > "$evidence_root/restored-gradle.log" 2>&1
python3 - "$evidence_root" "$mutant_exit" "$source_path" "$backup_path" <<'PY'
import hashlib
import json
import sys
from pathlib import Path
from xml.etree import ElementTree as E
root, status, source, backup = Path(sys.argv[1]), int(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4])
name = 'TEST-opensamguk.engine.status.StatusControllerTest.xml'
mutant = E.parse(root / 'mutant/xml' / name).getroot()
restored = E.parse(root / 'restored/xml' / name).getroot()
expected = 'open world PAUSED projection consumes the actual gate read even with an old successful tick()'
red = [c for c in mutant.findall('testcase') if c.get('name') == expected and c.find('failure') is not None]
if status == 0 or not red or int(mutant.get('skipped', '-1')) != 0:
    raise SystemExit('Removing the actual gate read did not turn the required regression red')
if any(int(restored.get(k, '-1')) != 0 for k in ('failures', 'errors', 'skipped')):
    raise SystemExit('Restored source is not green without skips')
if source.read_bytes() != backup.read_bytes():
    raise SystemExit('Source restoration failed')
summary = {'mutation': 'StatusController.status actual gate read replaced by false',
           'mutantExit': status, 'mutantTests': int(mutant.get('tests')),
           'mutantFailures': int(mutant.get('failures')), 'requiredRegressionRed': True,
           'restoredTests': int(restored.get('tests')), 'restoredFailures': 0,
           'restoredErrors': 0, 'restoredSkipped': 0,
           'restoredSourceSha256': hashlib.sha256(source.read_bytes()).hexdigest()}
(root / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary))
PY
