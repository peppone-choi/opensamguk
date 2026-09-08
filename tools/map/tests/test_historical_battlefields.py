import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location('historical_battlefields', ROOT / 'tools/map/validate_historical_battlefields.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class HistoricalBattlefieldsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ledger = json.loads((ROOT / 'data/curated/han/historical-battlefields-v1.json').read_text())
        cls.sources = json.loads((ROOT / 'data/curated/han/namu-source-records-v1.json').read_text())

    def test_approved_first_batch_keeps_four_approximate_positions_and_one_withheld(self):
        MODULE.validate(self.ledger, self.sources)
        entries = {r['id']: r for r in self.ledger['battlefields']}
        self.assertEqual({'chibi', 'guandu', 'changban', 'baidicheng', 'hulaoguan'}, set(entries))
        self.assertEqual({'chibi': 'NAVAL', 'guandu': 'FIELD', 'changban': 'FIELD', 'baidicheng': 'FORTRESS', 'hulaoguan': 'PASS'}, {k: r['role'] for k, r in entries.items()})
        self.assertEqual([208, 200, 208, 222, None], [r['eventYear'] for r in self.ledger['battlefields']])
        self.assertEqual(4, sum(r['positionStatus'] == 'APPROXIMATE' for r in entries.values()))
        self.assertEqual('WITHHELD', entries['hulaoguan']['positionStatus'])
        self.assertIsNone(entries['hulaoguan']['coordinates'])

    def test_changed_source_point_cannot_be_admitted_as_approved_position(self):
        ledger = copy.deepcopy(self.ledger)
        ledger['battlefields'][0]['coordinates']['lon'] += 1
        with self.assertRaisesRegex(ValueError, 'source point'):
            MODULE.validate(ledger, self.sources)

    def test_county_location_needs_explicit_alias_adjudication(self):
        ledger = copy.deepcopy(self.ledger)
        baidi = next(r for r in ledger['battlefields'] if r['id'] == 'baidicheng')
        baidi['modernEvidence'][0]['relation'] = 'DIRECT_NAMED_SITE'
        with self.assertRaisesRegex(ValueError, 'named site'):
            MODULE.validate(ledger, self.sources)

    def test_withheld_point_cannot_be_enabled_by_assigning_county_coordinate(self):
        ledger = copy.deepcopy(self.ledger)
        ledger['battlefields'][-1]['coordinates'] = {'lat': 34.84736, 'lon': 113.19887}
        with self.assertRaisesRegex(ValueError, 'WITHHELD'):
            MODULE.validate(ledger, self.sources)

    def test_invalid_coordinate_and_duplicate_identity_fail_closed(self):
        for value in [True, float('nan'), 10**400, 91]:
            ledger = copy.deepcopy(self.ledger)
            ledger['battlefields'][0]['coordinates']['lat'] = value
            with self.subTest(value=str(value)[:12]), self.assertRaises(ValueError):
                MODULE.validate(ledger, self.sources)
        ledger = copy.deepcopy(self.ledger)
        ledger['battlefields'][1]['id'] = ledger['battlefields'][0]['id']
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            MODULE.validate(ledger, self.sources)

    def test_primary_quote_validation_checks_hash_line_and_content(self):
        with tempfile.TemporaryDirectory() as directory:
            corpus = Path(directory)
            evidence = {'sourcePath': 'corpus/example.txt', 'sha256': '0' * 64,
                        'line': 1, 'quote': 'actual'}
            (corpus / 'example.txt').write_text('actual\n')
            with self.assertRaisesRegex(ValueError, 'SHA256'):
                MODULE.validate_primary(evidence, corpus)
            import hashlib
            evidence['sha256'] = hashlib.sha256((corpus / 'example.txt').read_bytes()).hexdigest()
            MODULE.validate_primary(evidence, corpus)
            evidence['quote'] = 'invented'
            with self.assertRaisesRegex(ValueError, 'quote'):
                MODULE.validate_primary(evidence, corpus)

    def test_provenance_cannot_use_host_path_or_nonexistent_source_record(self):
        ledger = copy.deepcopy(self.ledger)
        ledger['battlefields'][0]['primaryEvidence'][0]['sourcePath'] = '/tmp/private.txt'
        with self.assertRaisesRegex(ValueError, 'sourcePath'):
            MODULE.validate(ledger, self.sources)
        ledger = copy.deepcopy(self.ledger)
        ledger['battlefields'][0]['modernEvidence'][0]['recordId'] = 'missing:1'
        with self.assertRaisesRegex(ValueError, 'source record'):
            MODULE.validate(ledger, self.sources)
