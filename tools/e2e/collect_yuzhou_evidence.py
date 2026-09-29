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
import re
import sys


SCREENS = (
    "court", "hand", "orders", "posts", "retinue", "siege", "supply", "war-room", "yuedan"
)
JSON_ATTACHMENTS = {"db-hwiha-slice", "phase-events", "phase-evidence"}
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
GIT_SHA = re.compile(r"[0-9a-f]{40}\Z")


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
    db = json.loads(decoded["db-hwiha-slice.json"])
    sieges = db.get("sieges") or []
    require(isinstance(sieges, list), "DB sieges must be an array")
    fallen_turns = []
    for siege in sieges:
        require(isinstance(siege, dict), "invalid DB siege row")
        if siege.get("status") != "FALLEN":
            continue
        turns = siege.get("turns")
        require(isinstance(turns, int) and not isinstance(turns, bool) and turns >= 0,
                "invalid fallen siege turns")
        fallen_turns.append(turns)
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
        "row_diff_gate": {"status": "NOT_COLLECTED"},
        "siege_tempo": {"fallen_turns": sorted(fallen_turns),
                        "within_12_to_24": sum(12 <= turns <= 24 for turns in fallen_turns)},
        "phase_evidence": phase,
        "attachments_sha256": {name: hashlib.sha256(body).hexdigest()
                               for name, body in sorted(decoded.items())},
    }
    return decoded, rows, summary


def summarize_battle_export(source: Path, expected_encounter_ids: set[str] | None = None) -> dict:
    """Validate a synthetic QA projection; its product event mapping is still undecided."""
    document = json.loads(source.read_text(encoding="utf-8"))
    require(document.get("schemaVersion") == "qa-encounter-outcomes-draft-v0",
            "unsupported draft battle export schema")
    origin = document.get("origin")
    require(isinstance(origin, dict) and origin.get("worldId") == 990002,
            "battle export must identify the isolated Yuzhou world")
    require((origin.get("evidenceSource"), origin.get("publishBoundary")) in (
        ("MEMORY_ONLY", "IN_MEMORY_TEST"), ("DB_BACKED", "AFTER_SUCCESSFUL_FLUSH")),
        "battle export must label memory-only or successful DB flush boundary")
    for field in ("gitSha", "mapSha256", "scenarioSha256"):
        value = origin.get(field)
        pattern = GIT_SHA if field == "gitSha" else SHA256
        require(isinstance(value, str) and pattern.fullmatch(value) is not None,
                f"battle export missing {field} pin")
    rows = document.get("rows")
    require(isinstance(rows, list) and rows, "battle export needs resolved encounter rows")
    id_groups = {}
    for field in ("sealedEncounterIds", "disbandedEncounterIds", "unresolvedEncounterIds"):
        values = document.get(field)
        require(isinstance(values, list) and all(isinstance(value, str) and value for value in values)
                and len(values) == len(set(values)), f"invalid {field}")
        id_groups[field] = set(values)
    require(id_groups["disbandedEncounterIds"] <= id_groups["sealedEncounterIds"]
            and id_groups["unresolvedEncounterIds"] <= id_groups["sealedEncounterIds"]
            and not (id_groups["disbandedEncounterIds"] & id_groups["unresolvedEncounterIds"]),
            "invalid sealed encounter partition")
    seen = set()
    outcomes: Counter[str] = Counter()
    barriers: Counter[str] = Counter()
    commander_statuses: Counter[str] = Counter()
    variants: Counter[str] = Counter()
    separation_steps: list[int] = []
    unknown_separations = 0
    winnerless = max_rounds = callbacks = 0
    for row in rows:
        require(isinstance(row, dict), "invalid battle export row")
        encounter_id = row.get("encounterId")
        require(row.get("worldId") == origin["worldId"]
                and isinstance(encounter_id, str) and encounter_id and encounter_id not in seen,
                "missing or duplicate encounterId")
        seen.add(encounter_id)
        require(isinstance(row.get("resolvedYear"), int) and row["resolvedYear"] > 0
                and isinstance(row.get("resolvedMonth"), int) and 1 <= row["resolvedMonth"] <= 12
                and isinstance(row.get("resolvedPhase"), int) and 1 <= row["resolvedPhase"] <= 3,
                "invalid battle resolved phase")
        require(isinstance(row.get("provinceId"), str) and row["provinceId"]
                and isinstance(row.get("approachProvinceId"), str) and row["approachProvinceId"]
                and (row.get("worldMapVariant") is None or isinstance(row["worldMapVariant"], str)),
                "invalid battle province or variant")
        require(isinstance(row.get("topologyRevision"), str) and row["topologyRevision"]
                and isinstance(row.get("topologyHash"), str) and SHA256.fullmatch(row["topologyHash"])
                and isinstance(row.get("tilesContentHash"), str) and SHA256.fullmatch(row["tilesContentHash"])
                and isinstance(row.get("replayHash"), str) and SHA256.fullmatch(row["replayHash"]),
                "missing battle topology, tile, or replay pin")
        for field in ("deploymentRuleVersion", "layoutRuleVersion", "geometryRuleVersion",
                      "resolutionRuleVersion"):
            require(isinstance(row.get(field), int) and not isinstance(row[field], bool) and row[field] > 0,
                    f"invalid {field}")
        outcome, barrier, rounds = row.get("outcome"), row.get("barrier"), row.get("rounds")
        require(outcome in ("ATTACKER_VICTORY", "DEFENDER_VICTORY"), "invalid battle outcome")
        require(barrier in ("RETREAT_REQUIRED", "DESTRUCTION_REQUIRED", "ROUND_LIMIT"),
                "invalid battle barrier")
        require(isinstance(rounds, int) and not isinstance(rounds, bool) and 1 <= rounds <= 24,
                "invalid battle round count")
        winners, statuses = row.get("winners"), row.get("statuses")
        require(isinstance(winners, list) and all(isinstance(value, int) and value > 0 for value in winners)
                and winners == sorted(set(winners)), "invalid battle winners")
        require(isinstance(statuses, list) and len(statuses) >= 2, "invalid battle statuses")
        status_by_id = {}
        for status in statuses:
            require(isinstance(status, dict) and isinstance(status.get("generalId"), int)
                    and status["generalId"] > 0 and status.get("status") in ("HOLDING", "RETREATED", "DESTROYED")
                    and status["generalId"] not in status_by_id, "invalid commander status")
            status_by_id[status["generalId"]] = status["status"]
            commander_statuses[status["status"]] += 1
        require(all(status_by_id.get(value) == "HOLDING" for value in winners),
                "winner must be a holding commander")
        require([value["generalId"] for value in statuses] == sorted(status_by_id),
                "commander statuses must be sorted by general ID")
        callback = row.get("callbackInvoked")
        require(type(callback) is bool and callback == bool(winners),
                "callback evidence does not match winner boundary")
        separation = row.get("initialSeparationSteps")
        require(separation is None or (isinstance(separation, int) and not isinstance(separation, bool)
                and separation >= 0), "invalid initial BFS separation")
        if separation is None:
            unknown_separations += 1
        else:
            separation_steps.append(separation)
        outcomes[outcome] += 1
        barriers[barrier] += 1
        variants[row.get("worldMapVariant") or "UNSPECIFIED"] += 1
        winnerless += not winners
        max_rounds += rounds == 24
        callbacks += callback
    require(seen == id_groups["sealedEncounterIds"] - id_groups["disbandedEncounterIds"]
            - id_groups["unresolvedEncounterIds"],
            "battle export is missing or adds resolved encounter IDs")
    if expected_encounter_ids is not None:
        require(expected_encounter_ids == id_groups["sealedEncounterIds"],
                "battle export sealed IDs differ from DB-backed march events")
    return {
        "status": "CONTRACT_DRAFT",
        "reason": "kind/refs/facts producer and complete W4 coverage await L1 contract",
        "export_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "evidence_source": origin["evidenceSource"],
        "resolved_count": len(rows),
        "sealed_count": len(id_groups["sealedEncounterIds"]),
        "disbanded_count": len(id_groups["disbandedEncounterIds"]),
        "unresolved_count": len(id_groups["unresolvedEncounterIds"]),
        "outcomes": dict(sorted(outcomes.items())),
        "barriers": dict(sorted(barriers.items())),
        "commander_statuses": dict(sorted(commander_statuses.items())),
        "world_map_variants": dict(sorted(variants.items())),
        "initial_separation_steps": sorted(separation_steps),
        "unknown_separation_count": unknown_separations,
        "winnerless_count": winnerless,
        "round_24_count": max_rounds,
        "callback_count": callbacks,
    }


def sealed_encounter_ids(phase_events: list[dict], phase_evidence: dict) -> set[str]:
    """Read sealed encounters from committed march logs, rejecting incomplete encounter rows."""
    require(isinstance(phase_evidence, dict), "invalid phase evidence")
    ids: set[str] = set()
    for event in phase_events:
        if event.get("kind") != "march.corps":
            continue
        refs = event.get("refs")
        require(isinstance(refs, dict), "march.corps event missing refs")
        encounter_id = refs.get("encounterId")
        if refs.get("stop") == "ENCOUNTER":
            require(isinstance(encounter_id, str) and encounter_id,
                    "sealed march.corps event missing encounterId")
            ids.add(encounter_id)
        else:
            require(encounter_id is None,
                    "non-encounter march.corps event carries encounterId")
    require(ids, "no DB-backed sealed encounter IDs")
    require(phase_evidence.get("liveEncounterCount") == len(ids),
            "phase evidence encounter count differs from DB-backed march events")
    return ids


def summarize_row_diff(source: Path) -> dict:
    """Attach the diagnostic row comparison without turning it into a W1/W4 pass."""
    document = json.loads(source.read_text(encoding="utf-8"))
    require(document.get("schemaVersion") == "campaign-row-diff-v1"
            and document.get("status") in ("NO_ROW_DIFF", "REVIEW_REQUIRED"),
            "unsupported campaign row diff")
    pins = document.get("pins")
    require(isinstance(pins, dict), "row diff missing source pins")
    for field in ("baseline_git", "candidate_git", "baseline_map_sha",
                  "candidate_map_sha", "scenario_sha"):
        value = pins.get(field)
        pattern = GIT_SHA if field.endswith("git") else SHA256
        require(isinstance(value, str) and pattern.fullmatch(value) is not None,
                f"row diff missing {field} pin")
    baseline, candidate = document.get("baseline"), document.get("candidate")
    require(isinstance(baseline, dict) and isinstance(candidate, dict),
            "row diff missing source counts")
    for part in (baseline, candidate):
        require(isinstance(part.get("row_count"), int) and part["row_count"] > 0
                and isinstance(part.get("rows_sha256"), str)
                and SHA256.fullmatch(part["rows_sha256"]) is not None,
                "invalid row diff source count or hash")
    types = document.get("row_types")
    require(isinstance(types, dict) and "city" in types and "siege" in types and "bugok" in types,
            "row diff missing city, siege, or bugok counts")
    city = document.get("structural_map", {}).get("city_ids", {})
    shared = document.get("shared_city_values", {})
    require(all(isinstance(city.get(field), list) for field in ("baseline_only_ids", "candidate_only_ids"))
            and isinstance(shared.get("common_changed_ids"), list)
            and isinstance(shared.get("field_change_counts"), dict),
            "row diff missing structural or shared-city changes")
    old_ids, new_ids, changed = (city["baseline_only_ids"], city["candidate_only_ids"],
                                 shared["common_changed_ids"])
    require(all(isinstance(value, int) and value > 0 for group in (old_ids, new_ids, changed)
                for value in group)
            and len(set(old_ids + new_ids + changed)) == len(old_ids + new_ids + changed),
            "row diff city IDs are invalid or overlap")
    city_counts = types["city"]
    require(city_counts.get("baseline_only_rows") == len(old_ids) + len(changed)
            and city_counts.get("candidate_only_rows") == len(new_ids) + len(changed),
            "row diff symmetric city row counts do not match ID changes")
    require(sum(row["baseline"] for row in types.values()) == baseline["row_count"]
            and sum(row["candidate"] for row in types.values()) == candidate["row_count"],
            "row diff type totals do not match source rows")
    return {
        "status": document["status"],
        "reason": "diagnostic row differences require cause review; W1 repetitions and W4 measurements remain separate",
        "summary_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "pins": pins,
        "rows": {"baseline": baseline["row_count"], "candidate": candidate["row_count"]},
        "city": {"baseline_only_ids": old_ids, "candidate_only_ids": new_ids,
                 "common_changed_count": len(changed),
                 "field_change_counts": shared["field_change_counts"],
                 "baseline_only_rows": city_counts["baseline_only_rows"],
                 "candidate_only_rows": city_counts["candidate_only_rows"]},
        "campaign_outcomes": {kind: types[kind] for kind in ("siege", "bugok", "general", "nation",
                                                              "position", "calendar") if kind in types},
    }


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
    parser.add_argument("--battle-export", type=Path,
                        help="optional draft QA projection of per-encounter results; never marks W4 passed")
    parser.add_argument("--row-diff", type=Path,
                        help="optional diagnostic normalized-row comparison; never marks W1 or W4 passed")
    args = parser.parse_args()
    source = args.artifact_dir / "playwright-results.json"
    decoded, rows, summary = collect(source)
    if args.battle_export is not None:
        phase_events = json.loads(decoded["phase-events.json"])
        expected_ids = sealed_encounter_ids(phase_events, summary["phase_evidence"])
        summary["battle_result_gate"] = summarize_battle_export(args.battle_export, expected_ids)
    if args.row_diff is not None:
        summary["row_diff_gate"] = summarize_row_diff(args.row_diff)
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
    print(f"W4 battle-result gate: {summary['battle_result_gate']['status']}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
        print(f"Yuzhou evidence incomplete: {exc}", file=sys.stderr)
        sys.exit(1)
