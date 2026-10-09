"""Existing gh reads; writes default to dry-run. No credential discovery."""
import json
import os
import re
import subprocess
from .schema import json_data


class ApiError(ValueError):
    def __init__(self, status=0, retry_after=None):
        self.status = status
        self.retry_after = retry_after
        super().__init__("UNKNOWN_AUTH" if status in {0, 401, 403} else f"HTTP_{status}")


def response_data(done):
    """Consume public response metadata internally, never print auth headers."""
    payload = done.stdout
    retry_after = None
    status = 0
    if payload.startswith(b"HTTP/"):
        separator = b"\r\n\r\n" if b"\r\n\r\n" in payload else b"\n\n"
        headers, payload = payload.split(separator, 1)
        match = re.match(rb"HTTP/\S+ ([0-9]{3})", headers)
        status = int(match[1]) if match else 0
        retry = re.search(rb"(?im)^retry-after:\s*([0-9]+)", headers)
        retry_after = int(retry[1]) if retry else None
    if done.returncode or status >= 400:
        match = re.search(rb"HTTP ([0-9]{3})", done.stderr)
        raise ApiError(status or (int(match[1]) if match else 0), retry_after)
    return json_data(payload)


class GitHubReader:
    def __init__(self, get=None, gh=None):
        self.callback = get
        self.gh = gh or os.environ.get("PR_LOOP_GH_BIN", "gh")

    def get(self, path):
        if self.callback:
            return self.callback(path)
        result = subprocess.run([self.gh, "api", "--include", path], capture_output=True, timeout=60)
        return response_data(result)

    def pages(self, path, *, key=None, max_pages=100):
        result = []
        separator = "&" if "?" in path else "?"
        for page in range(1, max_pages + 1):
            data = self.get(f"{path}{separator}page={page}")
            rows = data[key] if key else data
            if not isinstance(rows, list):
                raise ValueError("GITHUB_PAGE_SCHEMA")
            result.extend(rows)
            if len(rows) < 100:
                return result
        raise ValueError("HOST_VERIFY_TOO_WIDE")

    def download(self, path):
        result = subprocess.run([self.gh, "api", path], capture_output=True, timeout=60)
        if result.returncode:
            match = re.search(rb"HTTP ([0-9]{3})", result.stderr)
            raise ApiError(int(match[1]) if match else 0)
        if len(result.stdout) > 50 * 1024 * 1024:
            raise ValueError("HOST_VERIFY_TOO_WIDE")
        return result.stdout


class GitHubWriter:
    def __init__(self, reader, *, enabled=False):
        self.reader = reader
        self.enabled = enabled

    def write(self, path, payload, method="POST"):
        if not self.enabled:
            return {"status": "DRY_RUN"}
        done = subprocess.run([self.reader.gh, "api", "--include", "--method", method, path, "--input", "-"],
                              input=json.dumps(payload).encode(), capture_output=True, timeout=60)
        return response_data(done)


class JiraAdapter:
    """MVP emits parent comment intents; no guessed instance or transitions."""
    def __init__(self, binding):
        self.binding = binding

    def intent(self, audit, issue, body):
        binding = self.binding.get("jira", {})
        if binding.get("status") != "VERIFIED":
            return {"state": "PENDING_AUTH", "reason": "PENDING_BINDING"}
        key = issue.get("key", "")
        if (issue.get("instanceHost") != binding["instanceHost"] or
                not key.startswith(binding["projectKey"] + "-") or not issue.get("issueId")):
            raise ValueError("JIRA_IDENTITY_MISMATCH")
        return {"schema": "wu-jira-intent/1", "intentId": audit["auditId"] + ":jira:" + key,
                "unitId": audit["unitId"], "mergeSha": audit["mergeSha"],
                "binding": {k: binding[k] for k in ("instanceHost", "projectKey")},
                "issueKey": key, "expectedIssueId": str(issue["issueId"]), "action": "comment",
                "body": body, "acFingerprint": audit["acceptanceFingerprint"],
                "remainingCriteria": audit["remainingAtRecord"], "state": "PENDING_PARENT_CONNECTOR"}

    @staticmethod
    def validate_receipt(intent, receipt):
        expected = {"intentId": intent["intentId"], "issueKey": intent["issueKey"],
                    "issueId": intent["expectedIssueId"], "instanceHost": intent["binding"]["instanceHost"]}
        if any(str(receipt.get(k, "")) != str(v) for k, v in expected.items()):
            raise ValueError("JIRA_RECEIPT_MISMATCH")
        back = receipt.get("readBack", {})
        if (not receipt.get("performedAt") or not receipt.get("remoteCommentId") or
                back.get("markerFound") is not True or back.get("issueKeyEcho") != intent["issueKey"] or
                not isinstance(back.get("statusName"), str) or not back["statusName"]):
            raise ValueError("JIRA_READBACK_REQUIRED")
        return {"remoteId": receipt["remoteCommentId"], "readBackAt": receipt["performedAt"],
                "grade": "parent-connector-readback"}
