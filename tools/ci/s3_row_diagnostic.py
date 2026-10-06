#!/usr/bin/env python3
"""Compare fixed S3 sources in isolated test trees without repinning a golden."""
from __future__ import annotations

import argparse
from collections import Counter
import datetime
import difflib
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

SOURCES = {"old": "62d178a0657da0a8da2ffc5a4773779890b5dbf1",
           "candidate": "efc870cd50ca00f04c6389acdef3820cb4af6b9c"}
SELECTOR = ("opensamguk.engine.boot.PassChainInvarianceIT."
            "S3 고리 — 출사 발령 행군 조우 공성 점령 징세 월단평이 관리자 개입 없이 이어진다")
BASELINE = "app/game-engine/src/test/kotlin/opensamguk/engine/invariance/WorldStateBaseline.kt"
TEST = "app/game-engine/src/test/kotlin/opensamguk/engine/boot/PassChainInvarianceIT.kt"
SUPPORT = "app/game-engine/src/test/kotlin/opensamguk/engine/boot/PassChainSupport.kt"
FIXTURE = "tools/e2e/fixtures/yuzhou/scenario_990002.json"
GOLDEN = "app/game-engine/src/test/resources/invariance/world-state-sha256.txt"
FIXED_BLOBS = {BASELINE: "dcfb84dc5531f8b6427c5bb341ef709bbc4257fa",
               FIXTURE: "9e76a48208c9d2db4b7f1318d0b1942387d43a9a",
               GOLDEN: "bafa4ac31505eddcef488468159e17303619807e"}
CASE_SECONDS = 40 * 60  # Existing game-engine CI budget, independently per tree.
EXPECTED_HASH = "e5999fd9136a8276b3583ca411cfc62214d19b817bd1a22f8a0070b0470527a1"
REQUEST_SUBJECT = "C1 S3 원문 진단 요청"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_json(path: Path, value) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def git(root: Path, *args: str) -> bytes:
    return subprocess.check_output(["git", *args], cwd=root)


def replace_once(source: str, before: str, after: str) -> str:
    if source.count(before) != 1:
        raise ValueError("Fixed instrumentation anchor is not unique")
    return source.replace(before, after, 1)


def instrument(originals: dict[str, str], output: Path) -> dict[str, str]:
    # All mutations are in temporary test sources. The original row builder,
    # canonicalizer, metadata omissions, digest return and assertions stay intact.
    result = dict(originals)
    anchor = '        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }'
    dump = '''        System.getProperty("opensamguk.test.s3.rows")?.let { target ->
            java.nio.file.Files.write(java.nio.file.Path.of(target), bytes,
                java.nio.file.StandardOpenOption.CREATE_NEW)
        }
'''
    result[BASELINE] = replace_once(result[BASELINE], anchor, dump + anchor)
    set_seed = json.dumps(str(output / "seed-canonical-rows.txt"))
    seed_nations = json.dumps(str(output / "seed-nations6.jsonl"))
    seed_receipt = json.dumps(str(output / "seed-receipt.json"))
    set_final = json.dumps(str(output / "final-canonical-rows.txt"))
    set_gate = json.dumps(str(output / "gate-canonical-rows.txt"))
    completion = json.dumps(str(output / "48-completed.json"))
    anchor = "        PassChainSupport.run(service, measuredWorld = world)"
    before = f'''        System.setProperty("opensamguk.test.s3.rows", {set_seed})
        WorldStateBaseline.sha256(world)
        val diagnosticSeedRows = jdbc.queryForList(
            "SELECT id, name, meta::text AS meta FROM nation WHERE world_id=? ORDER BY id", WORLD)
        java.nio.file.Files.writeString(java.nio.file.Path.of({seed_nations}),
            diagnosticSeedRows.joinToString("\\n", postfix = "\\n") {{ row ->
                opensamguk.infra.persistence.MetaJson.encode(mapOf(
                    "id" to row["id"], "name" to row["name"], "meta" to row["meta"]))
            }})
        java.nio.file.Files.writeString(java.nio.file.Path.of({seed_receipt}),
            opensamguk.infra.persistence.MetaJson.encode(mapOf(
                "world" to WORLD, "start" to PassChainSupport.START.toString(),
                "phases" to PassChainSupport.PHASES, "year" to world.getState().currentYear,
                "month" to world.getState().currentMonth, "phase" to world.getState().currentPhase)))
        System.clearProperty("opensamguk.test.s3.rows")
'''
    after = f'''
        System.setProperty("opensamguk.test.s3.rows", {set_final})
        val diagnosticFinalHash = WorldStateBaseline.sha256(world)
        java.nio.file.Files.writeString(java.nio.file.Path.of({completion}),
            opensamguk.infra.persistence.MetaJson.encode(mapOf(
                "world" to WORLD, "start" to PassChainSupport.START.toString(),
                "completedPhases" to PassChainSupport.PHASES, "runReturned" to true,
                "year" to world.getState().currentYear, "month" to world.getState().currentMonth,
                "phase" to world.getState().currentPhase, "fullSHA256" to diagnosticFinalHash)))
        System.setProperty("opensamguk.test.s3.rows", {set_gate})'''
    result[TEST] = replace_once(result[TEST], anchor, before + anchor + after)
    # The original actual assertMatches invocation and the golden resource remain.
    if result[TEST].count('WorldStateBaseline.assertMatches("s3-chain-48", world)') != 1:
        raise ValueError("Original S3 assertion absent")
    return result


def original_sources(root: Path, ref: str) -> dict[str, str]:
    for path, expected in FIXED_BLOBS.items():
        if git(root, "rev-parse", ref + ":" + path).decode().strip() != expected:
            raise ValueError("Fixed original blob mismatch: " + path)
    support = git(root, "show", ref + ":" + SUPPORT).decode()
    if 'const val PHASES = 48' not in support or 'Instant.parse("0190-01-01T00:00:00Z")' not in support:
        raise ValueError("Original phase/start pin mismatch")
    golden = git(root, "show", ref + ":" + GOLDEN).decode()
    if not any(line.strip() == "s3-chain-48 " + EXPECTED_HASH for line in golden.splitlines()):
        raise ValueError("Original S3 expected hash mismatch")
    return {path: git(root, "show", ref + ":" + path).decode() for path in (BASELINE, TEST)}


def execute(root: Path, label: str, ref: str, output: Path, manifest: dict) -> dict:
    target = output / label
    target.mkdir()
    originals = original_sources(root, ref)
    patched = instrument(originals, target)
    info = {"sourceSha": ref, "world": 23, "start": "0190-01-01T00:00:00Z", "phases": 48,
            "expectedHashPreserved": EXPECTED_HASH, "fixtureBlob": FIXED_BLOBS[FIXTURE],
            "originals": {}, "temporaryPatches": {}, "status": "PREPARED"}
    for path in originals:
        original_path = target / "original-test-source" / path
        original_path.parent.mkdir(parents=True, exist_ok=True)
        original_path.write_text(originals[path])
        patched_path = target / "temporary-test-source" / path
        patched_path.parent.mkdir(parents=True, exist_ok=True)
        patched_path.write_text(patched[path])
        info["originals"][path] = sha(originals[path].encode())
        info["temporaryPatches"][path] = sha(patched[path].encode())
    write_json(target / "execution.json", info)
    with tempfile.TemporaryDirectory(prefix="s3-" + label + "-") as directory:
        tree = Path(directory)
        archive = target / "whole-tracked-tree.tar"
        with archive.open("wb") as stream:
            subprocess.run(["git", "archive", ref], cwd=root, stdout=stream, check=True)
        subprocess.run(["tar", "-xf", str(archive), "-C", str(tree)], check=True)
        archive.unlink()
        for path, source in patched.items():
            (tree / path).write_text(source)
        if sha((tree / FIXTURE).read_bytes()) != sha(git(root, "show", ref + ":" + FIXTURE)):
            raise ValueError("Temporary fixture changed")
        command = ["./gradlew", ":app:game-engine:test", "--tests", SELECTOR,
                   "--no-daemon", "--no-build-cache", "--no-configuration-cache",
                   "--max-workers=1", "--console=plain", "-Pkotlin.compiler.execution.strategy=in-process"]
        info["command"] = command
        info["startedAt"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        started = time.time()
        with (target / "gradle-original.log").open("wb") as log:
            child = subprocess.Popen(command, cwd=tree, stdout=log, stderr=subprocess.STDOUT)
            info["driverChildPid"] = child.pid
            try:
                info["exitCode"] = child.wait(timeout=CASE_SECONDS)
            except subprocess.TimeoutExpired:
                # This exact child was created above; no process-name kills.
                child.terminate()
                try:
                    child.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait()
                info["exitCode"] = child.returncode
                info["timedOut"] = True
        xml_dir = target / "xml"
        xml_dir.mkdir()
        info["xml"] = []
        for path in sorted((tree / "app/game-engine/build/test-results/test").glob("TEST-*.xml")):
            data = path.read_bytes()
            node = ET.fromstring(data)
            (xml_dir / path.name).write_bytes(data)
            info["xml"].append({"file": path.name, "sha256": sha(data), "mtime": path.stat().st_mtime,
                "fresh": path.stat().st_mtime >= started - 1, "attrs": node.attrib,
                "cases": [{"class": case.get("classname"), "name": case.get("name"),
                           "failures": [f.get("type") for f in case.findall("failure")],
                           "skipped": case.find("skipped") is not None} for case in node.findall("testcase")]})
    info["scratchRemoved"] = not tree.exists()
    info["finishedAt"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    info["status"] = "ORIGINAL_TEST_RESULT_RETAINED"
    write_json(target / "execution.json", info)
    manifest["executions"][label] = info
    write_json(output / "manifest.json", manifest)
    if info.get("timedOut"):
        raise ValueError("Timed-out original execution; no next tree is started")
    return info


def compare(output: Path, executions: dict) -> dict:
    for label, info in executions.items():
        xml = info.get("xml", [])
        if (info.get("timedOut") or len(xml) != 1 or not xml[0]["fresh"] or
                int(xml[0]["attrs"].get("tests", 0)) != 1 or int(xml[0]["attrs"].get("skipped", 0)) != 0 or
                int(xml[0]["attrs"].get("errors", 0)) != 0 or len(xml[0]["cases"]) != 1):
            raise ValueError("Missing/error/skipped/non-focused XML: " + label)
        completion = json.loads((output / label / "48-completed.json").read_text())
        if completion.get("completedPhases") != 48 or completion.get("runReturned") is not True or completion.get("world") != 23:
            raise ValueError("Actual 48-phase completion absent: " + label)
        seed = [json.loads(line) for line in (output / label / "seed-nations6.jsonl").read_text().splitlines()]
        if len(seed) != 6 or len({row["id"] for row in seed}) != 6:
            raise ValueError("Actual six seed nations absent: " + label)
        seed_receipt = json.loads((output / label / "seed-receipt.json").read_text())
        if (seed_receipt.get("world") != 23 or seed_receipt.get("start") != "0190-01-01T00:00:00Z" or
                seed_receipt.get("phases") != 48 or seed_receipt.get("year") != 190 or seed_receipt.get("month") != 1):
            raise ValueError("Actual seed start/calendar mismatch: " + label)
        case = xml[0]["cases"][0]
        method = SELECTOR.split(".")[-1]
        if case["class"] != SELECTOR.rsplit(".", 1)[0] or case["name"] not in (method, method + "()"):
            raise ValueError("Original focused S3 selector mismatch: " + label)
        final = (output / label / "final-canonical-rows.txt").read_bytes()
        gate = (output / label / "gate-canonical-rows.txt").read_bytes()
        if final != gate or sha(final) != completion["fullSHA256"]:
            raise ValueError("Actual final/gate bytes diverged: " + label)
        for name in ("seed-canonical-rows.txt", "final-canonical-rows.txt", "gate-canonical-rows.txt", "seed-nations6.jsonl"):
            path = output / label / name
            info.setdefault("retainedFiles", {})[name] = {"bytes": path.stat().st_size, "sha256": sha(path.read_bytes())}
        write_json(output / label / "execution.json", info)
    old = (output / "old/final-canonical-rows.txt").read_text().splitlines(keepends=True)
    candidate = (output / "candidate/final-canonical-rows.txt").read_text().splitlines(keepends=True)
    delta = "".join(difflib.unified_diff(old, candidate, fromfile=SOURCES["old"], tofile=SOURCES["candidate"]))
    (output / "canonical-rows.diff").write_text(delta)
    result = {"status": "ACTUAL_ROW_COMPARISON_RETAINED_PRODUCT_VERDICT_UNCHANGED",
              "sourcePins": SOURCES, "oldRows": len(old), "candidateRows": len(candidate),
              "fullSHA256": {label: sha((output / label / "final-canonical-rows.txt").read_bytes()) for label in SOURCES},
              "originalExitCodes": {label: info["exitCode"] for label, info in executions.items()},
              "goldenOrOmittedKeysChanged": False, "singleCauseConfirmed": False,
              "diffSha256": sha(delta.encode()), "actualOperations": 0}
    def kind(line: str) -> str:
        match = re.match(r"^\[S\d+:([^,]+),", line)
        return match.group(1) if match else "unclassified"
    result["rowKindCounts"] = {"old": dict(Counter(map(kind, old))),
                               "candidate": dict(Counter(map(kind, candidate)))}
    result["changedRowsByKind"] = {
        "oldOnly": dict(Counter(kind(line) for line in set(old) - set(candidate))),
        "candidateOnly": dict(Counter(kind(line) for line in set(candidate) - set(old))),
    }
    old_info = executions["old"]
    if (old_info["exitCode"] != 0 or int(old_info["xml"][0]["attrs"].get("failures", 0)) != 0 or
            result["fullSHA256"]["old"] != EXPECTED_HASH):
        result["status"] = "OLD_ORIGINAL_BASELINE_NOT_GREEN_CAUSE_UNCONFIRMED"
        write_json(output / "comparison.json", result)
        raise ValueError("Original old baseline reexecution is not green")
    candidate_info = executions["candidate"]
    failures = int(candidate_info["xml"][0]["attrs"].get("failures", 0))
    candidate_hash_matches = result["fullSHA256"]["candidate"] == EXPECTED_HASH
    result["candidateOriginalHashMatched"] = candidate_hash_matches
    if not ((candidate_hash_matches and candidate_info["exitCode"] == 0 and failures == 0) or
            (not candidate_hash_matches and candidate_info["exitCode"] != 0 and failures == 1)):
        result["status"] = "CANDIDATE_ORIGINAL_EXIT_NOT_EXPLAINED_BY_RETAINED_HASH"
        write_json(output / "comparison.json", result)
        raise ValueError("Candidate original assertion/exit is inconsistent with retained bytes")
    write_json(output / "comparison.json", result)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--inspect-only", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    manifest = {"status": "PREPARED", "sourcePins": SOURCES, "fixtureBlob": FIXED_BLOBS[FIXTURE],
                "producerHead": git(root, "rev-parse", "HEAD").decode().strip(), "executions": {},
                "workflow": {key: os.environ.get(key) for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_SHA", "GITHUB_WORKFLOW_SHA")}}
    write_json(output / "manifest.json", manifest)
    try:
        if args.inspect_only:
            for label, ref in SOURCES.items():
                originals = original_sources(root, ref)
                patched = instrument(originals, output / label)
                for path in originals:
                    target = output / label / path
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_text(patched[path])
                manifest[label] = {"originalSHA256": {p: sha(s.encode()) for p, s in originals.items()},
                                   "temporarySHA256": {p: sha(s.encode()) for p, s in patched.items()}}
            manifest["status"] = "INSPECTION_ONLY_RUNTIME_NOT_EXECUTED"
            return 0
        if os.environ.get("GITHUB_RUN_ATTEMPT") != "1" or os.environ.get("GITHUB_EVENT_NAME") != "pull_request":
            raise ValueError("Only the separately allocated normal PR attempt1 is allowed")
        manifest["dockerServerVersion"] = subprocess.check_output(
            ["docker", "info", "--format", "{{.ServerVersion}}"], text=True, timeout=30).strip()
        for label, ref in SOURCES.items():
            execute(root, label, ref, output, manifest)
        manifest["comparison"] = compare(output, manifest["executions"])
        # Diagnostic success certifies collection only. Original failure exits
        # and required game-engine checks are never rewritten into product PASS.
        manifest["status"] = "ACTUAL_TEST_ARTIFACTS_COLLECTED_PRODUCT_RESULT_PRESERVED"
        return 0
    except Exception as error:
        manifest["status"] = "DIAGNOSTIC_UNAVAILABLE_CAUSE_NOT_CONFIRMED"
        manifest["errorType"] = type(error).__name__
        manifest["error"] = str(error)
        return 1
    finally:
        write_json(output / "manifest.json", manifest)


if __name__ == "__main__":
    raise SystemExit(main())
