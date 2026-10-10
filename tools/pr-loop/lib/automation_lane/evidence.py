"""Exact-head reuse and bounded prior-head context; never grant approval."""
import re
from work_units.schema import SHA
from .contracts import timestamp, covers, path_overlap, paths

FP = re.compile(r"sha256:[0-9a-f]{64}\Z")


def evaluate(record, request, policy, now):
    def invalid(code):
        return {"status": "REVALIDATE", "reason": code, "gateSatisfied": False}
    try:
        if not SHA.fullmatch(record.get("head", "")) or not SHA.fullmatch(request.get("head", "")):
            return invalid("EXACT_HEAD_REQUIRED")
        if record.get("result") != "PASS" or record.get("skipped") != 0 or record.get("failures") != 0:
            return invalid("NO_COMPLETE_PASS")
        age = (now - timestamp(record["recordedAt"])).total_seconds()
        if age < 0 or age > min(86400, policy.get("maxAgeSeconds", 86400)):
            return invalid("STALE_EVIDENCE")
        for field in ("tool", "contractFingerprint", "acceptanceFingerprint"):
            if not request.get(field) or record.get(field) != request[field]:
                return invalid("BINDING_CHANGED:" + field)
        if not FP.fullmatch(request["contractFingerprint"]) or not FP.fullmatch(request["acceptanceFingerprint"]):
            return invalid("FINGERPRINT_REQUIRED")
        if not request["tool"].get("name") or not request["tool"].get("version"):
            return invalid("TOOL_VERSION_REQUIRED")
        scope, wanted = record["scope"], request["scope"]
        paths(scope["paths"])
        paths(wanted["paths"])
        if not wanted["checks"] or not set(wanted["checks"]) <= set(scope["checks"]):
            return invalid("CHECK_SCOPE_NOT_COVERED")
        if not set(wanted["criteria"]) <= set(scope["criteria"]):
            return invalid("CRITERIA_NOT_COVERED")
        if any(not any(covers(prefix, path) for prefix in scope["paths"]) for path in wanted["paths"]):
            return invalid("PATH_SCOPE_NOT_COVERED")
        changed = request.get("changedPaths", [])
        paths(changed)
        security = [".github/workflows/", "infra/", "scripts/"] + paths(policy.get("securityPaths", []))
        contracts = ["tools/pr-loop/lib/work_units/", "tools/pr-loop/lib/automation_lane/"] + paths(policy.get("contractPaths", []))
        if path_overlap(security, changed):
            return invalid("SECURITY_CHANGE")
        if path_overlap(contracts, changed):
            return invalid("CONTRACT_CHANGE")
        if record.get("head") == request.get("head"):
            return {"status": "REUSE_EXACT", "boundHead": record["head"], "gateSatisfied": False}
        if request.get("baseHead") != record.get("head") or request.get("diffComplete") is not True:
            return invalid("DIFF_PROVENANCE_REQUIRED")
        if policy.get("classificationComplete") is not True:
            return invalid("RISK_CLASSIFIER_UNKNOWN")
        if path_overlap(scope["paths"], changed):
            return invalid("CHECKED_SCOPE_CHANGED")
        return {"status": "DELTA_CONTEXT_ONLY", "boundHead": record["head"],
                "currentHead": request["head"], "requiredReview": "CURRENT_HEAD_DELTA",
                "gateSatisfied": False}
    except (KeyError, TypeError, ValueError, AttributeError):
        return invalid("MALFORMED_EVIDENCE")
