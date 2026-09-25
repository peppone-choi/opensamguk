"""Naming ratchet and exception contract tests."""

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from naming_lint import KINDS, check, load_allowlist, scan


class NamingLintTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def test_new_hwiha_class_and_package_are_counted(self):
        self.write("logic/src/main/kotlin/opensamguk/logic/hwiha/HwihaProbe.kt",
                   "package opensamguk.logic.hwiha\nclass HwihaProbe\n")
        counts, findings = scan(self.root, {})
        self.assertEqual(counts["product_identifier"], 1)
        self.assertEqual(counts["product_path"], 1)
        self.assertEqual(counts["package_name"], 1)
        self.assertIn("HwihaProbe", findings["product_identifier"][0])

    def test_new_che_reference_outside_allowlist_is_counted(self):
        self.write("logic/src/main/kotlin/opensamguk/logic/input/Probe.kt",
                   'package opensamguk.logic.input\nval action = "che_probe"\n')
        counts, findings = scan(self.root, {})
        self.assertEqual(counts["retired_reference"], 1)
        self.assertIn("che_", findings["retired_reference"][0])

    def test_legacy_registry_and_version_identifier_are_counted(self):
        self.write("tools/probe.py", "CommandRegistry V2Action PublicAlphaCommandCatalog\n")
        counts, _ = scan(self.root, {})
        self.assertEqual(counts["retired_reference"], 2)
        self.assertEqual(counts["product_identifier"], 1)

    def test_cache_and_miniche_are_not_che_command_references(self):
        self.write("tools/probe.py", "cache_dir pycache_key miniche_map che_농지개간\n")
        counts, findings = scan(self.root, {})
        self.assertEqual(counts["retired_reference"], 1)
        self.assertEqual(findings["retired_reference"], ["tools/probe.py:1:che_"])

    def test_flyway_v20_is_not_a_v2_product_path(self):
        self.write("infra/src/main/resources/db/migration/V20__feature.sql", "SELECT 1;\n")
        counts, _ = scan(self.root, {})
        self.assertEqual(counts["product_path"], 0)

    def test_immutable_flyway_allowance_cannot_hide_edits(self):
        migration = self.write("infra/src/main/resources/db/migration/V1__baseline.sql", "-- che_old\n")
        allowlist = self.write("allowlist.json", json.dumps({"paths": [{
            "path": migration.relative_to(self.root).as_posix(),
            "kinds": ["retired_reference"],
            "reason": "ADR-LITE-066 immutable Flyway history",
            "sha256": hashlib.sha256(migration.read_bytes()).hexdigest(),
        }]}))
        allowed = load_allowlist(allowlist, self.root)
        self.assertEqual(scan(self.root, allowed)[0]["retired_reference"], 0)
        migration.write_text("-- che_new\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "immutable file changed"):
            load_allowlist(allowlist, self.root)

    def test_ratchet_fails_on_increase_and_requests_lower_baseline(self):
        baseline = {kind: 0 for kind in KINDS}
        counts, _ = scan(self.root, {})
        self.assertTrue(all(message.startswith("OK") for message in check(counts, baseline)))
        self.write("logic/src/main/kotlin/Probe.kt", "class HwihaProbe\n")
        counts, _ = scan(self.root, {})
        self.assertIn("FAIL product_identifier", "\n".join(check(counts, baseline)))
        baseline["product_identifier"] = 2
        self.assertIn("LOWER product_identifier", "\n".join(check(counts, baseline)))

    def test_lower_count_fails_cli_until_baseline_is_updated(self):
        baseline = self.write("baseline.json", json.dumps({kind: 1 for kind in KINDS}))
        allowlist = self.write("allowlist.json", '{"paths": []}')
        result = subprocess.run([sys.executable, str(Path(__file__).with_name("naming_lint.py")),
                                 "--root", str(self.root), "--baseline", str(baseline),
                                 "--allowlist", str(allowlist)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn("LOWER retired_reference", result.stdout)


if __name__ == "__main__":
    unittest.main()
