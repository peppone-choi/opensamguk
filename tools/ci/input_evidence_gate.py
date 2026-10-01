#!/usr/bin/env python3
"""Gate delivery-state promotions against pinned v3 states and typed v5 evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = Path("data/commands/input-catalog.json")
BASELINE = Path("data/commands/input-delivery-baseline-v3.json")
DEBT = Path("data/commands/input-evidence-debt-v1.json")
FIRST_STEPS_EXCLUSIONS = Path("data/help/first-steps-exclusions-v1.json")
BASELINE_SHA256 = "08ee487f9b1b0072d824a0818338e70c42da2ab86c4ba9d41756068c72bd7d24"
STAGES = (
    "PLANNED", "DOMAIN_READY", "HANDLER_READY", "UI_READY", "AI_READY",
    "HELP_READY", "TUTORIAL_READY", "REPLAY_READY", "VERIFIED",
)
PROOF_ROLES = {
    "DOMAIN_READY": {"domain-rule"},
    "HANDLER_READY": {"handler-test"},
    "UI_READY": {"ui-e2e"},
    "AI_READY": {"ai-selector", "ai-test"},
    "HELP_READY": {"help-topic"},
    "TUTORIAL_READY": {"tutorial-step", "tutorial-shortcut", "tutorial-na"},
    "REPLAY_READY": {"replay-test"},
    "VERIFIED": {"campaign-test"},
}
ROLE_PREFIX = {
    "domain-rule": ("logic/src/main/", "app/game-engine/src/main/"),
    "handler-test": ("logic/src/test/", "app/game-engine/src/test/", "app/game-api/src/test/"),
    "ui-e2e": ("web/game/e2e/",),
    "ai-selector": ("app/game-engine/src/main/",),
    "ai-test": ("app/game-engine/src/test/",),
    "help-topic": ("data/help/",),
    "tutorial-step": ("data/help/",),
    "tutorial-shortcut": ("web/game/e2e/",),
    "replay-test": ("logic/src/test/", "app/game-engine/src/test/"),
    "campaign-test": ("app/game-engine/src/test/",),
}
FIRST_STEPS = {
    "tutorial.signup", "tutorial.createGeneral", "tutorial.enlist", "tutorial.dispatch",
    "tutorial.work", "tutorial.employ", "tutorial.march", "tutorial.battle",
}

# 서버의 각 canonical parser/controller와 대조한 전달 계약이다. 미등록 입력은 추정하지 않는다.
UI_REQUEST_CONTRACTS = {
    "court.reward": {"paths": ["/api/game/api/commands/court/reward"],
                     "body": {"retainerId": "positive-int", "money": "bounded-positive:1000000000"}},
    "court.dispatchReply": {"paths": ["/api/game/api/commands/court/dispatchReply"],
                            "body": {"dispatchId": "string", "accept": "boolean"}},
    "work.start": {"paths": ["/api/game/api/commands/work/start"],
                   "body": {"countyId": "positive-int", "work": "enum:IRRIGATION,MILITARY_FARM,FORTIFICATION,ROAD,POST_STATION,WAREHOUSE,WATCHTOWER_BEACON,BARRACKS,MARKET_WATERWAY"}},
    "action.enlist": {"paths": ["/api/game/api/command/action.enlist"],
                      "body": {"mode": "enum:GENERAL,NATION", "targetId": "positive-int"}},
    "action.deploy": {"paths": ["/api/game/api/command/action.deploy"],
                      "body": {"bugokIds": "positive-ints", "destinationProvinceId": "string"}},
    "action.move": {"paths": ["/api/game/api/command/action.move"],
                    "body": {"destinationProvinceId": "string"}},
    "action.search": {"paths": ["/api/game/api/command/action.search"], "body": {}},
    "action.employ": {"paths": ["/api/game/api/command/action.employ"],
                      "body": {"targetGeneralId": "positive-int"}},
    "action.farm": {"paths": ["/api/game/api/command/action.farm"], "body": {}},
}


def _ui_source_proof(input_id: str, path_text: str, anchor: str, root: Path) -> dict:
    if anchor != input_id:
        raise ValueError(f"UI evidence anchor must be exact input ID: {input_id}")
    contract = UI_REQUEST_CONTRACTS.get(input_id)
    if contract is None:
        raise ValueError(f"UNSUPPORTED_UI_PROOF: no confirmed request binding: {input_id}")
    path = root / path_text
    if not path.resolve().is_relative_to(root.resolve()):
        raise ValueError(f"unsafe UI evidence symlink: {input_id}")
    parity = root / "web/game/e2e/support/parity.ts"
    if parity.exists() and not parity.resolve().is_relative_to(root.resolve()):
        raise ValueError(f"unsafe UI helper symlink: {input_id}")
    payload = {
        "source": path.read_text(encoding="utf-8"), "path": path_text,
        "inputId": input_id, "contract": contract,
        "paritySource": parity.read_text(encoding="utf-8") if parity.is_file() else None,
    }
    # 검증 root는 임시 fixture일 수 있어도 실행할 parser는 검토한 도구 경로로 고정한다.
    proof = _run_ui_parser(payload)
    if (proof.get("state") != "STATIC_PROOF_VALID" or proof.get("inputId") != input_id or
        proof.get("path") != path_text or not proof.get("cases") or proof.get("uiRuntimeExecuted") is not False):
        raise ValueError(f"UI parser protocol failed: {input_id}")
    helpers = {}
    pending = [(path, item) for item in proof.get("imports", [])]
    while pending:
        parent, module = pending.pop()
        if not isinstance(module, str) or not module.startswith("."):
            raise ValueError("UI helper import protocol mismatch")
        target = parent.parent / module
        candidates = [target, *(Path(str(target) + suffix) for suffix in (".ts", ".tsx", ".js", ".mjs", ".json")), target / "index.ts"]
        target = next((item for item in candidates if item.is_file()), None)
        if target is None or target.suffix not in (".ts", ".tsx", ".js", ".mjs", ".json"):
            raise ValueError("UI helper source missing or unsupported")
        if any(part.startswith(".env") for part in target.resolve().parts):
            raise ValueError("unsafe UI helper secret path")
        try:
            relative = str(target.resolve().relative_to(root.resolve()))
        except ValueError as error:
            raise ValueError("unsafe UI helper import") from error
        if relative == path_text or relative in helpers:
            continue
        content = target.read_text(encoding="utf-8")
        helpers[relative] = hashlib.sha256(content.encode()).hexdigest()
        if target.suffix != ".json":
            inspected = _run_ui_parser({"mode": "imports", "source": content, "path": relative})
            pending.extend((target, item) for item in inspected.get("imports", []))
    proof["helperSources"] = helpers
    if "web/game/e2e/support/parity.ts" not in helpers:
        proof["paritySha256"] = None
    return proof


def _run_ui_parser(payload: dict) -> dict:
    parser = Path(__file__).resolve().with_name("ui_input_proof.mjs")
    try:
        completed = subprocess.run(["node", str(parser)], input=json.dumps(payload), text=True,
                                   capture_output=True, timeout=30, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ValueError("UI parser unavailable") from error
    if completed.returncode != 0:
        # Node 의존성 부재도 실패이며 문자열 검사나 skip으로 대체하지 않는다.
        raise ValueError(f"UI source proof failed: {completed.stderr.strip()}")
    try:
        proof = json.loads(completed.stdout, object_pairs_hook=_unique_pairs)
    except (ValueError, TypeError) as error:
        raise ValueError("UI parser protocol failed") from error
    return proof


def _unique_pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_unique_pairs)


def _confirmed_exclusion(row: dict, root: Path) -> None:
    ledger_path = root / FIRST_STEPS_EXCLUSIONS
    if not ledger_path.is_file():
        raise ValueError("first-steps exclusion ledger missing")
    document = _load(ledger_path)
    if document.get("schemaVersion") != 1 or not isinstance(document.get("entries"), list):
        raise ValueError("invalid first-steps exclusion ledger")
    entries = [item for item in document["entries"] if item.get("inputId") == row["inputId"]]
    if len(entries) != 1 or entries[0].get("status") != "CONFIRMED" or entries[0].get("reason") != row["firstStepsExplanationNaReason"]:
        raise ValueError(f"first-steps N/A is not confirmed: {row['inputId']}")
    if row["firstStepsExplanationNaReason"] == "INPUT_PLANNED" and row["deliveryState"] != "PLANNED":
        raise ValueError(f"first-steps INPUT_PLANNED requires PLANNED deliveryState: {row['inputId']}")
    source = entries[0].get("source")
    if not isinstance(source, str) or "#" not in source:
        raise ValueError(f"first-steps N/A needs source: {row['inputId']}")
    path_text, anchor = source.split("#", 1)
    relative = Path(path_text)
    if (not anchor.strip() or relative.is_absolute() or ".." in relative.parts or
        not path_text.startswith("docs/development/")):
        raise ValueError(f"unsafe first-steps N/A source: {row['inputId']}")
    path = root / relative
    if not path.is_file():
        raise ValueError(f"first-steps N/A source missing input and anchor: {row['inputId']}")
    content = path.read_text(encoding="utf-8")
    marker = f'<a id="{anchor}"></a>'
    section = content.split(marker, 1)[1].split('<a id="first-steps-exclusion-', 1)[0] if marker in content else ""
    heading = section.lstrip().splitlines()[0] if section.strip() else ""
    if not heading.startswith("### ") or not heading.endswith(f"(`{row['inputId']}`)"):
        raise ValueError(f"first-steps N/A source missing input and anchor: {row['inputId']}")


def _proof(row: dict, stage: str, reference: str, root: Path) -> str:
    if not isinstance(reference, str) or ":" not in reference:
        raise ValueError(f"invalid evidence reference: {row['inputId']}/{stage}")
    role, target = reference.split(":", 1)
    if role not in PROOF_ROLES[stage]:
        raise ValueError(f"wrong evidence role: {row['inputId']}/{stage}/{role}")
    if role == "tutorial-na":
        if (stage != "TUTORIAL_READY" or row["firstStepsExplanationStepId"] != "N/A" or
            reference != f"tutorial-na:{row['firstStepsExplanationNaReason']}"):
            raise ValueError(f"wrong first-steps N/A evidence: {row['inputId']}")
        _confirmed_exclusion(row, root)
        return role
    if "#" not in target:
        raise ValueError(f"evidence needs path and anchor: {row['inputId']}/{stage}")
    path_text, anchor = target.split("#", 1)
    relative = Path(path_text)
    if not anchor.strip() or relative.is_absolute() or ".." in relative.parts:
        raise ValueError(f"unsafe evidence path or anchor: {row['inputId']}/{stage}")
    if not path_text.startswith(ROLE_PREFIX[role]):
        raise ValueError(f"evidence path does not match role: {row['inputId']}/{stage}/{role}")
    path = root / relative
    if not path.is_file():
        raise ValueError(f"evidence file missing: {path_text}")
    content = path.read_text(encoding="utf-8")
    if role == "help-topic":
        if row["helpTopicId"] != anchor:
            raise ValueError(f"help topic ID mismatch: {row['inputId']}")
        help_doc = _load(path)
        topic = next((item for item in help_doc.get("topics", []) if item.get("id") == anchor), None)
        if topic is None or topic.get("reviewState") != "APPROVED":
            raise ValueError(f"help prose is not approved: {row['inputId']}")
    elif role == "tutorial-step":
        if anchor != row["firstStepsExplanationStepId"] or path_text != "data/help/topics.json":
            raise ValueError(f"first-steps article mismatch: {row['inputId']}")
        topic = next((item for item in _load(path).get("topics", []) if item.get("id") == anchor), None)
        if topic is None or topic.get("reviewState") != "APPROVED":
            raise ValueError(f"first-steps prose is not approved: {row['inputId']}")
    elif role == "tutorial-shortcut":
        if anchor != row["firstStepsExplanationStepId"] or anchor not in content or row["inputId"] not in content:
            raise ValueError(f"first-steps shortcut mismatch: {row['inputId']}")
    elif role == "ui-e2e":
        _ui_source_proof(row["inputId"], path_text, anchor, root)
    elif anchor not in content or row["inputId"] not in content:
        raise ValueError(f"evidence anchor or input ID missing: {row['inputId']}/{stage}")
    return role


def validate(catalog: dict, baseline: dict, root: Path) -> list[dict[str, str]]:
    if catalog.get("schemaVersion") != 5 or baseline.get("schemaVersion") != 1:
        raise ValueError("wrong input catalog or baseline schemaVersion")
    baseline_rows = baseline.get("entries")
    if not isinstance(baseline_rows, list) or len(baseline_rows) != 74:
        raise ValueError("pinned v3 baseline must have 74 rows")
    frozen = {row["inputId"]: row["deliveryState"] for row in baseline_rows}
    if len(frozen) != len(baseline_rows) or any(state not in STAGES for state in frozen.values()):
        raise ValueError("invalid or duplicate baseline row")
    if Counter(frozen.values()) != {"PLANNED": 29, "HANDLER_READY": 13, "UI_READY": 32}:
        raise ValueError("v3 baseline state counts changed")
    rows = catalog.get("inputs")
    if not isinstance(rows, list):
        raise ValueError("inputs must be a list")
    ids = [row["inputId"] for row in rows]
    if len(ids) != len(set(ids)) or not set(frozen).issubset(ids):
        raise ValueError("duplicate input or pinned input removed")
    debt = []
    for row in rows:
        input_id = row["inputId"]
        declared = row["deliveryState"]
        if declared not in STAGES:
            raise ValueError(f"unknown deliveryState: {input_id}")
        if "tutorialObjectiveId" in row or "tutorialNaReason" in row:
            raise ValueError(f"retired progress fields in v5: {input_id}")
        step = row.get("firstStepsExplanationStepId")
        reason = row.get("firstStepsExplanationNaReason")
        if step not in FIRST_STEPS | {"UNMAPPED", "N/A"}:
            raise ValueError(f"unknown first-steps explanation step: {input_id}")
        if (step == "N/A") != (isinstance(reason, str) and bool(reason.strip())):
            raise ValueError(f"firstStepsExplanationNaReason must match N/A: {input_id}")
        if reason == "E9_PENDING_U3":
            raise ValueError(f"retired tutorial pending reason: {input_id}")
        if step == "N/A":
            _confirmed_exclusion(row, root)
        evidence = row.get("evidence")
        if not isinstance(evidence, dict) or any(stage not in PROOF_ROLES for stage in evidence):
            raise ValueError(f"invalid evidence map: {input_id}")
        validated_roles = {}
        for stage, refs in evidence.items():
            if not isinstance(refs, list) or not refs or len(refs) != len(set(map(str, refs))):
                raise ValueError(f"blank or duplicate evidence: {input_id}/{stage}")
            roles = {_proof(row, stage, ref, root) for ref in refs}
            if stage == "AI_READY" and roles != PROOF_ROLES[stage]:
                raise ValueError(f"AI evidence needs selector and test: {input_id}")
            if stage == "TUTORIAL_READY":
                required = {"tutorial-na"} if step == "N/A" else {"tutorial-step", "tutorial-shortcut"}
                if step == "UNMAPPED" or roles != required:
                    raise ValueError(f"first-steps explanation and shortcut evidence required: {input_id}")
            validated_roles[stage] = roles
        baseline_state = frozen.get(input_id, "PLANNED")
        baseline_index = STAGES.index(baseline_state)
        if STAGES.index(declared) < baseline_index:
            raise ValueError(f"v3 state demoted without baseline migration: {input_id}")
        proven = baseline_index
        for index in range(baseline_index + 1, len(STAGES)):
            stage = STAGES[index]
            if stage not in validated_roles:
                break
            proven = index
        if any(STAGES.index(stage) > proven for stage in evidence):
            raise ValueError(f"non-contiguous evidence stages: {input_id}")
        if STAGES.index(declared) != proven:
            raise ValueError(f"declared state differs from evidence: {input_id}: {declared} != {STAGES[proven]}")
        if input_id in frozen and baseline_index > 0 and any(
            not evidence.get(stage) for stage in STAGES[1:baseline_index + 1]
        ):
            debt.append({"inputId": input_id, "frozenState": baseline_state})
    return sorted(debt, key=lambda item: item["inputId"])


def check(root: Path = ROOT) -> list[dict[str, str]]:
    baseline_path = root / BASELINE
    digest = hashlib.sha256(baseline_path.read_bytes()).hexdigest()
    if digest != BASELINE_SHA256:
        raise ValueError(f"v3 baseline hash changed: {digest}")
    debt = validate(_load(root / CATALOG), _load(baseline_path), root)
    debt_doc = _load(root / DEBT)
    if debt_doc != {"schemaVersion": 1, "count": len(debt), "entries": debt}:
        raise ValueError("evidence debt report is stale")
    return debt


def validate_ui_runtime(proofs: list[dict], phase: dict, report: dict, head: str,
                        root: Path, candidate_sources: dict[str, str]) -> dict:
    """이미 고정한 후보의 실제 report만 검사한다. 정적 합격은 실행 성공으로 바꾸지 않는다."""
    if not isinstance(phase, dict) or not isinstance(report, dict) or not isinstance(proofs, list):
        raise ValueError("UI runtime document shape mismatch")
    if not isinstance(head, str) or re.fullmatch(r"[0-9a-f]{40}", head) is None:
        raise ValueError("UI runtime needs exact candidate head")
    if (phase.get("schema") != "web-e2e-phase-v1" or phase.get("app") != "game" or
        phase.get("phase") != "smoke" or phase.get("headSha") != head or
        phase.get("recordState") != "FINISHED" or phase.get("testState") != "PLAYWRIGHT_FINISHED" or
        phase.get("playwrightInvoked") is not True or phase.get("workflowStepOutcome") != "success" or
        not phase.get("finishedAt") or not phase.get("startedAt") or
        any(type(phase.get(key)) is not int or phase[key] != 0 for key in ("exitCode", "playwrightExitCode")) or
        any(not isinstance(phase.get(key), str) or not phase[key].isdigit() for key in ("runId", "runAttempt")) or
        not isinstance(phase.get("workflowSha"), str) or re.fullmatch(r"[0-9a-f]{40}", phase["workflowSha"]) is None):
        raise ValueError("UI runtime phase is missing, unfinished, or bound to another head")
    test_root = root / "web/game/e2e"
    if not isinstance(report.get("config"), dict) or not isinstance(report.get("stats"), dict):
        raise ValueError("UI runtime config/stats missing")
    if report.get("config", {}).get("rootDir") != str(test_root):
        raise ValueError("UI runtime report source root mismatch")
    stats = report.get("stats", {})
    if (any(type(stats.get(key)) is not int or stats[key] != 0 for key in ("skipped", "unexpected", "flaky")) or
        type(stats.get("expected")) is not int or stats["expected"] <= 0 or report.get("errors") != []):
        raise ValueError("UI runtime report has missing, skipped, failed, or flaky results")
    if not proofs:
        return {"state": "NO_UI_PROOFS", "headSha": head, "uiCasesPassed": 0,
                "runId": phase["runId"], "runAttempt": phase["runAttempt"], "cases": []}
    expected = {}
    for proof in proofs:
        path = proof["path"]
        if candidate_sources.get(path) != proof["sourceSha256"]:
            raise ValueError(f"UI source does not match candidate blob: {path}")
        if proof.get("paritySha256") is not None and candidate_sources.get("web/game/e2e/support/parity.ts") != proof["paritySha256"]:
            raise ValueError("UI helper does not match candidate blob")
        if any(candidate_sources.get(path) != digest for path, digest in proof.get("helperSources", {}).items()):
            raise ValueError("UI helper closure does not match candidate blobs")
        for case in proof["cases"]:
            for project in ("desktop", "mobile"):
                key = (path, case["title"], project)
                if key in expected:
                    raise ValueError("UI runtime duplicate proof case")
                expected[key] = proof["inputId"]
    observed = {}

    def visit(suites: list) -> None:
        if not isinstance(suites, list):
            raise ValueError("UI runtime invalid suites")
        for suite in suites:
            if not isinstance(suite, dict) or not isinstance(suite.get("specs", []), list):
                raise ValueError("UI runtime invalid suite")
            for spec in suite.get("specs", []):
                if not isinstance(spec, dict) or not isinstance(spec.get("tests", []), list):
                    raise ValueError("UI runtime invalid spec")
                file = spec.get("file")
                if not isinstance(file, str):
                    raise ValueError("UI runtime missing spec path")
                path = Path(file)
                path = path if path.is_absolute() else test_root / path
                try:
                    relative = str(path.resolve().relative_to(root.resolve()))
                except ValueError as error:
                    raise ValueError("UI runtime unsafe spec path") from error
                for test in spec.get("tests", []):
                    if not isinstance(test, dict):
                        raise ValueError("UI runtime invalid test")
                    key = (relative, spec.get("title"), test.get("projectName"))
                    if key not in expected:
                        continue
                    if key in observed:
                        raise ValueError("UI runtime duplicate executed case")
                    results = test.get("results")
                    if (spec.get("ok") is not True or test.get("expectedStatus") != "passed" or
                        test.get("status") != "expected" or not isinstance(results, list) or len(results) != 1 or
                        any(item.get("type") in ("skip", "fixme", "fail") for item in test.get("annotations", []))):
                        raise ValueError("UI runtime case was not one successful execution")
                    result = results[0]
                    if not isinstance(result, dict):
                        raise ValueError("UI runtime invalid result")
                    if (result.get("status") != "passed" or result.get("errors") != [] or result.get("retry") != 0 or
                        type(result.get("workerIndex")) is not int or result["workerIndex"] < 0 or
                        not isinstance(result.get("startTime"), str) or not result["startTime"] or
                        type(result.get("duration")) not in (int, float) or result["duration"] < 0):
                        raise ValueError("UI runtime case skipped, retried, failed, or not executed")
                    observed[key] = expected[key]
            visit(suite.get("suites", []))

    visit(report.get("suites"))
    if observed.keys() != expected.keys():
        raise ValueError("UI runtime missing desktop/mobile selected cases")
    return {"state": "UI_CASES_PASSED", "headSha": head, "runId": phase["runId"],
            "runAttempt": phase["runAttempt"], "workflowSha": phase["workflowSha"],
            "uiCasesPassed": len(observed), "skip": 0, "sources": candidate_sources,
            "cases": [{"path": path, "title": title, "project": project, "inputId": observed[(path, title, project)]}
                      for path, title, project in sorted(observed)]}


class RuntimeProofError(ValueError):
    def __init__(self, code: str, status: str = "FAILED"):
        super().__init__(code)
        self.code = code
        self.status = status


def _git(root: Path, *arguments: str) -> bytes:
    try:
        completed = subprocess.run(["git", *arguments], cwd=root, capture_output=True, timeout=30, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RuntimeProofError("GIT_OBJECT_UNAVAILABLE", "UNAVAILABLE") from error
    if completed.returncode != 0:
        raise RuntimeProofError("GIT_OBJECT_UNAVAILABLE", "UNAVAILABLE")
    return completed.stdout


def ui_candidate_identity(event: dict, context: dict[str, str], root: Path) -> dict:
    """runner Git과 event 원본을 대조한다. merge checkout을 PR head라고 기록하지 않는다."""
    names = ("GITHUB_WORKFLOW", "GITHUB_WORKFLOW_REF", "GITHUB_WORKFLOW_SHA", "GITHUB_RUN_ID",
             "GITHUB_RUN_ATTEMPT", "GITHUB_EVENT_NAME", "GITHUB_REPOSITORY", "GITHUB_SHA")
    if any(not isinstance(context.get(key), str) or not context[key] for key in names):
        raise RuntimeProofError("CI_CONTEXT_MISSING", "UNAVAILABLE")
    repository, kind = context["GITHUB_REPOSITORY"], context["GITHUB_EVENT_NAME"]
    if (event.get("repository", {}).get("full_name") != repository or
        not context["GITHUB_WORKFLOW_REF"].startswith(f"{repository}/.github/workflows/ci.yml@") or
        any(not context[key].isdigit() for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"))):
        raise RuntimeProofError("CI_PRODUCER_MISMATCH")
    sha = lambda value: isinstance(value, str) and re.fullmatch(r"[0-9a-f]{40}", value) is not None
    checkout = _git(root, "rev-parse", "HEAD").decode().strip()
    if checkout != context["GITHUB_SHA"] or not sha(context["GITHUB_WORKFLOW_SHA"]):
        raise RuntimeProofError("CHECKOUT_SHA_MISMATCH")
    if kind == "pull_request":
        pull = event.get("pull_request", {})
        candidate, base = pull.get("head", {}).get("sha"), pull.get("base", {}).get("sha")
        if (pull.get("head", {}).get("repo", {}).get("full_name") != repository or
            pull.get("base", {}).get("repo", {}).get("full_name") != repository):
            raise RuntimeProofError("PR_REPOSITORY_MISMATCH")
    elif kind == "push":
        candidate, base = event.get("after"), None
    elif kind == "schedule":
        candidate, base = context["GITHUB_SHA"], None
    else:
        raise RuntimeProofError("UNSUPPORTED_CI_EVENT", "UNAVAILABLE")
    if not sha(candidate) or (base is not None and not sha(base)):
        raise RuntimeProofError("CANDIDATE_EVENT_SHA_MISSING", "UNAVAILABLE")
    header = _git(root, "cat-file", "-p", checkout).decode().split("\n\n", 1)[0]
    parents = [line[7:] for line in header.splitlines() if line.startswith("parent ")]
    for commit in {candidate, checkout, *parents, *([base] if base is not None else [])}:
        if _git(root, "cat-file", "-t", commit).strip() != b"commit":
            raise RuntimeProofError("GIT_COMMIT_OBJECT_INVALID")
    if kind == "pull_request" and parents != [base, candidate]:
        raise RuntimeProofError("MERGE_BASE_PARENT_MISMATCH")
    if kind != "pull_request" and checkout != candidate:
        raise RuntimeProofError("PUSH_CANDIDATE_CHECKOUT_MISMATCH")
    return {"producer": {"workflow": context["GITHUB_WORKFLOW"], "workflowRef": context["GITHUB_WORKFLOW_REF"],
                          "workflowSha": context["GITHUB_WORKFLOW_SHA"], "runId": context["GITHUB_RUN_ID"],
                          "runAttempt": context["GITHUB_RUN_ATTEMPT"], "event": kind, "repository": repository},
            "candidateSha": candidate, "actualCheckoutSha": checkout, "baseSha": base, "checkoutParents": parents}


def ui_source_pins(paths: set[str], identity: dict, root: Path) -> list[dict]:
    pins = []
    for path in sorted(paths):
        relative = Path(path)
        if relative.is_absolute() or ".." in relative.parts:
            raise RuntimeProofError("SOURCE_PATH_UNSAFE")
        working = root / relative
        if not working.resolve().is_relative_to(root.resolve()):
            raise RuntimeProofError("SOURCE_PATH_UNSAFE")
        candidate = hashlib.sha256(_git(root, "show", f"{identity['candidateSha']}:{path}")).hexdigest()
        checkout = hashlib.sha256(_git(root, "show", f"{identity['actualCheckoutSha']}:{path}")).hexdigest()
        actual = hashlib.sha256(working.read_bytes()).hexdigest()
        if candidate != checkout or checkout != actual:
            raise RuntimeProofError("SOURCE_PIN_MISMATCH")
        pins.append({"path": path, "candidateBlobSha256": candidate,
                     "checkoutBlobSha256": checkout, "workingSha256": actual})
    return pins


def _selected_ui_sources(root: Path) -> tuple[list[dict], set[str]]:
    """시작 producer와 완료 consumer의 단일 선택 정본."""
    check(root)
    proofs = []
    paths = {str(CATALOG), "tools/ci/input_evidence_gate.py", "tools/ci/ui_input_proof.mjs",
             "tools/ci/package.json", "tools/ci/package-lock.json"}
    for row in _load(root / CATALOG)["inputs"]:
        for reference in row.get("evidence", {}).get("UI_READY", []):
            role, target = reference.split(":", 1)
            if role != "ui-e2e":
                raise RuntimeProofError("UI_PROOF_ROLE_MISMATCH")
            path, anchor = target.split("#", 1)
            proof = _ui_source_proof(row["inputId"], path, anchor, root)
            proofs.append(proof)
            paths.add(path)
            paths.update(proof.get("helperSources", {}))
    for proof in proofs:
        proof["cases"].sort(key=lambda case: case["title"])
        proof["helperSources"] = dict(sorted(proof.get("helperSources", {}).items()))
    proofs.sort(key=lambda proof: (proof["inputId"], proof["path"]))
    return proofs, paths


def _ci_context(context: dict[str, str] | None) -> dict[str, str]:
    names = ("GITHUB_WORKFLOW", "GITHUB_WORKFLOW_REF", "GITHUB_WORKFLOW_SHA", "GITHUB_RUN_ID",
             "GITHUB_RUN_ATTEMPT", "GITHUB_EVENT_NAME", "GITHUB_REPOSITORY", "GITHUB_SHA", "GITHUB_EVENT_PATH")
    return context if context is not None else {key: os.environ.get(key, "") for key in names}


def _receipt() -> dict:
    return {"schemaVersion": 1, "producer": {}, "candidateSha": None, "actualCheckoutSha": None,
            "baseSha": None, "checkoutParents": [], "sourcePins": [], "proofs": [],
            "status": "UNAVAILABLE", "reasons": []}


def _original_identity(event_path: Path, context: dict[str, str], root: Path) -> dict:
    if not context.get("GITHUB_EVENT_PATH") or Path(context["GITHUB_EVENT_PATH"]).resolve() != event_path.resolve():
        raise RuntimeProofError("CI_EVENT_ORIGINAL_PATH_MISMATCH")
    return ui_candidate_identity(_load(event_path), context, root)


def record_ui_start(event_path: Path, root: Path = ROOT,
                    context: dict[str, str] | None = None) -> tuple[dict, int]:
    receipt = _receipt()
    receipt.pop("proofs")
    receipt["selectedProofs"] = []
    receipt["generatedAt"] = datetime.now(timezone.utc).isoformat()
    receipt["kind"] = "ui-input-start"
    try:
        identity = _original_identity(event_path, _ci_context(context), root)
        receipt.update(identity)
        proofs, paths = _selected_ui_sources(root)
        receipt["sourcePins"] = ui_source_pins(paths, identity, root)
        receipt["selectedProofs"] = proofs
        receipt["status"] = "STATIC_PROOF_VALID"
        receipt["generatedAt"] = datetime.now(timezone.utc).isoformat()
        return receipt, 0
    except RuntimeProofError as error:
        receipt.update(status=error.status, reasons=[error.code])
    except FileNotFoundError:
        receipt.update(status="UNAVAILABLE", reasons=["UI_ARTIFACT_OR_SOURCE_MISSING"])
    except (KeyError, TypeError, ValueError, OSError, AttributeError):
        receipt.update(status="FAILED", reasons=["UI_START_RECORD_REJECTED"])
    return receipt, 1


def validate_ui_start(start: dict, identity: dict, pins: list[dict], proofs: list[dict]) -> None:
    if not isinstance(start, dict):
        raise RuntimeProofError("UI_START_RECORD_MISSING", "UNAVAILABLE")
    if (start.get("schemaVersion") != 1 or start.get("kind") != "ui-input-start" or
        start.get("status") != "STATIC_PROOF_VALID" or start.get("reasons") != []):
        raise RuntimeProofError("UI_START_RECORD_INVALID")
    if any(start.get(key) != identity[key] for key in identity):
        raise RuntimeProofError("UI_START_IDENTITY_MISMATCH")
    try:
        generated = datetime.fromisoformat(start["generatedAt"])
        if generated.tzinfo is None:
            raise ValueError("timezone missing")
    except (KeyError, TypeError, ValueError) as error:
        raise RuntimeProofError("UI_START_TIME_INVALID") from error
    if start.get("sourcePins") != pins or start.get("selectedProofs") != proofs:
        raise RuntimeProofError("UI_START_SOURCE_OR_SELECTION_MISMATCH")


def check_ui_runtime(phase_path: Path, report_path: Path, event_path: Path,
                     root: Path = ROOT, context: dict[str, str] | None = None) -> tuple[dict, int]:
    # 환경 전체를 읽거나 출력하지 않는다. CI 생산자에 필요한 공개 metadata만 선택한다.
    context = _ci_context(context)
    receipt = _receipt()
    try:
        identity = _original_identity(event_path, context, root)
        receipt.update(identity)
        phase, report = _load(phase_path), _load(report_path)
        producer = identity["producer"]
        if any(phase.get(key) != producer[value] for key, value in
               (("runId", "runId"), ("runAttempt", "runAttempt"), ("workflowSha", "workflowSha"),
                ("workflow", "workflow"), ("event", "event"), ("repository", "repository"))):
            raise RuntimeProofError("PHASE_PRODUCER_MISMATCH")
        proofs, paths = _selected_ui_sources(root)
        pins = ui_source_pins(paths, identity, root)
        receipt["sourcePins"] = pins
        validate_ui_start(phase.get("uiInputStart"), identity, pins, proofs)
        try:
            times = [datetime.fromisoformat(value) for value in (
                phase["uiInputStart"]["generatedAt"], phase["startedAt"], report["stats"]["startTime"], phase["finishedAt"])]
            generated, phase_started, playwright_started, phase_finished = times
            # 기존 shell startedAt은 초 단위다. 그 필드만 같은 측정 정밀도로 비교한다.
            phase_bound = generated.replace(microsecond=0) if phase_started.microsecond == 0 else generated
            if (any(value.tzinfo is None for value in times) or phase_bound > phase_started or
                max(generated, phase_started) > playwright_started or playwright_started > phase_finished):
                raise ValueError("start/phase/report time mismatch")
        except (KeyError, TypeError, ValueError) as error:
            raise RuntimeProofError("UI_START_PHASE_TIME_MISMATCH") from error
        sources = {pin["path"]: pin["candidateBlobSha256"] for pin in pins}
        verified = validate_ui_runtime(proofs, phase, report, identity["candidateSha"], root, sources)
        receipt["status"] = "NO_UI_PROOFS" if verified["state"] == "NO_UI_PROOFS" else "UI_RUNTIME_VERIFIED"
        receipt["proofs"] = verified["cases"]
        for proof in receipt["proofs"]:
            proof.update({"status": "passed", "skip": 0, "retry": 0, "attempt": producer["runAttempt"]})
        return receipt, 0
    except RuntimeProofError as error:
        receipt.update(status=error.status, reasons=[error.code])
    except FileNotFoundError:
        receipt.update(status="UNAVAILABLE", reasons=["UI_ARTIFACT_OR_SOURCE_MISSING"])
    except (KeyError, TypeError, ValueError, OSError, AttributeError):
        receipt.update(status="FAILED", reasons=["UI_PROOF_OR_RUNTIME_REJECTED"])
    return receipt, 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ui-runtime", action="store_true")
    parser.add_argument("--ui-start", action="store_true")
    parser.add_argument("--phase", type=Path)
    parser.add_argument("--results", type=Path)
    parser.add_argument("--github-event", type=Path)
    parser.add_argument("--receipt", type=Path)
    arguments = parser.parse_args()
    try:
        if arguments.ui_runtime and arguments.ui_start:
            raise ValueError("--ui-runtime and --ui-start are distinct stages")
        if arguments.ui_start:
            if not arguments.github_event or not arguments.receipt or arguments.phase or arguments.results:
                raise ValueError("--ui-start requires only --github-event --receipt")
            receipt, exit_code = record_ui_start(arguments.github_event)
            arguments.receipt.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(f"input UI start: {receipt['status']}; selected proofs={len(receipt['selectedProofs'])}; UI runtime not checked")
            raise SystemExit(exit_code)
        elif arguments.ui_runtime:
            if not all((arguments.phase, arguments.results, arguments.github_event, arguments.receipt)):
                raise ValueError("--ui-runtime requires --phase --results --github-event --receipt")
            receipt, exit_code = check_ui_runtime(arguments.phase, arguments.results, arguments.github_event)
            arguments.receipt.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(f"input UI runtime: {receipt['status']}; selected executions={len(receipt['proofs'])}")
            raise SystemExit(exit_code)
        else:
            if any((arguments.phase, arguments.results, arguments.github_event, arguments.receipt)):
                raise ValueError("runtime arguments require --ui-runtime")
            debt_rows = check()
            print(f"input evidence static gate valid: v5 catalog; pre-v4 evidence debt={len(debt_rows)}; UI runtime not checked")
    except (KeyError, TypeError, ValueError, OSError) as error:
        raise SystemExit(f"input evidence gate failed: {error}") from error
