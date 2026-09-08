"""Coordinate coverage must not borrow a homonymous county from another commandery."""
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.map import audit_county_coverage as coverage


class CountyCoverageTest(unittest.TestCase):
    def audit_fixture(self, groups, cities=(), jurisdictions=(), commanderies=(), namu=()):
        documents = {
            'CANON_PATH': {'groups': [{'canonicalGroup': group, 'units': [{'sourceName': name} for name in names]} for group, names in groups]},
            'TILES_PATH': {'cities': list(cities), 'jurisdictionRecords': list(jurisdictions), 'commanderyRecords': list(commanderies)},
            'RUNTIME_MAP_PATH': {'cities': []},
            'NAMU_PATH': {'rows': list(namu)},
        }
        with tempfile.TemporaryDirectory() as directory:
            paths = {}
            for key, data in documents.items():
                path = Path(directory) / (key + '.json')
                path.write_text(json.dumps(data, ensure_ascii=False))
                paths[key] = path
            with patch.multiple(coverage, **paths):
                return coverage.audit()

    def test_real_cross_commandery_homonyms_remain_unlocated(self):
        cases = [('南陽郡', '成都', '蜀郡', '44394'), ('南陽郡', '酇', '沛國', '82128'),
                 ('上黨郡', '猗氏', '河東郡', '95478'), ('中山國', '漢昌', '巴郡', '44580')]
        for target, name, actual, place_id in cases:
            with self.subTest(target=target, name=name):
                result = self.audit_fixture([(target, [name])],
                    cities=[{'id': place_id, 'nameCh': name + '县', 'lat': 30, 'lon': 110}],
                    jurisdictions=[{'nameCh': name + '县', 'seatPlaceId': place_id, 'commanderyId': 'P'}],
                    commanderies=[{'id': 'P', 'nameCh': actual}])
                self.assertEqual(0, result['totals']['tileOnly'])
                self.assertEqual(1, result['totals']['unlocated'])

    def test_jurisdiction_name_resolves_seat_label_and_group_suffix_is_not_removed(self):
        result = self.audit_fixture([('張掖屬國', ['候官']), ('武威郡', ['张掖'])],
            cities=[{'id': 'X027', 'nameCh': '張掖屬國', 'lat': 38.9, 'lon': 100.4}],
            jurisdictions=[{'nameCh': '候官', 'seatPlaceId': 'X027', 'commanderyId': 'P'}],
            commanderies=[{'id': 'P', 'nameCh': '張掖屬國'}])
        by_group = {g['canonicalGroup']: g for g in result['groups']}
        self.assertEqual(1, by_group['張掖屬國']['tileOnly'])
        self.assertEqual(['张掖'], by_group['武威郡']['unlocated'])

    def test_invalid_coordinates_do_not_count_from_either_ledger(self):
        for lat, lon in [(10**400, 110), (30, -(10**400)), (None, 100), (True, 100), (30, False), ('30', 100),
                         (float('nan'), 100), (30, float('inf')), (91, 100), (30, -181)]:
            for source in ['namu', 'tiles']:
                with self.subTest(lat=lat, lon=lon, source=source):
                    kwargs = {'namu': [{'canonicalGroup': '蜀郡', 'sourceName': '成都', 'lat': lat, 'lon': lon}]} if source == 'namu' else {
                        'cities': [{'id': '1', 'nameCh': '成都县', 'lat': lat, 'lon': lon}],
                        'jurisdictions': [{'nameCh': '成都县', 'seatPlaceId': '1', 'commanderyId': 'P'}],
                        'commanderies': [{'id': 'P', 'nameCh': '蜀郡'}]}
                    result = self.audit_fixture([('蜀郡', ['成都'])], **kwargs)
                    self.assertEqual(1, result['totals']['unlocated'])
                    self.assertTrue(result['diagnostics']['invalidCoordinates'])

    def test_conflicting_pair_is_excluded_but_identical_duplicate_is_counted_once(self):
        row = {'canonicalGroup': '蜀郡', 'sourceName': '成都', 'lat': 30, 'lon': 104}
        same = self.audit_fixture([('蜀郡', ['成都'])], namu=[row, dict(row)])
        self.assertEqual(1, same['totals']['namu'])
        conflict = self.audit_fixture([('蜀郡', ['成都'])], namu=[row, dict(row, lat=31)])
        self.assertEqual(1, conflict['totals']['unlocated'])
        self.assertTrue(conflict['diagnostics']['ambiguousCoordinates'])

    def test_conflicting_tile_seats_are_not_resolved_by_input_order(self):
        cities = [{'id': '1', 'nameCh': '漢昌縣', 'lat': 31, 'lon': 105},
                  {'id': '2', 'nameCh': '漢昌縣', 'lat': 32, 'lon': 106}]
        jurisdictions = [{'nameCh': '漢昌縣', 'seatPlaceId': place_id, 'commanderyId': 'P'} for place_id in ['1', '2']]
        for order in [cities, list(reversed(cities))]:
            result = self.audit_fixture([('巴郡', ['漢昌'])], cities=order,
                jurisdictions=jurisdictions, commanderies=[{'id': 'P', 'nameCh': '巴郡'}])
            self.assertEqual(1, result['totals']['unlocated'])
            self.assertEqual('tiles', result['diagnostics']['ambiguousCoordinates'][0]['source'])

    def test_unresolved_seat_identity_cannot_fall_back_to_name(self):
        result = self.audit_fixture([('蜀郡', ['成都'])],
            cities=[{'id': '1', 'nameCh': '成都县', 'lat': 30, 'lon': 104}],
            jurisdictions=[{'nameCh': '成都县', 'seatPlaceId': 'missing', 'commanderyId': 'P'}],
            commanderies=[{'id': 'P', 'nameCh': '蜀郡'}])
        self.assertEqual(1, result['totals']['unlocated'])
        self.assertTrue(result['diagnostics']['unresolvedTileIdentity'])

    def test_county_guo_and_dao_are_distinct_from_short_names(self):
        rows = [{'canonicalGroup': '蜀郡', 'sourceName': name, 'lat': 30, 'lon': 104} for name in ['安國', '夷道']]
        result = self.audit_fixture([('蜀郡', ['安國', '夷道', '安', '夷'])], namu=rows)
        self.assertEqual(2, result['totals']['namu'])
        self.assertEqual({'安', '夷'}, set(result['groups'][0]['unlocated']))


if __name__ == '__main__':
    unittest.main()
