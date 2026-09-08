#!/usr/bin/env python3
"""Validate attributed battlefield source data without generating game geometry."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path, PurePosixPath
import re

ROOT = Path(__file__).resolve().parents[2]
SHA256 = re.compile(r'[0-9a-f]{64}')
ROLES = {'FIELD', 'NAVAL', 'FORTRESS', 'PASS'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_primary(evidence, corpus):
    path = corpus / PurePosixPath(evidence['sourcePath']).name
    raw = path.read_bytes()
    require(hashlib.sha256(raw).hexdigest() == evidence['sha256'], 'primary SHA256 mismatch')
    lines = raw.decode('utf-8').splitlines()
    number = evidence['line']
    require(number <= len(lines) and evidence['quote'] in lines[number - 1], 'primary quote/line mismatch')


def valid_point(point):
    if not isinstance(point, dict):
        return False
    lat, lon = point.get('lat'), point.get('lon')
    return (type(lat) in (int, float) and type(lon) in (int, float)
            and -90 <= lat <= 90 and -180 <= lon <= 180
            and math.isfinite(lat) and math.isfinite(lon))


def validate(ledger, sources, primary_corpus=None, attachment_manifest=None):
    require(ledger.get('schemaVersion') == 1, 'unsupported schema')
    require(ledger.get('eventYearMeaning') == 'ATTESTED_EVENT_NOT_FOUNDATION_YEAR', 'eventYear meaning is required')
    source_by_id = {s['sourceId']: s for s in sources['sources']}
    records = {r['recordId']: r for r in sources['records']}
    attachments = {r['sha256']: Path(r['path']) for r in attachment_manifest['files']} if attachment_manifest else {}
    seen = set()
    for entry in ledger['battlefields']:
        identity = entry['id']
        require(identity not in seen, 'duplicate battlefield identity')
        seen.add(identity)
        require(entry['role'] in ROLES, 'unsupported battlefield role')
        require(entry['positionStatus'] in ('APPROXIMATE', 'WITHHELD'), 'unsupported position status')
        require(entry['boundaryStatus'] == 'NOT_RECONSTRUCTED', 'historical boundary is not reconstructed')
        require(entry['historicalDecimalCoordinatesVerified'] is False, 'decimal coordinates are not primary-verified')
        require(entry['eventYear'] is None or type(entry['eventYear']) is int, 'eventYear must be integer or null')
        point = entry['coordinates']
        if entry['positionStatus'] == 'WITHHELD':
            require(point is None, 'WITHHELD position must be null')
        else:
            require(valid_point(point), 'invalid approximate coordinate')
        require(bool(entry['primaryEvidence']), 'primary identity evidence is required')
        for evidence in entry['primaryEvidence']:
            path = PurePosixPath(evidence['sourcePath'])
            require(len(path.parts) == 2 and path.parts[0] == 'corpus' and path.suffix == '.txt'
                    and not path.is_absolute() and '..' not in path.parts, 'invalid primary sourcePath')
            require(bool(SHA256.fullmatch(evidence['sha256'])), 'invalid primary SHA256')
            require(type(evidence['line']) is int and evidence['line'] > 0, 'invalid primary line')
            require(bool(evidence['quote']) and bool(evidence['book']) and type(evidence['volume']) is int, 'missing primary citation')
            require(evidence['evidenceKind'] in ('DYNASTIC_HISTORY', 'LATER_HISTORICAL_GEOGRAPHY', 'LATER_COMMENTARY'), 'unknown source grade')
            if primary_corpus is not None:
                validate_primary(evidence, primary_corpus)
        require(bool(entry['modernEvidence']), 'modern provenance required even for withheld reference')
        point_supported = point is None
        for evidence in entry['modernEvidence']:
            source = source_by_id.get(evidence['sourceId'])
            record = records.get(evidence['recordId'])
            require(source is not None and record is not None, 'missing source record')
            require(record['sourceId'] == evidence['sourceId'], 'source record attribution mismatch')
            require(source['sha256'] == evidence['sourceId'] and source['title'] == evidence['title']
                    and source['sourceUrl'] == evidence['sourceUrl'], 'modern source metadata mismatch')
            require(evidence['headingLine'] == record['lineStart'], 'modern heading line mismatch')
            require(record['lineStart'] <= evidence['coordinateLine'] <= record['lineEnd'], 'modern coordinate line outside record')
            relation = evidence['relation']
            if relation == 'DIRECT_NAMED_SITE':
                require(record['kind'] == 'OTHER_NAMED_SITE' and entry['nameHan'] in record['namesHan'], 'direct named site evidence required')
            elif relation == 'EXPLICIT_ALIAS_LOCATION':
                require(any(entry['nameHan'] in alias['namesHan'] and alias['line'] in evidence['identityLines']
                            for alias in record['aliases']), 'explicit source alias/line required')
            elif relation == 'UNASSIGNED_COUNTY_REFERENCE':
                require(entry['positionStatus'] == 'WITHHELD', 'county reference cannot supply assigned point')
            else:
                raise ValueError('unknown modern relation')
            if point is not None:
                point_supported |= any(location['line'] == evidence['coordinateLine'] and point in location['coordinatePairs']
                                       for location in record['locations'])
            if attachment_manifest is not None:
                require(evidence['sourceId'] in attachments, 'missing source attachment')
                raw = attachments[evidence['sourceId']].read_bytes()
                require(hashlib.sha256(raw).hexdigest() == evidence['sourceId'], 'modern source SHA256 mismatch')
                lines = raw.decode('utf-8').splitlines()
                require(evidence['quote'] in lines[evidence['coordinateLine'] - 1], 'modern quote/line mismatch')
        require(point_supported, 'assigned coordinate differs from attributed source point')
    return {'battlefields': len(seen),
            'approximate': sum(r['positionStatus'] == 'APPROXIMATE' for r in ledger['battlefields']),
            'withheld': sum(r['positionStatus'] == 'WITHHELD' for r in ledger['battlefields']),
            'primaryFilesChecked': primary_corpus is not None,
            'attachmentFilesChecked': attachment_manifest is not None}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ledger', type=Path, default=ROOT / 'data/curated/han/historical-battlefields-v1.json')
    parser.add_argument('--sources', type=Path, default=ROOT / 'data/curated/han/namu-source-records-v1.json')
    parser.add_argument('--primary-corpus', type=Path)
    parser.add_argument('--attachment-manifest', type=Path)
    args = parser.parse_args()
    load = lambda path: json.loads(path.read_text(encoding='utf-8'))
    result = validate(load(args.ledger), load(args.sources), args.primary_corpus,
                      load(args.attachment_manifest) if args.attachment_manifest else None)
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
