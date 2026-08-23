#!/usr/bin/env python3
"""#524 회귀 고정: CHGIS TYPE_CH만으로는 郡·國·侯國·屬國을 못 가른다.

build_han_places.classify()가 정본 카탈로그(data/curated/han/administrative-units.json,
git 커밋됨 — CHGIS shapefile과 달리 gitignore 밖이라 신선한 체크아웃에서도 동작한다)로
CHGIS의 애매한 TYPE_CH='国'/'侯国'/'郡' 표기를 되짚는지 검증한다.

실측(#524 이슈 본문 + 이 티켓에서 CHGIS dbf를 직접 읽어 재확인):
  - TYPE_CH='国' 은 진짜 KINGDOM과 마을급 侯國(安眾/新成/征羌侯國)·屬國(犍為屬國)·
    郡으로 잘못 적힌 것(樂安郡)이 전부 뒤섞여 있다.
  - 반대로 常山/趙/中山/齊/北海/琅邪/梁/陳/下邳/彭城 같은 진짜 KINGDOM은 220년 그 해만
    TYPE_CH='郡'|'侯国'로 강등돼 있어 그대로 두면 COMMANDERY/COUNTY로 떨어진다.
"""
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MAP_TOOLS = ROOT / "tools" / "map"
sys.path.insert(0, str(MAP_TOOLS))

from build_han_places import classify, GROUP_KIND  # noqa: E402


class MisclassifiedGroupsAreFixedTest(unittest.TestCase):
    """#524에서 KINGDOM으로 오승격됐다고 보고된 侯國/屬國/郡이 더 이상 오승격되지 않는다."""

    def test_village_level_marquisates_stay_county(self):
        # TYPE_CH='国' 이지만 정본 카탈로그에 없는 마을급 侯國 — KINGDOM으로 승격 금지.
        for name_ch, name_ft in (
            ('安众侯国', '安眾侯國'),
            ('征羌侯国', '征羌侯國'),
            ('新成侯国', '新成侯國'),
        ):
            with self.subTest(name=name_ft):
                self.assertEqual(classify('国', name_ch, name_ft), ('COUNTY', 5))

    def test_unlisted_state_falls_back_to_county_not_kingdom(self):
        # 衛國: 정본 105개 群에 아예 없다 — 최소 KINGDOM 오승격은 막는다.
        self.assertEqual(classify('国', '卫国', '衛國'), ('COUNTY', 5))

    def test_dependency_is_commandery_not_kingdom(self):
        # 犍為屬國: #507 자체 근거로도 COMMANDERY다.
        self.assertEqual(classify('国', '犍为属国', '犍為屬國'), ('COMMANDERY', 6))

    def test_commandery_misnamed_with_kingdom_type_code_is_corrected(self):
        # 樂安郡: 이름은 郡이지만 정본 카탈로그의 樂安國은 KINGDOM이다 — 카탈로그가 이긴다.
        self.assertEqual(classify('国', '乐安郡', '樂安郡'), ('KINGDOM', 6))

    def test_renamed_commandery_is_not_promoted(self):
        # 陳留國: 정본 카탈로그의 陳留郡은 COMMANDERY다.
        self.assertEqual(classify('国', '陈留国', '陳留國'), ('COMMANDERY', 6))


class UndetectedKingdomsAreNowDetectedTest(unittest.TestCase):
    """#524에서 14/20 群 미탐지로 보고된 것 중, 220년 그 해 TYPE_CH가 '郡'|'侯国'로 강등돼
    있던 10개가 정본 카탈로그 override로 KINGDOM으로 되돌아온다."""

    def test_kingdoms_demoted_to_commandery_type_code_at_year_220_are_restored(self):
        for name_ch, name_ft in (
            ('常山郡', '常山郡'), ('赵郡', '趙郡'), ('中山郡', '中山郡'),
            ('齐郡', '齊郡'), ('北海郡', '北海郡'), ('琅邪郡', '琅邪郡'),
            ('梁郡', '梁郡'), ('陈郡', '陳郡'), ('下邳郡', '下邳郡'),
        ):
            with self.subTest(name=name_ft):
                self.assertEqual(classify('郡', name_ch, name_ft), ('KINGDOM', 6))

    def test_marquisate_type_code_kingdom_seat_is_restored(self):
        # 彭城國: CHGIS가 侯国로 적어놨지만 정본 카탈로그는 KINGDOM이다.
        self.assertEqual(classify('侯国', '彭城国', '彭城國'), ('KINGDOM', 6))


class MatchingMustBeExactStemNotSubstringTest(unittest.TestCase):
    """카탈로그 조회는 정확한 어간 일치여야 한다 — 부분 일치로 느슨해지면 안 된다.

    실측(#524 검증 중 발견): 카탈로그 자체에 '陳'(KINGDOM)이 '陳留'(COMMANDERY)의
    부분 문자열로 존재한다. 조회를 `dict.get(exact_stem)` 대신 in-연산 부분 일치로
    바꾸면, 카탈로그에 없는 이름이 우연히 다른 群 이름을 포함/포함됨으로써 잘못
    승격될 수 있다 — 이 테스트는 group_kind를 직접 주입해 그 실패 모드를 고정한다.
    """

    def test_unrelated_marquisate_does_not_falsely_match_a_superstring_group(self):
        # '陳留下侯國' 는 카탈로그에 없는 가상의 마을급 侯國이다. 정확 일치는 실패해야
        # 하고, '陳留'(COMMANDERY)를 부분 문자열로 포함한다고 승격돼선 안 된다.
        fake_catalog = {'陳留': 'COMMANDERY', '陳': 'KINGDOM'}
        self.assertEqual(
            classify('国', '陈留下侯国', '陳留下侯國', group_kind=fake_catalog),
            ('COUNTY', 5),
        )

    def test_unrelated_commandery_named_type_code_does_not_falsely_match_a_substring_group(self):
        # 반대 방향: 정본에 없는 郡 이름이 카탈로그의 짧은 群 이름을 부분 문자열로
        # 포함한다고 해서 그 group의 groupType을 빌려와선 안 된다.
        fake_catalog = {'陳': 'KINGDOM'}
        self.assertEqual(
            classify('郡', '陈留郡', '陳留郡', group_kind=fake_catalog),
            ('COMMANDERY', 6),
        )


class OrdinaryClassificationIsUnaffectedTest(unittest.TestCase):
    """카탈로그 override는 애매한 4개 TYPE_CH에만 걸린다 — 나머지 등급은 그대로다."""

    def test_ordinary_commandery_without_catalog_ambiguity_stays_commandery(self):
        self.assertEqual(classify('郡', '汝南郡', '汝南郡'), ('COMMANDERY', 6))

    def test_county_and_province_type_codes_are_untouched(self):
        self.assertEqual(classify('县', '某县', '某縣'), ('COUNTY', 5))
        self.assertEqual(classify('州', '某州', '某州'), ('PROVINCE', 7))

    def test_unknown_type_code_is_dropped(self):
        self.assertIsNone(classify('無効', 'x', 'x'))


class CatalogIsWiredFromCommittedFile(unittest.TestCase):
    """GROUP_KIND는 git에 커밋된 정본 카탈로그에서 로드된다 — CHGIS shapefile과 달리
    gitignore 밖이라 신선한 체크아웃에서도 항상 채워져 있어야 한다(#510: 입력 부재로
    테스트가 조용히 손상되지 않게 한다)."""

    def test_group_kind_is_populated_from_the_committed_catalog(self):
        self.assertGreater(len(GROUP_KIND), 0)
        self.assertEqual(GROUP_KIND.get('樂安'), 'KINGDOM')
        self.assertEqual(GROUP_KIND.get('陳留'), 'COMMANDERY')

    def test_catalog_only_ever_reports_kingdom_or_commandery(self):
        from collections import Counter
        counts = Counter(GROUP_KIND.values())
        # canonicalGroup과 sourceGroupName 둘 다 인덱싱해서 105개 群보다 항목 수가
        # 많을 수 있으니(#524 조사: 濟南國의 sourceGroupName은 '濟南') stem 단위가
        # 아니라 groupType 값 자체의 존재만 확인한다.
        self.assertEqual(set(counts), {'KINGDOM', 'COMMANDERY'})


if __name__ == '__main__':
    unittest.main()
