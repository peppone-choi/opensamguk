import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from verify_yuzhou_w1 import BASELINES, SUITES, verify


SHA = "a" * 40
PIN = "b" * 64
FIRST = "enlist=1 march=1 siege=1 income=3 salary=3 assessment=3 ranking=3 encounter=4 capture=4 dispatch=12"


class VerifyW1Test(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for number in (1, 2, 3):
            directory = self.root / f"yuzhou-w1-77-1-{number}"
            directory.mkdir()
            (directory / "pin-git-sha.txt").write_text(SHA + "\n")
            (directory / "pin-sha256.txt").write_text("".join(
                f"{PIN}  {name}\n" for name in (
                    "infra/src/main/resources/map/han-world-v3.json",
                    "infra/src/main/resources/scenario/scenario_990002.json",
                    "tools/e2e/fixtures/yuzhou/scenario_990002.json",
                )))
            (directory / "world-state-sha256.txt").write_text("".join(
                f"{key} {PIN}\n" for key in sorted(BASELINES)))
            for name, count in SUITES.items():
                suite = ET.Element("testsuite", name=name, tests=str(count), failures="0", errors="0", skipped="0")
                case_names = [f"test {index}" for index in range(count)]
                if name.endswith("S3PassChainProbeIT"):
                    case_names = ["적색 짝 fails when corps is absent"]
                if name.endswith("YuzhouCampaignInvarianceTest"):
                    case_names = ["36 phases", "seed 01 replay", "cutting npc deployment", "campaign"]
                for case_name in case_names:
                    ET.SubElement(suite, "testcase", classname=name, name=case_name)
                output = ET.SubElement(suite, "system-out")
                if name.endswith("PassChainInvarianceIT") and not name.endswith("S3PassChainProbeIT"):
                    output.text = f"s3-first-phase {FIRST}\nbehavior-baseline s3-chain-48 {PIN}\n"
                elif name.endswith("YuzhouCampaignInvarianceTest"):
                    output.text = "".join(f"behavior-baseline {key} {PIN}\n" for key in
                                          ("yuzhou-36-seed-00", "yuzhou-36-seed-01"))
                (directory / f"TEST-{name}.xml").write_bytes(ET.tostring(suite))

    def test_three_attempts_pass(self):
        result = verify(self.root, "77", "1", SHA)
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(len(result["attempts"]), 3)

    def test_missing_attempt_fails(self):
        (self.root / "yuzhou-w1-77-1-3" / "pin-git-sha.txt").unlink()
        with self.assertRaisesRegex(ValueError, "missing"):
            verify(self.root, "77", "1", SHA)

    def test_sha256_cannot_be_used_as_product_git_sha(self):
        with self.assertRaisesRegex(ValueError, "40-character product Git SHA"):
            verify(self.root, "77", "1", PIN)

    def test_different_pinned_product_sha_fails(self):
        path = self.root / "yuzhou-w1-77-1-2" / "pin-git-sha.txt"
        path.write_text("c" * 40 + "\n")
        with self.assertRaisesRegex(ValueError, "different product SHA"):
            verify(self.root, "77", "1", SHA)

    def test_different_event_order_fails(self):
        path = self.root / "yuzhou-w1-77-1-2" / "TEST-opensamguk.engine.boot.PassChainInvarianceIT.xml"
        path.write_text(path.read_text().replace("dispatch=12", "dispatch=13"))
        with self.assertRaisesRegex(ValueError, "differ in first_event_phase"):
            verify(self.root, "77", "1", SHA)

    def test_different_actual_checksum_fails(self):
        path = self.root / "yuzhou-w1-77-1-2" / "TEST-opensamguk.engine.boot.PassChainInvarianceIT.xml"
        path.write_text(path.read_text().replace(f"s3-chain-48 {PIN}", f"s3-chain-48 {'c' * 64}"))
        with self.assertRaisesRegex(ValueError, "state checksum differs"):
            verify(self.root, "77", "1", SHA)

    def test_skipped_red_pair_fails(self):
        path = self.root / "yuzhou-w1-77-1-1" / "TEST-opensamguk.engine.boot.S3PassChainProbeIT.xml"
        path.write_text(path.read_text().replace('skipped="0"', 'skipped="1"'))
        with self.assertRaisesRegex(ValueError, "skipped"):
            verify(self.root, "77", "1", SHA)


if __name__ == "__main__":
    unittest.main()
