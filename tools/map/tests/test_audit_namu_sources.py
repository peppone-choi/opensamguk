import unittest
from tools.map.audit_namu_sources import audit_documents


def record(name='安國縣', group='中山國', coords=None, kind='COUNTY_HEADING'):
    return {'recordId': 's:10', 'sourceId': 's', 'lineStart': 10, 'kind': kind,
            'namesHan': [name], 'aliases': [],
            'section': {'namesHan': [group], 'aliases': []},
            'locations': [] if coords is None else [{'line': 12, 'coordinatePairs': coords}]}


def run(records, name='安國', group='中山國', lat=38.0, lon=115.0, source_url='https://namu.wiki/w/삼국지/지명/기주'):
    sources = {'sources': [{'sourceId': 's', 'title': '삼국지/지명/기주'}], 'records': records}
    canon = {'groups': [{'canonicalGroup': group, 'units': [{'sourceName': name, 'ordinal': 1}]}]}
    old = {'rows': [{'canonicalGroup': group, 'sourceName': name, 'lat': lat, 'lon': lon,
                     'sourceUrl': source_url}]}
    return audit_documents(sources, canon, old, lambda x: x.replace('縣', '') if x.endswith('縣') else x)


class NamuAuditTest(unittest.TestCase):
    def test_agreement_accounts_for_both_input_rows(self):
        result = run([record(coords=[{'lat': 38.0, 'lon': 115.0}])])
        self.assertEqual(result['existingLedger'][0]['status'], 'SOURCE_COORDINATE_MATCH')
        self.assertEqual(len(result['canonicalCounties']), 1)

    def test_homonymous_other_group_not_verified(self):
        result = run([record(group='常山國', coords=[{'lat': 38.0, 'lon': 115.0}])])
        self.assertEqual(result['existingLedger'][0]['status'], 'GROUP_UNRESOLVED')

    def test_subordinate_site_never_supplies_county_location(self):
        result = run([record(coords=[{'lat': 38.0, 'lon': 115.0}], kind='OTHER_NAMED_SITE')])
        self.assertEqual(result['existingLedger'][0]['status'], 'NO_NORMALIZED_NAME_MATCH')

    def test_coordinate_disagreement_reported(self):
        result = run([record(coords=[{'lat': 39.0, 'lon': 115.0}])])
        self.assertEqual(result['existingLedger'][0]['status'], 'COORDINATE_CONFLICT')

    def test_multiple_locations_not_collapsed_to_matching_one(self):
        result = run([record(coords=[{'lat': 38.0, 'lon': 115.0}, {'lat': 39.0, 'lon': 115.0}])])
        self.assertEqual(result['existingLedger'][0]['status'], 'MULTIPLE_SOURCE_COORDINATES')

    def test_missing_coordinates_not_borrowed(self):
        self.assertEqual(run([record()])['existingLedger'][0]['status'], 'SOURCE_LOCATION_UNAVAILABLE')

    def test_country_suffix_preserved(self):
        self.assertEqual(run([record(name='安縣')])['existingLedger'][0]['status'], 'NO_NORMALIZED_NAME_MATCH')

    def test_invalid_numeric_not_match(self):
        for value in [True, float('nan'), 10**400]:
            with self.subTest(value=str(value)[:10]):
                self.assertEqual(run([record(coords=[{'lat': value, 'lon': 115.0}])])['existingLedger'][0]['status'], 'SOURCE_LOCATION_UNAVAILABLE')

    def test_unicode_compatibility_ideograph_matches(self):
        result = run([record(name='安樂縣', coords=[{'lat': 38.0, 'lon': 115.0}])], name='安樂')
        self.assertEqual(result['existingLedger'][0]['status'], 'SOURCE_COORDINATE_MATCH')

    def test_rounding_difference_not_exact_match_or_large_conflict(self):
        result = run([record(coords=[{'lat': 38.0, 'lon': 115.000002}])])
        self.assertEqual(result['existingLedger'][0]['status'], 'MATCH_AT_FIVE_DECIMALS')

    def test_uncertain_source_is_not_hidden_by_coordinate_agreement(self):
        source = record(coords=[{'lat': 38.0, 'lon': 115.0}])
        source['flags'] = ['UNCERTAIN']
        source['locations'][0]['uncertain'] = True
        row = run([source])['existingLedger'][0]
        self.assertEqual(row['candidates'][0]['sourceFlags'], ['UNCERTAIN'])
        self.assertTrue(row['candidates'][0]['sourceLocations'][0]['uncertain'])
        self.assertFalse(row['historicalLocationVerified'])

    def test_every_real_input_identity_is_accounted_once(self):
        import json
        from pathlib import Path
        root = Path(__file__).resolve().parents[3]
        load = lambda name: json.loads((root / 'data/curated/han' / name).read_text())
        sources = load('namu-source-records-v1.json')
        canonical = load('administrative-units.json')
        old = load('namu-place-locations-v1.json')
        result = audit_documents(sources, canonical, old)
        self.assertEqual([r['rowIndex'] for r in result['existingLedger']], list(range(len(old['rows']))))
        expected = [(g['canonicalGroup'], u['ordinal'], u['sourceName']) for g in canonical['groups'] for u in g['units']]
        actual = [(r['canonicalGroup'], r['ordinal'], r['sourceName']) for r in result['canonicalCounties']]
        self.assertEqual(actual, expected)
        self.assertEqual((len(old['rows']), len(expected), len(sources['sources'])), (227, 1180, 14))
        self.assertTrue(all(not r['historicalLocationVerified'] for r in result['existingLedger'] + result['canonicalCounties']))
        self.assertEqual(result, audit_documents(sources, canonical, old))

    def test_absent_cited_page_never_falls_back_to_another_page(self):
        row = run([record(coords=[{'lat': 38.0, 'lon': 115.0}])], source_url='https://namu.wiki/w/missing')['existingLedger'][0]
        self.assertEqual(row['status'], 'SOURCE_PAGE_NOT_SUPPLIED')
        self.assertEqual(row['candidates'], [])

    def test_wrong_host_never_credits_source_agreement(self):
        row = run([record(coords=[{'lat': 38.0, 'lon': 115.0}])], source_url='https://example.com/w/삼국지/지명/기주')['existingLedger'][0]
        self.assertEqual(row['status'], 'INVALID_SOURCE_REFERENCE')

    def test_famous_non_county_landmark_retained_separately(self):
        site = record(name='赤壁', kind='OTHER_NAMED_SITE', coords=[{'lat': 29.88533, 'lon': 113.61877}])
        result = run([site])
        self.assertEqual(result['namedSites'][0]['namesHan'], ['赤壁'])
        self.assertEqual(result['namedSites'][0]['coordinates'], [[29.88533, 113.61877]])
        self.assertFalse(result['namedSites'][0]['historicalLocationVerified'])
        self.assertEqual(result['existingLedger'][0]['status'], 'NO_NORMALIZED_NAME_MATCH')

    def test_prose_landmark_does_not_inherit_county_coordinates(self):
        source = {'sources': [{'sourceId': 's', 'title': 'p'}], 'records': [],
                  'namedMentions': [{'sourceId': 's', 'line': 10, 'namesHan': ['虎牢關'],
                                     'coordinateStatus': 'MENTION_NOT_LOCATION', 'locations': []}]}
        result = audit_documents(source, {'groups': []}, {'rows': []}, lambda value: value)
        self.assertEqual(result['landmarkMentions'][0]['namesHan'], ['虎牢關'])
        self.assertEqual(result['landmarkMentions'][0]['locations'], [])
        self.assertFalse(result['landmarkMentions'][0]['historicalLocationVerified'])
