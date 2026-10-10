"""Verify bounded local generation receipts; only propose cleanup for approval."""
import hashlib
import os
import stat
from pathlib import Path
from work_units.schema import read_record, SHA, SLUG
from .contracts import paths, timestamp, covers

MAX_ARTIFACT_BYTES = 50 * 1024 * 1024


def local_path(root, relative):
    paths([relative])
    parts = Path(relative).parts
    if any(p.casefold() in {".git", ".ssh", "auth", "credentials", "id_rsa", "id_ed25519"} or
           p.casefold().endswith((".env", ".key", ".pem")) for p in parts):
        raise ValueError("SENSITIVE_ARTIFACT_PATH")
    root = Path(root).resolve(strict=True)
    path = root / relative
    for component in [path, *path.parents]:
        if component == root:
            break
        if component.is_symlink():
            raise ValueError("ARTIFACT_SYMLINK")
    return path


def inventory(items, root, prefixes, active_owners, now):
    rows = []
    if not isinstance(items, list):
        items = [items]
    for item in items:
        row = {"path": item.get("path") if isinstance(item, dict) else None,
               "status": "OWNERSHIP_HELD", "cleanupCandidate": False}
        try:
            if not isinstance(item, dict):
                raise ValueError("ARTIFACT_OBJECT_REQUIRED")
            if root is None:
                raise ValueError("ARTIFACT_ROOT_NOT_SUPPLIED")
            paths([item["path"]])
            paths(prefixes)
            if not any(covers(prefix, item["path"]) for prefix in prefixes):
                raise ValueError("NOT_DECLARED_GENERATED_PATH")
            if not isinstance(item.get("owner"), str) or not isinstance(item.get("head"), str):
                raise ValueError("ARTIFACT_OWNER_HEAD")
            parts = item["owner"].split("/")
            if len(parts) != 2 or not all(SLUG.fullmatch(p) for p in parts) or not SHA.fullmatch(item["head"]):
                raise ValueError("ARTIFACT_OWNER_HEAD")
            artifact = local_path(root, item["path"])
            receipt = read_record(local_path(root, item["receipt"]))
            if not isinstance(receipt, dict):
                raise ValueError("GENERATION_RECEIPT_OBJECT_REQUIRED")
            generator = receipt.get("generator")
            if (receipt.get("schema") != "generated-artifact/1" or
                    any(receipt.get(k) != item[k] for k in ("path", "owner", "head")) or
                    not isinstance(generator, dict) or
                    not all(isinstance(generator.get(k), str) and generator[k].strip() for k in ("name", "version")) or
                    not isinstance(receipt.get("regenerate"), list) or not receipt["regenerate"] or
                    not all(isinstance(arg, str) and arg for arg in receipt["regenerate"])):
                raise ValueError("GENERATION_RECEIPT_MISMATCH")
            created, expires = timestamp(receipt["createdAt"]), timestamp(receipt["expiresAt"])
            if created > now or expires < created:
                raise ValueError("ARTIFACT_LIFETIME")
            checksum = hashlib.sha256()
            fd = os.open(artifact, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
            with os.fdopen(fd, "rb") as data:
                info = os.fstat(data.fileno())
                if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_ARTIFACT_BYTES:
                    raise ValueError("ARTIFACT_SIZE_OR_TYPE")
                total = 0
                for chunk in iter(lambda: data.read(1024 * 1024), b""):
                    total += len(chunk)
                    if total > MAX_ARTIFACT_BYTES:
                        raise ValueError("ARTIFACT_GREW_BEYOND_LIMIT")
                    checksum.update(chunk)
            if receipt.get("sha256") != checksum.hexdigest():
                raise ValueError("ARTIFACT_CONTENT_CHANGED")
            row.update(owner=item["owner"], head=item["head"], ownershipGrade="LOCAL_RECEIPT_MATCHED",
                       generator=receipt["generator"], expiresAt=receipt["expiresAt"],
                       regenerate=receipt["regenerate"], status="RETAIN")
            if active_owners is None:
                row.update(status="RETAIN_ACTIVE_OWNERS_UNKNOWN", nextAction="Resolve active lease owner identities first")
            elif expires <= now and item["owner"] not in active_owners:
                row.update(status="CLEANUP_PROPOSAL", cleanupCandidate=True, requiresApproval=True,
                           nextAction="Review owner, retention and regeneration before separately authorized cleanup")
        except (ValueError, KeyError, OSError, TypeError, AttributeError) as exc:
            row.update(reason=str(exc), nextAction="Supply matching generation/owner receipt; retain the original")
        rows.append(row)
    return rows
