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
    def test_default_gate_build_preserves_committed_world_city_ids(self) -> None:
        """게이트 전용 빌드는 배포 중인 han.json의 ID·소속을 따라야 한다.

        CANON_105가 바뀌었지만 전체 월드 3종을 함께 재생성하지 않은 현재 상태에서
        이론상 새 정렬을 쓰면 city 150이 예주로 이동한다. 배포 중인 han.json에서
        city 150은 기주이므로 게이트 키도 기주로 남아야 한다.
        """
        _, index, _ = build_han_world.build_gate()

        self.assertEqual(["冀州"], index[150])


if __name__ == "__main__":
    unittest.main()
