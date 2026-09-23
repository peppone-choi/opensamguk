#!/usr/bin/env python3
"""Keep every partition carry needing review visible in a separate decision table.

This is a proposal inventory, never an automatic historical adjudication.
"""
import argparse
import json
from pathlib import Path
import audit_territory_disconnections as audit

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'data/curated/han/territory-disconnection-adjudications-partition-v1.json'
OUTPUT = ROOT / 'data/curated/han/territory-disconnection-review-table-v1.json'
TILES = ROOT / 'data/map/han-tiles.json'


def build(source, tiles):
    pending = [r for r in source['adjudications'] if (r.get('partitionCarry') or {}).get('pendingReview')]
    if len(pending) != source['counts']['pendingReview']:
        raise ValueError('pendingReview count differs from source')
    current = {r['componentKey']: r for r in audit.inventory(tiles)}
    if 'PARENT-0038@456:178' not in current:
        raise ValueError('positive control 北海國 component absent from current grid')
    rows = []
    for r in pending:
        carry = r['partitionCarry']
        now = current.get(r['componentKey'])
        geometry = ('ABSENT' if now is None else 'EXACT' if
                    now['memberIds'] == r['memberIds'] and now['cellCount'] == r['cellCount']
                    else 'CHANGED')
        manual = carry['mode'] == 'NEW_UNVERIFIED' or (r['confidence'] == 'LOW' and r['review']['state'] != 'INHERITED')
        rows.append({
            'componentKey': r['componentKey'], 'unitId': r['unitId'], 'unitNameCh': r['unitNameCh'],
            'memberIds': r['memberIds'], 'cellCountAtPartition': r['cellCount'],
            'carryMode': carry['mode'], 'draftVerdict': r['verdict'],
            'sourceReviewState': r['review']['state'], 'sourceConfidence': r['confidence'],
            'currentGeometry': geometry,
            'currentMemberIds': now['memberIds'] if now else None,
            'currentCellCount': now['cellCount'] if now else None,
            'status': 'HUMAN_DECISION_REQUIRED' if manual else 'DRAFT_RECHECK_GEOMETRY',
            'decision': None, 'evidenceRefs': r['evidenceRefs'],
        })
    rows.sort(key=lambda r: r['componentKey'])
    return {
        'schemaVersion': 1, 'sourceLedgerId': source['ledgerId'],
        'sourcePartitionStageOutputSha256': source['partitionStageOutputSha256'],
        'scope': 'Partition carry review proposals only; draftVerdict is not an adopted ruling.',
        'counts': {'rows': len(rows), 'humanDecisionRequired': sum(r['status'] == 'HUMAN_DECISION_REQUIRED' for r in rows),
                   'currentExact': sum(r['currentGeometry'] == 'EXACT' for r in rows),
                   'currentChanged': sum(r['currentGeometry'] == 'CHANGED' for r in rows),
                   'currentAbsent': sum(r['currentGeometry'] == 'ABSENT' for r in rows)},
        'rows': rows,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    payload = json.dumps(build(json.loads(SOURCE.read_text()), json.loads(TILES.read_text())), ensure_ascii=False, indent=1) + '\n'
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_text() != payload:
            raise SystemExit('territory review table drift')
        print('territory review table byte-identical')
    else:
        OUTPUT.write_text(payload)
        print(OUTPUT.relative_to(ROOT))


if __name__ == '__main__':
    main()
