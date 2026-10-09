"""Single-host atomic issue/input/scope leases. No global lock claim."""
from datetime import datetime, timezone
import hashlib
from contextlib import nullcontext
from .schema import digest, durable_write, locked, read_record, SLUG


def conflicts(left, right):
    if set(left.get("issues", [])) & set(right.get("issues", [])):
        return True
    if set(left.get("inputs", [])) & set(right.get("inputs", [])):
        return True
    for a, b in ((left, right), (right, left)):
        if a.get("unknownScope") and (b.get("inputs") or b.get("scopes")):
            return True
        if "ALL_INPUTS" in a.get("scopes", []) and (b.get("inputs") or b.get("scopes")):
            return True
        if "ALL_UI_INPUTS" in a.get("scopes", []) and (b.get("inputs") or b.get("scopes")):
            return True
        if set(a.get("scopes", [])) & set(b.get("scopes", [])):
            return True
        for scope in a.get("scopes", []):
            if scope.endswith(".*") and any(i.startswith(scope[:-1]) for i in b.get("inputs", [])):
                return True
    return False


def active_leases(state):
    root = state / "work-units/leases"
    if not root.exists():
        return []
    return [read_record(p) for p in sorted(root.glob("*.json"))]


def acquire(state, unit_id, nonce, lease, *, dry_run=False, replace=False):
    if not SLUG.fullmatch(unit_id):
        raise ValueError("CLAIM_UNIT_ID")
    root = state / "work-units"
    with (nullcontext() if dry_run else locked(root)):
        path = root / "leases" / (unit_id + ".json")
        proposed = dict(lease, unitId=unit_id, nonceSha256=hashlib.sha256(nonce.encode()).hexdigest())
        for old in active_leases(state):
            if old["unitId"] == unit_id:
                if replace and old["nonceSha256"] == proposed["nonceSha256"]:
                    continue
                if any(old.get(k) != proposed.get(k) for k in ("nonceSha256", "issues", "inputs", "scopes", "unknownScope")):
                    raise ValueError("CLAIM_REUSE_DIFFERS")
                return old
            if conflicts(old, proposed):
                raise ValueError("LEASE_CONFLICT:" + old["unitId"])
        proposed["acquired_at"] = datetime.now(timezone.utc).isoformat()
        if not dry_run:
            durable_write(path, proposed, exclusive=not replace)
        return proposed


def lease_covers(lease, impact):
    if impact["result"] == "UNRESOLVED":
        return False
    if "ALL_INPUTS" in lease.get("scopes", []):
        return True
    if not set(impact["scopes"]) <= set(lease.get("scopes", [])):
        return False
    for identity in impact["inputIds"]:
        if identity not in lease.get("inputs", []) and not any(
                s.endswith(".*") and identity.startswith(s[:-1]) for s in lease.get("scopes", [])):
            return False
    return not impact.get("sammo") or lease.get("sammo", False)


def release(state, unit_id, nonce):
    with locked(state / "work-units"):
        path = state / "work-units/leases" / (unit_id + ".json")
        if path.exists():
            if read_record(path)["nonceSha256"] != hashlib.sha256(nonce.encode()).hexdigest():
                raise ValueError("LEASE_OWNER")
            path.unlink()
