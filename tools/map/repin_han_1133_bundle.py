#!/usr/bin/env python3
"""han-world-v3-1133 릴리스 번들을 제자리에서 재핀한다 / 현재 체크아웃과 맞는지 검사한다.

재핀은 **사용자 결정이 있을 때만** 한다(ADR-LITE-058·060). blob 해시가 `StrategicTopology.contentHash` 로
들어가므로, 재핀하면 1133 에 핀된 월드는 초기화 전까지 로드되지 않는다.

하는 일:
  1. 카탈로그의 14개 경로를 작업 트리 파일과 대조해, 바이트가 바뀐 것만 새 blob 으로 쓴다
     (gzip level 9, mtime 0, 파일명 없음 — 쓰기 전에 안 바뀐 blob 을 같은 방법으로 재현해 방법을 검증한다).
  2. 코틀린 상수 스냅샷(HanWorldV31133*)을 현재 생성물에서 다시 뜨고 runtime-constants.json 을 맞춘다.
  3. catalog.json 을 다시 쓰고 새 CATALOG_SHA256 · runtime-constants 해시를 출력한다
     (Han1133Artifacts.kt · HanRuntimeConstantsIntegrityTest.kt 의 핀은 사람이 옮긴다 — `--check` 가 대조한다).

사용:
    python3 tools/map/repin_han_1133_bundle.py --check
    python3 tools/map/repin_han_1133_bundle.py --write --source-base-commit <main 커밋>
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUNDLE = ROOT / "data/map/han-world-v3-1133-artifacts-v1"
CONSTANTS = "common/src/main/kotlin/opensamguk/common/constants"
SNAPSHOT_HEADER = "// Frozen 1133-identity release snapshot. Future generators must not overwrite.\n"
ARTIFACTS_KT = ROOT / "infra/src/main/kotlin/opensamguk/infra/seed/Han1133Artifacts.kt"
CONSTANTS_TEST = ROOT / "infra/src/test/kotlin/opensamguk/infra/seed/HanRuntimeConstantsIntegrityTest.kt"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def pack(data: bytes) -> bytes:
    out = io.BytesIO()
    with gzip.GzipFile(fileobj=out, mode="wb", compresslevel=9, mtime=0, filename="") as handle:
        handle.write(data)
    return out.getvalue()


def encode(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()


def plan(source_base_commit: str | None) -> dict[Path, bytes | None]:
    """경로 → 새 바이트(None 이면 삭제). 이미 맞는 파일은 넣지 않는다."""
    writes: dict[Path, bytes | None] = {}
    catalog = json.loads((BUNDLE / "catalog.json").read_bytes())
    kept: set[str] = set()
    for entry in catalog["files"]:
        old_blob = (BUNDLE / entry["blob"]).read_bytes()
        if entry["blob"] not in kept and pack(gzip.decompress(old_blob)) != old_blob:
            raise SystemExit(f"gzip 방법이 기존 blob 을 재현하지 못한다: {entry['blob']}")
        data = (ROOT / entry["path"]).read_bytes()
        if sha(data) == entry["sha256"]:
            kept.add(entry["blob"])
            continue
        packed = pack(data)
        stale = entry["blob"]
        entry.update(blob=f"blobs/{sha(data)}.json.gz", bytes=len(data),
                     compressedSha256=sha(packed), sha256=sha(data))
        writes[BUNDLE / entry["blob"]] = packed
        writes.setdefault(BUNDLE / stale, None)
        kept.add(entry["blob"])
    for blob in kept:
        if writes.get(BUNDLE / blob, b"") is None:
            del writes[BUNDLE / blob]
    if source_base_commit and writes:
        catalog["sourceBaseCommit"] = source_base_commit

    constants = json.loads((BUNDLE / "runtime-constants.json").read_bytes())
    for entry in constants["files"]:
        source = (ROOT / entry["source"]).read_text(encoding="utf-8")
        original = Path(entry["source"]).stem
        frozen = Path(entry["snapshot"]).stem
        snapshot = (SNAPSHOT_HEADER + source.replace(original, frozen)).encode()
        entry["sourceSha256"], entry["snapshotSha256"] = sha(source.encode()), sha(snapshot)
        if (ROOT / entry["snapshot"]).read_bytes() != snapshot:
            writes[ROOT / entry["snapshot"]] = snapshot
    for path, value in ((BUNDLE / "catalog.json", encode(catalog)),
                        (BUNDLE / "runtime-constants.json", encode(constants))):
        if path.read_bytes() != value:
            writes[path] = value
    return writes


def pinned(path: Path, pattern: str) -> str:
    match = re.search(pattern, path.read_text(encoding="utf-8"))
    if not match:
        raise SystemExit(f"{path.relative_to(ROOT)} 에서 핀을 찾지 못했다")
    return match.group(1)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--source-base-commit")
    args = parser.parse_args()

    writes = plan(args.source_base_commit)
    if args.write:
        for path, value in writes.items():
            if value is None:
                path.unlink()
            else:
                path.write_bytes(value)
        writes = plan(None)
    problems = [f"드리프트: {path.relative_to(ROOT)}" for path in writes]
    catalog_sha = sha((BUNDLE / "catalog.json").read_bytes())
    constants_sha = sha((BUNDLE / "runtime-constants.json").read_bytes())
    if pinned(ARTIFACTS_KT, r'CATALOG_SHA256 = "([0-9a-f]{64})"') != catalog_sha:
        problems.append(f"Han1133Artifacts.CATALOG_SHA256 != {catalog_sha}")
    if constants_sha not in CONSTANTS_TEST.read_text(encoding="utf-8"):
        problems.append(f"HanRuntimeConstantsIntegrityTest 1133 핀 != {constants_sha}")
    for problem in problems:
        print(problem, file=sys.stderr)
    print(f"catalog.json {catalog_sha}\nruntime-constants.json {constants_sha}")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
