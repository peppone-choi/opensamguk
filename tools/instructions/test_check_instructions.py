import contextlib
import io
import tempfile
import unittest
from pathlib import Path

import check_instructions as ci


class CheckInstructionsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.write("CLAUDE.md", "@AGENTS.md\n")
        self.write("AGENTS.md", "- See [ref](docs/ref.md).\n")
        self.write("docs/ref.md", "rule: never fake results\n")
        self.write("src/a.kt", "x\n")
        self.write(".claude/rules/kt.md", '---\npaths:\n  - "src/**/*.{kt,kts}"\n---\n# Kotlin\n')
        self.write("rules.tsv", "r1\tdocs/ref.md\tnever fake results\n")

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, rel, text):
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def run_check(self, max_lines=200):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = ci.check(self.root, self.root / "rules.tsv", max_lines, strict=False)
        return code, out.getvalue()

    def test_passes_and_lists_linked_docs(self):
        code, out = self.run_check()
        self.assertEqual(0, code, out)
        self.assertIn("docs/ref.md", out)

    def test_line_limit(self):
        self.write("docs/ref.md", "never fake results\n" + "x\n" * 200)
        code, out = self.run_check()
        self.assertEqual(1, code)
        self.assertIn("docs/ref.md: 201 lines > 200", out)

    def test_missing_link_target(self):
        self.write("AGENTS.md", "- See [gone](docs/gone.md) and [ref](docs/ref.md).\n")
        code, out = self.run_check()
        self.assertEqual(1, code)
        self.assertIn("missing link/import target docs/gone.md", out)

    def test_missing_import_but_code_span_ignored(self):
        self.write("CLAUDE.md", "@AGENTS.md\n`@not/imported.md`\n@missing.md\n")
        code, out = self.run_check()
        self.assertEqual(1, code)
        self.assertIn("missing.md", out)
        self.assertNotIn("not/imported.md", out)

    def test_rule_glob_without_match(self):
        self.write(".claude/rules/kt.md", '---\npaths:\n  - "lib/**/*.kt"\n---\n')
        code, out = self.run_check()
        self.assertEqual(1, code)
        self.assertIn("paths glob matches no file: lib/**/*.kt", out)

    def test_protected_phrase_removed(self):
        self.write("docs/ref.md", "rule: fake results are fine\n")
        code, out = self.run_check()
        self.assertEqual(1, code)
        self.assertIn("protected rule r1 not found in docs/ref.md", out)

    def test_glob_semantics(self):
        self.assertTrue(ci.glob_regex("**/*.kt").match("a.kt"))
        self.assertTrue(ci.glob_regex("**/*.kt").match("x/y/a.kt"))
        self.assertFalse(ci.glob_regex("src/*.kt").match("src/x/a.kt"))
        self.assertEqual(["a.kt", "a.kts"], ci.expand_braces("a.{kt,kts}"))


if __name__ == "__main__":
    unittest.main()
