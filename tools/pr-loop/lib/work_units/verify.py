"""Structural checks do not wait for their own CI. Host checks are separate."""
import hashlib
from . import bundle_hash
from .acceptance import parse_ac
from .adapters import GitHubReader
from .claim import lease_covers
from .gaps import compare
from .qa import required_lanes, validate_qa, verify_executed
from .schema import binding, manifest, SHA
from .surface import derive_impact

RED = {"failure", "timed_out", "startup_failure", "action_required"}


def structural_check(base, head, changes, *, branch=None, reader=None, registration=None):
    policy = binding(base.json("work-units/binding.json"))
    impact = derive_impact(base, head, changes)
    reasons = list(impact["reasons"])
    receipt = {"schema": "wu-ci-receipt/1", "status": "REPORT_ONLY" if policy["mode"] == "report" else "ENFORCED",
               "mode": policy["mode"], "baseSha": base.sha, "headSha": head.sha,
               "gateTreeBlobs": {p: base.blob_sha(p) for p in (
                   "work-units/binding.json", "work-units/surface-map.json", "tools/ci/work_unit_gate.py")},
               "impact": impact, "requiredLanes": required_lanes(impact), "qaPlan": [],
               "reasons": reasons, "reservation": "UNVERIFIED_IN_CI", "valid": False}
    candidates = []
    for path in head.entries("work-units/units"):
        if path.endswith(".json"):
            if len(candidates) >= 200:
                raise ValueError("HOST_VERIFY_TOO_WIDE")
            data = head.json(path)
            if branch is None or data.get("branch") == branch:
                candidates.append((path, data))
    if len(candidates) != 1:
        reasons.append("ONE_WORK_UNIT_REQUIRED")
    else:
        path, unit = candidates[0]
        try:
            manifest(unit, policy["repo"])
            if path != "work-units/units/" + unit["unitId"] + ".json":
                reasons.append("UNIT_PATH_MISMATCH")
            receipt.update(unitId=unit["unitId"], manifestPath=path, manifestBlob=head.blob_sha(path))
            declared = unit["commandImpact"]
            if declared["result"] != impact["result"]:
                reasons.append("IMPACT_RESULT_MISMATCH")
            for key in ("inputIds", "scopes"):
                if not set(impact[key]) <= set(declared[key]):
                    reasons.append("IMPACT_OMITTED:" + key)
            valid_ids = {r["inputId"] for r in head.json("data/commands/input-catalog.json")["inputs"]}
            if not set(declared["inputIds"]) <= valid_ids:
                reasons.append("IMPACT_UNKNOWN_INPUT")
            known_scopes = {"ALL_INPUTS", "ALL_UI_INPUTS"} | {i.split(".", 1)[0] + ".*" for i in valid_ids}
            if not set(declared["scopes"]) <= known_scopes:
                reasons.append("IMPACT_UNKNOWN_SCOPE")
            if impact["sammo"] != declared["sammo"]:
                reasons.append("LEGACY_IMPACT_OMITTED")
            if impact["result"] == "NONE" and declared["na"] != impact["na"]:
                reasons.append("IMPACT_NA_MISMATCH")
            receipt["qaPlan"], qa_reasons = validate_qa(head, unit, impact)
            reasons += qa_reasons
            for issue in unit["issues"]:
                if issue["system"] == "jira":
                    jira = policy["jira"]
                    if jira["status"] != "VERIFIED":
                        reasons.append("JIRA_PENDING_BINDING")
                    elif (issue.get("instanceHost") != jira["instanceHost"] or
                          not issue["key"].startswith(jira["projectKey"] + "-")):
                        reasons.append("JIRA_IDENTITY_MISMATCH")
                elif reader:
                    actual = reader.get(f"repos/{issue['repo']}/issues/{issue['number']}")
                    if actual.get("number") != issue["number"] or "pull_request" in actual:
                        reasons.append("ISSUE_IDENTITY_MISMATCH")
                    if not actual.get("closed_at") and actual.get("state") not in {"open", "closed"}:
                        reasons.append("ISSUE_STATE_UNKNOWN")
                    ref = f"{issue['repo']}#{issue['number']}"
                    if unit["acceptance"]["source"] == ref:
                        ac = parse_ac(actual.get("body", ""), legacy=unit["acceptance"]["mode"] == "legacy")
                        if ac["fingerprint"] != unit["acceptance"]["fingerprint"]:
                            reasons.append("AC_DRIFT")
                        if not set(unit["acceptance"]["criteria"]) <= set(ac["criteria"]):
                            reasons.append("AC_CRITERIA_UNKNOWN")
            if reader is None:
                reasons.append("ISSUE_LINK_UNVERIFIED_IN_CI")
            if unit["acceptance"]["source"] not in [
                    f"{i['repo']}#{i['number']}" for i in unit["issues"] if i["system"] == "github"]:
                reasons.append("AC_SOURCE_MISMATCH")
            if registration is not None:
                if (registration.get("version") != 2 or registration.get("unitId") != unit["unitId"] or
                        hashlib.sha256(registration["nonce"].encode()).hexdigest() != unit["reservation"]["nonceSha256"]):
                    reasons.append("RESERVATION_MISMATCH")
                elif not lease_covers(registration["lease"], impact):
                    reasons.append("LEASE_EXCEEDED")
                else:
                    receipt["reservation"] = "VERIFIED"
        except (ValueError, KeyError, TypeError) as exc:
            reasons.append(str(exc))
    _, gap_reasons = compare(base, head)
    reasons += gap_reasons
    # CI cannot prove private local reservations; this is not a structural error.
    receipt["valid"] = not [r for r in reasons if r not in {
        "ISSUE_LINK_UNVERIFIED_IN_CI", "JIRA_PENDING_BINDING"}]
    return receipt


def check_conclusions(checks, required):
    """No branch-required check substitutes for a required execution lane."""
    state, reasons = "PASS", []
    for name in required:
        rows = [c for c in checks if c.get("name") == name]
        if not rows:
            reasons.append("PENDING_CI:" + name)
            state = "PENDING_CI" if state != "RED" else state
            continue
        current = max(rows, key=lambda c: c.get("id", 0))
        conclusion = current.get("conclusion")
        if current.get("status") != "completed" or conclusion == "cancelled":
            state = "PENDING_CI" if state != "RED" else state
            reasons.append("PENDING_CI:" + name)
        elif conclusion != "success":
            state = "RED"
            reasons.append(("RED:" if conclusion in RED else "REQUIRED_LANE_SKIPPED:") + name)
    return {"result": state, "reasons": reasons}


def verify_pr(base, head, changes, *, raw, reader, registration=None, review=None, checks=None,
              executions=None, run_id=None):
    receipt = structural_check(base, head, changes, branch=raw["head"]["ref"], reader=reader,
                               registration=registration)
    lanes = receipt["requiredLanes"]
    names = ["naming-lint"] + (["jvm"] if lanes["jvm"] else []) + (
        ["web (game)"] if lanes["web"] else []) + (["contracts"] if lanes["contracts"] else [])
    conclusion = check_conclusions(checks or [], names)
    reasons = list(receipt["reasons"]) + conclusion["reasons"]
    execution = verify_executed(receipt, executions or [], lane="all", run_id=run_id)
    reasons += execution["reasons"]
    if review is None or review.get("head") != head.sha or review.get("verdict") != "MERGEABLE" or not review.get("independent"):
        reasons.append("INDEPENDENT_REVIEW_REQUIRED")
    if raw["head"]["sha"] != head.sha:
        reasons.append("STALE_HEAD")
    if registration is None:
        reasons.append("RESERVATION_ABSENT")
    blocking = [r for r in reasons if r != "JIRA_PENDING_BINDING"]
    return {"result": "PASS" if receipt["valid"] and not blocking else conclusion["result"] if conclusion["result"] != "PASS" else "FAIL",
            "mode": receipt["mode"], "reasons": reasons, "receipt": receipt,
            "libHash": bundle_hash(), "review": review, "checks": checks or [], "execution": execution}


def merge_proof(raw, reader, *, final_head, manifest_path, host_verify, receipt, attested=False):
    repo = (raw.get("base", {}).get("repo") or {}).get("full_name")
    merge = raw.get("merge_commit_sha")
    reasons = []
    if (raw.get("merged") is not True or not raw.get("merged_at") or raw.get("base", {}).get("ref") != "main" or
            not SHA.fullmatch(merge or "") or raw.get("head", {}).get("sha") != final_head):
        return {"result": "NOT_MERGED", "reasons": ["MERGE_NOT_PROVEN"]}
    if receipt.get("headSha") != final_head or host_verify.get("receipt", {}).get("headSha") != final_head:
        reasons.append("STALE_HEAD")
    if host_verify.get("result") != "PASS" or host_verify.get("libHash") != bundle_hash():
        reasons.append("HOST_VERIFY_REQUIRED")
    if receipt.get("reservation") != "VERIFIED" and not attested:
        reasons.append("RESERVATION_ATTESTATION_REQUIRED")
    comparison = reader.get(f"repos/{repo}/compare/{merge}...main")
    if comparison.get("status") not in {"ahead", "identical"}:
        reasons.append("MERGE_NOT_ON_MAIN")
    commit = reader.get(f"repos/{repo}/commits/{merge}")
    if len(commit.get("parents", [])) != 1:
        reasons.append("SQUASH_PARENT_REQUIRED")
    def blob(ref):
        data = reader.get(f"repos/{repo}/contents/{manifest_path}?ref={ref}")
        if data.get("type") != "file":
            raise ValueError("MANIFEST_OBJECT_TYPE")
        return data.get("sha")
    if blob(final_head) != blob(merge) or blob(final_head) != receipt.get("manifestBlob"):
        reasons.append("MANIFEST_MERGE_BLOB_MISMATCH")
    return {"result": "MERGE_PROVEN" if not reasons else "MANUAL",
            "mergeSha": merge, "finalHead": final_head, "reasons": reasons}
