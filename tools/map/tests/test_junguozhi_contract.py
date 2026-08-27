#!/usr/bin/env python3
from __future__ import annotations

import json
import hashlib
import importlib.util
import shutil
import sys
import tempfile
import unittest
import urllib.error
from collections import Counter
from copy import deepcopy
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[3]
MAP_TOOLS = ROOT / "tools" / "map"
sys.path.insert(0, str(MAP_TOOLS))

import junguozhi_contract as CONTRACT  # noqa: E402
from junguozhi_contract import CatalogContractError, build_catalog, render_catalog  # noqa: E402

FETCH_SPEC = importlib.util.spec_from_file_location(
    "fetch_sources", ROOT / "tools" / "corpus" / "fetch_sources.py"
)
assert FETCH_SPEC is not None and FETCH_SPEC.loader is not None
FETCH = importlib.util.module_from_spec(FETCH_SPEC)
FETCH_SPEC.loader.exec_module(FETCH)


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
CTEXT_REFRESH_INPUTS = tuple(
    ROOT / "data/corpus/ctext/junguozhi" / f"{slug}.html"
    for slug in ("yi", "er", "san", "si", "wu")
)
SOURCE_REFRESH_INPUTS = (
    *(ROOT / "data/corpus" / f"hhs-{volume:03d}.txt" for volume in (*range(109, 114), 65)),
    *CTEXT_REFRESH_INPUTS,
)


class CTextFetchContractTest(unittest.TestCase):
    def test_required_ctext_fetch_propagates_terminal_url_error_after_retries(self) -> None:
        # Production break caught: collapsing an exhausted required CText network
        # failure to the same None as an already-cached file lets the CLI false-green.
        requested_urls: list[str] = []

        def open_url(request, timeout):
            self.assertEqual(30, timeout)
            requested_urls.append(request.full_url)
            raise urllib.error.URLError("offline")

        job = (
            "https://ctext.org/hou-han-shu/jun-guo-yi/zh",
            "ctext/junguozhi/yi.html",
        )
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(
            FETCH, "OUT", directory
        ), mock.patch.object(FETCH.urllib.request, "urlopen", open_url):
            with self.assertRaisesRegex(RuntimeError, "required source fetch failed"):
                FETCH.fetch(job)

            self.assertEqual([job[0], job[0], job[0]], requested_urls)
            self.assertFalse((Path(directory) / job[1]).exists())

    def test_ctext_only_cli_fails_when_required_outputs_remain_missing(self) -> None:
        # Production break caught: the bounded CLI must select only the five CText
        # jobs and cannot report success when redirects leave every output missing.
        requested_urls: list[str] = []

        class RedirectResponse:
            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return None

            def read(self) -> bytes:
                return b"#REDIRECT missing"

        def open_url(request, timeout):
            self.assertEqual(30, timeout)
            requested_urls.append(request.full_url)
            return RedirectResponse()

        with tempfile.TemporaryDirectory() as directory, mock.patch.object(
            FETCH, "OUT", directory
        ), mock.patch.object(FETCH.urllib.request, "urlopen", open_url):
            try:
                result = FETCH.main(["--ctext-only", "--jobs", "1"])
            except (TypeError, SystemExit) as error:
                result = f"unsupported bounded CLI: {error}"

            self.assertEqual(1, result)
            self.assertEqual(
                [
                    f"https://ctext.org/hou-han-shu/jun-guo-{slug}/zh"
                    for slug in ("yi", "er", "san", "si", "wu")
                ],
                requested_urls,
            )

    def test_ctext_only_cli_returns_nonzero_on_terminal_url_failures(self) -> None:
        # Production break caught: the CLI must translate propagated required-source
        # failures into a non-zero process result, never optional-title success.
        requested_urls: list[str] = []

        def open_url(request, timeout):
            self.assertEqual(30, timeout)
            requested_urls.append(request.full_url)
            raise urllib.error.URLError("offline")

        with tempfile.TemporaryDirectory() as directory, mock.patch.object(
            FETCH, "OUT", directory
        ), mock.patch.object(FETCH.urllib.request, "urlopen", open_url):
            result = FETCH.main(["--ctext-only", "--jobs", "4"])

            self.assertEqual(1, result)
            self.assertEqual(
                {
                    f"https://ctext.org/hou-han-shu/jun-guo-{slug}/zh": 3
                    for slug in ("yi", "er", "san", "si", "wu")
                },
                dict(Counter(requested_urls)),
            )

    @unittest.skipUnless(
        all(path.is_file() for path in CTEXT_REFRESH_INPUTS),
        "source-refresh-only: fetch CText snapshots with --ctext-only",
    )
    def test_ctext_only_cli_accepts_all_five_reviewed_nonempty_snapshots(self) -> None:
        # Production break caught: already-cached reviewed snapshots are a valid
        # bounded success and must not trigger unrelated legacy or network work.
        def unexpected_open(*_args, **_kwargs):
            raise AssertionError("reviewed CText cache should avoid HTTP")

        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory) / "ctext" / "junguozhi"
            cache.mkdir(parents=True)
            for slug in ("yi", "er", "san", "si", "wu"):
                shutil.copy2(ROOT / "data/corpus/ctext/junguozhi" / f"{slug}.html", cache)
            with mock.patch.object(FETCH, "OUT", directory), mock.patch.object(
                FETCH.urllib.request, "urlopen", unexpected_open
            ):
                result = FETCH.main(["--ctext-only", "--jobs", "4"])

            self.assertEqual(0, result)

    @unittest.skipUnless(
        all(path.is_file() for path in CTEXT_REFRESH_INPUTS),
        "source-refresh-only: fetch CText snapshots with --ctext-only",
    )
    def test_ctext_only_cli_rejects_nonempty_snapshot_with_wrong_hash(self) -> None:
        # Production break caught: non-empty is necessary but not sufficient; an
        # already-cached page must still match the reviewed snapshot contract.
        def unexpected_open(*_args, **_kwargs):
            raise AssertionError("non-empty CText cache should reach hash validation")

        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory) / "ctext" / "junguozhi"
            cache.mkdir(parents=True)
            for path in CTEXT_REFRESH_INPUTS:
                shutil.copy2(path, cache)
            with (cache / "yi.html").open("ab") as stream:
                stream.write(b"drift")
            with mock.patch.object(FETCH, "OUT", directory), mock.patch.object(
                FETCH.urllib.request, "urlopen", unexpected_open
            ):
                result = FETCH.main(["--ctext-only", "--jobs", "4"])

            self.assertEqual(1, result)

    def test_fetch_rejects_concurrency_above_the_existing_safe_default(self) -> None:
        # Production break caught: accepting --jobs above four recreates the known
        # upstream empty-body failure mode and invalidates the non-empty contract.
        parse_jobs = getattr(FETCH, "safe_jobs", int)

        with self.assertRaises(ValueError):
            parse_jobs("5")
        self.assertEqual(4, parse_jobs("4"))

    def test_fetch_catalog_includes_all_five_ctext_pages_under_public_domain_cache(self) -> None:
        # Production break caught: removing or misrouting any CText volume leaves the
        # committed evidence without a reproducible public-domain snapshot.
        expected = [
            (
                f"https://ctext.org/hou-han-shu/jun-guo-{slug}/zh",
                f"ctext/junguozhi/{slug}.html",
            )
            for slug in ("yi", "er", "san", "si", "wu")
        ]

        self.assertEqual(expected, [job for job in FETCH.titles() if job[1].endswith(".html")])

    def test_fetch_routes_ctext_url_to_nested_cache_and_preserves_response_bytes(self) -> None:
        # Production break caught: treating a CText URL as a Wikisource title or
        # failing to create its nested cache directory makes the fetch unreproducible.
        requested_urls: list[str] = []
        body = b"<html><td class=\"ctext\">\xe6\xb2\xb3\xe5\x8d\x97\xe5\xb0\xb9</td></html>"

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return None

            def read(self) -> bytes:
                return body

        def open_url(request, timeout):
            self.assertEqual(30, timeout)
            requested_urls.append(request.full_url)
            return Response()

        job = (
            "https://ctext.org/hou-han-shu/jun-guo-yi/zh",
            "ctext/junguozhi/yi.html",
        )
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(
            FETCH, "OUT", directory
        ), mock.patch.object(FETCH.urllib.request, "urlopen", open_url):
            try:
                result = FETCH.fetch(job)
            except FileNotFoundError:
                result = "nested-cache-directory-was-not-created"
            cached = Path(directory) / job[1]

            self.assertEqual(job[1], result)
            self.assertEqual([job[0]], requested_urls)
            self.assertEqual(body, cached.read_bytes() if cached.exists() else None)

    def test_fetch_retries_empty_ctext_response_without_creating_a_snapshot(self) -> None:
        # Production break caught: accepting an HTTP-200 empty body would pin a
        # meaningless snapshot and silently erase the evidence corpus.
        requested_urls: list[str] = []

        class EmptyResponse:
            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return None

            def read(self) -> bytes:
                return b""

        def open_url(request, timeout):
            self.assertEqual(30, timeout)
            requested_urls.append(request.full_url)
            return EmptyResponse()

        job = (
            "https://ctext.org/hou-han-shu/jun-guo-yi/zh",
            "ctext/junguozhi/yi.html",
        )
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(
            FETCH, "OUT", directory
        ), mock.patch.object(FETCH.urllib.request, "urlopen", open_url):
            result = FETCH.fetch(job)
            cached = Path(directory) / job[1]

            self.assertEqual(("empty", job[1]), result)
            self.assertEqual([job[0], job[0], job[0]], requested_urls)
            self.assertFalse(cached.exists())


def copy_source_fixture(directory: str) -> Path:
    data_root = Path(directory) / "data"
    corpus = data_root / "corpus"
    corpus.mkdir(parents=True)
    for volume in (*range(109, 114), 65):
        shutil.copy2(ROOT / "data" / "corpus" / f"hhs-{volume:03d}.txt", corpus)
    shutil.copytree(ROOT / "data/corpus/ctext", corpus / "ctext")
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

    def test_group_evidence_carries_genuine_heading_bounded_passage_and_exact_coverage(self) -> None:
        # Production break caught: replacing a traditional passage with mixed-script
        # display names, dropping its stable locator, or omitting a member makes the
        # prerequisite unusable for evidence inheritance.
        group = self.catalog["groups"][0]
        expected_quote = (
            "河南尹\n"
            "二十一城，永和五年戶二十萬八千四百八十六，口百一萬八百二十七。\n"
            "雒陽\n"
            "周時號成周。有狄泉，在城中。有唐聚。有上程聚。有士鄉聚。有褚氏聚。"
            "有榮錡澗。有前亭。有圉鄉。有大解城。河南周公時所城雒邑也，春秋時謂之王城。"
            "東城門名鼎門，北城門名乾祭。又有甘城，有蒯鄉。梁故國，伯翳後。有霍陽山。"
            "有注城。熒陽有鴻溝水。有廣武城。有总亭，总叔國。有隴城。有薄亭。有敖亭。"
            "有費澤。卷\n"
            "有長城，經陽武到密。有垣雝城，或曰古衡雍。有扈城亭。原武陽武中牟\n"
            "有圃田澤。有清口水。有管城。有曲遇聚。有蔡亭。開封菀陵有棐林。有制澤。"
            "有瑣侯亭。平陰穀城瀍水出。有函谷關。緱氏有鄔聚。有轘轅關。鞏\n"
            "有尋谷水。有東訾聚，今名訾城。有坎埳聚。有黃亭。有湟水。有明谿泉。"
            "成睪有旃然水。有瓶丘聚。有漫水。有汜水。京密\n"
            "有大騩山。有梅山。有陘山。新城\n"
            "有高都城。有廣成聚。有鄤聚，古鄤氏，今名蠻中。匽師\n"
            "有尸鄉，春秋時曰尸氏。新鄭詩鄭國，祝融墟。平"
        )

        self.assertEqual(
            {
                "book": "後漢書",
                "volume": "郡國一",
                "section": "河南尹 (n78666-n78675)",
                "quote": expected_quote,
                "grade": "STANDARD_HISTORY",
                "claim": "group-membership-attested",
                "locationConfidence": "UNKNOWN",
            },
            group.get("evidence", [None])[0],
        )
        self.assertEqual("n78666-n78675", group["traditionalTextCitation"].get("locator"))
        self.assertEqual(
            [f'[109,"河南尹",{ordinal}]' for ordinal in range(1, 22)],
            group.get("memberCoverageIds"),
        )

        all_coverage = [
            member_id
            for catalog_group in self.catalog["groups"]
            for member_id in catalog_group.get("memberCoverageIds", [])
        ]
        self.assertEqual(1180, len(all_coverage))
        self.assertEqual(1180, len(set(all_coverage)))
        self.assertTrue(all(len(catalog_group.get("evidence", [])) == 1 for catalog_group in self.catalog["groups"]))

    def _validate_evidence(self, catalog) -> None:
        validator = getattr(CONTRACT, "validate_catalog_evidence", None)
        if validator is None:
            raise CatalogContractError("evidence validator is missing")
        validator(catalog, ROOT / "data/corpus/ctext/junguozhi")

    def test_mutated_ctext_passage_fails_after_the_mutated_snapshot_hash_is_verified(self) -> None:
        # Production break caught: trusting a once-generated quote after the pinned
        # snapshot's passage bytes change severs quote provenance.
        with tempfile.TemporaryDirectory() as directory:
            corpus = copy_source_fixture(directory)
            ctext_dir = corpus / "ctext" / "junguozhi"
            page = ctext_dir / "yi.html"
            page.write_text(
                page.read_text(encoding="utf-8").replace("周時號成周。", "周時號偽成周。", 1),
                encoding="utf-8",
            )
            mutated_hash = hashlib.sha256(page.read_bytes()).hexdigest()
            catalog = deepcopy(self.catalog)
            catalog["groups"][0]["traditionalTextCitation"]["snapshotSha256"] = mutated_hash
            with mock.patch.dict(CONTRACT.CTEXT_SHA256, {109: mutated_hash}), self.assertRaisesRegex(
                CatalogContractError, "evidence quote mismatch"
            ):
                CONTRACT.validate_catalog_evidence(catalog, ctext_dir)

    def test_mutated_evidence_locator_fails_for_the_intended_reason(self) -> None:
        # Production break caught: a quote with a locator outside its heading-bounded
        # source range cannot be independently audited.
        catalog = deepcopy(self.catalog)
        catalog["groups"][0]["traditionalTextCitation"]["locator"] = "n1-n2"

        with self.assertRaisesRegex(CatalogContractError, "evidence locator mismatch"):
            self._validate_evidence(catalog)

    def test_mutated_evidence_snapshot_hash_fails_for_the_intended_reason(self) -> None:
        # Production break caught: catalog evidence must name the exact verified
        # snapshot, not merely a page URL that can drift.
        catalog = deepcopy(self.catalog)
        catalog["groups"][0]["traditionalTextCitation"]["snapshotSha256"] = "0" * 64

        with self.assertRaisesRegex(CatalogContractError, "evidence snapshot hash mismatch"):
            self._validate_evidence(catalog)

    def test_missing_coverage_member_fails_for_the_intended_reason(self) -> None:
        # Production break caught: incomplete inheritance could silently leave one
        # existing unit without the reviewed group passage.
        catalog = deepcopy(self.catalog)
        catalog["groups"][0]["memberCoverageIds"].pop()

        with self.assertRaisesRegex(CatalogContractError, "missing member"):
            self._validate_evidence(catalog)

    def test_foreign_coverage_member_fails_for_the_intended_reason(self) -> None:
        # Production break caught: coverage must never leak across group or volume
        # boundaries even when the foreign ID is otherwise valid.
        catalog = deepcopy(self.catalog)
        catalog["groups"][0]["memberCoverageIds"][0] = catalog["groups"][1][
            "memberCoverageIds"
        ][0]

        with self.assertRaisesRegex(CatalogContractError, "foreign member"):
            self._validate_evidence(catalog)

    def test_duplicate_coverage_member_fails_for_the_intended_reason(self) -> None:
        # Production break caught: duplicate IDs can hide a missing all-and-only
        # member when validation compares only list length.
        catalog = deepcopy(self.catalog)
        catalog["groups"][0]["memberCoverageIds"][-1] = catalog["groups"][0][
            "memberCoverageIds"
        ][0]

        with self.assertRaisesRegex(CatalogContractError, "duplicate member"):
            self._validate_evidence(catalog)

    def test_unit_with_wrong_stored_volume_fails_for_the_intended_reason(self) -> None:
        # Production break caught: expected coverage derived only from the enclosing
        # group can hide a unit whose own stored sourceVolume crosses a volume boundary.
        catalog = deepcopy(self.catalog)
        catalog["groups"][0]["units"][0]["sourceVolume"] = 110

        with self.assertRaisesRegex(CatalogContractError, "unit sourceVolume mismatch"):
            self._validate_evidence(catalog)

    def test_unit_with_foreign_stored_group_fails_for_the_intended_reason(self) -> None:
        # Production break caught: expected coverage derived only from the enclosing
        # group can hide a unit whose own canonicalGroup belongs to another group.
        catalog = deepcopy(self.catalog)
        catalog["groups"][0]["units"][0]["canonicalGroup"] = "河內郡"

        with self.assertRaisesRegex(CatalogContractError, "unit canonicalGroup mismatch"):
            self._validate_evidence(catalog)

    def test_mixed_script_source_names_substituted_as_quote_fail(self) -> None:
        # Production break caught: synthesizing a quote from mixed-script sourceName
        # values fabricates text that is absent from the traditional CText witness.
        catalog = deepcopy(self.catalog)
        group = catalog["groups"][0]
        group["evidence"][0]["quote"] = " ".join(unit["sourceName"] for unit in group["units"])

        with self.assertRaisesRegex(CatalogContractError, "evidence quote mismatch"):
            self._validate_evidence(catalog)

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
