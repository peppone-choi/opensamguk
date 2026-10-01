import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from changed_paths import ROOT, city_patterns, classify  # noqa: E402

# Files outside data/ and tools/ that a Han map gate was traced reading (2026-09-30). Changing any
# of them must run the map steps; a missing file means the gate moved and MAP_INPUTS needs review.
TRACED_MAP_INPUTS = (
    "common/src/main/kotlin/opensamguk/common/constants/BaselineCityConst.kt",
    "common/src/main/kotlin/opensamguk/common/constants/ArchiveCityConst.kt",
    "common/src/main/kotlin/opensamguk/common/constants/BaselineGateIndex.kt",
    "infra/src/main/resources/map/han.json",
    "infra/src/main/resources/map/han-world-v3.json",
    "infra/src/main/resources/map/han-780-v1.json",
    "infra/src/main/resources/map/che.json",
    "infra/src/main/resources/campaign/county-production-v1.json",
    "infra/src/main/resources/scenario/scenario_990002.json",
    "infra/src/main/kotlin/opensamguk/infra/seed/Archive1447Artifacts.kt",
    "infra/src/test/kotlin/opensamguk/infra/seed/ArchiveRuntimeConstantsIntegrityTest.kt",
    "web/game/public/map/elevation/han-world-v3-metres.png",
    "web/gateway/public/map/elevation/han-world-v3-metres.png",
    "web/shared/src/iso/countyNameGloss.generated.ts",
    "docs/superpowers/research/2026-09-17-march-tempo-baseline.md",
    "docs/superpowers/research/2026-09-17-siege-supply-baseline.md",
    ".ai/research/2026-08-24-namu-places-crosscheck.md",
    ".github/workflows/ci.yml",
    ".github/workflows/map-artifact.yml",
    "tools/map/seat_sources.json",
    "tools/scenario/city_map.json",
    "tools/e2e/fixtures/yuzhou/scenario_990002.json",
    "tools/ops/jwt_rollout_contract_test.py",   # any tools/**/*.py: the coupled test rglobs them
)


class ChangedPathsTest(unittest.TestCase):
    def setUp(self):
        self.patterns = city_patterns()

    def test_city_data_and_executed_command_trigger_all_city_shards(self):
        for path in ("data/map/han-tiles.json",
                     "logic/src/main/kotlin/opensamguk/logic/actions/military/CheIdong.kt"):
            with self.subTest(path=path):
                self.assertTrue(classify([path], self.patterns)["city"])

    def test_unrelated_engine_command_keeps_city_skipped(self):
        result = classify(["logic/src/main/kotlin/opensamguk/logic/actions/military/CheJingbyeong.kt"],
                          self.patterns)
        self.assertTrue(result["jvm"])
        self.assertFalse(result["city"])

    def test_docs_only_keeps_heavy_jobs_skipped(self):
        self.assertFalse(any(classify(["docs/development/example.md", ".ai/decisions.md"], self.patterns).values()))

    def test_artifact_workflow_runs_map_contracts_without_city_shards(self):
        result = classify([".github/workflows/map-artifact.yml"], self.patterns)
        self.assertTrue(result["map"] and result["map_slow"] and result["contracts"])
        self.assertFalse(result["city"])

    def test_kotlin_only_change_skips_map_gates_but_keeps_contracts(self):
        for path in ("app/game-api/src/main/kotlin/opensamguk/gameapi/security/GameApiJwtVerifier.kt",
                     "logic/src/main/kotlin/opensamguk/logic/actions/military/CheJingbyeong.kt",
                     "common/src/main/kotlin/opensamguk/common/model/Example.kt",
                     "infra/src/main/kotlin/opensamguk/infra/persistence/Example.kt",
                     "app/game-engine/build.gradle.kts"):
            with self.subTest(path=path):
                result = classify([path], self.patterns)
                self.assertTrue(result["jvm"] and result["contracts"])
                self.assertFalse(result["map"] or result["map_slow"])

    def test_every_traced_map_input_runs_map_gates(self):
        for path in TRACED_MAP_INPUTS:
            with self.subTest(path=path):
                self.assertTrue((ROOT / path).exists(), "traced input moved; review MAP_INPUTS")
                result = classify([path], self.patterns)
                self.assertTrue(result["map"] and result["map_slow"] and result["contracts"])

    def test_non_map_contract_inputs_run_contracts_only(self):
        for path in ("web/gateway/app/admin/page.tsx", "docs/admin/game-server-recovery.md",
                     "tools/ci/naming_lint_baseline.json", ".github/workflows/reset-game-server.yml"):
            with self.subTest(path=path):
                result = classify([path], self.patterns)
                self.assertTrue(result["contracts"])
                self.assertFalse(result["map"])

    def test_web_quality_tools_run_the_web_job(self):
        # tools/web 의 적색 프로브는 web (game) 행 안에서 돈다 — 도구만 바꾼 PR 도 그 잡을 깨워야 한다.
        for path in ("tools/web/measure-pages.mjs", "tools/web/board-lint.test.mjs"):
            with self.subTest(path=path):
                self.assertTrue(classify([path], self.patterns)["web"])

    def test_server_sources_read_by_web_tests_run_the_web_job(self):
        # web/shared 의 종류 표 시험이 EventKind.kt 와 엔진 쓰기 위치를 읽는다 — 서버만 바꾼 PR 에서 바로 빨개져야 한다.
        for path in ("logic/src/main/kotlin/opensamguk/logic/record/EventKind.kt",
                     "app/game-engine/src/main/kotlin/opensamguk/engine/siege/RoadFortSiegeService.kt"):
            with self.subTest(path=path):
                self.assertTrue((ROOT / path).is_file(), path)
                self.assertTrue(classify([path], self.patterns)["web"])
        for path in ("logic/src/main/kotlin/opensamguk/logic/record/GameEvent.kt",
                     "app/game-api/src/main/kotlin/opensamguk/gameapi/read/EventFeedReader.kt",
                     "app/game-engine/src/test/kotlin/opensamguk/engine/status/StatusControllerTest.kt"):
            with self.subTest(path=path):
                self.assertFalse(classify([path], self.patterns)["web"])

    def test_unknown_top_level_path_runs_everything_heavy(self):
        result = classify(["docker/game-api.Dockerfile"], self.patterns)
        self.assertTrue(all(result[key] for key in ("jvm", "contracts", "map", "map_slow", "web")))

    def test_city_task_definition_triggers_city(self):
        self.assertTrue(classify(["app/game-engine/build.gradle.kts"], self.patterns)["city"])

    def test_path_file_requires_two_sections(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "city-paths.txt"
            path.write_text("[data]\ndata/map/han-tiles.json\n")
            with self.assertRaises(ValueError):
                city_patterns(path)


if __name__ == "__main__":
    unittest.main()
