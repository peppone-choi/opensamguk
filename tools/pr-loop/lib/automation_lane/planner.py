"""Separate advisory queues with aging, scope conflicts, and shared budgets."""
from copy import deepcopy
from work_units.claim import conflicts
from work_units.schema import REPO, SHA, SLUG
from .contracts import KINDS, prepare, reason, path_overlap, paths
from .evidence import evaluate
from .resources import admission
from .artifacts import inventory

AGE_BOUND_SECONDS = 7 * 24 * 3600
ACTIVE_BATCH_STAGES = {"SCANNING", "PLAN_PENDING", "IMPLEMENTING", "REVIEW_PENDING", "FIXING", "READY_FOR_MERGE", "BLOCKED"}


def records(value):
    return value if isinstance(value, list) else [value]


def batches_report(batches):
    rows = []
    for item in records(batches):
        batch = item if isinstance(item, dict) else {}
        row = dict(batch, observerRequiredToStart=False, executionAllowed=False)
        reasons = [] if isinstance(item, dict) else ["MALFORMED_BATCH"]
        repo, stage = batch.get("repo"), batch.get("stage")
        repo = repo if isinstance(repo, str) and REPO.fullmatch(repo) else None
        if repo is None:
            reasons.append("BATCH_REPOSITORY_REQUIRED")
        valid_stage = isinstance(stage, str) and stage in ACTIVE_BATCH_STAGES | {"DONE", "STOPPED"}
        if not valid_stage:
            reasons.append("BATCH_STAGE_UNKNOWN")
        count = batch.get("findingsCount", 0)
        if not isinstance(count, int) or isinstance(count, bool) or count < 0:
            reasons.append("FINDING_COUNT_UNKNOWN")
            count = 0
        if valid_stage and stage not in {"SCANNING", "STOPPED"} and count < 5:
            reasons.append("FIVE_CONFIRMED_FINDINGS_REQUIRED")
        prs = batch.get("prs", [])
        if not isinstance(prs, list) or not all(isinstance(pr, int) and not isinstance(pr, bool) and pr > 0 for pr in prs):
            reasons.append("BATCH_PR_LIST_REQUIRED")
        elif len(prs) > 1:
            reasons.append("ONE_PR_PER_CYCLE_REQUIRED")
        head = batch.get("head", "")
        if not isinstance(head, str):
            reasons.append("BATCH_HEAD_TYPE_REQUIRED")
        if stage == "READY_FOR_MERGE" and (
                not isinstance(head, str) or not SHA.fullmatch(head) or
                batch.get("reviewHead") != batch.get("head") or batch.get("ciHead") != batch.get("head") or
                batch.get("review") != "SOURCE_MERGEABLE" or batch.get("ci") != "PASS"):
            reasons.append("EXACT_HEAD_REVIEW_CI_REQUIRED")
        row.update(conflictRepo=repo, conflictScopeUnknown=bool(reasons),
                   active=bool(reasons) or (valid_stage and stage in ACTIVE_BATCH_STAGES),
                   observerReasons=reasons)
        rows.append(row)
    counts = {}
    for row in rows:
        if row["active"] and row["conflictRepo"] is not None:
            counts[row["conflictRepo"]] = counts.get(row["conflictRepo"], 0) + 1
    for row in rows:
        if counts.get(row["conflictRepo"], 0) > 1:
            row["observerReasons"].append("MULTIPLE_ACTIVE_BATCHES_IN_REPO")
            row["conflictScopeUnknown"] = True
        row["observerStatus"] = "BLOCKED" if row.get("blockers") else "CONTRACT_HELD" if row["observerReasons"] else "OBSERVED"
    return rows


def lease_for(unit):
    return {"project": unit["project"], "task": unit["task"], "repo": unit["repo"],
            "issues": unit.get("issues", []), "inputs": unit.get("inputs", []),
            "scopes": unit.get("scopes", []), "unknownScope": unit.get("unknownScope", False),
            "writePaths": unit.get("writePaths", [])}


def lease_scope(lease):
    if not isinstance(lease, dict):
        return {"repo": None, "issues": [], "globalScopeUnknown": True,
                "reasons": ["LEASE_OBJECT_REQUIRED"]}
    repo = lease.get("repo")
    repo = repo if isinstance(repo, str) and REPO.fullmatch(repo) else None
    result = {"repo": repo, "issues": [], "globalScopeUnknown": repo is None,
              "reasons": [] if repo else ["LEASE_REPOSITORY_REQUIRED"]}
    for field in ("issues", "inputs", "scopes"):
        values = lease.get(field)
        if not isinstance(values, list) or not all(isinstance(v, str) and v for v in values):
            result["reasons"].append("LEASE_SCOPE_LIST_REQUIRED:" + field)
            if field == "issues":
                result["globalScopeUnknown"] = True
        elif field == "issues":
            result["issues"] = values
    if not isinstance(lease.get("unknownScope", False), bool):
        result["reasons"].append("LEASE_UNKNOWN_SCOPE_TYPE")
    try:
        if "writePaths" not in lease:
            raise ValueError("LEASE_FILE_SCOPE_UNKNOWN")
        values = lease["writePaths"]
        if not isinstance(values, list) or not all(isinstance(v, str) for v in values):
            raise ValueError("LEASE_FILE_SCOPE_LIST_REQUIRED")
        paths(values)
    except (ValueError, TypeError, AttributeError) as exc:
        result["reasons"].append(str(exc))
    return result


def clashes(left, right):
    scope = lease_scope(right)
    if scope["globalScopeUnknown"] or set(left.get("issues", [])) & set(scope["issues"]):
        return True
    if left["repo"] != scope["repo"]:
        return False
    return bool(scope["reasons"]) or conflicts(left, right) or path_overlap(left["writePaths"], right["writePaths"])


def report(snapshot, now, *, artifact_root=None):
    units = deepcopy(snapshot.get("units", []))
    rows = [prepare(unit, snapshot.get("dependencies", {}), now) for unit in units]
    # Repeated keys may otherwise produce two contradictory ownership proposals.
    for row in rows:
        if sum(other["key"] == row["key"] for other in rows) > 1:
            row.update(status="BLOCKED", reasons=[reason("DUPLICATE_PROJECT_TASK", "Resolve duplicate unit identity")])
    assignments, held = [], deepcopy(records(snapshot.get("activeLeases", [])))
    lease_reports = [lease_scope(lease) for lease in held]
    lanes = snapshot.get("lanes", [])
    ids = [lane["id"] for lane in lanes]
    if len(ids) != len(set(ids)):
        raise ValueError("DUPLICATE_LANE_ID")
    used_lanes = set(snapshot.get("occupiedLanes", []))
    reserved = {"cpuPercent": 0, "memoryBytes": 0, "diskBytes": 0, "heavy": 0}
    batch_rows = batches_report(snapshot.get("batches", []))
    active_repos = {b["conflictRepo"] for b in batch_rows if b["active"]}
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
        if not read_only and any(b["conflictScopeUnknown"] and
                                 b["conflictRepo"] in {None, unit["repo"]} for b in batch_rows):
            problem = "HOTFIX_BATCH_SCOPE_UNKNOWN"
        elif unit.get("batchId") and (None in active_repos or unit["repo"] in active_repos):
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
    for item in records(snapshot.get("evidenceRequests", [])):
        if not isinstance(item, dict):
            evidence.append({"status": "REVALIDATE", "reason": "EVIDENCE_REQUEST_OBJECT_REQUIRED", "gateSatisfied": False})
            continue
        verdict = evaluate(item.get("record", {}), item.get("request", {}), snapshot.get("evidencePolicy", {}), now)
        evidence.append(dict(verdict, id=item.get("id")))
    active = records(snapshot.get("activeLeases", []))
    def owner(lease):
        if not isinstance(lease, dict) or not all(isinstance(lease.get(k), str) and SLUG.fullmatch(lease[k])
                                                for k in ("project", "task")):
            return None
        return f"{lease['project']}/{lease['task']}"
    owners = {owner(lease) for lease in active}
    owners.update(a["unit"] for a in assignments)
    if (snapshot.get("activeOwnersComplete") is not True or
            None in owners or any(scope["reasons"] for scope in lease_reports)):
        owners = None
    artifacts = inventory(snapshot.get("artifacts", []), artifact_root,
                          snapshot.get("generatedPrefixes", ["build/", "test-results/"]), owners, now)
    return {"schema": "automation-observation/1", "mode": "OBSERVE", "executionAllowed": False,
            "queues": {kind: [r for r in rows if r["kind"] == kind] for kind in KINDS},
            "preparation": [r for r in rows if r["status"] == "PREPARATION"],
            "blocked": [r for r in rows if r["status"] == "BLOCKED"], "assignments": assignments,
            "evidence": evidence, "resources": deepcopy(snapshot.get("resources", {})),
            "reservedProposals": reserved, "activeLeaseReports": lease_reports,
            "batches": batch_rows, "artifacts": artifacts}
