#!/usr/bin/env python3
"""Fail closed on incomplete browser shards; retain their original execution receipts."""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
from datetime import datetime
from pathlib import Path


def read(path: Path) -> dict:
    if not path.is_file() or path.is_symlink() or path.stat().st_size > 64 * 1024 * 1024:
        raise ValueError(f"missing or invalid evidence: {path.name}")
    value = json.loads(path.read_text())
    if not isinstance(value, dict):
        raise ValueError("evidence must be an object")
    return value


def identity(spec: dict, test: dict, suite_path: tuple[str, ...] = ()) -> tuple:
    # JSON reporter merges projects into one spec and retains the first project's
    # test.id. A mobile-only shard therefore has a different spec.id from --list.
    # Reused source lines in parameterized describes are distinct executions.
    key = tuple(spec.get(k) for k in ("file", "line", "column", "title")) + (suite_path, test.get("projectName"))
    if (not all(isinstance(key[i], str) and key[i] for i in (0, 3, 5)) or
            not all(type(key[i]) is int and key[i] > 0 for i in (1, 2))):
        raise ValueError("missing browser source/project identity")
    return key


def tests(report: dict) -> dict[tuple, tuple[dict, dict]]:
    found = {}

    def visit(suite, parents=()):
        title = suite.get("title", "")
        if not isinstance(title, str):
            raise ValueError("invalid browser suite identity")
        suite_path = parents + (title,)
        for spec in suite.get("specs", []):
            for test in spec.get("tests", []):
                key = identity(spec, test, suite_path)
                if key in found:
                    raise ValueError("missing or duplicate browser test identity")
                found[key] = (spec, test)
        for child in suite.get("suites", []):
            visit(child, suite_path)

    for suite in report.get("suites", []):
        visit(suite)
    if report.get("errors"):
        raise ValueError("browser report contains collection/execution errors")
    return found


def instant(value) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("browser receipt time has no timezone")
    return parsed


ARTIFACT_NAME = re.compile(
    r"web-(game|gateway)-(smoke|topdown-screens)-e2e-shard-([1-9][0-9]*)-of-([1-9][0-9]*)-attempt-([1-9][0-9]*)"
)


def latest_shard_paths(root: Path, *, app: str, count: int, attempt: str) -> dict[tuple[str, int], Path]:
    """Select complete smoke/topdown pairs from each shard's newest uploaded attempt."""
    if app not in {"game", "gateway"} or count != (4 if app == "game" else 1):
        raise ValueError("invalid browser shard selection")
    if not attempt.isdecimal() or int(attempt) < 1 or not root.is_dir() or root.is_symlink():
        raise ValueError("invalid browser attempt or evidence root")
    candidates: dict[int, dict[int, dict[str, Path]]] = {}
    for directory in root.iterdir():
        match = ARTIFACT_NAME.fullmatch(directory.name)
        if not match or not directory.is_dir() or directory.is_symlink():
            raise ValueError("unexpected browser shard artifact")
        source_app, phase, index_text, count_text, source_attempt_text = match.groups()
        index, source_count, source_attempt = map(int, (index_text, count_text, source_attempt_text))
        if (source_app != app or source_count != count or not 1 <= index <= count or
                source_attempt > int(attempt)):
            raise ValueError("browser shard artifact identity or future attempt differs")
        phases = candidates.setdefault(index, {}).setdefault(source_attempt, {})
        if phase in phases:
            raise ValueError("duplicate browser shard artifact")
        phases[phase] = directory / "phase.json"
    selected = {}
    for index in range(1, count + 1):
        if index not in candidates:
            raise ValueError("missing browser shard")
        latest = max(candidates[index])
        phases = candidates[index][latest]
        if set(phases) != {"smoke", "topdown-screens"}:
            raise ValueError("latest browser shard attempt has a missing phase")
        for phase, path in phases.items():
            if not path.is_file() or path.is_symlink():
                raise ValueError("latest browser shard attempt has missing evidence")
            selected[phase, index] = path
    return selected


def check(root: Path, *, app: str, count: int, run_id: str, attempt: str,
          head: str, workflow_sha: str, output: Path) -> dict:
    selected = latest_shard_paths(root, app=app, count=count, attempt=attempt)
    receipts = {phase: {} for phase in ("smoke", "topdown-screens")}
    for (phase, index), path in sorted(selected.items()):
        record = read(path)
        expected = {"schema": "web-e2e-phase-v1", "app": app, "runId": run_id,
                    "runAttempt": path.parent.name.rsplit("-attempt-", 1)[1],
                    "phase": phase, "shardIndex": index, "headSha": head,
                    "workflowSha": workflow_sha, "shardCount": count,
                    "recordState": "FINISHED", "exitCode": 0,
                    "workflowStepOutcome": "success"}
        if (any(type(record.get(key)) is not int for key in ("shardCount", "shardIndex", "exitCode")) or
                any(record.get(k) != v for k, v in expected.items())):
            raise ValueError("browser shard producer identity or outcome differs")
        if instant(record["finishedAt"]) < instant(record["startedAt"]):
            raise ValueError("browser execution time moved backwards")
        receipts[phase][index] = (path, record)

    summary = {"schema": "web-shards-v1", "app": app, "runId": run_id,
               "runAttempt": attempt, "headSha": head, "workflowSha": workflow_sha,
               "shardCount": count, "phases": {}}
    for phase, shards in receipts.items():
        if set(shards) != set(range(1, count + 1)):
            raise ValueError(f"{phase}: missing browser shard")
        canonical = None
        collected = None
        source_identity = None
        actual = {}
        source_receipts = []
        for index, (path, record) in sorted(shards.items()):
            inventory_report = read(path.parent / "expected.json")
            inventory = tests(inventory_report)
            identities = set(inventory)
            source = {key: tuple(spec.get(k) for k in ("title", "file", "line", "column"))
                      for key, (spec, _) in inventory.items()}
            if canonical is None:
                canonical = identities
                collected = copy.deepcopy(inventory_report.get("suites", []))
                source_identity = source
            if identities != canonical or source != source_identity:
                raise ValueError("browser shards disagree on full test inventory")
            if not inventory:
                if phase != "topdown-screens" or record.get("testState") != "NO_TOPDOWN_SPECS":
                    raise ValueError("empty browser smoke inventory")
                if record.get("playwrightInvoked") is not False or record.get("playwrightExitCode") is not None:
                    raise ValueError("invalid empty topdown receipt")
                selected = {}
            else:
                if (record.get("testState") != "PLAYWRIGHT_FINISHED" or
                        record.get("playwrightInvoked") is not True or record.get("playwrightExitCode") != 0):
                    raise ValueError("browser tests did not finish successfully")
                selected = tests(read(path.parent / "results.json"))
            for key, (spec, test) in selected.items():
                if key not in canonical or key in actual:
                    raise ValueError("duplicate or unexpected executed browser test (source identity)")
                expected_spec, _ = inventory[key]
                if any(spec.get(k) != expected_spec.get(k) for k in ("title", "file", "line", "column")):
                    raise ValueError("browser test source identity differs from collection")
                results = test.get("results", [])
                # Preserve Playwright's existing retry policy; selected O3 proofs separately require retry0.
                if test.get("status") not in {"expected", "flaky"} or not results or results[-1].get("status") != "passed":
                    raise ValueError("failed/skipped/unexecuted browser test")
                actual[key] = (spec, test)
            source_receipts.append({"shardIndex": index, "artifact": path.parent.name,
                                    "runAttempt": record["runAttempt"], "phase": record,
                                    "phaseSha256": hashlib.sha256(path.read_bytes()).hexdigest()})
        if set(actual) != canonical:
            raise ValueError("browser shard union has missing tests")
        # Retain collected describe/file hierarchy and original executed test objects.
        # No fabricated single-run timing or invocation receipt is emitted.
        def populate(suite, parents=()):
            suite_path = parents + (suite.get("title", ""),)
            for spec in suite.get("specs", []):
                spec["tests"] = [copy.deepcopy(actual[identity(spec, test, suite_path)][1])
                                 for test in spec.get("tests", [])]
            for child in suite.get("suites", []):
                populate(child, suite_path)

        for suite in collected:
            populate(suite)
        merged = {"suites": collected, "errors": []}
        directory = output / phase
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "results.json").write_text(json.dumps(merged, indent=2) + "\n")
        aggregate = dict(summary, schema="web-e2e-shard-aggregate-v1", phase=phase,
                         testCount=len(actual), shardReceipts=source_receipts)
        (directory / "phase.json").write_text(json.dumps(aggregate, indent=2) + "\n")
        summary["phases"][phase] = {"testCount": len(actual)}
    output.mkdir(parents=True, exist_ok=True)
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    return summary


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    for name in ("app", "run-id", "attempt", "head", "workflow-sha"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--count", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = vars(parser.parse_args())
    root = args.pop("root")
    print(json.dumps(check(root, **args)))


if __name__ == "__main__":
    main()
