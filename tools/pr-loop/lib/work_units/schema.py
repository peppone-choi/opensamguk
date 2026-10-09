"""Bounded data validation and durable local records; never load head code."""
import fcntl
import hashlib
import json
import os
import re
import tempfile
from contextlib import contextmanager
from pathlib import Path, PurePosixPath
from datetime import datetime, timezone

SHA = re.compile(r"[0-9a-f]{40}\Z")
SLUG = re.compile(r"[a-z0-9][a-z0-9._-]{0,127}\Z")
REPO = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\Z")
MAX_BLOB = 2 * 1024 * 1024
STAGES = ("PLANNED", "DOMAIN_READY", "HANDLER_READY", "UI_READY", "AI_READY",
          "HELP_READY", "TUTORIAL_READY", "REPLAY_READY", "VERIFIED")


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                    ensure_ascii=False).encode()).hexdigest()


def safe_path(path):
    if not isinstance(path, str) or not path or "\\" in path or "\0" in path:
        raise ValueError("UNSAFE_PATH")
    p = PurePosixPath(path)
    if p.is_absolute() or any(x in {"", ".", ".."} for x in path.split("/")):
        raise ValueError("UNSAFE_PATH")
    if any(x.startswith(".env") or x in {".aws", ".codex"} for x in p.parts):
        raise ValueError("SENSITIVE_PATH")
    return path


def json_data(payload, *, max_size=MAX_BLOB):
    if len(payload) > max_size:
        raise ValueError("BLOB_TOO_LARGE")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("DUPLICATE_JSON_KEY")
            result[key] = value
        return result
    return json.loads(payload, object_pairs_hook=unique)


def issue_ref(value, repo=None):
    match = re.fullmatch(r"([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)#([1-9][0-9]*)", value or "")
    if not match or (repo and match[1] != repo):
        raise ValueError("ISSUE_REPOSITORY_MISMATCH")
    return {"system": "github", "repo": match[1], "number": int(match[2])}


def binding(data):
    if data.get("schemaVersion") != 1 or data.get("mode") not in {"report", "enforce"}:
        raise ValueError("BINDING_SCHEMA")
    if not REPO.fullmatch(data.get("repo", "")):
        raise ValueError("BINDING_REPOSITORY")
    jira = data.get("jira", {})
    if jira.get("status") not in {"UNBOUND", "VERIFIED"}:
        raise ValueError("JIRA_BINDING_STATUS")
    if jira["status"] == "VERIFIED":
        if not re.fullmatch(r"[a-z0-9.-]+", jira.get("instanceHost", "")) or not re.fullmatch(
                r"[A-Z][A-Z0-9_]+", jira.get("projectKey", "")):
            raise ValueError("JIRA_BINDING_IDENTITY")
        if not jira.get("verifiedAt") or not jira.get("verifiedBy"):
            raise ValueError("JIRA_BINDING_UNVERIFIED")
    if not isinstance(data.get("explicitExemptions"), list):
        raise ValueError("EXEMPTIONS_SCHEMA")
    if data.get("catalogAdapter") != "opensamguk-input-catalog-v5":
        raise ValueError("CATALOG_ADAPTER_UNSUPPORTED")
    return data


def explicit_exemption(policy, *, repo, pr, head, now=None):
    now = now or datetime.now(timezone.utc)
    for entry in policy["explicitExemptions"]:
        if entry.get("repo") != repo or entry.get("pr") != pr or entry.get("head") != head:
            continue
        if not entry.get("reason") or not entry.get("approvedBy") or not entry.get("expiresAt"):
            raise ValueError("EXEMPTION_EVIDENCE")
        expiry = datetime.fromisoformat(entry["expiresAt"].replace("Z", "+00:00"))
        if expiry.tzinfo is None or expiry <= now:
            raise ValueError("EXEMPTION_EXPIRED")
        return dict(entry, automaticCompletion=False)
    return None


def manifest(data, repo):
    if data.get("schemaVersion") != 1 or not SLUG.fullmatch(data.get("unitId", "")):
        raise ValueError("MANIFEST_SCHEMA")
    branch = data.get("branch", "")
    if not isinstance(branch, str) or not branch or branch.startswith("-") or ".." in branch:
        raise ValueError("MANIFEST_BRANCH")
    issues = data.get("issues", [])
    if not issues:
        raise ValueError("ISSUE_REQUIRED")
    seen = set()
    for issue in issues:
        if issue.get("system") == "github":
            parsed = issue_ref(f"{issue.get('repo')}#{issue.get('number')}", repo)
            identity = (parsed["repo"], parsed["number"])
        elif issue.get("system") == "jira":
            if not re.fullmatch(r"[A-Z][A-Z0-9_]+-[1-9][0-9]*", issue.get("key", "")):
                raise ValueError("JIRA_ISSUE_IDENTITY")
            identity = ("jira", issue["key"])
        else:
            raise ValueError("ISSUE_SYSTEM")
        if identity in seen:
            raise ValueError("DUPLICATE_ISSUE")
        seen.add(identity)
    ac = data.get("acceptance", {})
    if ac.get("mode") not in {"block", "legacy"} or not re.fullmatch(
            r"sha256:[0-9a-f]{64}", ac.get("fingerprint", "")):
        raise ValueError("AC_SCHEMA")
    if not isinstance(ac.get("source"), str) or not ac["source"]:
        raise ValueError("AC_SOURCE")
    criteria = ac.get("criteria", [])
    if not criteria or len(set(criteria)) != len(criteria) or not all(
            re.fullmatch(r"AC-[1-9][0-9]*|LEGACY-WHOLE", c) for c in criteria):
        raise ValueError("AC_CRITERIA")
    impact = data.get("commandImpact", {})
    if impact.get("result") not in {"NONE", "DIRECT", "BROAD"}:
        raise ValueError("IMPACT_RESULT")
    for key in ("inputIds", "scopes"):
        if not isinstance(impact.get(key), list) or not all(isinstance(x, str) for x in impact[key]):
            raise ValueError("IMPACT_SCHEMA")
        if len(set(impact[key])) != len(impact[key]):
            raise ValueError("DUPLICATE_IMPACT")
    if not isinstance(impact.get("sammo"), bool):
        raise ValueError("LEGACY_IMPACT_REQUIRED")
    if impact["result"] == "NONE" and (impact["inputIds"] or impact["scopes"] or impact["sammo"]):
        raise ValueError("NONE_IMPACT_HAS_COMMANDS")
    if impact["result"] != "NONE" and impact.get("na") is not None:
        raise ValueError("NA_REQUIRES_NONE")
    if not isinstance(data.get("qa"), dict) or not isinstance(data.get("qaNa"), dict):
        raise ValueError("QA_SCHEMA")
    verification = data.get("verification", {})
    if verification.get("results") not in {"NOT_RUN", "PASS", "FAIL", "PARTIAL"}:
        raise ValueError("VERIFICATION_SCHEMA")
    for key in ("commands", "skips", "limitations"):
        if not isinstance(verification.get(key), list):
            raise ValueError("VERIFICATION_SCHEMA")
    if not re.fullmatch(r"[0-9a-f]{64}", data.get("reservation", {}).get("nonceSha256", "")):
        raise ValueError("RESERVATION_SCHEMA")
    return data


def registration(data):
    if data.get("version") not in {1, 2} or not data.get("nonce"):
        raise ValueError("REGISTRATION_SCHEMA")
    if data["version"] == 2:
        if not SLUG.fullmatch(data.get("unitId", "")) or not isinstance(data.get("lease"), dict):
            raise ValueError("REGISTRATION_V2_SCHEMA")
        if data.get("issue"):
            issue_ref(data["issue"], data["repo"])
            if not re.fullmatch(r"sha256:[0-9a-f]{64}", data.get("acFingerprint", "")):
                raise ValueError("REGISTRATION_AC")
    return data


def receipt(data):
    if data.get("schema") != "wu-ci-receipt/1" or data.get("status") not in {
            "BOOTSTRAP_NOT_ENFORCED", "REPORT_ONLY", "ENFORCED"}:
        raise ValueError("RECEIPT_SCHEMA")
    if not all(SHA.fullmatch(data.get(k, "")) for k in ("baseSha", "headSha")):
        raise ValueError("RECEIPT_IDENTITY")
    if not isinstance(data.get("requiredLanes"), dict) or not isinstance(data.get("reasons"), list):
        raise ValueError("RECEIPT_FIELDS")
    return data


def audit(data):
    if data.get("schema") != "wu-audit/1" or not re.fullmatch(r"[0-9a-f]{64}", data.get("auditId", "")):
        raise ValueError("AUDIT_SCHEMA")
    if not all(SHA.fullmatch(data.get(k, "")) for k in ("finalHead", "mergeSha", "manifestBlob")):
        raise ValueError("AUDIT_IDENTITY")
    if data.get("hostVerify", {}).get("result") != "PASS" or not data.get("mergedAt"):
        raise ValueError("AUDIT_PROOF")
    if not data.get("criteriaCovered") or not isinstance(data.get("remainingAtRecord"), list):
        raise ValueError("AUDIT_CRITERIA")
    return data


def outbox(data):
    if data.get("state") not in {"PENDING", "PENDING_AUTH", "DRY_RUN",
                                "PENDING_PARENT_CONNECTOR", "DONE", "MANUAL"}:
        raise ValueError("OUTBOX_STATE")
    if data.get("action") not in {"close", "comment", "create-issue"} or not data.get("intentId"):
        raise ValueError("OUTBOX_ACTION")
    if data["state"] == "DONE":
        evidence = data.get("doneEvidence", {})
        if not evidence.get("remoteId") or not evidence.get("readBackAt") or not evidence.get("grade"):
            raise ValueError("OUTBOX_DONE_REQUIRES_READBACK")
    return data


def ensure_directory(path):
    path = Path(path)
    for parent in [path, *path.parents]:
        if parent.is_symlink():
            raise ValueError("UNSAFE_STATE_SYMLINK")
    path.mkdir(parents=True, exist_ok=True)
    return path


@contextmanager
def locked(root, name=".lock"):
    root = ensure_directory(root)
    lock = root / name
    fd = os.open(lock, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "a+") as handle:
        fcntl.flock(handle, fcntl.LOCK_EX)
        yield


def durable_write(path, data, *, exclusive=False):
    path = Path(path)
    ensure_directory(path.parent)
    if path.is_symlink():
        raise ValueError("UNSAFE_STATE_SYMLINK")
    payload = (json.dumps(data, sort_keys=True, ensure_ascii=False) + "\n").encode()
    if exclusive:
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "wb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
    else:
        fd, temporary = tempfile.mkstemp(prefix=".wu-", dir=path.parent)
        try:
            with os.fdopen(fd, "wb") as handle:
                os.fchmod(handle.fileno(), 0o600)
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
    fd = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def read_record(path):
    path = Path(path)
    if path.is_symlink() or path.stat().st_size > MAX_BLOB:
        raise ValueError("UNSAFE_STATE_RECORD")
    data = json_data(path.read_bytes())
    if data.get("schema") == "wu-audit/1":
        return audit(data)
    if "intentId" in data and "state" in data:
        return outbox(data)
    return data
