#!/usr/bin/env python3
"""Extract the isolated Yuzhou Playwright gate's attachments and phase counts."""

import argparse
import base64
from collections import Counter
import csv
import hashlib
import io
import json
from pathlib import Path
import sys


SCREENS = (
    "court", "hand", "orders", "posts", "retinue", "siege", "supply", "war-room", "yuedan"
)
JSON_ATTACHMENTS = {"db-hwiha-slice", "phase-events", "phase-evidence"}
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def result_attachments(document: dict) -> list[dict]:
    stats = document.get("stats", {})
    require(stats.get("expected") == 1 and stats.get("skipped") == 0
            and stats.get("unexpected") == 0 and stats.get("flaky") == 0,
            "Yuzhou Playwright run must have exactly one clean passing test")
    suites = document.get("suites", [])
    matches = [spec for suite in suites if suite.get("file", "").endswith("yuzhou-live.spec.ts")
               or suite.get("title", "").endswith("yuzhou-live.spec.ts")
               for spec in suite.get("specs", [])]
    require(len(matches) == 1, "expected one yuzhou-live.spec.ts case")
    tests = matches[0].get("tests", [])
    require(len(tests) == 1 and len(tests[0].get("results", [])) == 1,
            "expected one Yuzhou test result without retries")
    result = tests[0]["results"][0]
    require(result.get("status") == "passed", "Yuzhou test did not pass")
    attachments = result.get("attachments", [])
    require(isinstance(attachments, list), "missing Playwright attachments")
    return attachments


def collect(source: Path) -> tuple[dict[str, bytes], list[tuple[int, int, int, str, int]], dict]:
    document = json.loads(source.read_text(encoding="utf-8"))
    decoded: dict[str, bytes] = {}
    for attachment in result_attachments(document):
        name = attachment.get("name")
        require(isinstance(name, str) and name and "/" not in name and "\\" not in name
                and name not in decoded, "invalid or duplicate attachment name")
        suffix = ".png" if name.startswith("screen-") else ".json"
        require(name.startswith("screen-") or name.startswith("api-") or name in JSON_ATTACHMENTS,
                f"unexpected attachment: {name}")
        require(isinstance(attachment.get("body"), str), f"missing inline body: {name}")
        payload = base64.b64decode(attachment["body"], validate=True)
        require(bool(payload), f"empty attachment: {name}")
        if suffix == ".png":
            require(payload.startswith(PNG_SIGNATURE), f"invalid PNG: {name}")
        else:
            json.loads(payload)
        decoded[name + suffix] = payload

    screens = {name for name in decoded if name.startswith("screen-")}
    require(screens == {f"screen-{screen}.png" for screen in SCREENS},
            "the nine required screen captures are incomplete")
    api_names = {name for name in decoded if name.startswith("api-")}
    for screen in SCREENS:
        require(any(name.startswith(f"api-{screen}-") for name in api_names),
                f"missing API response for {screen}")
    require({f"{name}.json" for name in JSON_ATTACHMENTS} <= decoded.keys(),
            "missing DB, phase event, or phase evidence attachment")

    events = json.loads(decoded["phase-events.json"])
    require(isinstance(events, list), "phase-events must be an array")
    counts: Counter[tuple[int, int, int, str]] = Counter()
    for event in events:
        key = (event.get("year"), event.get("month"), event.get("phase"), event.get("kind"))
        require(all(isinstance(value, int) and not isinstance(value, bool) for value in key[:3])
                and isinstance(key[3], str) and bool(key[3]), "invalid phase event row")
        counts[key] += 1
    rows = [(*key, count) for key, count in sorted(counts.items())]
    phase = json.loads(decoded["phase-evidence.json"])
    first_by_kind: dict[str, dict[str, int]] = {}
    totals_by_kind: Counter[str] = Counter()
    for year, month, turn_phase, kind, count in rows:
        first_by_kind.setdefault(kind, {"year": year, "month": month, "phase": turn_phase})
        totals_by_kind[kind] += count
    summary = {
        "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "screens": sorted(screens),
        "api_count": len(api_names),
        "event_count": len(events),
        "event_counts_by_kind": dict(sorted(totals_by_kind.items())),
        "first_event_by_kind": dict(sorted(first_by_kind.items())),
        "battle_result_gate": {
            "status": "NOT_COLLECTED",
            "reason": "phase-events and final lastBattle values do not provide every resolved encounter outcome",
        },
        "phase_evidence": phase,
        "attachments_sha256": {name: hashlib.sha256(body).hexdigest()
                               for name, body in sorted(decoded.items())},
    }
    return decoded, rows, summary


def render_tsv(rows: list[tuple[int, int, int, str, int]]) -> bytes:
    output = io.StringIO(newline="")
    writer = csv.writer(output, delimiter="\t", lineterminator="\n")
    writer.writerow(("year", "month", "phase", "event_kind", "count"))
    writer.writerows(rows)
    return output.getvalue().encode("utf-8")


def write_once(path: Path, body: bytes) -> None:
    if path.exists():
        require(path.read_bytes() == body, f"existing evidence differs: {path}")
        return
    path.write_bytes(body)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact_dir", type=Path, help="isolated local_v1_gate.sh E2E_ARTIFACT_DIR")
    parser.add_argument("--check-only", action="store_true", help="validate without writing attachments")
    args = parser.parse_args()
    source = args.artifact_dir / "playwright-results.json"
    decoded, rows, summary = collect(source)
    if not args.check_only:
        attachment_dir = args.artifact_dir / "attachments"
        attachment_dir.mkdir(exist_ok=True)
        for name, body in decoded.items():
            write_once(attachment_dir / name, body)
        write_once(args.artifact_dir / "phase-event-counts.tsv", render_tsv(rows))
        write_once(args.artifact_dir / "yuzhou-evidence-manifest.json",
                   (json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8"))
    print(f"Yuzhou evidence: {len(summary['screens'])} screens, "
          f"{summary['api_count']} API responses, {summary['event_count']} events")
    print("W4 battle-result gate: NOT_COLLECTED (requires per-encounter outcome evidence)")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
        print(f"Yuzhou evidence incomplete: {exc}", file=sys.stderr)
        sys.exit(1)
