"""래칫 판정 공용 — naming_lint · web_copy_lint · web_ui_lint 가 함께 쓴다(2026-10-01, K0 · K10).

예전 규칙은 「실측 = 기준선 JSON 정확히 일치」였다. 고치면 같은 PR 에서 JSON 을 내려야 했다. 그래서 병합 하나가 같은 JSON 줄을
고친 열린 PR 전부를 충돌(DIRTY)로 만들었다(#1158 · #1163 · #1164 · #1174 · #1177).

지금 규칙:
- PR 은 「실측 ≤ min(기준선 JSON, 병합 기준 커밋의 실측)」이면 통과다. 병합 기준 실측은 `--base-ref` 커밋의 트리를
  꺼내 같은 스캔을 한 번 더 돌려 얻는다. 그래서 PR 이 JSON 을 고치지 않아도 되고, JSON 이 실측보다 높게 남아도 그 여유를 쓸 수 없다
  (앞 PR 이 2개 고치고 JSON 을 안 내린 뒤 새 PR 이 2개를 넣으면 빨갛다).
- 실측이 기준선보다 작으면 실패가 아니라 안내(NOTE)다. 기준선 내리기는 따로 한다 — `--write-baseline` 로 실측을 적는 래칫 PR.
- `--base-ref` 가 없으면(main push · 로컬) 한계는 기준선 JSON 이다.
- 맞바꿈(하나 고치고 다른 하나 추가)은 개수 규칙으로는 못 잡는다. 위반 목록 지문은 후속이다.
- 허용 목록(naming · web copy)이 병합 기준보다 늘면 안내(NOTE)를 찍는다 — 실패는 아니다. 늘린 줄은 리뷰어가 본다.
"""
from __future__ import annotations

import io
import json
import subprocess
import tarfile
import tempfile
from collections import Counter
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator, Mapping


def limits(baseline: Mapping[str, int], base: Mapping[str, int] | None, kinds: tuple[str, ...]) -> dict[str, int]:
    """종류마다 넘으면 안 되는 수 — min(기준선, 병합 기준 실측). 병합 기준이 없으면 기준선."""
    return {kind: baseline[kind] if base is None else min(baseline[kind], base.get(kind, 0)) for kind in kinds}


def judge(counts: Mapping[str, int], baseline: Mapping[str, int], base: Mapping[str, int] | None,
          kinds: tuple[str, ...], baseline_name: str) -> tuple[list[str], bool]:
    """(출력 줄, 실패 여부). 실패는 실측이 한계를 넘을 때뿐이다."""
    bound = limits(baseline, base, kinds)
    messages: list[str] = []
    failed = False
    for kind in kinds:
        current, limit, line = counts.get(kind, 0), bound[kind], baseline[kind]
        source = f"baseline {line}" if base is None else f"min(baseline {line}, base {base.get(kind, 0)})"
        if current > limit:
            failed = True
            messages.append(f"FAIL {kind}: {current} > {source}; remove new violations")
        elif current < line:
            messages.append(f"NOTE {kind}: {current} < baseline {line} — lower {baseline_name} in a separate ratchet PR (--write-baseline)")
        else:
            messages.append(f"OK {kind}: {current} <= {source}")
    return messages, failed


@contextmanager
def tree_at(ref: str, repo: Path, paths: tuple[str, ...]) -> Iterator[Path]:
    """ref 커밋의 paths 를 임시 폴더에 꺼낸다(git 작업 트리가 아니므로 lint 는 폴더를 다 훑는다 — 꺼낸 것은 추적 파일뿐).

    ref 가 커밋이 아니거나 paths 가 하나도 없으면 ValueError(설정 오류 · exit 2)다. 빈 트리를 넘기면 병합 기준 실측이 0 이 되어
    PR 이 「새 위반」으로 몰린다(K10 #1180).
    """
    if subprocess.run(["git", "-C", str(repo), "rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}"],
                      capture_output=True).returncode != 0:
        raise ValueError(f"--base-ref {ref!r} is not a commit in {repo} (shallow clone? fetch-depth 0)")
    # 그 커밋에 있는 경로만 꺼낸다(없는 경로를 archive 에 넘기면 git 이 128 로 죽는다).
    wanted = [path for path in paths
              if subprocess.run(["git", "-C", str(repo), "cat-file", "-e", f"{ref}:{path}"], capture_output=True).returncode == 0]
    if not wanted:
        raise ValueError(f"--base-ref {ref!r} has none of {', '.join(paths)}")
    with tempfile.TemporaryDirectory(prefix="ratchet-base-") as tmp:
        if wanted:
            archive = subprocess.run(["git", "-C", str(repo), "archive", "--format=tar", ref, "--", *wanted],
                                     check=True, capture_output=True).stdout
            with tarfile.open(fileobj=io.BytesIO(archive), mode="r:") as tar:
                tar.extractall(tmp, filter="data")
        yield Path(tmp)


def allowlist_growth(current: Mapping[str, Mapping], base: Mapping[str, Mapping], allowlist_name: str) -> list[str]:
    """병합 기준보다 늘어난 허용(경로 · 종류) — 실패가 아니라 안내다. 허용 목록을 늘려 위반을 숨기는 길을 리뷰어 눈앞에 둔다(K10 #1180)."""
    added = [f"{path}[{kind}]" for path, entry in sorted(current.items())
             for kind in sorted(entry.get("kinds", ())) if kind not in base.get(path, {}).get("kinds", ())]
    return [f"NOTE {allowlist_name}: {len(added)} new exemption(s) vs base — reviewer must check: {', '.join(added)}"] if added else []


def write_baseline(path: Path, counts: Mapping[str, int], kinds: tuple[str, ...]) -> None:
    path.write_text(json.dumps({kind: int(counts.get(kind, 0)) for kind in sorted(kinds)}, indent=2) + "\n", encoding="utf-8")


def as_counts(counter: Counter, kinds: tuple[str, ...]) -> dict[str, int]:
    return {kind: int(counter[kind]) for kind in kinds}
