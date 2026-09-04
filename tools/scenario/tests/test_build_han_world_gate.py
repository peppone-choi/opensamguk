from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "tools/scenario/build_han_world.py"
SPEC = importlib.util.spec_from_file_location("build_han_world", MODULE_PATH)
assert SPEC and SPEC.loader
build_han_world = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(build_han_world)


class CommittedWorldGateTest(unittest.TestCase):
    def test_stable_city_sort_name_uses_reviewed_source_spelling(self) -> None:
        """표시명 정규화가 기존 런타임 city id 순서를 바꾸면 안 된다."""
        canonical = {"id": "85448", "nameCh": "曲成县"}
        unrelated = {"id": "99999", "nameCh": "曲城县"}

        self.assertEqual("曲成（城）县", build_han_world.stable_city_sort_name(canonical))
        self.assertEqual("曲城县", build_han_world.stable_city_sort_name(unrelated))
        self.assertLess(
            build_han_world.stable_city_sort_name(unrelated),
            build_han_world.stable_city_sort_name(canonical),
        )

    def test_default_gate_build_preserves_committed_world_city_ids(self) -> None:
        """게이트 전용 빌드는 배포 중인 han.json의 ID·소속을 따라야 한다.

        CANON_105가 바뀌었지만 전체 월드 3종을 함께 재생성하지 않은 현재 상태에서
        이론상 새 정렬을 쓰면 city 150이 예주로 이동한다. 배포 중인 han.json에서
        city 150은 기주이므로 게이트 키도 기주로 남아야 한다.
        """
        _, index, _ = build_han_world.build_gate()

        self.assertEqual(["冀州"], index[150])

    def test_default_cli_does_not_silently_check_world_v2(self) -> None:
        self.assertEqual("han.json", build_han_world.OUT_JSON.name)
        self.assertNotEqual(build_han_world.OUT_JSON, build_han_world.OUT_V2_JSON)


if __name__ == "__main__":
    unittest.main()
