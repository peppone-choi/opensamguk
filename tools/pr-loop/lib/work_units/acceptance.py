"""Authoritative AC blocks, stable fingerprints and partial acceptance."""
import hashlib
import re

BLOCK = re.compile(r"<!-- work-unit-ac v1 -->(.*?)<!-- /work-unit-ac -->", re.S)


def normalize(text):
    return "\n".join(line.rstrip() for line in text.replace("\r\n", "\n").splitlines()
                     if line.strip())


def parse_ac(body, *, legacy=False):
    blocks = BLOCK.findall(body or "")
    if not blocks:
        if not legacy:
            raise ValueError("AC_MIGRATION_PENDING")
        text = normalize(body or "")
        if not text:
            raise ValueError("AC_EMPTY")
        return {"mode": "legacy", "fingerprint": "sha256:" + hashlib.sha256(text.encode()).hexdigest(),
                "criteria": ["LEGACY-WHOLE"], "checks": {}, "priority": 3, "depends": [],
                "inputs": [], "legacyAc": True}
    if len(blocks) != 1:
        raise ValueError("AC_AMBIGUOUS")
    text = normalize(blocks[0])
    rows = re.findall(r"^- (AC-[1-9][0-9]*)(?: \[check: ([^\]]+)\])?:\s*(.+)$", text, re.M)
    if len(re.findall(r"^- ", text, re.M)) != len(rows):
        raise ValueError("AC_CRITERIA_MALFORMED")
    criteria = [row[0] for row in rows]
    if not criteria or len(criteria) != len(set(criteria)):
        raise ValueError("AC_CRITERIA")
    priorities = re.findall(r"^priority: P([0-3])$", text, re.M)
    if len(priorities) > 1 or len(re.findall(r"^priority:", text, re.M)) != len(priorities):
        raise ValueError("AC_PRIORITY")
    def members(key):
        found = re.findall(rf"^{key}:\s*(.+)$", text, re.M)
        if len(found) > 1:
            raise ValueError("AC_FIELD_AMBIGUOUS")
        return [x.strip() for x in found[0].split(",")] if found else []
    deps = members("depends")
    if any(not re.fullmatch(r"#[1-9][0-9]*", x) for x in deps):
        raise ValueError("DEPENDENCY_REPOSITORY")
    inputs = members("inputs")
    if any(not re.fullmatch(r"[a-z][A-Za-z0-9]*\.[A-Za-z0-9]+", x) for x in inputs):
        raise ValueError("AC_INPUTS")
    checks = {identity: check for identity, check, _ in rows if check}
    if any(not re.fullmatch(r"gap-closed [a-z][A-Za-z0-9]*\.[A-Za-z0-9]+ [A-Z_]+", c)
           for c in checks.values()):
        raise ValueError("AC_CHECK_UNSUPPORTED")
    return {"mode": "block", "fingerprint": "sha256:" + hashlib.sha256(text.encode()).hexdigest(),
            "criteria": criteria, "checks": checks, "priority": int(priorities[0]) if priorities else 3,
            "depends": [int(d[1:]) for d in deps], "inputs": inputs, "legacyAc": False}


def remaining(ac, audits, *, issue):
    covered = set()
    for audit in audits:
        if audit.get("acceptanceFingerprint") == ac["fingerprint"] and issue in audit.get("issues", []):
            covered.update(audit.get("criteriaCovered", []))
    return [c for c in ac["criteria"] if c not in covered]
