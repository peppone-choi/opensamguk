from __future__ import annotations

import unittest

from tools.map.external_province_systems import load_external_province_displays


class ExternalProvinceSeedTest(unittest.TestCase):
    def test_reviewed_220_systems_are_not_han_commandery_labels(self) -> None:
        rows = load_external_province_displays()
        self.assertEqual(rows["X045"].administrative_system, "BYEONHAN")
        self.assertEqual(rows["X031"].administrative_system, "BYEONHAN")
        self.assertEqual(rows["X044"].administrative_system, "JINHAN")
        self.assertEqual(rows["X040"].administrative_system, "MAHAN")
        self.assertEqual(rows["X028"].administrative_system, "GOGURYEO")

    def test_later_gaya_aliases_are_not_220_canonical_names(self) -> None:
        rows = load_external_province_displays()
        self.assertNotEqual(rows["X047"].canonical_name, "대가야")
        self.assertNotEqual(rows["X048"].canonical_name, "성산가야")
        self.assertNotEqual(rows["X049"].canonical_name, "고령가야")

    def test_post_han_ryukyu_name_stays_pending(self) -> None:
        self.assertEqual(load_external_province_displays()["X057"].review_state, "PENDING")


if __name__ == "__main__":
    unittest.main()
