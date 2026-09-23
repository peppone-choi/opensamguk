"""A local relocation must preserve unrelated territory and the prior reviewed stage."""
import copy
import json
import unittest
from pathlib import Path

from tools.map import relocate_han_province as relocation
from tools.map.tests.frozen_geuk_map import input_bytes

ROOT = Path(__file__).resolve().parents[3]


class GeukRelocationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source_bytes = input_bytes()
        cls.source = json.loads(cls.source_bytes)
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
        # 오배정 縣 4곳을 제자리로 돌리고, 사료가 지목한 郡으로 縣 4곳(無慮·高顯·遼陽·比景)의
        # 씨앗칸을 옮긴 뒤의 실측이다(2,035칸 이동). 앞 단계 값
        # 121603/95396/26207 은 아래 priorSummary 가 그대로 들고 있다 —
        # data/curated/han/county-misbinding-rebindings-v1.json 참조.
        # 2026-09-14: w1 11곳 편입. EXACT 95026→95902, unresolved 26612→25736.
        # 2026-09-15: w2 176곳 편입. EXACT 96096→115195, unresolved 25542→6443(합은 그대로).
        # 2026-09-16 1098: 五原郡 본토 이동(南匈奴 직할 → 城 연결 120칸)·忻州 飛地 흡수·平陰 省 뒤 실측. 앞 단계 priorSummary 는 불변.
        self.assertEqual(121742, result['summary']['cityLinkedCellCount'])
        # 2026-09-17: 郡國 밖 취락 城(w5)과 漢昌 巴中 보정으로 승인 칸이 늘었다.
        self.assertEqual(121210, result['summary']['exactApprovedCellCount'])
        self.assertEqual(532, result['summary']['unresolvedCellCount'])
        rebinding = result['countyRebindingProjection']
        self.assertEqual(2699, rebinding['changedCellCount'])  # + 2026-09-16 1098 五原郡 九原·河陰
        self.assertEqual(121603, rebinding['priorSummary']['cityLinkedCellCount'])
        self.assertEqual(121071, rebinding['priorSummary']['exactApprovedCellCount'])
        self.assertEqual(532, rebinding['priorSummary']['unresolvedCellCount'])
        # 1447 城 단계는 앞선 지도 문서를 다시 고정한다. 옛 劇 재배치의
        # 61칸 증명은 고정 입력과 재배치 원장으로 따로 검사한다.
        self.assertEqual(61, len(self.ledger['ownerDelta']))

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
