"""test_check_glob_pattern_equivalence.py — #513 regression.

check_product_authority_policy() used to call `ROOT.glob(pattern)` once per
pattern in PRODUCT_AUTHORITY_SOURCE_GLOBS, walking the filesystem tree up to
23 times. The fix (`scannable_repo_files()` + `_glob_pattern_to_regex()`)
enumerates the tree once via git and matches each pattern against that list
in memory instead — but that's only safe if the in-memory matcher accepts
and rejects exactly the same relative paths `pathlib.Path.glob()` would.

This asserts that byte-for-byte, for every pattern actually declared in
PRODUCT_AUTHORITY_SOURCE_GLOBS (read from the module, not hardcoded here, so
a newly added pattern is covered automatically) against a synthetic fixture
tree covering the tricky cases: `**` matching zero directories, `**`
matching several, a non-recursive `*` pattern that must NOT cross a `/`, and
paths that no pattern should match at all.
"""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import check  # noqa: E402

FIXTURE_FILES = (
    "build.gradle.kts",
    "settings.gradle.kts",
    "app/build.gradle.kts",
    "app/deep/nested/build.gradle.kts",
    "randomdir/settings.gradle.kts",  # settings.gradle.kts has no ** -> root-only
    "logic/src/main/kotlin/Foo.kt",
    "src/main/kotlin/Foo.kt",  # ** at pattern start must match zero dirs
    "logic/src/main/kotlin/sub/deep/Foo.kts",
    "logic/src/baseline/java/Foo.java",
    "logic/src/baseline/kotlin/Bar.kt",
    "web/game/src/x.ts",
    "web/x.ts",  # ** must also match zero dirs mid-pattern
    "web/game/app/deep/y.tsx",
    "web/game/src/main/kotlin/x.kt",  # web ts-family patterns must NOT match .kt
    "tools/agent-system/check.py",
    "tools/a/b/c/x.py",
    "tools/x.sh",
    "scripts/agent/foo.py",
    "scripts/foo.sh",
    "a.yaml",  # **/*.yaml must match zero-dir top-level files too
    "a/b/c.yaml",
    ".github/workflows/ci.yml",
    "no_match.txt",
)


class GlobPatternEquivalenceTests(unittest.TestCase):
    def test_regex_matches_are_identical_to_pathlib_glob_for_every_pattern(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for rel in FIXTURE_FILES:
                path = root / rel
                path.parent.mkdir(parents=True, exist_ok=True)
                path.touch()

            for pattern in check.PRODUCT_AUTHORITY_SOURCE_GLOBS:
                expected = {
                    p.relative_to(root).as_posix()
                    for p in root.glob(pattern)
                    if p.is_file()
                }
                regex = check._glob_pattern_to_regex(pattern)
                got = {rel for rel in FIXTURE_FILES if regex.match(rel)}
                self.assertEqual(
                    got, expected,
                    f"pattern {pattern!r} diverges from pathlib.Path.glob(): "
                    f"regex got {sorted(got)}, pathlib expected {sorted(expected)}",
                )


if __name__ == "__main__":
    unittest.main()
