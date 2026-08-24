"""test_check_product_authority_scan.py — #513 regression.

check_product_authority_policy() used to call ROOT.glob(pattern) once per
pattern (24 patterns, 9 of them full-tree recursive), walking the entire
filesystem repeatedly. On a checkout with a large untracked-but-not-ignored
tree (see #513) this made the guard either take ~1000s (CI timeout budget is
5 minutes) or, once such a tree got merely gitignored, still scan tens of
thousands of irrelevant files and produce false "product-authority" errors
from code that was never part of this repo's product surface.

The fix: `scannable_repo_files()` enumerates the repo ONCE via
`git ls-files --cached --others --exclude-standard` — every file git would
ever show for this checkout, i.e. tracked, or untracked-but-not-ignored — and
the 24 patterns are matched against that in-memory list instead of walking
the tree per pattern.

These tests assert the *property* the fix relies on — "untracked and
ignored" is excluded, "untracked but not ignored" is still caught — using a
throwaway git repo with a directory named something other than `.omo`. A
test that keyed off the literal name `.omo` would pass today and silently
stop meaning anything the moment a *different* untracked directory (`.omx/`,
a new tool's cache dir, ...) showed up on someone's machine — exactly the
recurrence #513 flagged against the name-based approach it rejected.
"""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import check  # noqa: E402


def _git(cwd: Path, *args: str) -> None:
    subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True)


def _init_repo(root: Path) -> None:
    _git(root, "init", "-q")
    _git(root, "config", "user.email", "fixture@example.invalid")
    _git(root, "config", "user.name", "fixture")


class ScannableRepoFilesTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        _init_repo(self.root)
        self._orig_root = check.ROOT
        check.ROOT = self.root
        self.addCleanup(setattr, check, "ROOT", self._orig_root)

    def tearDown(self):
        self.tmp.cleanup()

    def _write(self, rel: str, content: str = "x") -> None:
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def test_ignored_untracked_directory_is_excluded_by_property_not_name(self):
        # Deliberately NOT named .omo — the fix must key off "gitignored +
        # untracked", not off a hardcoded directory name.
        self._write(".gitignore", "totally-unrelated-scratch-dir/\n")
        self._write("totally-unrelated-scratch-dir/src/main/kotlin/A.kt", "x")
        self._write("logic/src/main/kotlin/Tracked.kt", "x")
        _git(self.root, "add", ".gitignore", "logic/src/main/kotlin/Tracked.kt")

        files = check.scannable_repo_files()

        self.assertIn("logic/src/main/kotlin/Tracked.kt", files)
        self.assertNotIn(
            "totally-unrelated-scratch-dir/src/main/kotlin/A.kt", files
        )

    def test_untracked_but_not_ignored_file_is_still_included(self):
        # This is the whole reason the enumeration uses --others as well as
        # --cached: a violation in a file the developer hasn't `git add`-ed
        # yet must still be caught pre-commit.
        self._write("logic/src/main/kotlin/NotYetAdded.kt", "x")

        files = check.scannable_repo_files()

        self.assertIn("logic/src/main/kotlin/NotYetAdded.kt", files)

    def test_non_git_directory_raises_instead_of_silently_returning_empty(self):
        non_git_root = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: subprocess.run(["rm", "-rf", str(non_git_root)]))
        check.ROOT = non_git_root

        with self.assertRaises(RuntimeError):
            check.scannable_repo_files()


class ProductAuthorityPolicyScanTests(unittest.TestCase):
    """Mutation-style checks on check_product_authority_policy() itself,
    not just the file listing it now runs on."""

    VIOLATION = "PHP is the grand truth for this module.\n"

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        _init_repo(self.root)
        self._orig_root = check.ROOT
        check.ROOT = self.root
        self.addCleanup(setattr, check, "ROOT", self._orig_root)

    def tearDown(self):
        self.tmp.cleanup()

    def _write(self, rel: str, content: str) -> None:
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def _product_authority_messages(self) -> list[str]:
        return [
            f.message
            for f in check.check_product_authority_policy()
            if f.check == "product-authority"
        ]

    def test_violation_in_ignored_untracked_source_is_not_flagged(self):
        self._write(".gitignore", "scratch-tree/\n")
        self._write("scratch-tree/src/main/kotlin/Old.kt", self.VIOLATION)
        _git(self.root, "add", ".gitignore")

        messages = self._product_authority_messages()

        self.assertFalse(
            any("scratch-tree/src/main/kotlin/Old.kt" in m for m in messages)
        )

    def test_violation_in_tracked_source_is_still_flagged(self):
        self._write("logic/src/main/kotlin/Real.kt", self.VIOLATION)
        _git(self.root, "add", "logic/src/main/kotlin/Real.kt")

        messages = self._product_authority_messages()

        self.assertTrue(
            any("logic/src/main/kotlin/Real.kt" in m for m in messages)
        )

    def test_violation_in_untracked_but_not_git_add_ed_source_is_still_flagged(self):
        # Same file/content as the tracked case above, but never `git add`-ed.
        # This is the case that distinguishes the chosen --cached --others
        # approach from a --cached-only implementation (rejected by #513).
        self._write("logic/src/main/kotlin/NotAdded.kt", self.VIOLATION)

        messages = self._product_authority_messages()

        self.assertTrue(
            any("logic/src/main/kotlin/NotAdded.kt" in m for m in messages)
        )


if __name__ == "__main__":
    unittest.main()
