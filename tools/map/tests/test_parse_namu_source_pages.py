import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location('namu_parser', ROOT / 'tools/map/parse_namu_source_pages.py')
PARSER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PARSER)


class NamuSourceParserTest(unittest.TestCase):
    def page(self, text):
        return PARSER.parse_page(text, title='삼국지/지명/옹주', source_id='fixture')

    def test_group_alias_and_county_timeline_keep_separate_attribution(self):
        result = self.page('''9. 천수군(天水郡)[편집]
지명
[한]한양군(漢陽郡)→[삼국]천수군
성기현(成紀縣)
지명: 성기(成紀)
소속: [한]량주자사부 한양군→[삼국]옹주 천수군
위치: 《35.24150, 105.68042》 간쑤성 핑량시
''')
        section = result['sections'][0]
        county = result['records'][0]
        self.assertEqual(['天水郡'], section['namesHan'])
        self.assertEqual(['漢陽郡'], section['aliases'][0]['namesHan'])
        self.assertEqual(section['sectionId'], county['sectionId'])
        self.assertEqual(['成紀縣'], county['namesHan'])
        self.assertEqual(['한', '삼국'], [x['marker'] for x in county['affiliations'][0]['timeline']])
        self.assertEqual(7, county['locations'][0]['line'])
        self.assertEqual('EXPLICIT', county['coordinateStatus'])

    def test_temporal_multiple_seats_do_not_absorb_subordinate_gate(self):
        result = self.page('''2. 경조군(京兆郡)[편집]
신풍현(新豐縣)
위치: 189년에 치소를 이전했다.
~189: 《34.39895, 109.25768》 친링가도
189~: 《34.44956, 109.37344》 셰커우가도
홍문정(鴻門亭): 《34.41441, 109.27544》
남전현(藍田縣)
위치: 《34.23927, 109.14697》 시안시
남전관(藍田關): 《33.95802, 109.46652》?
''')
        first, gate, second, other = result['records']
        self.assertEqual('MULTIPLE', first['coordinateStatus'])
        self.assertEqual(['~189', '189~'], [x['label'] for x in first['locations'] if x['coordinatePairs']])
        self.assertEqual('OTHER_NAMED_SITE', gate['kind'])
        self.assertEqual(first['recordId'], gate['parentCountyId'])
        self.assertEqual(1, len(second['locations']))
        self.assertIn('UNCERTAIN', other['flags'])

    def test_alias_variants_preserve_county_state_and_dao_suffixes(self):
        result = self.page('''2. 하남윤(河南尹)[편집]
성고현(成皐縣·成睾縣)
위치: 《34.8, 113.2》
효국(猇國): 후한 폐지
책도(翟道): 후한 폐지
''')
        self.assertEqual([['成皐縣', '成睾縣'], ['猇國'], ['翟道']], [r['namesHan'] for r in result['records']])
        self.assertEqual('MISSING', result['records'][1]['coordinateStatus'])

    def test_unknown_and_uncertain_coordinates_are_not_single_confirmed_point(self):
        result = self.page('''8. 제북국(濟北國)[편집]
성현(成縣)?: 《35.84921, 117.19174》
이는 잘못된 비정이라 판단된다.
영주현(靈州縣)
위치: 어디인지 알 수 없다.
''')
        self.assertIn('UNCERTAIN', result['records'][0]['flags'])
        self.assertIn('DISPUTED', result['records'][0]['flags'])
        self.assertEqual('UNKNOWN', result['records'][1]['coordinateStatus'])

    def test_prose_coordinates_are_attributed_as_mentions_not_county_locations(self):
        result = self.page('''2. 경조군(京兆郡)[편집]
신풍현(新豐縣)
이 성터(《34.1, 109.2》)는 다른 후보이다.
홍문정(鴻門亭): 위치 미상
''')
        self.assertEqual([], result['records'][0]['locations'])
        self.assertEqual('UNASSIGNED_PROSE', result['coordinateMentions'][0]['scope'])
        self.assertEqual('UNKNOWN', result['records'][1]['coordinateStatus'])

    def test_toc_and_narrative_name_are_not_headings_and_empty_sections_survive(self):
        result = self.page('''2. 경조군(京兆郡)
2. 경조군(京兆郡)[편집]
3. 빙익군(馮翊郡)[편집]
약양현(櫟陽縣)은 전한 때 있었다.
''')
        self.assertEqual(2, len(result['sections']))
        self.assertEqual([], result['records'])

    def test_manifest_deduplicates_identical_sources_and_checks_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'source.txt'
            source.write_text('최근 수정 시각: 2026-07-15 07:47:15\n2. 경조군(京兆郡)[편집]\n신풍현(新豐縣)\n')
            entry = {'path': str(source), 'title': '삼국지/지명/옹주',
                     'sha256': hashlib.sha256(source.read_bytes()).hexdigest()}
            manifest = {'files': [entry, dict(entry)]}
            first = PARSER.parse_manifest(manifest)
            self.assertEqual(first, PARSER.parse_manifest(manifest))
            self.assertEqual(1, len(first['sources']))
            self.assertEqual('https://namu.wiki/w/삼국지/지명/옹주', first['sources'][0]['sourceUrl'])
            self.assertEqual('2026-07-15 07:47:15', first['sources'][0]['sourceLastModified'])
            self.assertEqual(2, first['sources'][0]['attachmentCount'])
            self.assertNotIn(directory, json.dumps(first))
            source.write_text('changed')
            with self.assertRaises(ValueError):
                PARSER.parse_manifest(manifest)

    def test_invalid_coordinate_is_reported_without_valid_pair(self):
        result = self.page('2. 경조군(京兆郡)[편집]\n신풍현(新豐縣)\n위치: 《91, 109.2》\n')
        self.assertEqual('UNPARSED', result['records'][0]['coordinateStatus'])
        self.assertEqual([], result['records'][0]['locations'][0]['coordinatePairs'])
        self.assertEqual('INVALID_COORDINATE', result['diagnostics'][0]['code'])

    def test_actual_whitespace_and_bracket_typos_are_flagged_explicit_coordinates(self):
        result = self.page('2. 남해군(南海郡)[편집]\n수춘현(壽春縣)\n위치: 《32.58125 116.77905》 안후이성\n중도현(中都縣)\n위치: 《24.67911, 109.69725]광시자치구 일대?\n')
        self.assertEqual(2, len(result['records']))
        self.assertEqual({'lat': 32.58125, 'lon': 116.77905}, result['records'][0]['locations'][0]['coordinatePairs'][0])
        self.assertEqual({'lat': 24.67911, 'lon': 109.69725}, result['records'][1]['locations'][0]['coordinatePairs'][0])
        self.assertEqual(2, len(result['diagnostics']))

    def test_book_title_brackets_are_not_coordinate_diagnostics(self):
        result = self.page('2. 경조군(京兆郡)[편집]\n신풍현(新豐縣)\n《한서》에 나온다.\n')
        self.assertEqual([], result['diagnostics'])

    def test_korean_only_group_alias_uses_same_section_explicit_gloss(self):
        result = self.page('9. 천수군(天水郡)[편집]\n74년에 한양군(漢陽郡)으로 개명했다. 농서군(隴西郡)을 분할했다.\n지명\n[-113]천수군→[74]한양군→[220]천수군\n성기현(成紀縣)\n')
        alias = result['sections'][0]['aliases'][0]
        self.assertIn('漢陽郡', alias['namesHan'])
        self.assertNotIn('隴西郡', alias['namesHan'])
        self.assertEqual(2, next(g['line'] for g in alias['nameAttributions'] if g['nameHan'] == '漢陽郡'))

    def test_korean_only_neighbor_label_does_not_leak_into_county(self):
        result = self.page('2. 무평군(武平郡)[편집]\n봉계현(封谿縣)\n위치: 《21.211374, 105.71751》\n무정현?: 《21.22074, 105.74098》\n')
        county, site = result['records']
        self.assertEqual('EXPLICIT', county['coordinateStatus'])
        self.assertEqual(1, len(county['locations']))
        self.assertEqual('OTHER_NAMED_SITE', site['kind'])
        self.assertEqual(county['recordId'], site['parentCountyId'])

    def test_explicit_own_alias_location_label_is_kept_qualified(self):
        result = self.page('9. 천수군(天水郡)[편집]\n서현(西縣)\n지명: 서현→시창현(始昌縣)\n위치: 서진 때 현을 이전했다.\n서현: 《34.4, 105.3》\n시창현: 《34.5, 105.4》\n')
        self.assertEqual(1, len(result['records']))
        self.assertEqual('MULTIPLE', result['records'][0]['coordinateStatus'])

    def test_number_in_named_site_label_is_not_a_temporal_phase(self):
        result = self.page('2. 경조군(京兆郡)[편집]\n신풍현(新豐縣)\n위치: 《34.1, 109.2》\n제1고성: 《34.3, 109.4》\n')
        self.assertEqual(2, len(result['records']))
        self.assertEqual('EXPLICIT', result['records'][0]['coordinateStatus'])
        self.assertEqual('OTHER_NAMED_SITE', result['records'][1]['kind'])

    def test_temporal_label_grammar_accepts_only_attested_year_or_era_forms(self):
        for label in ['~190', '190~', '~234?', '234?~', '~기원전180', '전한', '서진 이후', '삼국 이전', '한', '위']:
            result = self.page('2. 경조군(京兆郡)[편집]\n신풍현(新豐縣)\n위치: 치소 이전\n' + label + ': 《34.1, 109.2》\n')
            with self.subTest(label=label):
                self.assertEqual(1, len(result['records']))
                self.assertEqual('EXPLICIT', result['records'][0]['coordinateStatus'])

    def test_baidicheng_alias_is_named_mention_without_inherited_county_coordinate(self):
        result = self.page('9. 파동군(巴東郡)[편집]\n영안현(永安縣)\n지명: 백제성(白帝城), [진]어현(魚縣)→[222]영안현\n유비(劉備)가 영안궁(永安宮)에서 최후를 맞았다.\n위치: 《31.04339, 109.57039》 백제성\n')
        mentions = result['namedMentions']
        baidi = next(m for m in mentions if m['namesHan'] == ['白帝城'])
        self.assertEqual('ALIAS_MENTION', baidi['relation'])
        self.assertEqual(3, baidi['line'])
        self.assertEqual(result['records'][0]['recordId'], baidi['parentRecordId'])
        self.assertEqual([], baidi['locations'])
        self.assertEqual('MENTION_NOT_LOCATION', baidi['coordinateStatus'])
        self.assertTrue(any(m['namesHan'] == ['永安宮'] for m in mentions))
        self.assertFalse(any('劉備' in m['namesHan'] for m in mentions))

    def test_hulaoguan_narrative_keeps_source_line_and_no_county_coordinate(self):
        result = self.page('2. 하남윤(河南尹)[편집]\n성고현(成皐縣·成睾縣)\n이전까진 호뢰관(虎牢關)이었다. 사수관(汜水關)은 사수(汜水)에서 이름을 따왔다.\n위치: 《34.84736, 113.19887》 후라오관촌\n')
        mentions = result['namedMentions']
        self.assertEqual([['虎牢關'], ['汜水關'], ['汜水']], [m['namesHan'] for m in mentions])
        self.assertTrue(all(m['line'] == 3 and m['relation'] == 'NARRATIVE_MENTION' and not m['locations'] for m in mentions))

    def test_geographic_variant_gloss_retains_each_name_without_coordinates(self):
        result = self.page('2. 경조군(京兆郡)[편집]\n신풍현(新豐縣)\n지명: 산성(山城·古城)\n')
        self.assertEqual([['山城'], ['古城']], [m['namesHan'] for m in result['namedMentions']])
