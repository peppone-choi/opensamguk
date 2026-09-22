"""Exact local provenance for exploratory simulator output; never a release gate."""
from __future__ import annotations

import hashlib
import json
import subprocess
from pathlib import Path


def snapshot(root: Path, paths: list[Path]) -> dict:
    names = sorted({p.relative_to(root).as_posix() for p in paths})
    hashes = {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in names}
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    changed = subprocess.check_output(
        ['git', 'status', '--porcelain', '--untracked-files=all', '--', *names], cwd=root, text=True,
    ).splitlines()
    return {'sourceRevision': revision, 'sourceFiles': hashes,
            'sourceChanges': changed}


def evidence(root: Path, paths: list[Path], before: dict, result: object) -> dict:
    # Refuse a result if files/revision changed while the simulator was running.
    if snapshot(root, paths) != before:
        raise ValueError('simulation sources changed during calculation; rerun on a stable checkout')
    # Hash the JSON value readers receive, not Python's numeric-key ordering.
    def unique_object(pairs):
        values = {}
        for key, value in pairs:
            if key in values:
                raise ValueError(f'duplicate JSON key after encoding: {key!r}')
            values[key] = value
        return values

    result = json.loads(json.dumps(result, ensure_ascii=False, allow_nan=False),
                        object_pairs_hook=unique_object)
    canonical = json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)
    return {'schemaVersion': 1, 'status': 'EXPLORATORY', 's2GatePassed': False,
            **before, 'resultSha256': hashlib.sha256(canonical.encode('utf-8')).hexdigest(),
            'result': result}
