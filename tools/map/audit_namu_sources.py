#!/usr/bin/env python3
"""Account for source text agreement; never certify historical location or mutate maps."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import json
import math
from pathlib import Path
import sys
import unicodedata
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.map.audit_county_coverage import make_normalizer


def names(item):
    return list(item.get('namesHan', [])) + [name for alias in item.get('aliases', []) for name in alias.get('namesHan', [])]


def coordinates(record):
    result = set()
    for location in record.get('locations', []):
        for pair in location.get('coordinatePairs', []):
            lat, lon = pair.get('lat'), pair.get('lon')
            if (type(lat) in (int, float) and type(lon) in (int, float)
                    and -90 <= lat <= 90 and -180 <= lon <= 180
                    and math.isfinite(lat) and math.isfinite(lon)):
                result.add((lat, lon))
    return sorted(result)


def audit_documents(source, canonical, old, normalize=None):
    base_normalizer = normalize or make_normalizer()
    normalize = lambda value: base_normalizer(unicodedata.normalize("NFKC", value))
    canonical_group_names = {g["canonicalGroup"]: {normalize(g["canonicalGroup"]), normalize(g.get("sourceGroupName", g["canonicalGroup"]))} for g in canonical["groups"]}
    source_by_id = {s['sourceId']: s for s in source['sources']}
    index = defaultdict(list)
    sections = {s.get('sectionId', s.get('id')): s for s in source.get('sections', [])}
    for record in source['records']:
        if record['kind'] != 'COUNTY_HEADING':
            continue
        for name in sorted({normalize(name) for name in names(record)}):
            index[name].append(record)

    def candidates(name, group, title=None):
        found = index.get(normalize(name), [])
        if title:
            found = [r for r in found if source_by_id[r['sourceId']]['title'] == title]
        result = []
        for r in found:
            section = r.get('section', {})
            if isinstance(section, str):
                section = sections.get(section, {})
            if r.get('sectionId') in sections:
                section = sections[r['sectionId']]
            groups = names(section)
            # A group alias is source testimony only; its date is not resolved here.
            group_match = bool(canonical_group_names.get(group, {normalize(group)}) & {normalize(g) for g in groups})
            result.append({'recordId': r['recordId'], 'sourceId': r['sourceId'],
                           'line': r['lineStart'], 'groupNameSupported': group_match,
                           'aliasNameMatch': normalize(name) not in {normalize(n) for n in r.get('namesHan', [])},
                           'coordinates': [list(pair) for pair in coordinates(r)],
                           'sourceFlags': r.get('flags', []),
                           'coordinateStatus': r.get('coordinateStatus'),
                           'sourceLocations': [{key: loc.get(key) for key in ('line', 'label', 'unknown', 'uncertain')} for loc in r.get('locations', [])]})
        return sorted(result, key=lambda r: (r['sourceId'], r['line'], r['recordId']))

    def select(found):
        supported = [r for r in found if r['groupNameSupported']]
        return supported or found

    legacy = []
    for index_old, row in enumerate(old['rows']):
        reference = urlsplit(row.get('sourceUrl', ''))
        valid_reference = reference.scheme in ('https', 'http') and reference.hostname == 'namu.wiki' and reference.path.startswith('/w/')
        title = unquote(reference.path[3:]) if valid_reference else None
        supplied = title in {s['title'] for s in source['sources']}
        found = candidates(row['sourceName'], row['canonicalGroup'], title) if supplied else []
        selected = select(found)
        pairs = sorted({tuple(pair) for r in selected for pair in r['coordinates']})
        old_valid = coordinates({'locations': [{'coordinatePairs': [row]}]})
        if not valid_reference:
            status = 'INVALID_SOURCE_REFERENCE'
        elif not supplied:
            status = 'SOURCE_PAGE_NOT_SUPPLIED'
        elif not found:
            status = 'NO_NORMALIZED_NAME_MATCH'
        elif not any(r['groupNameSupported'] for r in found):
            status = 'GROUP_UNRESOLVED'
        elif not old_valid:
            status = 'INVALID_LEDGER_COORDINATE'
        elif not pairs:
            status = 'SOURCE_LOCATION_UNAVAILABLE'
        elif len(pairs) > 1:
            status = 'MULTIPLE_SOURCE_COORDINATES'
        elif any(abs(row['lat'] - a) <= 1e-9 and abs(row['lon'] - b) <= 1e-9 for a, b in pairs):
            status = 'SOURCE_COORDINATE_MATCH'
        elif any(round(row['lat'], 5) == round(a, 5) and round(row['lon'], 5) == round(b, 5) for a, b in pairs):
            status = 'MATCH_AT_FIVE_DECIMALS'
        else:
            status = 'COORDINATE_CONFLICT'
        legacy.append({'rowIndex': index_old, 'canonicalGroup': row['canonicalGroup'],
                       'sourceName': row['sourceName'], 'status': status,
                       'ledgerCoordinate': [row['lat'], row['lon']], 'candidates': found,
                       'historicalLocationVerified': False})

    counties = []
    for group in canonical['groups']:
        for unit in group['units']:
            found = candidates(unit['sourceName'], group['canonicalGroup'])
            supported = [r for r in found if r['groupNameSupported']]
            if not found:
                status = 'NO_NORMALIZED_NAME_MATCH'
            elif not supported:
                status = 'NAME_MATCH_GROUP_UNRESOLVED'
            elif any(r['coordinates'] for r in supported):
                status = 'GROUP_NAME_MATCH_WITH_COORDINATES'
            else:
                status = 'GROUP_NAME_MATCH_WITHOUT_COORDINATES'
            counties.append({'canonicalGroup': group['canonicalGroup'], 'sourceName': unit['sourceName'],
                             'ordinal': unit['ordinal'], 'status': status, 'candidates': found,
                             'historicalLocationVerified': False})
    sites = [{**{key: r.get(key) for key in ('recordId', 'sourceId', 'lineStart', 'lineEnd', 'nameKo', 'namesHan', 'parentCountyId', 'coordinateStatus')},
              'coordinates': [list(pair) for pair in coordinates(r)],
              'sourceFlags': r.get('flags', []),
              'sourceLocations': [{key: loc.get(key) for key in ('line', 'label', 'unknown', 'uncertain')} for loc in r.get('locations', [])],
              'categoryReviewed': False, 'historicalLocationVerified': False}
             for r in source['records'] if r['kind'] == 'OTHER_NAMED_SITE']
    return {'schemaVersion': 1, 'scope': 'SUPPLIED_TEXT_AGREEMENT_NOT_HISTORICAL_VERIFICATION',
            'temporalCaveat': 'Source pages describe multiple periods, including circa 260; canonical membership is circa 140. Alias matches do not prove seat continuity.',
            'totals': {'sourcePages': len(source['sources']), 'sourceRecords': len(source['records']),
                       'existingLedgerRows': len(legacy), 'canonicalCounties': len(counties),
                       'existingLedgerStatuses': dict(sorted(Counter(r['status'] for r in legacy).items())),
                       'canonicalStatuses': dict(sorted(Counter(r['status'] for r in counties).items())),
                       'geographicNameMentions': len(source.get('namedMentions', [])),
                       'nonCountySourceEntries': len(sites),
                       'nonCountyEntriesWithCoordinates': sum(bool(r['coordinates']) for r in sites),
                       'historicalLocationsVerified': 0},
            'sources': source['sources'], 'sourceDiagnostics': source.get('diagnostics', []),
            'existingLedger': legacy, 'canonicalCounties': counties, 'namedSites': sites,
            'landmarkMentions': [{**mention, 'historicalLocationVerified': False} for mention in source.get('namedMentions', [])]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sources', type=Path, default=ROOT / 'data/curated/han/namu-source-records-v1.json')
    parser.add_argument('--out', type=Path)
    args = parser.parse_args()
    load = lambda p: json.loads(p.read_text(encoding='utf-8'))
    result = audit_documents(load(args.sources), load(ROOT / 'data/curated/han/administrative-units.json'),
                             load(ROOT / 'data/curated/han/namu-place-locations-v1.json'))
    rendered = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + '\n'
    if args.out:
        args.out.write_text(rendered, encoding='utf-8')
    else:
        print(rendered, end='')


if __name__ == '__main__':
    main()
