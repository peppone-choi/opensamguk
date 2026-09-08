"""A local relocation must preserve unrelated territory and the prior reviewed stage."""
import copy
import json
import unittest
from pathlib import Path

from tools.map import relocate_han_province as relocation

ROOT = Path(__file__).resolve().parents[3]


class GeukRelocationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source_bytes = (ROOT / 'data/map/han-tiles.json').read_bytes()
        cls.source = json.loads(cls.source_bytes)
        if relocation.digest(cls.source) == json.loads(relocation.LEDGER.read_text())['outputDocumentSha256']:
            cls.source = relocation.restore_document(cls.source, json.loads(relocation.LEDGER.read_text()))
            cls.source_bytes = (json.dumps(cls.source, ensure_ascii=False, separators=(',', ':')) + '\n').encode()
        cls.ledger, cls.result = relocation.prepare(cls.source_bytes)

    def test_exact_projected_target_owns_new_territory(self):
        owner = relocation.owner_values(self.result)
        self.assertEqual(703, owner[192 * 768 + 490])
        self.assertEqual(36, owner.count(703))
        self.assertEqual(46, owner.count(709))
        self.assertEqual((490, 192), (self.result['cities'][162]['col'], self.result['cities'][162]['row']))
        self.assertEqual(1, relocation.component_count(owner, 703, 768))
        self.assertEqual(1, relocation.component_count(owner, 709, 768))

    def test_only_reviewed_cells_and_label_change(self):
        before, after = relocation.owner_values(self.source), relocation.owner_values(self.result)
        changed = {i for i, pair in enumerate(zip(before, after)) if pair[0] != pair[1]}
        self.assertEqual(61, len(changed))
        self.assertTrue(all(before[i] in (703, 709) for i in changed))
        self.assertFalse(any(after[i] == 703 for i, owner in enumerate(before) if owner == 703))
        for field in ['terrain', 'seatOwner', 'juns', 'provinceRecords', 'jurisdictionRecords', 'commanderyRecords']:
            self.assertEqual(self.source[field], self.result[field], field)
        self.assertEqual(self.source['cities'][:162], self.result['cities'][:162])
        self.assertEqual(self.source['cities'][163:], self.result['cities'][163:])

    def test_restoration_and_second_application_are_exact(self):
        self.assertEqual(self.source, relocation.restore_document(self.result, self.ledger))
        self.assertEqual(self.result, relocation.relocate_document(self.result, self.ledger))

    def test_prior_fragment_stage_still_validates_after_relocation(self):
        from tools.map import adjudicate_han_province_fragments as fragments
        ledger = json.loads((ROOT / 'data/curated/han/province-fragment-adjudications-v1.json').read_text())
        self.assertEqual(self.result, fragments.materialize_document(self.result, ledger))
        broken = copy.deepcopy(ledger)
        broken['outputOwnerSha256'] = '0' * 64
        with self.assertRaises(ValueError):
            fragments.materialize_document(self.result, broken)

    def test_territory_projection_preserves_review_and_rejects_changed_row(self):
        from tools.map import audit_territory_disconnections as audit
        ledger = json.loads((ROOT / 'data/curated/han/territory-disconnection-adjudications-v1.json').read_text())
        unchanged = copy.deepcopy(ledger)
        result = audit.check(self.result, ledger)
        self.assertEqual([], result['errors'])
        self.assertEqual(ledger, unchanged)
        self.assertEqual('DERIVED_FROM_EXISTING_REVIEW', result['relocationProjection']['basis'])
        broken = copy.deepcopy(ledger)
        row = next(row for row in broken['adjudications'] if row['componentKey'] == 'PARENT-0038@498:182')
        row['memberIds'] = row['memberIds'][:-1]
        with self.assertRaises(ValueError):
            audit.check(self.result, broken)

    def test_reconciliation_projects_only_actual_changed_cell_buckets(self):
        from tools.map import build_han_parent_reconciliation as reconciliation
        result = json.loads(reconciliation.render_ledger())
        self.assertEqual(107155, result['summary']['cityLinkedCellCount'])
        self.assertEqual(80956, result['summary']['exactApprovedCellCount'])
        self.assertEqual(26199, result['summary']['unresolvedCellCount'])
        self.assertEqual(61, result['relocationCountProjection']['changedCellCount'])

    def test_forged_ledger_hashes_cannot_replace_the_frozen_input(self):
        source, output = copy.deepcopy(self.source), copy.deepcopy(self.result)
        source['_meta']['note'] = output['_meta']['note'] = 'unreviewed source'
        ledger = copy.deepcopy(self.ledger)
        ledger['inputDocumentSha256'] = relocation.digest(source)
        ledger['outputDocumentSha256'] = relocation.digest(output)
        with self.assertRaises(ValueError):
            relocation.relocate_document(source, ledger)

    def test_city_order_normalization_rejects_duplicate_or_missing_ids(self):
        for kind in ['duplicate', 'missing']:
            document = copy.deepcopy(self.result)
            if kind == 'duplicate':
                document['cities'][-1] = copy.deepcopy(document['cities'][0])
            else:
                document['cities'].pop()
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                relocation.canonicalize_city_order(document, self.ledger)

    def test_forged_output_and_extra_reverse_delta_are_rejected(self):
        output, ledger = copy.deepcopy(self.result), copy.deepcopy(self.ledger)
        owner = relocation.owner_values(output)
        reviewed = {row['row'] * 768 + row['col'] for row in ledger['ownerDelta']}
        pos = next(i for i, value in enumerate(owner) if value == 1 and i not in reviewed)
        owner[pos] = 350
        relocation._refresh(output, owner)
        ledger['ownerDelta'].append({'col': pos % 768, 'row': pos // 768,
                                     'before': '200026', 'after': '45028'})
        ledger['outputDocumentSha256'] = relocation.digest(output)
        with self.assertRaises(ValueError):
            relocation.restore_document(output, ledger)

    def test_changed_terrain_or_delta_cannot_be_replayed(self):
        broken = copy.deepcopy(self.result)
        broken['terrain'][0] = [1, 1]
        with self.assertRaises(ValueError):
            relocation.relocate_document(broken, self.ledger)
        ledger = copy.deepcopy(self.ledger)
        ledger['ownerDelta'][0]['after'] = '85217'
        with self.assertRaises(ValueError):
            relocation.relocate_document(self.source, ledger)


if __name__ == '__main__':
    unittest.main()
