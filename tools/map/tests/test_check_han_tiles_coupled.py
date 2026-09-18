"""결합 목록(GH #818)의 완전성·배선 게이트.

목록은 손으로 관리하므로, 새 도구가 han-tiles 를 읽으면서 --check 를 갖게 되면 이 테스트가 빨개져
COUPLED 에 넣거나 EXEMPT 에 사유를 적게 만든다.
"""
import re
import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_han_tiles_coupled as C  # noqa: E402

ROOT = C.ROOT
INPUT_RE = re.compile(r"han-tiles|han-world-v3")
CHECK_FLAG_RE = re.compile(r"""add_argument\(\s*['"]--check['"]""")

# han-tiles 를 읽고 --check 도 있지만 일괄 게이트에 못 넣는 도구와 그 사유(2026-09-18 origin/main 실측).
EXEMPT = {
    "tools/map/check_han_tiles_coupled.py": "이 도구 자신",
    "tools/map/build_tile_grid.py": "gitignored terrain-grid.json 이 필요해 CI checkout 에서 못 돈다",
    "tools/map/han_tiles_protected_orchestrator.py": "격리 빌드 오케스트레이터 — 필수 인자 없이는 안 돈다",
    "tools/map/rebalance_han_tiles.py": "main 에서 ValueError(no city index) — 이미 죽은 단계, GH #818 후속",
    "tools/map/relocate_han_province.py": "main 에서 ValueError(neither pinned input nor output), GH #818 후속",
    "tools/map/materialize_province_jurisdictions.py": "main 에서 ValueError(parent seat inside another parent), GH #818 후속",
    "tools/map/validate_han_strategic_site_anchors.py": "main 에서 이미 적색(han-tiles 입력 해시 불일치), GH #818 후속",
    "tools/scenario/migrate_han_ownership_claims.py": "main 에서 이미 적색(scenario-province-claims-v1 drift), GH #818 후속",
    "tools/map/adjudicate_han_province_fragments.py": "통과하지만 83초 — contracts 예산 밖, 미배선",
    "tools/map/build_han_parent_reconciliation.py": "통과하지만 23초 — contracts 예산 밖, 미배선",
    "tools/map/measure_province_seat_offset.py": (
        "Q1·Q1b 게이트 — 현행 커밋본에서 의도적으로 적색(480·24건, GH #806 계획 §6). ★ 지리 재분할이 han-tiles 에 "
        "들어오는 PR 이 COUPLED 로 옮긴다. 적색인 것은 test_measure_province_seat_offset 가 고정한다"),
}


class CoupledListTest(unittest.TestCase):
    def test_every_tiles_consumer_with_check_is_listed_or_exempt(self):
        listed = {arg for c in C.COUPLED for arg in c.check if arg.startswith("tools/") and arg.endswith(".py")}
        missing = []
        for path in sorted((ROOT / "tools").rglob("*.py")):
            rel = path.relative_to(ROOT).as_posix()
            if "/tests/" in rel:
                continue
            text = path.read_text(encoding="utf-8")
            if INPUT_RE.search(text) and CHECK_FLAG_RE.search(text) and rel not in listed and rel not in EXEMPT:
                missing.append(rel)
        self.assertEqual(missing, [], "han-tiles 를 읽는 --check 도구가 결합 목록에 없다")

    def test_exempt_and_listed_do_not_rot(self):
        listed = {arg for c in C.COUPLED for arg in c.check if arg.startswith("tools/") and arg.endswith(".py")}
        for rel in list(EXEMPT) + sorted(listed):
            self.assertTrue((ROOT / rel).is_file(), rel)
        self.assertEqual(listed & set(EXEMPT), set())

    def test_artifacts_are_committed(self):
        # data/map/* 는 통째로 gitignored 다 — 추적되지 않는 산출물은 CI 에 없다.
        for c in C.COUPLED:
            for a in c.artifacts:
                out = subprocess.run(["git", "ls-files", "--", a], cwd=ROOT, capture_output=True, text=True).stdout
                self.assertTrue(out.strip(), f"{c.key}: {a} 가 git 에 없다")

    def test_ci_runs_batch_and_every_slow_check(self):
        ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertIn("python3 tools/map/check_han_tiles_coupled.py --check", ci)
        for c in C.COUPLED:
            if c.slow:
                self.assertIn(" ".join(c.check), ci, f"{c.key}: slow 항목은 ci.yml 에 개별 스텝이 있어야 한다")

    def test_keys_unique(self):
        keys = [c.key for c in C.COUPLED]
        self.assertEqual(len(keys), len(set(keys)))


class RunnerReportsAllStaleTest(unittest.TestCase):
    def test_failing_checks_are_all_named_and_exit_nonzero(self):
        fake = (
            C.Coupled("a-red", ("x",), (sys.executable, "-c", "import sys; sys.exit(1)"), ("regen-a",)),
            C.Coupled("b-green", ("y",), (sys.executable, "-c", "pass"), None),
            C.Coupled("c-red", ("z",), (sys.executable, "-c", "import sys; sys.exit(3)"), None),
        )
        real = C.COUPLED
        C.COUPLED = fake
        try:
            import contextlib, io
            err = io.StringIO()
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(err):
                rc = C.run_checks(include_slow=False)
        finally:
            C.COUPLED = real
        self.assertEqual(rc, 1)
        self.assertIn("a-red", err.getvalue())
        self.assertIn("c-red", err.getvalue())
        self.assertNotIn("b-green", err.getvalue())


if __name__ == "__main__":
    unittest.main()
