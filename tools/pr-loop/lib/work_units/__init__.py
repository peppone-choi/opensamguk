"""Deployable, stdlib-only work-unit contracts. Installation is explicit."""
import hashlib
from pathlib import Path

WU_VERSION = 1


def bundle_hash(root=None):
    root = Path(root or Path(__file__).resolve().parents[2])
    paths = sorted(list(root.glob("lib/work_units/*.py")) +
                   [p for p in root.glob("bin/*") if p.name in {
                       "start-task", "finish-task", "task-lifecycle", "pr-loop",
                       "pr-loop-watch", "work-queue", "work-complete"}])
    digest = hashlib.sha256()
    for path in paths:
        if path.is_symlink():
            raise ValueError("unsafe installed bundle")
        digest.update(path.relative_to(root).as_posix().encode() + b"\0")
        digest.update(path.read_bytes())
    return digest.hexdigest()
