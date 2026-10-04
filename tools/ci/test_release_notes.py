"""release_notes: merge and squash PRs are found, titles stay verbatim, a revert cancels its original in range."""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "release_notes.py"


class ReleaseNotesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self.tmp.name)
        self.git("init", "-q", "-b", "main")
        self.commit("init")
        self.base = self.git("rev-parse", "HEAD").strip()

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def git(self, *args: str) -> str:
        return subprocess.run(["git", "-C", str(self.repo), "-c", "user.name=t", "-c", "user.email=t@t", *args],
                              check=True, capture_output=True, text=True).stdout

    def commit(self, message: str) -> None:
        (self.repo / "f.txt").write_text(message, encoding="utf-8")
        self.git("add", "f.txt")
        self.git("commit", "-q", "-m", message)

    def merge_pr(self, number: int, title: str) -> None:
        self.git("checkout", "-q", "-b", f"b{number}")
        self.commit(f"work for {number}")
        self.git("checkout", "-q", "main")
        self.git("merge", "-q", "--no-ff", f"b{number}", "-m", f"Merge pull request #{number} from o/b{number}\n\n{title}")

    def notes(self, *extra: str) -> subprocess.CompletedProcess:
        return subprocess.run([sys.executable, str(SCRIPT), "--repo", str(self.repo), "--to", "main", *extra],
                              capture_output=True, text=True)

    def test_sections_verbatim_titles_and_revert_pairs(self) -> None:
        self.commit("feat(game): 연감 화면 (#10)")
        self.merge_pr(11, "fix(api): 목록 표시를 맞춘다")
        self.commit('Revert "feat(game): 연감 화면" (#12)')
        self.commit("보안: 장수 생성 경로에 공통 인증 적용 (#13)")
        self.merge_pr(14, "ci: 잠금 파일 그대로 설치")
        self.commit('Revert "feat(map): 범위 밖 기능" (#15)')
        self.commit("docs: 오타")
        run = self.notes("--from", self.base, "--version", "v0.1.0")
        self.assertEqual(0, run.returncode, run.stderr)
        out = run.stdout
        self.assertIn("# v0.1.0 릴리스 노트 (초안)", out)
        self.assertIn("병합 PR 6개(그중 되돌린 짝 1개는 뺐다)", out)
        self.assertIn("## 고친 것\n\n- **api** 목록 표시를 맞춘다 (#11)", out)
        self.assertIn("## 개발 · 운영\n\n- **ci** 잠금 파일 그대로 설치 (#14)", out)
        self.assertIn("## 그 밖\n\n- 보안: 장수 생성 경로에 공통 인증 적용 (#13)", out)  # verbatim, no security section
        self.assertIn('## 되돌림\n\n- Revert "feat(map): 범위 밖 기능" (#15)', out)  # original not in range → kept
        self.assertNotIn("연감 화면 (#10)", out.split("<details>")[0])  # cancelled pair is not listed as shipped
        self.assertIn("- #10 ↔ #12", out)
        self.assertIn("## PR 없이 들어간 커밋\n\n- docs: 오타 (", out)
        self.assertNotIn("새 기능", out)

    def test_needs_a_start_without_tags(self) -> None:
        run = self.notes()
        self.assertEqual(2, run.returncode)
        self.assertIn("pass --from", run.stderr)

    def test_latest_v_tag_is_the_default_start(self) -> None:
        self.commit("feat: 이전 (#1)")
        self.git("tag", "v0.0.1")
        self.commit("feat: 이후 (#2)")
        run = self.notes()
        self.assertEqual(0, run.returncode, run.stderr)
        self.assertIn("- 이후 (#2)", run.stdout)
        self.assertNotIn("이전", run.stdout)


if __name__ == "__main__":
    unittest.main()
