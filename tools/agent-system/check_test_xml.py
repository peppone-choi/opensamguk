#!/usr/bin/env python3
"""check_test_xml.py — Gradle test-results XML 게이트: failures/errors + skipped.

배경 (skipped-it-guard, 2026-08): `assumeTrue(dockerAvailable)` 로 스킵된 IT는
Gradle이 `BUILD SUCCESSFUL`을 찍는다. 사람/에이전트가 그걸 보고 "테스트 통과"로
오인해 실제로는 한 번도 green이 아니었던 변경이 두 커밋 동안 머지됐다
(`ScenarioBlankUnificationIT` / `UnitSetTable.defaultCrewTypeId`, han 신생국이
시작 시점에 병종 징병 불가). 판정 근거는 test-results XML의 `skipped` 속성이지,
로그 문자열이 아니다.

기본 동작: failures/errors가 있으면 항상 실패. skipped가 하나라도 있으면 실패
(그 테스트는 검증되지 않았다는 뜻이므로).

opt-out: OPENSAM_ALLOW_SKIPPED_IT=1 — Docker 없이 로컬에서 빠르게 반복하는
정당한 시나리오를 위한 것. 이 경우에도 침묵하지 않는다: 스킵된 테스트 수와
이름을 항상 stderr에 눈에 띄게 찍는다. CI는 이 env를 절대 설정하지 않는다 —
CI 러너는 Docker가 항상 있어야 하므로 CI에서의 skip은 무조건 실패다.

quarantine (skipped_it_quarantine.json, 2026-08-24): #517 첫 CI 실행에서
`LongSimReplayGateTest#12 month structural replay matches PHP golden()`이
Docker가 살아 있는 러너에서도 스킵됐다 — 원인은 Docker가 아니라
`LONGSIM_SCHEMA4_CANDIDATE_DIR`(외부 PHP golden candidate, CI 미배선, CLAUDE.md
P5 backlog "long-sim multi-turn"). opt-out은 전역 예외라 이런 건을 가려버리므로
쓰지 않는다. 대신 이름별 quarantine 등록만 통과시키되, 티켓 없는 등록은 로더가
거부한다(빈 "가드가 있다는 착각"을 막기 위함) — 등록된 스킵도 항상 stderr에
QUARANTINED로 눈에 띄게 찍힌다.
"""
import argparse
import json
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def load_quarantine(path: Path) -> dict:
    if not path.is_file():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        print(f"quarantine file {path} is not valid JSON: {exc}", file=sys.stderr)
        sys.exit(1)
    if not isinstance(data, dict):
        print(f"quarantine file {path} must be a JSON object of test-key -> {{ticket, reason}}", file=sys.stderr)
        sys.exit(1)
    for key, entry in data.items():
        ticket = entry.get("ticket") if isinstance(entry, dict) else None
        reason = entry.get("reason") if isinstance(entry, dict) else None
        if not ticket or not isinstance(ticket, str) or not ticket.strip():
            print(f"quarantine entry {key!r} in {path} has no non-empty 'ticket' — rejecting the whole file "
                  "(a quarantine without a tracked ticket is worse than no guard).", file=sys.stderr)
            sys.exit(1)
        if not reason or not isinstance(reason, str) or not reason.strip():
            print(f"quarantine entry {key!r} in {path} has no non-empty 'reason' — rejecting the whole file.",
                  file=sys.stderr)
            sys.exit(1)
    return data


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("module_roots", nargs="+", help="repo-root 기준 모듈 경로 (e.g. infra, app/game-engine)")
    ap.add_argument("--repo-root", default=".")
    ap.add_argument(
        "--quarantine",
        default=None,
        help="skipped-test quarantine JSON path (default: <repo-root>/tools/agent-system/skipped_it_quarantine.json)",
    )
    args = ap.parse_args()

    root = Path(args.repo_root).resolve()
    module_roots = [root / rel for rel in args.module_roots]
    quarantine_path = Path(args.quarantine) if args.quarantine else root / "tools/agent-system/skipped_it_quarantine.json"
    quarantine = load_quarantine(quarantine_path)

    files = []
    missing = []
    for module_root in module_roots:
        found = sorted(module_root.glob("build/test-results/test/TEST-*.xml"))
        if not found:
            missing.append(module_root.relative_to(root))
        files.extend(found)
    files = sorted(files)
    if missing:
        missing_str = ", ".join(str(p) for p in missing)
        print(f"No Gradle test XML files found for selected module roots: {missing_str}", file=sys.stderr)
        return 1

    bad = []
    skipped_entries = []  # (key, display)
    total_tests = 0
    total_skipped = 0
    for path in files:
        tree = ET.parse(path)
        suite = tree.getroot()
        tests = int(float(suite.attrib.get("tests", "0")))
        failures = int(float(suite.attrib.get("failures", "0")))
        errors = int(float(suite.attrib.get("errors", "0")))
        skipped = int(float(suite.attrib.get("skipped", "0")))
        total_tests += tests
        total_skipped += skipped
        if failures or errors:
            bad.append((path, tests, failures, errors, skipped))
        if skipped:
            for testcase in suite.findall("testcase"):
                if testcase.find("skipped") is not None:
                    classname = testcase.attrib.get("classname", "?")
                    name = testcase.attrib.get("name", "?")
                    key = f"{classname}#{name}"
                    skipped_entries.append((key, f"{key}  ({path.relative_to(root)})"))

    if bad:
        for path, tests, failures, errors, skipped in bad:
            print(f"RED {path}: tests={tests} failures={failures} errors={errors} skipped={skipped}", file=sys.stderr)
        return 1

    if total_skipped:
        quarantined = [(k, d) for k, d in skipped_entries if k in quarantine]
        unquarantined = [(k, d) for k, d in skipped_entries if k not in quarantine]

        print(
            f"=== {total_skipped} SKIPPED TEST(S) DETECTED across {len(files)} suite(s) ===",
            file=sys.stderr,
        )
        for key, display in quarantined:
            entry = quarantine[key]
            print(f"  QUARANTINED: {display} — ticket={entry['ticket']} reason={entry['reason']}", file=sys.stderr)
        for _, display in unquarantined:
            print(f"  SKIPPED: {display}", file=sys.stderr)

        if unquarantined:
            allow = os.environ.get("OPENSAM_ALLOW_SKIPPED_IT") == "1"
            if not allow:
                print(
                    "A skipped test is NOT a passing test. BUILD SUCCESSFUL does not mean these ran "
                    "(most likely: Docker unavailable, assumeTrue(dockerAvailable) short-circuited them — "
                    "unless it's quarantined above, in which case see its ticket).",
                    file=sys.stderr,
                )
                print(
                    "If this is a genuine no-Docker local iteration run, re-run with "
                    "OPENSAM_ALLOW_SKIPPED_IT=1 — but do not claim these tests as verified. "
                    "A recurring non-Docker skip belongs in skipped_it_quarantine.json with a ticket, not this flag.",
                    file=sys.stderr,
                )
                return 1
            print(
                "OPENSAM_ALLOW_SKIPPED_IT=1 set — continuing despite skips. "
                "These tests were NOT verified; do not report them as passing.",
                file=sys.stderr,
            )
        elif quarantined:
            print(
                "All skipped tests are quarantined with a tracked ticket — continuing. "
                "These tests were NOT verified; do not report them as passing.",
                file=sys.stderr,
            )

    print(f"XML gate green: {len(files)} suites, {total_tests} tests, {total_skipped} skipped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
