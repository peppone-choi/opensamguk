"""Single-host atomic issue/input/scope leases. No global lock claim."""
from datetime import datetime, timezone
import hashlib
from contextlib import nullcontext
from .schema import durable_write, locked, read_record, lease_key, scoped_lease, registration, REPO


def conflicts(left, right):
    if set(left.get("issues", [])) & set(right.get("issues", [])):
        return True
    if left.get("repo") and right.get("repo") and left["repo"] != right["repo"]:
        return False
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
    leases = {}
    for path in sorted(root.glob("*.json")):
        data = scoped_lease(state, path)
        prior = leases.get(data["leaseKey"])
        if prior is not None and prior != data:
            raise ValueError("LEGACY_LEASE_TARGET_DIFFERS")
        leases[data["leaseKey"]] = data
    return list(leases.values())


def migrate_lease_files(state):
    """Called under the lease lock. Prove all owners before publishing anything."""
    leases = active_leases(state)
    for data in leases:
        root = state / "work-units/leases"
        old, target = root / (data["task"] + ".json"), root / (data["leaseKey"] + ".json")
        if not old.exists() or scoped_lease(state, old) != data:
            continue
        if target.exists():
            if read_record(target) != data:
                raise ValueError("LEGACY_LEASE_TARGET_DIFFERS")
        else:
            durable_write(target, data, exclusive=True)
        old.unlink()


def acquire(state, unit_id, nonce, lease, *, project, repo, dry_run=False, replace=False):
    return _acquire(state, unit_id, nonce, lease, project=project, repo=repo,
                    dry_run=dry_run, replace=replace)


def import_legacy(state, data, lease):
    owner = registration(read_record(state / "tasks" / (lease_key(data["project"], data["task"]) + ".json")))
    if owner != data or owner["version"] != 1 or owner["phase"] != "active":
        raise ValueError("LEGACY_IMPORT_ACTIVE_OWNER_REQUIRED")
    return _acquire(state, data["task"], data["nonce"], lease, project=data["project"],
                    repo=data["repo"], replace=True, import_existing=True)


def _acquire(state, unit_id, nonce, lease, *, project, repo, dry_run=False,
             replace=False, import_existing=False):
    key = lease_key(project, unit_id)
    if not REPO.fullmatch(repo or ""):
        raise ValueError("LEASE_REPOSITORY_REQUIRED")
    root = state / "work-units"
    with (nullcontext() if dry_run else locked(root)):
        if not dry_run:
            migrate_lease_files(state)
        path = root / "leases" / (key + ".json")
        proposed = dict(lease, unitId=unit_id, project=project, task=unit_id, repo=repo,
                        leaseKey=key, nonceSha256=hashlib.sha256(nonce.encode()).hexdigest())
        for old in active_leases(state):
            if old["leaseKey"] == key:
                if old["repo"] != repo:
                    raise ValueError("CLAIM_REUSE_DIFFERS")
                if replace and old["nonceSha256"] == proposed["nonceSha256"]:
                    continue
                if any(old.get(k) != proposed.get(k) for k in ("nonceSha256", "issues", "inputs", "scopes", "unknownScope", "sammo")):
                    raise ValueError("CLAIM_REUSE_DIFFERS")
                return old
            if not import_existing and conflicts(old, proposed):
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


def release(state, unit_id, nonce, *, project, repo):
    with locked(state / "work-units"):
        migrate_lease_files(state)
        path = state / "work-units/leases" / (lease_key(project, unit_id) + ".json")
        if path.exists():
            data = scoped_lease(state, path)
            if data["repo"] != repo or data["nonceSha256"] != hashlib.sha256(nonce.encode()).hexdigest():
                raise ValueError("LEASE_OWNER")
            path.unlink()
