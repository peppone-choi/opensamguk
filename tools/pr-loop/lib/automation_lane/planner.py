"""Separate advisory queues with aging, scope conflicts, and shared budgets."""
from copy import deepcopy
from work_units.claim import conflicts
from work_units.schema import REPO, SHA
from .contracts import KINDS, prepare, reason, path_overlap
from .evidence import evaluate
from .resources import admission
from .artifacts import inventory

AGE_BOUND_SECONDS = 7 * 24 * 3600
ACTIVE_BATCH_STAGES = {"SCANNING", "PLAN_PENDING", "IMPLEMENTING", "REVIEW_PENDING", "FIXING", "READY_FOR_MERGE", "BLOCKED"}


def batches_report(batches):
    counts = {}
    for batch in batches:
        if (isinstance(batch, dict) and isinstance(batch.get("repo"), str) and
                isinstance(batch.get("stage"), str) and batch["stage"] in ACTIVE_BATCH_STAGES):
            counts[batch.get("repo")] = counts.get(batch.get("repo"), 0) + 1
    rows = []
    for batch in batches:
        if (not isinstance(batch, dict) or not isinstance(batch.get("repo", ""), str) or
                not isinstance(batch.get("stage", ""), str) or
                not isinstance(batch.get("prs", []), list) or
                not isinstance(batch.get("head", ""), str)):
            rows.append({"observerRequiredToStart": False, "executionAllowed": False,
                         "observerStatus": "CONTRACT_HELD", "observerReasons": ["MALFORMED_BATCH"]})
            continue
        row = dict(batch, observerRequiredToStart=False, executionAllowed=False)
        reasons = []
        if not REPO.fullmatch(batch.get("repo", "")):
            reasons.append("BATCH_REPOSITORY_REQUIRED")
        if counts.get(batch.get("repo"), 0) > 1:
            reasons.append("MULTIPLE_ACTIVE_BATCHES_IN_REPO")
        if batch.get("stage") not in ACTIVE_BATCH_STAGES | {"DONE", "STOPPED"}:
            reasons.append("BATCH_STAGE_UNKNOWN")
        count = batch.get("findingsCount", 0)
        if not isinstance(count, int) or isinstance(count, bool) or count < 0:
            reasons.append("FINDING_COUNT_UNKNOWN")
            count = 0
        if batch.get("stage") not in {"SCANNING", "STOPPED"} and count < 5:
            reasons.append("FIVE_CONFIRMED_FINDINGS_REQUIRED")
        if len(batch.get("prs", [])) > 1:
            reasons.append("ONE_PR_PER_CYCLE_REQUIRED")
        if batch.get("stage") == "READY_FOR_MERGE" and (
                not SHA.fullmatch(batch.get("head", "")) or
                batch.get("reviewHead") != batch.get("head") or batch.get("ciHead") != batch.get("head") or
                batch.get("review") != "SOURCE_MERGEABLE" or batch.get("ci") != "PASS"):
            reasons.append("EXACT_HEAD_REVIEW_CI_REQUIRED")
        row["observerStatus"] = "BLOCKED" if batch.get("blockers") else "CONTRACT_HELD" if reasons else "OBSERVED"
        row["observerReasons"] = reasons
        rows.append(row)
    return rows


def lease_for(unit):
    return {"project": unit["project"], "task": unit["task"], "repo": unit["repo"],
            "issues": unit.get("issues", []), "inputs": unit.get("inputs", []),
            "scopes": unit.get("scopes", []), "unknownScope": unit.get("unknownScope", False),
            "writePaths": unit.get("writePaths", [])}


def clashes(left, right):
    # Imported work-unit leases don't themselves prove file ownership/scope.
    same_repo = left.get("repo") == right.get("repo")
    return conflicts(left, right) or (same_repo and ("writePaths" not in right or
                                     path_overlap(left.get("writePaths", []), right.get("writePaths", []))))


def report(snapshot, now, *, artifact_root=None):
    units = deepcopy(snapshot.get("units", []))
    rows = [prepare(unit, snapshot.get("dependencies", {}), now) for unit in units]
    # Repeated keys may otherwise produce two contradictory ownership proposals.
    for row in rows:
        if sum(other["key"] == row["key"] for other in rows) > 1:
            row.update(status="BLOCKED", reasons=[reason("DUPLICATE_PROJECT_TASK", "Resolve duplicate unit identity")])
    assignments, held = [], deepcopy(snapshot.get("activeLeases", []))
    lanes = snapshot.get("lanes", [])
    ids = [lane["id"] for lane in lanes]
    if len(ids) != len(set(ids)):
        raise ValueError("DUPLICATE_LANE_ID")
    used_lanes = set(snapshot.get("occupiedLanes", []))
    reserved = {"cpuPercent": 0, "memoryBytes": 0, "diskBytes": 0, "heavy": 0}
    batch_rows = batches_report(snapshot.get("batches", []))
    active_repos = {b.get("repo") for b in batch_rows if b.get("stage") in ACTIVE_BATCH_STAGES}
    candidates = [(unit, row) for unit, row in zip(units, rows) if row["status"] == "READY"]
    def order(pair):
        unit, row = pair
        aged = row["waitSeconds"] >= AGE_BOUND_SECONDS
        return (0 if aged else 1, -row["waitSeconds"] if aged else 0,
                0 if unit["project"] == "opensamguk" and unit["kind"] == "product" else 1,
                unit.get("priority", 3), -row["waitSeconds"], row["key"])
    for unit, row in sorted(candidates, key=order):
        read_only = unit["kind"] in {"planning", "review"}
        proposed = lease_for(unit)
        problem = None
        if unit.get("batchId") and unit["repo"] in active_repos:
            problem = "ACTIVE_HOTFIX_BATCH_IN_REPO"
        elif not read_only and any(clashes(proposed, active) for active in held):
            problem = "ACTIVE_SCOPE_CONFLICT"
        lane = next((l for l in lanes if l["id"] not in used_lanes and
                     l.get("kind") == unit["kind"] and unit["profile"] in l.get("profiles", []) and
                     (not read_only or l.get("isolatedSnapshot") is True)), None)
        if problem is None and lane is None:
            problem = "NO_COMPATIBLE_LANE"
        if problem is None:
            problem = admission(snapshot.get("resources", {}), unit.get("demand", {}), now, reserved)
        if problem:
            row.update(status="DEFERRED", reasons=[reason(problem, "Refresh capacity/scope or wait; do not bypass the blocker")])
            continue
        used_lanes.add(lane["id"])
        if not read_only:
            held.append(proposed)
        if unit.get("batchId"):
            active_repos.add(unit["repo"])
        for field in ("cpuPercent", "memoryBytes", "diskBytes"):
            reserved[field] += unit["demand"][field]
        reserved["heavy"] += int(unit["demand"]["heavy"])
        row["status"] = "PROPOSED"
        assignment = {"unit": row["key"], "lane": lane["id"], "kind": unit["kind"],
                      "head": unit["head"], "leaseKey": row["leaseKey"], "executionAllowed": False,
                      "requiresExternalAtomicClaim": not read_only, "deliveryLevel": row["deliveryLevel"],
                      "requiresExternalProviderLease": read_only,
                      "issueComplete": False}
        assignments.append(assignment)
    evidence = []
    for item in snapshot.get("evidenceRequests", []):
        verdict = evaluate(item.get("record", {}), item.get("request", {}), snapshot.get("evidencePolicy", {}), now)
        evidence.append(dict(verdict, id=item.get("id")))
    owners = {f"{x.get('project')}/{x.get('task')}" for x in snapshot.get("activeLeases", [])}
    owners.update(a["unit"] for a in assignments)
    if (snapshot.get("activeOwnersComplete") is not True or
            any(not x.get("project") or not x.get("task") for x in snapshot.get("activeLeases", []))):
        owners = None
    artifacts = inventory(snapshot.get("artifacts", []), artifact_root,
                          snapshot.get("generatedPrefixes", ["build/", "test-results/"]), owners, now)
    return {"schema": "automation-observation/1", "mode": "OBSERVE", "executionAllowed": False,
            "queues": {kind: [r for r in rows if r["kind"] == kind] for kind in KINDS},
            "preparation": [r for r in rows if r["status"] == "PREPARATION"],
            "blocked": [r for r in rows if r["status"] == "BLOCKED"], "assignments": assignments,
            "evidence": evidence, "resources": deepcopy(snapshot.get("resources", {})),
            "reservedProposals": reserved, "batches": batch_rows, "artifacts": artifacts}
