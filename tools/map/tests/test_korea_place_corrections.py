import copy
import json
import unittest
from tools.map import refine_korea_places as K
from tools.map import reclassify_han_lowland_terrain as L
from tools.map.measure_province_seat_offset import expand_rle

class KoreaCorrectionsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.current = json.loads(K.TILES.read_text())
        cls.ledger = json.loads(K.LEDGER.read_text())
        cls.original, _ = K.peel(cls.current)

    def test_restores_the_exact_preceding_stage(self):
        self.assertEqual(K.digest(self.original), self.ledger['inputSha256'])
        lowland = json.loads(L.LEDGER.read_text())
        self.assertEqual(K.digest(self.original), lowland['geometry']['stages'][0]['outputDocumentSha256'])
        self.assertEqual([], K.check(self.current, self.ledger))

    def test_preserves_original_frame_terrain_and_stable_ids(self):
        from tools.map.korea_map_extension import NORTH_ROWS
        self.assertEqual(self.original['terrain'],[r[:768] for r in self.current['terrain'][NORTH_ROWS:]])
        for key in ('cities', 'provinceRecords'):
            self.assertEqual([r['id'] for r in self.original[key]], [r['id'] for r in self.current[key]][:len(self.original[key])])
        self.assertEqual(843,self.current['_meta']['rows'])
        self.assertEqual(864,self.current['_meta']['cols'])
        for old,new in zip(self.original['cities'],self.current['cities']):
            if old['id'] not in {'X030','X036'}:
                self.assertEqual((old['col'],old['row']+NORTH_ROWS),(new['col'],new['row']))
        self.assertEqual(173,len(self.current['parentRegions']))

    def test_new_settlements_own_their_real_cells(self):
        meta=self.current['_meta']
        owner=expand_rle(self.current['owner'],meta['rows'],meta['cols'])
        added=[c for c in self.current['cities'] if c['id'] in {d['id'] for d in self.ledger['decisions']['settlements']}]
        self.assertEqual(35,len(added))
        for city in added:
            province=self.current['provinceRecords'][owner[city['row'],city['col']]]
            self.assertEqual(city['id'],province['jurisdictionId'])
            self.assertEqual(self.current['cities'].index(city),province['cityIndex'])

    def test_rejects_unreviewed_cross_border_move(self):
        decisions=copy.deepcopy(self.ledger['decisions'])
        decisions['decisions'][0].update(lon=126.99,lat=37.56)
        with self.assertRaisesRegex(ValueError,'outside the reviewed jurisdiction'):
            K.apply(self.original, decisions)

    def test_tampered_destination_does_not_pass(self):
        doc=copy.deepcopy(self.current)
        next(r for r in doc['cities'] if r['id']=='X036')['lon']+=1
        self.assertTrue(K.check(doc,self.ledger))

    def test_rebuilding_previous_stages_keeps_corrections(self):
        peeled, previous = L.peel(self.current)
        lowland, _ = L.build_stage(peeled, json.loads(L.DECISIONS.read_text()))
        self.assertEqual(self.current, K.restack(lowland, previous))

    def test_withdrawn_place_and_route_identities_do_not_reappear(self):
        import gzip
        retirement=json.loads((K.ROOT/'data/curated/han/korea-retired-settlements-v1.json').read_text())
        removed={r['id'] for r in retirement['places']}
        self.assertEqual(173,len(removed))
        self.assertFalse(removed & {r['id'] for r in self.current['cities']})
        self.assertFalse(removed & {r['jurisdictionId'] for r in self.current['provinceRecords']})
        self.assertTrue(all(not any(c.isdigit() for c in r['name']) for r in self.ledger['decisions']['settlements']))
        selection=json.loads((K.ROOT/'data/curated/han/route-node-selection-v1.json').read_text())['routeNodes']
        self.assertFalse(set(retirement['numericIdsReserved']) & {r['numericCityId'] for r in selection})
        retired_keys={r['routeNodeKey'] for r in retirement['routeNodeKeys']}
        self.assertFalse(retired_keys & {r['routeNodeKey'] for r in selection})
        prior=K.ROOT/'data/map/han-world-v3-1341-artifacts-v1'
        entry=next(r for r in json.loads((prior/'catalog.json').read_text())['files'] if r['path']=='data/curated/han/route-node-selection-v1.json')
        old={r['numericCityId']:(r['routeNodeKey'],r['physicalPlaceRef']) for r in json.loads(gzip.decompress((prior/entry['blob']).read_bytes()))['routeNodes']}
        for row in selection:
            self.assertEqual(old[row['numericCityId']],(row['routeNodeKey'],row['physicalPlaceRef']))
        for name in ('external-places.json',):
            places=json.loads((K.ROOT/'data/map'/name).read_text())['places']
            self.assertFalse(removed & {r['id'] for r in places})

    def test_geographic_placeholders_are_not_active_places(self):
        decisions = self.ledger['decisions']
        names = [r['name'] for r in decisions['settlements']]
        names += [r['displayName'] for r in decisions['labelOverrides']]
        for name in names:
            self.assertNotIn('취락', name)
            self.assertNotIn(name, {'송화강 합류', '송눈 평원', '흑룡강 중류', '흑룡강 상류',
                                    '흑룡강 남안', '송화강 하구', '우수리 북부', '흑룡강 하구길', '금강 하구'})
        self.assertEqual({'X078': '신소도', 'X048': '본피', 'X049': '고동람'}, {
            **{r['id']: r['name'] for r in decisions['settlements'] if r['id'] == 'X078'},
            **{r['id']: r['displayName'] for r in decisions['labelOverrides'] if r['id'] in {'X048', 'X049'}},
        })
        self.assertFalse({'PARENT-0173', 'PARENT-0174', 'PARENT-0175'} &
                         {r['id'] for r in self.current['parentRegions']})

    def test_retirement_holes_are_preserved_in_the_runtime_roster(self):
        from tools.scenario.han_active_city_ids import active_numeric_ids, RETIRED_CURRENT_CITY_IDS
        retirement = json.loads((K.ROOT/'data/curated/han/korea-retired-settlements-v1.json').read_text())
        self.assertEqual({n for n in retirement['numericIdsReserved'] if n <= 1194},
                         set(RETIRED_CURRENT_CITY_IDS))
        self.assertEqual(1168, len(active_numeric_ids(1168)))
        self.assertIn(1177, active_numeric_ids(1168))
        self.assertNotIn(1143, active_numeric_ids(1168))
        self.assertEqual(list(range(1, 1195)), active_numeric_ids(1194))

    def test_extended_northern_terrain_does_not_invent_playable_ownership(self):
        from tools.map.korea_map_extension import NORTH_ROWS
        meta = self.current['_meta']
        owner = expand_rle(self.current['owner'], meta['rows'], meta['cols'])
        self.assertTrue((owner[:NORTH_ROWS] == -1).all())
        self.assertTrue((owner[:, 768:] == -1).all())
