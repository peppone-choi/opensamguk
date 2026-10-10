"""Common snapshot contract; project adapters retain their runtime semantics."""
from datetime import datetime, timezone
import re
from work_units.acceptance import parse_ac
from work_units.schema import SHA, REPO, lease_key, safe_path

KINDS = ("product", "planning", "review", "docs")


def timestamp(value):
    if not isinstance(value, str):
        raise ValueError("TIMESTAMP_REQUIRED")
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.tzinfo is None:
        raise ValueError("TIMEZONE_REQUIRED")
    return result.astimezone(timezone.utc)


def covers(prefix, path):
    return prefix == path or (prefix.endswith("/") and path.startswith(prefix))


def path_overlap(left, right):
    return any(covers(a, b) or covers(b, a) for a in left for b in right)


def paths(values):
    if not isinstance(values, list):
        raise ValueError("PATH_LIST_REQUIRED")
    for value in values:
        safe_path(value.rstrip("/"))
    return values


def reason(code, action):
    return {"code": code, "nextAction": action}


def dependency_done(ref, dependencies):
    if not isinstance(ref, str) or not ref:
        raise ValueError("DEPENDENCY_REFERENCE_REQUIRED")
    if ref.startswith("unit:"):
        parts = ref.removeprefix("unit:").split("/")
        if len(parts) != 2:
            raise ValueError("DEPENDENCY_UNIT_IDENTITY")
        lease_key(*parts)
        return dependencies.get(ref) == "UNIT_DONE"
    return dependencies.get(ref) == "closed"


def acceptance(unit):
    if "body" in unit:
        parsed = parse_ac(unit["body"])
        return dict(parsed, depends=[f"{unit['repo']}#{n}" for n in parsed["depends"]])
    # A project adapter can supply normalized criteria without adopting RTK runtime.
    ac = unit.get("acceptance", {})
    if (ac.get("adapter") != "normalized/1" or ac.get("head") != unit["head"] or
            not ac.get("validatorVersion") or not ac.get("criteria") or
            not all(isinstance(c, str) and c for c in ac["criteria"]) or
            len(set(ac["criteria"])) != len(ac["criteria"]) or
            not isinstance(ac.get("depends", []), list) or
            not re.fullmatch(r"sha256:[0-9a-f]{64}", ac.get("fingerprint", ""))):
        raise ValueError("AC_ADAPTER_EVIDENCE_REQUIRED")
    return ac


def prepare(unit, dependencies, now):
    if not isinstance(unit, dict):
        return {"key": "UNKNOWN", "kind": "unknown", "status": "PREPARATION",
                "reasons": [reason("UNIT_OBJECT_REQUIRED", "Repair the malformed snapshot item")]}
    row = {"key": f"{unit.get('project', '?')}/{unit.get('task', '?')}",
           "kind": unit.get("kind"), "status": "PREPARATION", "reasons": []}
    try:
        if not REPO.fullmatch(unit.get("repo", "")) or not SHA.fullmatch(unit.get("head", "")):
            raise ValueError("UNIT_REPOSITORY_HEAD_REQUIRED")
        row["leaseKey"] = lease_key(unit.get("project"), unit.get("task"))
        if unit.get("kind") not in KINDS or unit.get("delivery") not in {"USER_FEATURE", "FOUNDATION"}:
            raise ValueError("UNIT_KIND_DELIVERY_REQUIRED")
        if not unit.get("issues") or not all(isinstance(ref, str) and ref for ref in unit["issues"]):
            raise ValueError("ISSUE_REFERENCE_REQUIRED")
        priority = unit.get("priority", 3)
        if not isinstance(priority, int) or isinstance(priority, bool) or not 0 <= priority <= 3:
            raise ValueError("UNIT_PRIORITY_REQUIRED")
        for field in ("issues", "inputs", "scopes"):
            if not isinstance(unit.get(field, []), list) or not all(isinstance(x, str) and x for x in unit.get(field, [])):
                raise ValueError("LEASE_SCOPE_LIST_REQUIRED")
        created = timestamp(unit["createdAt"])
        if created > now:
            raise ValueError("UNIT_TIME_IN_FUTURE")
        row["waitSeconds"] = (now - created).total_seconds()
        if unit.get("blockers"):
            row.update(status="BLOCKED", reasons=[reason(b["code"], b["nextAction"]) for b in unit["blockers"]])
            return row
        ac = acceptance(unit)
        selected = unit.get("criteria", [])
        if not selected or len(selected) != len(set(selected)) or not set(selected) <= set(ac["criteria"]):
            raise ValueError("UNIT_CRITERIA_REQUIRED")
        missing = [ref for ref in ac.get("depends", []) + unit.get("depends", [])
                   if not dependency_done(ref, dependencies)]
        if missing:
            row["reasons"].append(reason("DEPENDENCY_PENDING", "Resolve: " + ", ".join(missing)))
        decisions = unit.get("decisions", {})
        if any(decisions.get(key) != "APPROVED" for key in unit.get("requiredDecisions", [])):
            row["reasons"].append(reason("DECISION_PENDING", "Record the required product decisions"))
        plan = unit.get("plan", {})
        if (plan.get("status") != "READY" or plan.get("head") != unit["head"] or
                not set(selected) <= set(plan.get("criteria", []))):
            row["reasons"].append(reason("PLAN_PENDING", "Supply a plan for this head and selected criteria"))
        if not unit.get("profile"):
            raise ValueError("RUNTIME_PROFILE_REQUIRED")
        paths(unit.get("writePaths", []))
        if unit["kind"] in {"product", "docs"} and not unit.get("writePaths"):
            raise ValueError("WRITE_SCOPE_REQUIRED")
        row.update(criteria=selected, acceptanceFingerprint=ac["fingerprint"],
                   deliveryLevel="INTERNAL_FOUNDATION" if unit["delivery"] == "FOUNDATION" else "FEATURE_SLICE",
                   issueComplete=False)
        if not row["reasons"]:
            row["status"] = "READY"
    except (ValueError, KeyError, TypeError, AttributeError) as exc:
        row["reasons"].append(reason(str(exc), "Complete or repair the snapshot item; other items remain eligible"))
    return row
