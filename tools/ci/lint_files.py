"""lint 가 읽을 파일을 git 이 보는 범위로 좁힌다 — 추적 파일 + 아직 추적하지 않은 새 파일(.gitignore 존중).

로컬에서 Playwright 를 돌리면 남는 `web/game/test-results/results.json`(스크린샷 base64) 같은 무시 대상까지 os.walk 가
훑어 naming_lint · web_copy_lint · web_ui_lint 가 거짓으로 늘었다(2026-10-01, K8 발견). CI 는 깨끗한 체크아웃이라 해당이
없지만 로컬 게이트를 믿을 수 없게 된다. 새로 만든 · 아직 커밋하지 않은 파일은 계속 센다(--others --exclude-standard).

git 작업 트리가 아니면(테스트의 임시 폴더, 트리 사본) None 을 돌려주고, 부르는 쪽은 지금처럼 폴더를 다 훑는다.
"""

from __future__ import annotations

import subprocess
from pathlib import Path


def git_visible_files(root: Path) -> set[str] | None:
    """root 기준 상대 경로(posix) 집합. git 작업 트리가 아니거나 git 이 없으면 None."""
    try:
        inside = subprocess.run(["git", "-C", str(root), "rev-parse", "--is-inside-work-tree"],
                                capture_output=True, text=True, timeout=30)
        if inside.returncode != 0 or inside.stdout.strip() != "true":
            return None
        listed = subprocess.run(["git", "-C", str(root), "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                                capture_output=True, timeout=120, check=True)
    except (OSError, subprocess.SubprocessError):
        return None
    return {p.decode("utf-8", "surrogateescape") for p in listed.stdout.split(b"\0") if p}


def is_visible(path: Path, root: Path, visible: set[str] | None) -> bool:
    """visible 이 None 이면(비 git) 늘 참. 아니면 git 이 보는 파일인지."""
    if visible is None:
        return True
    try:
        return path.relative_to(root).as_posix() in visible
    except ValueError:
        return False
