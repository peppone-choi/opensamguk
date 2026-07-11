import unittest
import sys
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_rtk14_stats as b


class Rtk14StatsBuilderTest(unittest.TestCase):
    def test_curated_alias_uses_raw_source_values(self):
        devsam = [
            {"name": "구역거", "L": 51, "S": 72, "I": 49, "born": 152, "dead": 193},
            {"name": "유약", "L": 67, "S": 63, "I": 61, "born": 206, "dead": 260},
        ]
        rtk = {
            "구력거": [{"born": 152, "dead": 193, "L": 80, "S": 69, "I": 56, "pol": 12, "cha": 34}],
            "유엽": [{"born": 176, "dead": 235, "L": 40, "S": 32, "I": 92, "pol": 98, "cha": 76}],
        }

        out, stats, report = b.assign(devsam, rtk)

        self.assertEqual({"politics": 12, "charm": 34}, out["구역거"])
        self.assertEqual({"politics": 50, "charm": 50}, out["유약"])
        self.assertEqual(["구역거->구력거"], report["aliases"])
        self.assertEqual(["유약"], report["fallback"])
        self.assertEqual(1, stats["alias"])

    def test_courtesy_name_uses_primary_name_candidates(self):
        devsam = [
            {"name": "조홍 자렴", "L": 79, "S": 83, "I": 53, "born": 169, "dead": 232},
        ]
        rtk = {
            "조홍": [
                {"born": 169, "dead": 232, "L": 79, "S": 83, "I": 53, "pol": 44, "cha": 71},
                {"born": 156, "dead": 184, "L": 61, "S": 72, "I": 48, "pol": 35, "cha": 52},
            ],
        }

        out, stats, report = b.assign(devsam, rtk)

        self.assertEqual({"politics": 44, "charm": 71}, out["조홍 자렴"])
        self.assertEqual(["조홍 자렴->조홍"], report["aliases"])
        self.assertEqual(1, stats["alias"])

    def test_duplicate_devsam_names_preserve_exact_keys_and_report_reuse(self):
        devsam = [
            {"name": "소제1", "L": 20, "S": 11, "I": 48, "born": 168, "dead": 190},
            {"name": "소제2", "L": 22, "S": 16, "I": 78, "born": 224, "dead": 268},
        ]
        rtk = {
            "소제": [{"born": 168, "dead": 190, "L": 20, "S": 11, "I": 48, "pol": 21, "cha": 31}],
        }

        out, stats, report = b.assign(devsam, rtk)

        self.assertEqual({"소제1", "소제2"}, set(out.keys()))
        self.assertEqual({"politics": 21, "charm": 31}, out["소제1"])
        self.assertEqual({"politics": 21, "charm": 31}, out["소제2"])
        self.assertEqual(["소제2"], report["collapsed"])
        self.assertEqual(1, stats["collapsed"])

    def test_inject_scenario_appends_politics_charm_to_general_tuples(self):
        scenario = {
            "general": [[1, "구역거", None, 0, None, 51, 72, 49, 0, 152, 193, "안전", None]],
            "general_ex": [[2, "없는장수", None, 0, None, 10, 10, 10, 0, 100, 200, None, None]],
        }
        injected = b.inject_scenario(scenario, {"구역거": {"politics": 12, "charm": 34}})

        self.assertEqual(12, injected["general"][0][14])
        self.assertEqual(34, injected["general"][0][15])
        self.assertEqual(50, injected["general_ex"][0][14])
        self.assertEqual(50, injected["general_ex"][0][15])
        self.assertEqual(16, len(injected["general"][0]))

    def test_inject_scenario_overwrites_only_existing_stat_slots(self):
        original = [1, "유비", None, 1, None, 76, 73, 74, 82, 161, 223, "대담", None, "keep", 1, 2]
        scenario = {"general": [original.copy()], "general_ex": []}

        injected = b.inject_scenario(scenario, {"유비": {"politics": 78, "charm": 99}})

        self.assertEqual(original[:14], injected["general"][0][:14])
        self.assertEqual([78, 99], injected["general"][0][14:16])
        self.assertEqual(16, len(injected["general"][0]))

    def test_committed_scenario_shape_is_491_plus_187_generals(self):
        scenario = b.read_scenario("infra/src/main/resources/scenario/scenario_1010.json")

        self.assertEqual(491, len(scenario["general"]))
        self.assertEqual(187, len(scenario["general_ex"]))
        self.assertEqual(678, len(b.read_devsam(scenario)))

    def test_build_one_preserves_original_filename_in_output_dir(self):
        rtk = {"구력거": [{"born": 152, "dead": 193, "L": 80, "S": 69, "I": 56, "pol": 12, "cha": 34}]}
        with TemporaryDirectory() as td:
            source = Path(td) / "scenario_9999.json"
            out_dir = Path(td) / "rtk14-scenarios.local"
            source.write_text(
                '{"general":[[1,"구역거",null,0,null,51,72,49,0,152,193,null,null]],"general_ex":[]}',
                encoding="utf-8",
            )

            out_path, devsam_count, entry_count, stats, report = b.build_one(source, out_dir, rtk)

            self.assertEqual(str(out_dir / "scenario_9999.json"), out_path)
            self.assertEqual(1, devsam_count)
            self.assertEqual(1, entry_count)
            self.assertEqual(1, stats["alias"])
            written = b.read_scenario(out_path)
            self.assertEqual(12, written["general"][0][14])
            self.assertEqual(34, written["general"][0][15])

    def test_rtk_source_json_keeps_raw_politics_and_charm_values(self):
        with TemporaryDirectory() as td:
            source = Path(td) / "rtk-source.json"
            source.write_text(
                '{"rows":[{"name":"구력거","born":152,"dead":193,"L":80,"S":69,"I":56,"pol":12,"cha":34}]}',
                encoding="utf-8",
            )

            rtk = b.read_rtk14_source_json(source)

            self.assertEqual(12, rtk["구력거"][0]["pol"])
            self.assertEqual(34, rtk["구력거"][0]["cha"])
            self.assertEqual([{
                "name": "구력거",
                "born": 152,
                "dead": 193,
                "L": 80,
                "S": 69,
                "I": 56,
                "pol": 12,
                "cha": 34,
            }], b.rtk_to_source_rows(rtk))


if __name__ == "__main__":
    unittest.main()
