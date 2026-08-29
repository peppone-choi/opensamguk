#!/usr/bin/env python3
import importlib.util
import sys
import types
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]


def load_builder():
    fake_hanja = types.SimpleNamespace(translate=lambda value, mode: value)
    previous = sys.modules.get("hanja")
    sys.modules["hanja"] = fake_hanja
    try:
        spec = importlib.util.spec_from_file_location(
            "build_readings_under_test", ROOT / "tools/map/build_readings.py"
        )
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    finally:
        if previous is None:
            sys.modules.pop("hanja", None)
        else:
            sys.modules["hanja"] = previous


class HanReadingOverrideTest(unittest.TestCase):
    def test_hejian_kingdom_variants_share_the_reviewed_korean_reading(self):
        overrides = load_builder().OVERRIDES

        self.assertEqual("하간국", overrides["河閒國"])
        self.assertEqual("하간국", overrides["河間國"])


if __name__ == "__main__":
    unittest.main()
