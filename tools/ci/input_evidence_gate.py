#!/usr/bin/env python3
"""Gate delivery-state promotions against pinned v3 states and typed v4 evidence."""

from __future__ import annotations

import hashlib
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = Path("data/commands/input-catalog.json")
BASELINE = Path("data/commands/input-delivery-baseline-v3.json")
DEBT = Path("data/commands/input-evidence-debt-v1.json")
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
    "TUTORIAL_READY": {"tutorial-step", "tutorial-na"},
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
    "tutorial-step": ("data/tutorial/", "docs/development/fixtures/help-tutorial/"),
    "replay-test": ("logic/src/test/", "app/game-engine/src/test/"),
    "campaign-test": ("app/game-engine/src/test/",),
}


def _unique_pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_unique_pairs)


def _proof(row: dict, stage: str, reference: str, root: Path) -> str:
    if not isinstance(reference, str) or ":" not in reference:
        raise ValueError(f"invalid evidence reference: {row['inputId']}/{stage}")
    role, target = reference.split(":", 1)
    if role not in PROOF_ROLES[stage]:
        raise ValueError(f"wrong evidence role: {row['inputId']}/{stage}/{role}")
    if role == "tutorial-na":
        if stage != "TUTORIAL_READY" or reference != f"tutorial-na:{row['tutorialNaReason']}":
            raise ValueError(f"wrong tutorial N/A evidence: {row['inputId']}")
        if row["tutorialNaReason"] == "E9_PENDING_U3":
            raise ValueError(f"pending E9 is not completed tutorial evidence: {row['inputId']}")
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
    elif anchor not in content or row["inputId"] not in content:
        raise ValueError(f"evidence anchor or input ID missing: {row['inputId']}/{stage}")
    return role


def validate(catalog: dict, baseline: dict, root: Path) -> list[dict[str, str]]:
    if catalog.get("schemaVersion") != 4 or baseline.get("schemaVersion") != 1:
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
        objective = row.get("tutorialObjectiveId")
        reason = row.get("tutorialNaReason")
        if (objective == "N/A") != (isinstance(reason, str) and bool(reason.strip())):
            raise ValueError(f"tutorialNaReason must match N/A: {input_id}")
        if objective != "N/A" and reason is not None:
            raise ValueError(f"non-N/A objective has N/A reason: {input_id}")
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


if __name__ == "__main__":
    try:
        debt_rows = check()
    except (KeyError, TypeError, ValueError) as error:
        raise SystemExit(f"input evidence gate failed: {error}") from error
    print(f"input evidence gate valid: v4 catalog; pre-v4 evidence debt={len(debt_rows)}")
