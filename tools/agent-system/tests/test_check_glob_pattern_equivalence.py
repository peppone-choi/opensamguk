"""test_check_glob_pattern_equivalence.py — #513 regression.

check_product_authority_policy() used to call `ROOT.glob(pattern)` once per
pattern in PRODUCT_AUTHORITY_SOURCE_GLOBS, walking the filesystem tree up to
24 times. The fix (`scannable_repo_files()` + `_glob_pattern_to_regex()`)
enumerates the tree once via git and matches each pattern against that list
in memory instead — but that's only safe if the in-memory matcher accepts
and rejects exactly the same relative paths `pathlib.Path.glob()` would.

This asserts that byte-for-byte, for every pattern actually declared in
PRODUCT_AUTHORITY_SOURCE_GLOBS (read from the module, not hardcoded here, so
a newly added pattern is picked up automatically), against a synthetic
fixture tree covering the tricky cases: `**` matching zero directories, `**`
matching several, a non-recursive `*` pattern that must NOT cross a `/`, and
paths that no pattern should match at all.

Being *picked up* automatically is not the same as being *covered*: a new
pattern that matches nothing in FIXTURE_FILES would make
`root.glob(pattern)` and the regex both return an empty set, which compare
equal without ever exercising the pattern's translation. The loop below
asserts `expected` is non-empty per pattern specifically to turn that vacuous
pass into a failure -- a new pattern still needs a matching fixture file
added to FIXTURE_FILES for this test to actually prove anything about it.
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
    "logic/src/main/java/Foo.java",
    "logic/src/baseline/java/Foo.java",
    "logic/src/baseline/kotlin/Bar.kt",
    "logic/src/baseline/kotlin/sub/Bar.kts",
    "web/game/src/x.ts",
    "web/x.ts",  # ** must also match zero dirs mid-pattern
    "web/game/app/deep/y.tsx",
    "web/game/src/x.js",
    "web/game/src/x.mjs",
    "web/game/src/x.cjs",
    "web/game/src/main/kotlin/x.kt",  # web ts-family patterns must NOT match .kt
    "tools/agent-system/check.py",
    "tools/a/b/c/x.py",
    "tools/a/b/c/x.ts",
    "tools/a/b/c/x.js",
    "tools/a/b/c/x.mjs",
    "tools/a/b/c/x.cjs",
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
                # Guard against a vacuous pass: if a newly added pattern matches
                # nothing in FIXTURE_FILES, `got == expected == set()` below would
                # go green without ever exercising the pattern's translation. Fail
                # loudly instead so a new pattern forces a matching fixture entry.
                self.assertTrue(
                    expected,
                    f"pattern {pattern!r} matched zero files in FIXTURE_FILES -- "
                    "this test can't prove regex/glob equivalence for it. Add a "
                    "fixture file that pattern is meant to match.",
                )
                regex = check._glob_pattern_to_regex(pattern)
                got = {rel for rel in FIXTURE_FILES if regex.match(rel)}
                self.assertEqual(
                    got, expected,
                    f"pattern {pattern!r} diverges from pathlib.Path.glob(): "
                    f"regex got {sorted(got)}, pathlib expected {sorted(expected)}",
                )


if __name__ == "__main__":
    unittest.main()
