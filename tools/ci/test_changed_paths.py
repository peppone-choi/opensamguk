import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from changed_paths import city_patterns, classify  # noqa: E402


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
