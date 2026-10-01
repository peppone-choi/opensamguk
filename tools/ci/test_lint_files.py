"""lint_files 와 세 lint(naming · web_copy · web_ui)의 파일 고르기 — git 이 무시하는 파일은 빼고, 새 파일은 센다.

2026-10-01 K8 발견: 로컬 e2e 뒤 남는 web/game/test-results/results.json 이 naming_lint 를 거짓으로 늘렸다(148 → 152).
"""

from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

import naming_lint
import web_copy_lint
import web_ui_lint
from lint_files import git_visible_files


def git(root: Path, *args: str) -> None:
    subprocess.run(["git", "-C", str(root), "-c", "user.email=k10@test", "-c", "user.name=k10", *args],
                   check=True, capture_output=True)


class LintFilesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def write(self, rel: str, text: str = 'export const a = "x";\n') -> None:
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def make_repo(self) -> None:
        git(self.root, "init", "-q")
        self.write(".gitignore", "web/*/test-results/\n**/generated/\n")
        self.write("web/game/app/tracked.tsx")
        git(self.root, "add", ".")
        git(self.root, "commit", "-q", "-m", "init")
        self.write("web/game/app/new-untracked.tsx")              # 아직 커밋 안 한 새 파일 — 센다
        self.write("web/game/test-results/results.json", "{}")    # 로컬 e2e 결과 — 뺀다
        self.write("web/game/generated/out.tsx")                  # 생성물 — 뺀다

    def test_visible_set_respects_gitignore_and_keeps_new_files(self) -> None:
        self.make_repo()
        visible = git_visible_files(self.root)
        self.assertIsNotNone(visible)
        self.assertIn("web/game/app/tracked.tsx", visible)
        self.assertIn("web/game/app/new-untracked.tsx", visible)
        self.assertNotIn("web/game/test-results/results.json", visible)
        self.assertNotIn("web/game/generated/out.tsx", visible)

    def test_not_a_git_tree_means_no_filter(self) -> None:
        self.write("web/game/app/a.tsx")
        self.assertIsNone(git_visible_files(self.root))
        # 비 git 트리(테스트 · 트리 사본)는 지금처럼 다 훑는다
        names = {p.name for p in web_copy_lint.source_files(self.root)}
        self.assertEqual(names, {"a.tsx"})

    def test_each_lint_skips_ignored_files(self) -> None:
        self.make_repo()
        for module in (naming_lint, web_copy_lint, web_ui_lint):
            with self.subTest(lint=module.__name__):
                rels = {p.relative_to(self.root).as_posix() for p in module.source_files(self.root)}
                self.assertIn("web/game/app/tracked.tsx", rels)
                self.assertIn("web/game/app/new-untracked.tsx", rels)
                self.assertNotIn("web/game/generated/out.tsx", rels)
                self.assertNotIn("web/game/test-results/results.json", rels)


if __name__ == "__main__":
    unittest.main()
