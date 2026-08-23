#!/usr/bin/env python3
from __future__ import annotations

import json
import hashlib
import shutil
import sys
import tempfile
import unittest
from collections import Counter
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[3]
MAP_TOOLS = ROOT / "tools" / "map"
sys.path.insert(0, str(MAP_TOOLS))

import junguozhi_contract as CONTRACT  # noqa: E402
from junguozhi_contract import CatalogContractError, build_catalog, render_catalog  # noqa: E402


EXPECTED_GROUPS = (
    "河南尹 河內郡 河東郡 弘農郡 京兆尹 左馮翊 右扶風 "
    "潁川郡 汝南郡 梁國 沛國 陳國 魯國 "
    "魏郡 鉅鹿郡 常山國 中山國 安平國 河閒國 清河國 趙國 勃海郡 "
    "陳留郡 東郡 東平國 任城國 泰山郡 濟北國 山陽郡 濟陰郡 "
    "東海郡 琅邪國 彭城國 廣陵郡 下邳國 "
    "濟南國 平原郡 樂安國 北海國 東萊郡 齊國 "
    "南陽郡 南郡 江夏郡 零陵郡 桂陽郡 武陵郡 長沙郡 "
    "九江郡 丹陽郡 廬江郡 會稽郡 吳郡 豫章郡 "
    "漢中郡 巴郡 廣漢郡 蜀郡 犍為郡 牂牁郡 越巂郡 益州郡 永昌郡 "
    "廣漢屬國 蜀郡屬國 犍為屬國 "
    "隴西郡 漢陽郡 武都郡 金城郡 安定郡 北地郡 武威郡 張掖郡 酒泉郡 敦煌郡 "
    "張掖屬國 張掖居延屬國 "
    "上黨郡 太原郡 上郡 西河郡 五原郡 雲中郡 定襄郡 鴈門郡 朔方郡 "
    "涿郡 廣陽郡 代郡 上谷郡 漁陽郡 右北平郡 遼西郡 遼東郡 玄菟郡 樂浪郡 遼東屬國 "
    "南海郡 蒼梧郡 鬱林郡 合浦郡 交趾郡 九真郡 日南郡"
).split()
SOURCE_REFRESH_INPUTS = (
    *(ROOT / "data/corpus" / f"hhs-{volume:03d}.txt" for volume in (*range(109, 114), 65)),
    *(ROOT / "data/chgis-source/junguozhi" / f"{slug}.html" for slug in ("yi", "er", "san", "si", "wu")),
)


def copy_source_fixture(directory: str) -> Path:
    data_root = Path(directory) / "data"
    corpus = data_root / "corpus"
    corpus.mkdir(parents=True)
    for volume in (*range(109, 114), 65):
        shutil.copy2(ROOT / "data" / "corpus" / f"hhs-{volume:03d}.txt", corpus)
    shutil.copytree(ROOT / "data" / "chgis-source", data_root / "chgis-source")
    return corpus


@unittest.skipUnless(
    all(path.is_file() for path in SOURCE_REFRESH_INPUTS),
    "source-refresh-only: fetch gitignored HHS corpus with tools/corpus/fetch_sources.py",
)
class JunguozhiContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.catalog = build_catalog(ROOT / "data" / "corpus")

    def test_canonical_groups_are_exact_and_unique(self) -> None:
        groups = self.catalog["groups"]
        names = [group["canonicalGroup"] for group in groups]

        self.assertEqual(EXPECTED_GROUPS, names)
        self.assertEqual(105, len(names))
        self.assertEqual(105, len(set(names)))
        self.assertNotIn("巴陵秦置", names)
        self.assertNotIn("龜茲屬國", names)

    def test_group_type_splits_commandery_from_kingdom(self) -> None:
        types = [group["groupType"] for group in self.catalog["groups"]]
        counts = Counter(types)
        # 續漢書 郡國志 卷113 「凡郡、國百五」: 75郡 + 6屬國(卷118 百官志「屬國，分郡
        # 離遠縣置之，如郡差小，置本郡名」→ COMMANDERY) + 4尹/翊/風(卷117 百官志
        # 「司隸所部郡七 … 更以河南郡爲尹 … 其餘弘農、河內、河東三郡」— 郡의 장관
        # 관직명일 뿐 단위 종류가 아니므로 COMMANDERY) = 85, 純國 20 = 105.
        self.assertEqual({"COMMANDERY": 85, "KINGDOM": 20}, dict(counts))
        self.assertEqual("KINGDOM", next(g for g in self.catalog["groups"] if g["canonicalGroup"] == "魯國")["groupType"])
        self.assertEqual("COMMANDERY", next(g for g in self.catalog["groups"] if g["canonicalGroup"] == "河南尹")["groupType"])
        self.assertEqual("COMMANDERY", next(g for g in self.catalog["groups"] if g["canonicalGroup"] == "廣漢屬國")["groupType"])

    def test_all_units_have_unique_stable_source_identities(self) -> None:
        units = [unit for group in self.catalog["groups"] for unit in group["units"]]
        identities = [
            (unit["sourceVolume"], unit["canonicalGroup"], unit["ordinal"])
            for unit in units
        ]

        self.assertEqual(1180, len(units))
        self.assertEqual(1180, len(set(identities)))
        self.assertEqual(
            {"COUNTY": 1043, "DAO": 19, "MARQUISATE": 108, "TOWN": 10},
            self.catalog["unitTypeCounts"],
        )

    def test_source_mismatch_and_guizi_exception_are_explicit(self) -> None:
        self.assertEqual(
            [
                {
                    "sourceVolume": 110,
                    "canonicalGroup": "安平國",
                    "declaredCities": 13,
                    "enumeratedUnits": 12,
                }
            ],
            self.catalog["declaredVsEnumeratedMismatches"],
        )

        shang = next(group for group in self.catalog["groups"] if group["canonicalGroup"] == "上郡")
        guizi = shang["units"][8]
        self.assertEqual(113, guizi["sourceVolume"])
        self.assertEqual(9, guizi["ordinal"])
        self.assertEqual("龟兹属国", guizi["sourceName"])
        self.assertEqual("龜茲", guizi["nameCorrection"]["correctedName"])
        self.assertEqual(
            "龜茲音丘慈，縣名，屬上郡。", guizi["nameCorrection"]["sourceQuote"]
        )
        self.assertEqual("COUNTY", guizi["unitType"])

    def test_catalog_is_public_domain_identity_data_without_coordinates(self) -> None:
        encoded = json.dumps(self.catalog, ensure_ascii=False)

        self.assertEqual("後漢書", self.catalog["source"]["book"])
        self.assertEqual([109, 110, 111, 112, 113], self.catalog["source"]["volumes"])
        self.assertNotIn('"lon"', encoded)
        self.assertNotIn('"lat"', encoded)
        self.assertNotIn("CHGIS", encoded)
        self.assertNotIn("-{", encoded)
        self.assertNotIn("}-", encoded)

        units = [unit for group in self.catalog["groups"] for unit in group["units"]]
        self.assertTrue(all("canonicalName" not in unit for unit in units))
        self.assertEqual(1, sum("nameCorrection" in unit for unit in units))
        self.assertTrue(all("traditionalTextCitation" in group for group in self.catalog["groups"]))

        placeholder_units = [
            (
                unit["canonicalGroup"],
                unit["ordinal"],
                unit["sourceName"],
                unit["sourceNameIssue"]["witnessText"],
            )
            for unit in units
            if unit["sourceNameStatus"] == "SOURCE_PLACEHOLDER"
        ]
        self.assertEqual(
            [
                ("北地郡", 5, "参[�]", "參讀"),
                ("武威郡", 7, "朴[B459]", "樸峦"),
                ("交趾郡", 10, "朱[B42B]", "朱觏"),
            ],
            placeholder_units,
        )

    def test_group_citations_point_to_the_source_heading(self) -> None:
        source_lines: dict[Path, list[str]] = {}
        for group in self.catalog["groups"]:
            citation = group["sourceCitation"]
            path = ROOT / citation["corpusPath"]
            lines = source_lines.setdefault(path, path.read_text(encoding="utf-8").splitlines())

            self.assertIn(group["sourceGroupName"], lines[citation["line"] - 1])

    def test_unknown_source_heading_cannot_be_hidden_by_positional_canonicalization(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            corpus = copy_source_fixture(directory)
            first_volume = corpus / "hhs-109.txt"
            text = first_volume.read_text(encoding="utf-8")
            first_volume.write_text(text.replace("===河南尹===", "===偽河南尹===", 1), encoding="utf-8")

            mutated_hash = hashlib.sha256(first_volume.read_bytes()).hexdigest()
            with mock.patch.dict(CONTRACT.CORPUS_SHA256, {109: mutated_hash}), self.assertRaisesRegex(
                CatalogContractError, "source group sequence mismatch"
            ):
                build_catalog(corpus)

    def test_source_unit_mutation_cannot_preserve_a_false_green_count(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            corpus = copy_source_fixture(directory)
            first_volume = corpus / "hhs-109.txt"
            lines = first_volume.read_text(encoding="utf-8").splitlines()
            row_index = next(index for index, line in enumerate(lines) if "〖雒阳" in line)
            lines[row_index] = "not a source unit row"
            first_volume.write_text("\n".join(lines) + "\n", encoding="utf-8")

            mutated_hash = hashlib.sha256(first_volume.read_bytes()).hexdigest()
            with mock.patch.dict(CONTRACT.CORPUS_SHA256, {109: mutated_hash}), self.assertRaisesRegex(
                CatalogContractError, "unit count mismatch"
            ):
                build_catalog(corpus)

    def test_rendering_is_byte_deterministic_and_newline_terminated(self) -> None:
        first = render_catalog(self.catalog)
        second = render_catalog(build_catalog(ROOT / "data" / "corpus"))

        self.assertEqual(first, second)
        self.assertTrue(first.endswith("\n"))

    def test_committed_catalog_is_the_exact_generator_output(self) -> None:
        artifact = ROOT / "data" / "curated" / "han" / "administrative-units.json"

        self.assertEqual(render_catalog(self.catalog), artifact.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
