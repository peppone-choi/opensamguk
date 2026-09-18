# -*- coding: utf-8 -*-
"""수(水, 등급 1 — 나루·수구)·진(鎭, 등급 2 — 요새·보루) 거점 원장 검증.

원장: data/curated/han/strategic-strongholds-v1.json

개수·이름을 박지 않는다. 대신 행마다 성립해야 할 성질을 단언한다.

1. 채택 행은 正史 인용(primaryEvidence)을 하나 이상 달고, 그중 하나는 184–280 사건이다.
2. role ↔ cityLevel ↔ 이름 끝 글자 규칙이 맞는다(津·口·渡·浦 = FERRY/1, 鎭·壘·塢 = FORT/2).
3. id·nameHan·좌표가 겹치지 않는다.
4. tileAnchor 는 han-tiles.json 에서 **이 파일 안의 독립 구현으로** 다시 계산한 값과 같다.
   투영식은 web/shared/src/HanMapCanvas.tsx projectBattlefieldTarget 그대로다.
5. 좌표는 나무위키 수확본(namu-source-records-v1) 행의 명시 좌표와 같다.
6. 후보 풀(OTHER_NAMED_SITE 중 범위 글자로 끝나는 이름을 가진 행)은 채택 아니면 제외로
   정확히 한 번씩 처리돼 있다.

사료 원문 대조(sha256·인용 부분열)는 shiliao 코퍼스가 저장소 밖이라 환경변수
SHILIAO_REPO(코퍼스 corpus/ 를 담은 디렉터리)가 있을 때만 돈다. 없으면 skip 이다.
"""
import hashlib
import json
import math
import os
import re
import unicodedata
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
LEDGER_PATH = ROOT / 'data/curated/han/strategic-strongholds-v1.json'
TILES_PATH = ROOT / 'data/map/han-tiles.json'
NAMU_PATH = ROOT / 'data/curated/han/namu-source-records-v1.json'
RESEATS_PATH = ROOT / 'data/curated/han/city-seed-reseats-v1.json'
PASSES_PATH = ROOT / 'data/curated/han/strategic-passes-v1.json'

# 글자 규칙은 원장 classificationRule 이 정본이다(2026-09-15 사용자 결정으로 FORT 가 塞·營·戍·군사 城까지 넓어졌다).
_RULE = json.loads(Path(LEDGER_PATH).read_text(encoding='utf-8'))['classificationRule']
FERRY_SUFFIXES = set(_RULE['FERRY']['nameSuffixes'])
FORT_SUFFIXES = set(_RULE['FORT']['nameSuffixes'])
CONDITIONAL_FORT = _RULE['FORT'].get('conditionalSuffixes', {})
OUT_OF_SCOPE_SUFFIXES = set(_RULE['outOfRoleScopeSuffixes'])
ROLE_LEVEL = {'FERRY': 1, 'FORT': 2}
ZHENGSHI_PATH = re.compile(r'^corpus/(sgz|hhs|js|js2)-[0-9]+[A-Z]?\.txt$')
EVIDENCE_KINDS = {'RECEIVED_TEXT', 'PEI_COMMENTARY', 'LATER_COMMENTARY'}
REASONS = set(json.loads(Path(LEDGER_PATH).read_text(encoding='utf-8'))['reasonCodes'])
ERA = (184, 280)


def load(path):
    with open(path, encoding='utf-8') as handle:
        return json.load(handle)


def nfkc(text):
    return unicodedata.normalize('NFKC', text)


LEDGER = load(LEDGER_PATH)
ROWS = LEDGER['strongholds']
EXCLUDED = LEDGER['excluded']


class RecomputedTiles:
    """han-tiles.json 에서 거점 앵커를 다시 계산한다. 원장 생성 코드와 공유하는 것은 없다."""

    def __init__(self):
        # 앵커는 거점 省 분할 **전** 문서 기준이다 — 분할 단계(carve_strategic_site_provinces)가 이 원장을
        # 입력으로 칸을 떼어 가므로, 커밋된 han-tiles 에서는 그 단계를 벗겨 내고 다시 잰다.
        from tools.map import carve_strategic_site_provinces as carving
        from tools.map import fold_cityless_jurisdictions as folding
        # peel_only: ★ 지리 재분할(GH #806)은 분할 단계의 **입력**이라 벗기지 않는다. carving.peel() 은 ★ 까지 벗겨
        # 옛 기하의 앵커(은퇴한 DIRECT id)를 그대로 통과시킨다.
        tiles, _ = carving.peel_only(folding.peel(load(TILES_PATH))[0])
        meta = tiles['_meta']
        self.meta = meta
        self.projection = meta['projection']
        self.cols = meta['cols']
        self.rows = meta['rows']
        self.terrain = tiles['terrain']
        owner = []
        for value, count in tiles['owner']:
            owner.extend([value] * count)
        if len(owner) != self.cols * self.rows:
            raise AssertionError('owner run-length 가 격자 크기와 안 맞는다')
        self.owner = owner
        cities = [dict(city) for city in tiles['cities']]
        # 런타임(citySeedReseat.ts)과 같은 조건 — id 와 현재 칸이 표와 맞을 때만 옮긴다.
        for reseat in load(RESEATS_PATH)['reseats']:
            city = cities[reseat['cityIndex']]
            if city['id'] == reseat['placeId'] and [city['col'], city['row']] == reseat['fromCell']:
                city['col'], city['row'] = reseat['toCell']
        self.cities = cities
        self.city_index_by_id = {city['id']: index for index, city in enumerate(cities)}
        self.provinces = tiles['provinceRecords']
        self.jurisdictions = {record['id']: record for record in tiles['jurisdictionRecords']}
        self.commanderies = {record['id']: record for record in tiles['commanderyRecords']}

    def project(self, latitude, longitude):
        p = self.projection
        col = (longitude * p['k'] - p['x0'] + p['pad']) / p['cell']
        row = (p['y1'] + p['pad'] - latitude) / p['cell']
        if 0 <= col < self.cols and 0 <= row < self.rows:
            return col, row
        return None

    def anchor(self, latitude, longitude):
        point = self.project(latitude, longitude)
        if point is None:
            return {'status': 'OUTSIDE_GRID'}
        col, row = point
        cell_col, cell_row = math.floor(col), math.floor(row)
        code = self.terrain[cell_row][cell_col]
        province_index = self.owner[cell_row * self.cols + cell_col]
        result = {
            'col': cell_col,
            'row': cell_row,
            'projected': {'col': round(col, 3), 'row': round(row, 3)},
            'terrain': {'code': code, 'name': self.meta['terrainLegend'][code]},
            'provinceIndex': province_index,
            'sameCellPlaces': [
                {
                    'cityIndex': index,
                    'cityId': city['id'],
                    'name': city['name'],
                    'nameCh': city['nameCh'],
                    'kind': city['kind'],
                }
                for index, city in enumerate(self.cities)
                if city['col'] == cell_col and city['row'] == cell_row
            ],
        }
        if province_index < 0:
            result.update(
                status='WATER_OR_OUT_OF_SCOPE',
                provinceId=None,
                jurisdictionId=None,
                jurisdictionName=None,
                jurisdictionNameKo=None,
                jurisdictionKind=None,
                commanderyId=None,
                commanderyName=None,
                nearestSeat=None,
            )
            return result
        province = self.provinces[province_index]
        jurisdiction_id = province['jurisdictionId']
        jurisdiction = self.jurisdictions[jurisdiction_id]
        members = {
            record['cityIndex']
            for record in self.provinces
            if record.get('jurisdictionId') == jurisdiction_id and isinstance(record.get('cityIndex'), int)
        }
        if jurisdiction.get('seatPlaceId') in self.city_index_by_id:
            members.add(self.city_index_by_id[jurisdiction['seatPlaceId']])
        if not members:
            raise AssertionError(f'관할 {jurisdiction_id} 에 cities[] 점이 없다')

        def distance(index):
            city = self.cities[index]
            return math.hypot(col - city['col'], row - city['row'])

        nearest = min(members, key=lambda index: (distance(index), index))
        seat = self.cities[nearest]
        commandery = self.commanderies.get(jurisdiction.get('commanderyId'))
        result.update(
            status='LAND',
            provinceId=province['id'],
            jurisdictionId=jurisdiction_id,
            jurisdictionName=jurisdiction.get('nameCh'),
            jurisdictionNameKo=jurisdiction.get('displayName'),
            jurisdictionKind=jurisdiction.get('kind'),
            commanderyId=jurisdiction.get('commanderyId'),
            commanderyName=commandery['nameCh'] if commandery else None,
            nearestSeat={
                'cityIndex': nearest,
                'cityId': seat['id'],
                'name': seat['name'],
                'nameCh': seat['nameCh'],
                'col': seat['col'],
                'row': seat['row'],
                'cellDistance': round(distance(nearest), 2),
            },
        )
        return result


def in_pool(record):
    if record['kind'] != 'OTHER_NAMED_SITE':
        return False
    return any(name and nfkc(name)[-1] in FERRY_SUFFIXES | FORT_SUFFIXES for name in record['namesHan'])


class StrongholdEvidenceTest(unittest.TestCase):
    def test_every_adopted_row_cites_zhengshi_inside_era(self):
        self.assertTrue(ROWS, '채택 행이 하나도 없다')
        for row in ROWS:
            with self.subTest(row=row['id']):
                evidence = row['primaryEvidence']
                self.assertGreater(len(evidence), 0, '正史 근거 없는 채택 행')
                names = {row['nameHan'], *row['aliases']}
                for cite in evidence:
                    self.assertRegex(cite['sourcePath'], ZHENGSHI_PATH)
                    self.assertEqual(cite['sourceRepository'], 'shiliao')
                    self.assertRegex(cite['sha256'], r'^[0-9a-f]{64}$')
                    self.assertGreater(cite['line'], 0)
                    self.assertIn(cite['evidenceKind'], EVIDENCE_KINDS)
                    self.assertIn(cite['eventDatingBasis'], {'TEXT_EXPLICIT', 'REIGN_CONTEXT'})
                    self.assertTrue(
                        any(name in cite['quote'] for name in names),
                        f"인용문에 이름·alias 가 없다: {cite['quote']}",
                    )
                    years = cite['eventYears']
                    self.assertLessEqual(years['from'], years['to'])
                in_era = [
                    cite for cite in evidence
                    if cite['eventYears']['from'] <= ERA[1] and cite['eventYears']['to'] >= ERA[0]
                ]
                self.assertTrue(in_era, '184–280 사건에 이어지는 인용이 없다')
                self.assertEqual(row['positionStatus'], 'APPROXIMATE')

    @unittest.skipUnless(os.environ.get('SHILIAO_REPO'), 'SHILIAO_REPO 미설정 — 코퍼스 대조 생략')
    def test_quotes_exist_verbatim_in_corpus(self):
        repo = Path(os.environ['SHILIAO_REPO'])

        def unwrap(line):
            line = re.sub(r'\[\[[^\]|]*\|([^\]]*)\]\]', r'\1', line)
            line = re.sub(r'\[\[([^\]]*)\]\]', r'\1', line)
            line = re.sub(r'-\{([^}]*)\}-', r'\1', line)
            line = re.sub(r'\{\{YL\|([^}]*)\}\}', r'\1', line)
            return re.sub(r'\{\{ProperNoun\|([^{}]*)\}\}', r'\1', line)

        for row in ROWS:
            for cite in row['primaryEvidence']:
                with self.subTest(row=row['id'], path=cite['sourcePath'], line=cite['line']):
                    raw = (repo / cite['sourcePath']).read_bytes()
                    self.assertEqual(hashlib.sha256(raw).hexdigest(), cite['sha256'])
                    line = raw.decode('utf-8').split('\n')[cite['line'] - 1]
                    self.assertIn(cite['quote'], unwrap(line))


class StrongholdRoleTest(unittest.TestCase):
    def test_role_level_and_suffix_agree(self):
        for row in ROWS:
            with self.subTest(row=row['id']):
                self.assertIn(row['role'], ROLE_LEVEL)
                self.assertEqual(row['cityLevel'], ROLE_LEVEL[row['role']])
                suffix = row['nameHan'][-1]
                expected = FERRY_SUFFIXES if row['role'] == 'FERRY' else FORT_SUFFIXES
                self.assertIn(suffix, expected, f"{row['nameHan']} 끝 글자가 {row['role']} 규칙과 다르다")
                self.assertNotEqual(suffix, '關', '關 은 strategic-passes-v1 범위다')
                self.assertNotIn(suffix, OUT_OF_SCOPE_SUFFIXES, f"{row['nameHan']} 는 범위 밖 글자다")
                condition = CONDITIONAL_FORT.get(suffix) if row['role'] == 'FORT' else None
                if condition:
                    # 조건부 글자(城)는 184–280 군사 기사를 인용해야만 FORT 다.
                    military = set(condition['militarySupports'])
                    self.assertTrue(
                        any(military & set(cite.get('supports', [])) for cite in row['primaryEvidence']),
                        f"{row['nameHan']} 는 군사 거점 근거(supports)가 없다",
                    )
                self.assertEqual(row['nameHan'], nfkc(row['nameHan']), 'nameHan 은 NFKC 정규화 값이어야 한다')

    def test_ferry_crossing_support_quotes_a_crossing(self):
        """FERRY_CROSSING_IN_EVENT 는 인용문 자체가 건넘·배를 말할 때만 쓴다.

        鸇陰口(三國志 卷15 張既傳)가 이 표지를 달고 있었으나 원문은 적이 길을 막은 자리였고 張既는 그곳을
        피했다(2026-09-18 정정). 이름이 나온다는 것과 그 나루로 건넜다는 것은 다른 주장이다.
        """
        for row in ROWS:
            for cite in row['primaryEvidence']:
                if 'FERRY_CROSSING_IN_EVENT' in cite.get('supports', []):
                    with self.subTest(row=row['id']):
                        self.assertTrue(
                            set('渡度濟济船') & set(cite['quote']),
                            f"{row['nameHan']} 인용문에 건넘(渡·度·濟)·배(船) 글자가 없다: {cite['quote']}",
                        )

    def test_no_pass_is_restated(self):
        passes = load(PASSES_PATH)['passes']
        pass_points = {(p['coordinates']['latitude'], p['coordinates']['longitude']) for p in passes}
        pass_names = {p['nameHan'] for p in passes}
        for row in ROWS:
            with self.subTest(row=row['id']):
                point = (row['coordinates']['latitude'], row['coordinates']['longitude'])
                self.assertNotIn(point, pass_points)
                self.assertNotIn(row['nameHan'], pass_names)


class StrongholdIdentityTest(unittest.TestCase):
    def test_ids_names_and_coordinates_are_unique(self):
        for field, key in (
            ('id', lambda row: row['id']),
            ('nameHan', lambda row: row['nameHan']),
            ('coordinates', lambda row: (row['coordinates']['latitude'], row['coordinates']['longitude'])),
        ):
            with self.subTest(field=field):
                values = [key(row) for row in ROWS]
                duplicates = sorted({str(value) for value in values if values.count(value) > 1})
                self.assertEqual(duplicates, [], f'{field} 중복')
        for row in ROWS:
            self.assertRegex(row['id'], r'^[a-z][a-z0-9-]*$')

    def test_alias_never_names_another_row(self):
        names = {row['nameHan']: row['id'] for row in ROWS}
        for row in ROWS:
            for alias in row['aliases']:
                with self.subTest(row=row['id'], alias=alias):
                    self.assertIn(names.get(alias, row['id']), {row['id']})

    def test_coordinates_come_from_namu_explicit_record(self):
        records = {record['recordId']: record for record in load(NAMU_PATH)['records']}
        for row in ROWS:
            with self.subTest(row=row['id']):
                primary = [m for m in row['modernEvidence'] if m['relation'] == 'NAMED_SITE_COORDINATE']
                self.assertEqual(len(primary), 1)
                for modern in row['modernEvidence']:
                    record = records[modern['recordId']]
                    self.assertEqual(record['coordinateStatus'], 'EXPLICIT')
                    pairs = [(p['lat'], p['lon']) for loc in record['locations'] for p in loc['coordinatePairs']]
                    point = (modern['coordinates']['latitude'], modern['coordinates']['longitude'])
                    self.assertIn(point, pairs)
                    self.assertEqual(modern['namesHanAsRecorded'], record['namesHan'])
                self.assertEqual(
                    (row['coordinates']['latitude'], row['coordinates']['longitude']),
                    (primary[0]['coordinates']['latitude'], primary[0]['coordinates']['longitude']),
                )

    def test_candidate_pool_is_accounted_exactly_once(self):
        records = load(NAMU_PATH)['records']
        seen = {}
        for row in ROWS:
            for modern in row['modernEvidence']:
                seen.setdefault(modern['recordId'], []).append(row['id'])
        for entry in EXCLUDED:
            self.assertIn(entry['reason'], REASONS)
            for record_id in entry['namuRecordIds']:
                seen.setdefault(record_id, []).append('excluded:' + entry['reason'])
        for record_id, owners in seen.items():
            self.assertEqual(len(owners), 1, f'{record_id} 가 두 번 처리됐다: {owners}')
        for record in records:
            if in_pool(record):
                with self.subTest(record=record['recordId'], names=record['namesHan']):
                    self.assertIn(record['recordId'], seen, '후보 풀 행이 채택도 제외도 아니다')
                    if record['coordinateStatus'] != 'EXPLICIT':
                        self.assertEqual(seen[record['recordId']], ['excluded:NO_EXPLICIT_COORDINATE'])

    def test_exclusions_carry_their_proof(self):
        adopted = {row['nameHan'] for row in ROWS}
        for entry in EXCLUDED:
            with self.subTest(name=entry['nameHan'], reason=entry['reason']):
                self.assertTrue(entry['detail'])
                if entry['reason'] == 'OUT_OF_ROLE_SCOPE':
                    continue
                self.assertNotIn(entry['nameHan'], adopted)
                if entry['reason'] == 'NO_ZHENGSHI_ATTESTATION':
                    queries = entry['queries']
                    self.assertTrue(queries['terms'])
                    for term in queries['terms']:
                        self.assertEqual(term['zhengshiLines'], 0)
                        self.assertTrue(term['variantsTried'])
                    # 0건이 조회 고장이 아니라는 증거 — 같은 조회에 걸리는 대조어.
                    self.assertTrue(queries['controls'])
                    self.assertTrue(all(control['zhengshiLines'] > 0 for control in queries['controls']))
                if entry['reason'] == 'DUPLICATE_OF_STRATEGIC_PASS':
                    pass_ids = {p['id'] for p in load(PASSES_PATH)['passes']}
                    self.assertIn(entry['duplicateOf'].split('#')[1], pass_ids)


class StrongholdTileAnchorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tiles = RecomputedTiles()

    def test_tile_anchor_matches_han_tiles(self):
        for row in ROWS:
            with self.subTest(row=row['id']):
                expected = self.tiles.anchor(row['coordinates']['latitude'], row['coordinates']['longitude'])
                self.assertEqual(row['tileAnchor'], expected)

    def test_same_cell_flag_matches_county_seats(self):
        for row in ROWS:
            with self.subTest(row=row['id']):
                anchor = row['tileAnchor']
                on_seat = any(place['kind'] != 'EXTERNAL_PLACE' for place in anchor['sameCellPlaces'])
                self.assertEqual(row['sameCellAsCountySeat'], on_seat)

    def test_water_cells_are_not_relocated(self):
        for row in ROWS:
            anchor = row['tileAnchor']
            with self.subTest(row=row['id']):
                self.assertIn(anchor['status'], {'LAND', 'WATER_OR_OUT_OF_SCOPE'})
                point = self.tiles.project(row['coordinates']['latitude'], row['coordinates']['longitude'])
                self.assertEqual((anchor['col'], anchor['row']), (math.floor(point[0]), math.floor(point[1])))
                if anchor['status'] == 'WATER_OR_OUT_OF_SCOPE':
                    self.assertEqual(anchor['provinceIndex'], -1)
                    self.assertIsNone(anchor['nearestSeat'])


if __name__ == '__main__':
    unittest.main()
