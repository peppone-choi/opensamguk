"""Durable merged audit/outbox. Default dry-run; no Jira transitions."""
import hashlib
import os
import socket
import time
import io
import re
import zipfile
import subprocess
import uuid
from datetime import datetime, timezone
from pathlib import Path
from . import bundle_hash
from .acceptance import parse_ac, remaining
from .adapters import ApiError, GitHubWriter, JiraAdapter
from .schema import digest, durable_write, locked, read_record, safe_path, json_data
from .schema import STAGES, ensure_directory
from .verify import merge_proof, verify_pr
from .surface import RemoteTree


def host_verify(raw, reader, registration, review):
    """Installed code recalculates the bounded PR file list and exact-H checks."""
    repo = raw["base"]["repo"]["full_name"]
    head = raw["head"]["sha"]
    comparison = reader.get(f"repos/{repo}/compare/{raw['base']['sha']}...{head}")
    base_sha = comparison["merge_base_commit"]["sha"]
    base, candidate = RemoteTree(reader, repo, base_sha), RemoteTree(reader, repo, head)
    files = reader.pages(f"repos/{repo}/pulls/{raw['number']}/files?per_page=100")
    if len(files) > 1000:
        raise ValueError("HOST_VERIFY_TOO_WIDE")
    statuses = {"added": "A", "modified": "M", "removed": "D", "renamed": "R", "copied": "C"}
    changes = [{"path": f["filename"], "oldPath": f.get("previous_filename"),
                "status": statuses.get(f["status"], "?")} for f in files]
    checks = reader.pages(f"repos/{repo}/commits/{head}/check-runs?per_page=100", key="check_runs")
    if any(c.get("head_sha") != head for c in checks):
        raise ValueError("CHECK_HEAD_MISMATCH")
    executions, run_id = execution_artifacts(reader, repo, head, checks)
    host = verify_pr(base, candidate, changes, raw=raw, reader=reader,
                     registration=registration, review=review, checks=checks,
                     executions=executions, run_id=run_id)
    host["binding"] = base.json("work-units/binding.json")
    path = host["receipt"].get("manifestPath")
    if not path:
        raise ValueError("ONE_WORK_UNIT_REQUIRED")
    return host, candidate.json(path)


def execution_artifacts(reader, repo, head, checks):
    """Exact-run bounded artifact data; never extract or execute archive files."""
    naming = [c for c in checks if c.get("name") == "naming-lint"]
    if not naming:
        return [], None
    current = max(naming, key=lambda c: c.get("id", 0))
    match = re.fullmatch(r"https://github\.com/" + re.escape(repo) +
                         r"/actions/runs/([0-9]+)(?:/job/[0-9]+)?", current.get("details_url", ""))
    if not match:
        raise ValueError("EXECUTION_RUN_IDENTITY")
    run_id = match[1]
    run = reader.get(f"repos/{repo}/actions/runs/{run_id}")
    if run.get("head_sha") != head or run.get("path", "").split("@", 1)[0] != ".github/workflows/ci.yml":
        raise ValueError("EXECUTION_RUN_IDENTITY")
    artifacts = reader.pages(f"repos/{repo}/actions/runs/{run_id}/artifacts?per_page=100", key="artifacts")
    executions = []
    for artifact in artifacts:
        if not re.fullmatch(r"wu-(?:jvm-core|game-engine|web)-executed-attempt-[0-9]+", artifact["name"]):
            continue
        if artifact.get("expired") or artifact.get("size_in_bytes", 0) > 50 * 1024 * 1024:
            raise ValueError("EXECUTION_ARTIFACT_UNAVAILABLE")
        archive = zipfile.ZipFile(io.BytesIO(reader.download(
            f"repos/{repo}/actions/artifacts/{artifact['id']}/zip")))
        if len(archive.infolist()) > 50 or sum(i.file_size for i in archive.infolist()) > 50 * 1024 * 1024:
            raise ValueError("HOST_VERIFY_TOO_WIDE")
        for item in archive.infolist():
            safe_path(item.filename.rstrip("/"))
            if (item.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("UNSAFE_EXECUTION_ARCHIVE")
            if not item.filename.endswith(".json"):
                continue
            data = json_data(archive.read(item), max_size=50 * 1024 * 1024)
            if data.get("schema") == "wu-executed/1":
                if data.get("attempt", 0) > run.get("run_attempt", 0):
                    raise ValueError("EXECUTION_FUTURE_ATTEMPT")
                executions.append(data)
    return executions, run_id


def records(state, kind):
    result = []
    source = state / "work-units" / kind
    for parent in [source, *source.parents]:
        if parent.is_symlink():
            raise ValueError("UNSAFE_STATE_SYMLINK")
    for path in sorted(source.glob("*.json")):
        try:
            result.append(read_record(path))
        except (ValueError, OSError, TypeError, KeyError) as exc:
            # Keep unreadable evidence for diagnosis and isolate it from healthy
            # tasks. Pending intents still require a valid audit before writes.
            root = ensure_directory(state / "work-units/quarantine" / kind)
            target = root / (path.name + "." + uuid.uuid4().hex)
            os.replace(path, target)
            durable_write(Path(str(target) + ".reason.json"), {"source": path.name, "reason": str(exc)})
    return result


def marker(audit):
    return (f"<!-- work-complete v1 unit={audit['unitId']} pr={audit['pr']} "
            f"merge={audit['mergeSha']} ac={audit['acceptanceFingerprint'][7:19]} -->")


def intent_path(state, identity):
    return state / "work-units/outbox" / (digest(identity) + ".json")


def build_intents(audit):
    body = marker(audit) + "\n" + (
        "Partial implementation; remaining: " + ", ".join(audit["remainingAtRecord"])
        if audit["remainingAtRecord"] else "Acceptance recorded; closure requires explicit attestation.")
    result = []
    for target in audit["issues"]:
        identity = f"{audit['auditId']}:{target}:comment"
        result.append({"intentId": identity, "auditId": audit["auditId"], "target": target,
                       "action": "comment", "state": "PENDING", "attempts": 0,
                       "nextAttemptAt": 0, "lastError": None, "body": body,
                       "acFingerprint": audit["acceptanceFingerprint"]})
    for issue in audit.get("jiraIssues", []):
        emitted = JiraAdapter(audit["binding"]).intent(audit, issue, body)
        identity = emitted.get("intentId", f"{audit['auditId']}:jira:{issue['key']}")
        result.append(dict(emitted, intentId=identity, auditId=audit["auditId"],
                           target=issue["key"], action="comment", attempts=0,
                           nextAttemptAt=0, lastError=None))
    # CAS/immutable AC is not available. PR-A never automatically closes prose
    # acceptance, stories, or epics merely because a PR has merged.
    if not audit["remainingAtRecord"]:
        result.append({"intentId": audit["auditId"] + ":close-candidate", "auditId": audit["auditId"],
                       "target": audit["primaryIssue"], "action": "close", "state": "MANUAL",
                       "attempts": 0, "nextAttemptAt": 0,
                       "lastError": "IMMUTABLE_AC_OR_MANUAL_CLOSURE_REQUIRED"})
    return result


def record(state, raw, reader, host, *, unit, registration=None, attestation=None, merged_tree=None):
    primary = unit["acceptance"]["source"]
    repo, number = primary.rsplit("#", 1)
    current = reader.get(f"repos/{repo}/issues/{number}")
    ac = parse_ac(current.get("body", ""), legacy=unit["acceptance"]["mode"] == "legacy")
    if ac["fingerprint"] != unit["acceptance"]["fingerprint"]:
        raise ValueError("AC_DRIFT")
    attested = bool(attestation and attestation.get("head") == raw["head"]["sha"] and
                    attestation.get("acFingerprint") == ac["fingerprint"] and attestation.get("approvedBy") and
                    attestation.get("reason") and attestation.get("bundleHash") == bundle_hash() and
                    attestation.get("manifestBlob") == host["receipt"]["manifestBlob"])
    if registration is None and attested:
        blocking = [r for r in host["reasons"] if r not in {"RESERVATION_ABSENT", "JIRA_PENDING_BINDING"}]
        if not blocking and host["receipt"]["valid"]:
            host = dict(host, result="PASS", reasons=[])
    if ac["legacyAc"] and not attested:
        raise ValueError("LEGACY_ATTESTATION_REQUIRED")
    proof = merge_proof(raw, reader, final_head=raw["head"]["sha"],
                        manifest_path=host["receipt"]["manifestPath"], host_verify=host,
                        receipt=host["receipt"], attested=attested)
    if proof["result"] != "MERGE_PROVEN":
        raise ValueError(",".join(proof["reasons"]))
    covered = unit["acceptance"]["criteria"]
    for criterion in covered:
        check = ac["checks"].get(criterion)
        if check:
            if merged_tree is None:
                raise ValueError("MACHINE_AC_MERGED_TREE_REQUIRED")
            _, identity, stage = check.split()
            rows = {r["inputId"]: r for r in merged_tree.json("data/commands/input-catalog.json")["inputs"]}
            if (stage not in STAGES or identity not in rows or
                    STAGES.index(rows[identity]["deliveryState"]) < STAGES.index(stage)):
                raise ValueError("MACHINE_AC_NOT_SATISFIED")
        elif not (host.get("review") or {}).get("independent"):
            raise ValueError("PROSE_AC_INDEPENDENT_REVIEW_REQUIRED")
    audit_id = digest([repo, raw["number"], raw["head"]["sha"], proof["mergeSha"], host["receipt"]["manifestBlob"]])
    issues = [f"{i['repo']}#{i['number']}" for i in unit["issues"] if i["system"] == "github"]
    audit = {"schema": "wu-audit/1", "auditId": audit_id, "unitId": unit["unitId"],
             "repo": repo, "pr": raw["number"], "finalHead": raw["head"]["sha"],
             "mergeSha": proof["mergeSha"], "mergedAt": raw["merged_at"],
             "manifestBlob": host["receipt"]["manifestBlob"], "registrationCopy": registration,
             "verdict": host["review"], "checksAtHead": host["checks"],
             "hostVerify": {"libHash": host["libHash"], "result": host["result"]},
             "acceptanceFingerprint": ac["fingerprint"], "criteriaCovered": covered,
             "remainingAtRecord": [], "primaryIssue": primary, "issues": issues,
             "reservation": "VERIFIED" if registration else "ATTESTED",
             "jiraIssues": [i for i in unit["issues"] if i["system"] == "jira"],
             "binding": host.get("binding", {"jira": {"status": "UNBOUND"}})}
    with locked(state / "work-units", ".completion.lock"):
        prior = records(state, "audits")
        audit["remainingAtRecord"] = remaining(ac, prior + [audit], issue=primary)
        path = state / "work-units/audits" / (audit_id + ".json")
        if not path.exists():
            durable_write(path, audit, exclusive=True)
        else:
            stored = read_record(path)
            if any(stored[k] != audit[k] for k in ("mergeSha", "finalHead", "manifestBlob", "acceptanceFingerprint")):
                raise ValueError("AUDIT_ID_COLLISION")
            audit = stored
        _ensure_intents(state, audit)
        durable_check(state, audit_id)
    return audit


def _ensure_intents(state, audit):
    for intent in build_intents(audit):
        path = intent_path(state, intent["intentId"])
        if not path.exists():
            durable_write(path, intent, exclusive=True)


def durable_check(state, audit_id):
    audit = read_record(state / "work-units/audits" / (audit_id + ".json"))
    for intent in build_intents(audit):
        stored = read_record(intent_path(state, intent["intentId"]))
        if stored["auditId"] != audit_id or stored["intentId"] != intent["intentId"]:
            raise ValueError("INTENT_NOT_DURABLE")
    return True


def scan(state):
    with locked(state / "work-units", ".completion.lock"):
        for audit in records(state, "audits"):
            _ensure_intents(state, audit)
            durable_check(state, audit["auditId"])
    return status(state)


def status(state):
    return {"deployment": "SOURCE_ONLY/PENDING_DEPLOYMENT", "bundleHash": bundle_hash(),
            "audits": len(records(state, "audits")), "outbox": records(state, "outbox"),
            "quarantined": [str(p.relative_to(state / "work-units/quarantine")) for p in
                            sorted((state / "work-units/quarantine").glob("*/*.reason.json"))]}


def noncompletion_report(state, raw, registration, reason):
    """REPORT retirement evidence grants no acceptance credit or remote intent."""
    identity = digest([registration["repo"], raw["number"], raw["head"]["sha"]])
    report = {"schema": "wu-noncompletion/1", "result": "NOT_COMPLETED", "mode": "report",
              "repo": registration["repo"], "pr": raw["number"], "head": raw["head"]["sha"],
              "reason": str(reason), "automaticCompletion": False, "bundleHash": bundle_hash()}
    durable_write(state / "work-units/noncompletion" / (identity + ".json"), report)
    return identity


def attest(state, audit_id, evidence):
    if not evidence.get("approvedBy") or not evidence.get("reason") or not evidence.get("head") or (
            evidence.get("bundleHash") != bundle_hash() or not evidence.get("manifestBlob")):
        raise ValueError("ATTESTATION_EVIDENCE")
    if audit_id:
        audit = read_record(state / "work-units/audits" / (audit_id + ".json"))
        if evidence["head"] != audit["finalHead"] or evidence.get("acFingerprint") != audit["acceptanceFingerprint"]:
            raise ValueError("ATTESTATION_IDENTITY")
    else:
        audit_id = digest([evidence["head"], evidence["manifestBlob"], evidence.get("acFingerprint")])
    path = state / "work-units/attestations" / (audit_id + ".json")
    durable_write(path, evidence, exclusive=True)
    return {"status": "MANUAL_ATTESTATION_RECORDED", "attestationId": audit_id, "automaticClosure": False}


def writer_authorized(state):
    path = state / "work-units/writer-host.json"
    if not path.is_file() or path.is_symlink():
        return False
    config = read_record(path)
    return (config.get("host") == socket.gethostname() and config.get("bundleHash") == bundle_hash()
            and config.get("approvedBy") and config.get("enabled") is True)


def drain(state, reader, *, writer=None, enabled=False, now=None):
    writer = writer or GitHubWriter(reader, enabled=enabled)
    now = time.time() if now is None else now
    with locked(state / "work-units", ".drain.lock"):
        for intent in records(state, "outbox"):
            if intent["state"] in {"DONE", "MANUAL", "PENDING_PARENT_CONNECTOR"} or intent["action"] != "comment":
                continue
            path = intent_path(state, intent["intentId"])
            if intent.get("nextAttemptAt", 0) > now:
                continue
            if not enabled:
                intent["state"] = "DRY_RUN"
                durable_write(path, intent)
                continue
            if not writer_authorized(state):
                intent.update(state="PENDING_AUTH", lastError="WRITER_HOST_UNVERIFIED")
                durable_write(path, intent)
                continue
            try:
                audit = read_record(state / "work-units/audits" / (intent["auditId"] + ".json"))
                if intent["target"] not in audit["issues"] or intent["body"].split("\n", 1)[0] != marker(audit):
                    raise ValueError("INTENT_AUDIT_MISMATCH")
                repo, number = intent["target"].rsplit("#", 1)
                endpoint = f"repos/{repo}/issues/{number}"
                before = reader.get(endpoint)
                ac = parse_ac(before.get("body", ""))
                if ac["fingerprint"] != intent["acFingerprint"]:
                    raise ValueError("AC_DRIFT")
                actor = reader.get("user")["login"]
                existing = [c for c in reader.pages(endpoint + "/comments?per_page=100")
                            if marker(audit) in c.get("body", "") and c.get("user", {}).get("login") == actor]
                intent["attempts"] += 1
                if not existing:
                    writer.write(endpoint + "/comments", {"body": intent["body"]})
                back = [c for c in reader.pages(endpoint + "/comments?per_page=100")
                        if marker(audit) in c.get("body", "") and c.get("user", {}).get("login") == actor]
                after = parse_ac(reader.get(endpoint).get("body", ""))
                if after["fingerprint"] != ac["fingerprint"]:
                    raise ValueError("AC_DRIFT")
                if not back:
                    raise ValueError("WRITE_READBACK_MISSING")
                intent.update(state="DONE", lastError=None, doneEvidence={
                    "remoteId": back[0]["id"], "readBackAt": datetime.now(timezone.utc).isoformat(),
                    "grade": "github-comment-readback"})
            except ApiError as exc:
                intent.update(state="PENDING_AUTH" if exc.status in {0, 401, 403} else "PENDING",
                              lastError=str(exc), nextAttemptAt=now + (
                                  exc.retry_after if exc.status == 429 and exc.retry_after else
                                  min(3600, 30 * 2 ** min(intent["attempts"], 7))))
            except (ValueError, OSError, KeyError, subprocess.TimeoutExpired) as exc:
                intent.update(state="MANUAL" if str(exc) == "AC_DRIFT" else "PENDING",
                              lastError=str(exc), nextAttemptAt=now + 60)
            durable_write(path, intent)
    return status(state)


def export_jira(state):
    return [i for i in records(state, "outbox") if i.get("schema") == "wu-jira-intent/1" and i["state"] != "DONE"]


def ingest_receipt(state, receipt):
    path = intent_path(state, receipt.get("intentId", ""))
    with locked(state / "work-units", ".drain.lock"):
        intent = read_record(path)
        if intent.get("schema") != "wu-jira-intent/1":
            raise ValueError("JIRA_INTENT_SCHEMA")
        evidence = JiraAdapter.validate_receipt(intent, receipt)
        intent.update(state="DONE", doneEvidence=evidence)
        durable_write(path, intent)
    return intent
