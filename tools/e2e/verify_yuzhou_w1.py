#!/usr/bin/env python3
"""Fail closed on three independent W1 runs of one pinned product commit."""

import argparse
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


SUITES = {
    "opensamguk.engine.boot.PassChainInvarianceIT": 1,
    "opensamguk.engine.boot.S3PassChainProbeIT": 1,
    "opensamguk.engine.invariance.YuzhouCampaignInvarianceTest": 4,
}
BASELINES = {"s3-chain-48", "yuzhou-36-seed-00", "yuzhou-36-seed-01"}
LINKS = {"enlist", "dispatch", "march", "encounter", "siege", "capture", "income", "salary", "assessment", "ranking"}
GIT_SHA1 = re.compile(r"[0-9a-f]{40}\Z")
FIRST = re.compile(r"^s3-first-phase (.+)$", re.MULTILINE)
STATE = re.compile(r"^behavior-baseline (\S+) ([0-9a-f]{64})$", re.MULTILINE)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def read_one(path: Path) -> str:
    require(path.is_file(), f"missing {path}")
    return path.read_text(encoding="utf-8").strip()


def pin_hashes(path: Path) -> dict[str, str]:
    expected = {
        "infra/src/main/resources/map/han-world-v3.json",
        "infra/src/main/resources/scenario/scenario_990002.json",
        "tools/e2e/fixtures/yuzhou/scenario_990002.json",
    }
    values = {}
    for line in read_one(path).splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})\s+(.+)", line)
        require(match is not None, f"invalid pin hash line: {line}")
        digest, name = match.groups()
        require(name in expected and name not in values, f"unexpected or duplicate pin: {name}")
        values[name] = digest
    require(set(values) == expected, f"missing pin hashes: {expected - set(values)}")
    require(values["infra/src/main/resources/scenario/scenario_990002.json"] ==
            values["tools/e2e/fixtures/yuzhou/scenario_990002.json"], "scenario copies differ")
    return values


def baseline_hashes(path: Path) -> dict[str, str]:
    values = {}
    for line in read_one(path).splitlines():
        if not line or line.startswith("#"):
            continue
        match = re.fullmatch(r"(\S+) ([0-9a-f]{64})", line)
        require(match is not None, f"invalid baseline line: {line}")
        key, digest = match.groups()
        require(key not in values, f"duplicate baseline: {key}")
        if key in BASELINES:
            values[key] = digest
    require(set(values) == BASELINES, f"missing baseline hashes: {BASELINES - set(values)}")
    return values


def inspect_xml(directory: Path, expected: dict[str, str]) -> tuple[dict[str, int], dict[str, str], dict[str, str]]:
    paths = sorted(directory.glob("TEST-*.xml"))
    require(len(paths) == len(SUITES), f"expected exactly three XML files in {directory}; found {len(paths)}")
    actual_names = {f"TEST-{name}.xml" for name in SUITES}
    require({path.name for path in paths} == actual_names, f"wrong XML suites in {directory}")
    first = None
    states = {}
    xml_digests = {}
    for path in paths:
        suite = ET.parse(path).getroot()
        name = suite.get("name")
        require(name in SUITES and path.name == f"TEST-{name}.xml", f"XML suite identity mismatch: {path}")
        counts = {key: int(suite.get(key, "-1")) for key in ("tests", "failures", "errors", "skipped")}
        require(counts == {"tests": SUITES[name], "failures": 0, "errors": 0, "skipped": 0},
                f"red, skipped or missing tests in {path}: {counts}")
        cases = suite.findall("testcase")
        require(len(cases) == SUITES[name] and len({case.get("name") for case in cases}) == len(cases),
                f"testcase count or identity mismatch: {path}")
        require(all(case.get("classname") == name and not any(case.find(tag) is not None
                    for tag in ("failure", "error", "skipped")) for case in cases), f"bad testcase: {path}")
        if name.endswith("S3PassChainProbeIT"):
            require("적색 짝" in cases[0].get("name", ""), "red-pair testcase missing")
        if name.endswith("YuzhouCampaignInvarianceTest"):
            case_names = " ".join(case.get("name", "") for case in cases)
            require("36 phases" in case_names and "seed 01 replay" in case_names and
                    "cutting npc deployment" in case_names, "36-phase, second seed or mock red pair missing")
        output = "\n".join((node.text or "") for node in suite.iter("system-out"))
        matches = FIRST.findall(output)
        if name.endswith("PassChainInvarianceIT") and not name.endswith("S3PassChainProbeIT"):
            require(len(matches) == 1, f"expected one first-event trace: {path}")
            pairs = matches[0].split()
            require(len(pairs) == len(LINKS), f"missing first-event links: {matches[0]}")
            parsed = {}
            for pair in pairs:
                match = re.fullmatch(r"([a-z]+)=([1-9][0-9]*)", pair)
                require(match is not None, f"invalid first-event item: {pair}")
                link, phase = match.groups()
                require(link not in parsed, f"duplicate link: {link}")
                parsed[link] = int(phase)
            require(set(parsed) == LINKS and all(1 <= v <= 48 for v in parsed.values()),
                    f"missing or late first events: {parsed}")
            require(parsed["enlist"] <= parsed["dispatch"] and
                    parsed["march"] <= parsed["encounter"] <= parsed["capture"] and
                    parsed["siege"] <= parsed["capture"], f"first-event chain out of order: {parsed}")
            first = parsed
        else:
            require(not matches, f"unexpected first-event trace in {path}")
        for key, digest in STATE.findall(output):
            require(key in BASELINES and key not in states, f"unexpected or duplicate state hash: {key}")
            states[key] = digest
        xml_digests[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
    require(first is not None and states == expected, f"state checksum differs from baseline: {states} != {expected}")
    return first, states, xml_digests


def verify(root: Path, run_id: str, run_attempt: str, product_sha: str) -> dict:
    require(GIT_SHA1.fullmatch(product_sha) is not None, "invalid 40-character product Git SHA")
    attempts = []
    for number in (1, 2, 3):
        directory = root / f"yuzhou-w1-{run_id}-{run_attempt}-{number}"
        require(directory.is_dir(), f"missing W1 attempt {number}: {directory}")
        actual_sha = read_one(directory / "pin-git-sha.txt")
        require(actual_sha == product_sha, f"W1 attempt {number} uses different product SHA")
        pins = pin_hashes(directory / "pin-sha256.txt")
        baseline = baseline_hashes(directory / "world-state-sha256.txt")
        first, states, xml_digests = inspect_xml(directory, baseline)
        attempts.append({"number": number, "product_sha": actual_sha, "pin_sha256": pins,
                         "first_event_phase": first, "normalized_state_sha256": states, "xml_sha256": xml_digests})
    for field in ("product_sha", "pin_sha256", "first_event_phase", "normalized_state_sha256"):
        require(all(item[field] == attempts[0][field] for item in attempts[1:]),
                f"three W1 attempts differ in {field}")
    return {"status": "PASS", "gate": "W1", "run_id": run_id, "run_attempt": run_attempt,
            "product_sha": product_sha, "attempts": attempts}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--run-attempt", required=True)
    parser.add_argument("--product-sha", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        result = verify(args.root, args.run_id, args.run_attempt, args.product_sha)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"W1 PASS: three independent 48-turn DB/Redis and 36-phase runs at {args.product_sha}")
        return 0
    except (OSError, ValueError, ET.ParseError) as exc:
        print(f"W1 NO-GO: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
