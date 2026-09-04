"""The territory-disconnection ledger must cover every disconnected component with evidence.

Red first: the committed map has disconnected commanderies and counties, and until each
secondary component has a reviewed row the ``--check`` gate must fail. The synthetic
fixtures pin the rules the gate enforces so a green run proves the rules, not the data.
"""

from __future__ import annotations

import copy
import json
import pathlib
import tempfile
import unittest
from pathlib import Path

from tools.map import audit_territory_disconnections as audit

ROOT = Path(__file__).resolve().parents[3]


def _rle(values: list[int]) -> list[list[int]]:
    runs: list[list[int]] = []
    for value in values:
        if runs and runs[-1][0] == value:
            runs[-1][1] += 1
        else:
            runs.append([value, 1])
    return runs


def _grid(rows: list[str]) -> list[int]:
    """'.' is sea (-1); a letter is province index ord(letter) - ord('a')."""
    return [-1 if ch == "." else ord(ch) - ord("a") for row in rows for ch in row]


def _document() -> dict:
    """Two commanderies on a 6x4 grid.

    Provinces: a,b -> county J1 (commandery C1, seat J1); c -> county J2 (C1);
    d -> county J3 (C2, seat J3); e -> county J4 (C2) on an island; f -> J1's second
    piece, cut off from a/b by C2's county J3.
    """
    rows = [
        "aab.ee",
        "abcd..",
        "..dddf",
        "......",
    ]
    owner = _grid(rows)
    terrain = ["111011", "111100", "001111", "000000"]
    provinces = [
        {"id": "P-a", "nameCh": "甲鄉", "jurisdictionId": "J1", "parentRegionId": "C1"},
        {"id": "P-b", "nameCh": "乙鄉", "jurisdictionId": "J1", "parentRegionId": "C1"},
        {"id": "P-c", "nameCh": "丙鄉", "jurisdictionId": "J2", "parentRegionId": "C1"},
        {"id": "P-d", "nameCh": "丁鄉", "jurisdictionId": "J3", "parentRegionId": "C2"},
        {"id": "P-e", "nameCh": "戊鄉", "jurisdictionId": "J4", "parentRegionId": "C2"},
        {"id": "P-f", "nameCh": "己鄉", "jurisdictionId": "J1", "parentRegionId": "C1"},
    ]
    parent_ids = ["C1", "C2"]
    parent_owner = [-1 if o < 0 else parent_ids.index(provinces[o]["parentRegionId"]) for o in owner]
    return {
        "_meta": {
            "cols": 6, "rows": 4,
            "terrainLegend": {"0": "SEA", "1": "PLAIN", "3": "RIVER"},
        },
        "terrain": terrain,
        "owner": _rle(owner),
        "parentOwner": _rle(parent_owner),
        "provinceRecords": provinces,
        "jurisdictionRecords": [
            {"id": "J1", "nameCh": "甲县", "commanderyId": "C1"},
            {"id": "J2", "nameCh": "乙县", "commanderyId": "C1"},
            {"id": "J3", "nameCh": "丙县", "commanderyId": "C2"},
            {"id": "J4", "nameCh": "丁县", "commanderyId": "C2"},
        ],
        "commanderyRecords": [
            {"id": "C1", "nameCh": "甲郡", "seatJurisdictionId": "J1"},
            {"id": "C2", "nameCh": "乙郡", "seatJurisdictionId": "J3"},
        ],
        "parentRegions": [{"id": "C1"}, {"id": "C2"}],
    }


def _ledger(rows: list[dict]) -> dict:
    return {
        "schemaVersion": 1,
        "ledgerId": audit.LEDGER_ID,
        "referenceYear": 220,
        "policy": {
            "noAutomaticRepair": True,
            "noProximityReparenting": True,
            "noRepresentativeColorFill": True,
        },
        "ifRules": {rule: f"{rule} description" for rule in sorted(audit.IF_RULES)},
        "adjudications": rows,
    }


def _row(**overrides: object) -> dict:
    row = {
        "unitKind": "COMMANDERY", "unitId": "C1", "unitNameCh": "甲郡",
        "componentKey": "C1#1", "cellCount": 1, "memberIds": ["J1"], "holdsSeat": True,
        "verdict": "GEOMETRY_DEFECT", "confidence": "MEDIUM",
        "effectiveFrom": None, "effectiveTo": None,
        "ifRule": "DEFECT_PRESERVE_PENDING_GEOMETRY_PR",
        "evidenceRefs": ["map:han-tiles owner grid - P-f is cut off by 丙县"],
        "rationale": "test",
        "defectNote": "P-f",
    }
    row.update(overrides)
    return row


ISLAND = dict(
    unitId="C2", unitNameCh="乙郡", componentKey="C2#1", cellCount=2, memberIds=["J4"],
    holdsSeat=False, verdict="WATER_SEPARATED", confidence="HIGH", ifRule="WATER_ROUTE_ONLY",
    evidenceRefs=["map:han-tiles terrain - component touches SEA only"], defectNote=None,
)
COUNTY_PIECE = dict(
    unitKind="JURISDICTION", unitId="J1", unitNameCh="甲县", componentKey="J1#1", cellCount=1,
    memberIds=["P-f"], memberNamesCh=["己鄉"], holdsSeat=False,
)


class InventoryTest(unittest.TestCase):
    def test_inventory_lists_every_secondary_component_with_its_boundary(self):
        rows = audit.inventory(_document())
        keys = {(r["componentKey"], r["cellCount"], tuple(r["memberIds"]), r["holdsSeat"]) for r in rows}
        self.assertEqual(keys, {
            ("C1#1", 1, ("J1",), True),        # P-f: seat county's second piece
            ("C2#1", 2, ("J4",), False),       # island county
            ("J1#1", 1, ("P-f",), False),      # county-level split
        })
        by_key = {r["componentKey"]: r for r in rows}
        self.assertEqual(by_key["C1#1"]["landNeighbourIds"], ["C2"])
        self.assertEqual(by_key["C2#1"]["landNeighbourIds"], [])
        self.assertEqual(by_key["C2#1"]["negativeBoundary"], {"SEA": 3})

    def test_seatless_largest_piece_is_judged_and_seat_piece_is_the_body(self):
        document = _document()
        document["commanderyRecords"][0]["seatJurisdictionId"] = "J2"  # seat moves to 1-cell 乙县
        keys = {r["componentKey"] for r in audit.inventory(document)}
        # C1 pieces: {a,b,c} rank 0 holds the seat now → body; {f} rank 1 judged.
        self.assertEqual(keys, {"C1#1", "C2#1", "J1#1"})
        document["commanderyRecords"][0]["seatJurisdictionId"] = None
        keys = {r["componentKey"] for r in audit.inventory(document)}
        self.assertEqual(keys, {"C1#1", "C2#1", "J1#1"}, "no seat → largest piece is the body")

    def test_seat_in_a_smaller_piece_makes_the_larger_piece_the_judged_one(self):
        """The branch the seat rule exists for. Every seat value the sibling test tries
        (J1, J2, None) lands in C1's largest piece, so all three answer rank 0 and the
        rule was never actually exercised: 甲县 owns both the body and the cut-off cell.

        Give the cut-off cell its own county 戊县 and seat C1 there, and the body has to
        move to the smaller piece — the larger blob becomes the judged component C1#0.
        """
        document = _document()
        for province in document["provinceRecords"]:
            if province["id"] == "P-f":
                province["jurisdictionId"] = "J5"
        document["jurisdictionRecords"].append(
            {"id": "J5", "nameCh": "戊县", "commanderyId": "C1"}
        )
        self.assertEqual(
            {r["componentKey"] for r in audit.inventory(document)},
            {"C1#1", "C2#1"},
            "seat still in the big blob → the 1-cell piece is judged",
        )
        document["commanderyRecords"][0]["seatJurisdictionId"] = "J5"
        judged = {r["componentKey"]: r for r in audit.inventory(document)}
        self.assertEqual(set(judged), {"C1#0", "C2#1"})
        self.assertEqual(judged["C1#0"]["cellCount"], 6)
        self.assertFalse(judged["C1#0"]["holdsSeat"])


class CheckTest(unittest.TestCase):
    def full_ledger(self) -> dict:
        return _ledger([
            _row(),
            _row(**ISLAND),
            _row(**{**COUNTY_PIECE, "defectNote": "P-f"}),
        ])

    def test_full_ledger_passes(self):
        result = audit.check(_document(), self.full_ledger())
        self.assertEqual(result["errors"], [])
        self.assertEqual((result["componentCount"], result["adjudicatedCount"]), (3, 3))
        self.assertEqual(result["verdictCounts"], {"GEOMETRY_DEFECT": 2, "WATER_SEPARATED": 1})

    def test_missing_row_is_reported_as_unadjudicated(self):
        ledger = self.full_ledger()
        ledger["adjudications"].pop(1)
        errors = audit.check(_document(), ledger)["errors"]
        self.assertEqual(len(errors), 1)
        self.assertTrue(errors[0].startswith("UNADJUDICATED C2#1"), errors)

    def test_stale_row_and_drift_are_reported(self):
        ledger = self.full_ledger()
        ledger["adjudications"].append(_row(componentKey="C1#9", cellCount=3))
        ledger["adjudications"][0]["cellCount"] = 2
        ledger["adjudications"][1]["memberIds"] = ["J3"]
        errors = audit.check(_document(), ledger)["errors"]
        self.assertEqual(
            [e.split(" ")[0] for e in errors], ["CELL_DRIFT", "MEMBER_DRIFT", "STALE_ROW"],
            errors,
        )

    def test_water_verdict_tolerates_an_off_map_edge_but_needs_real_water(self):
        document = _document()
        document["_meta"]["terrainLegend"]["9"] = "OUT_OF_SCOPE"
        # The island's boundary is the sea cell west of it plus the two cells below it;
        # turning those two off-map leaves one real sea cell, which is still water.
        document["terrain"] = ["111011", "111199", "001111", "000000"]
        result = audit.check(document, self.full_ledger())
        self.assertEqual(result["errors"], [], "SEA + OUT_OF_SCOPE boundary is still water-separated")
        document["terrain"] = ["111911", "111199", "001111", "000000"]
        errors = audit.check(document, self.full_ledger())["errors"]
        self.assertEqual([e.split(" ")[0] for e in errors], ["NOT_WATER_BOUNDED"], errors)

    def test_water_verdict_needs_no_land_neighbours_and_water_boundary(self):
        ledger = self.full_ledger()
        ledger["adjudications"][0].update(
            verdict="WATER_SEPARATED", ifRule="WATER_ROUTE_ONLY", defectNote=None,
        )
        errors = audit.check(_document(), ledger)["errors"]
        self.assertEqual([e.split(" ")[0] for e in errors], ["NOT_WATER_SEPARATED"], errors)

    def test_water_route_rule_is_recomputed_even_on_an_external_polity_row(self):
        # EXTERNAL_POLITY may carry WATER_ROUTE_ONLY (an island polity). The claim is
        # still a claim about the grid, so it is recomputed like any other water row.
        ledger = self.full_ledger()
        ledger["adjudications"][0].update(
            verdict="EXTERNAL_POLITY", ifRule="WATER_ROUTE_ONLY", defectNote=None,
            evidenceRefs=["shiliao:三國志/卷30 東夷傳「倭人」"],
        )
        errors = audit.check(_document(), ledger)["errors"]
        self.assertEqual([e.split(" ")[0] for e in errors], ["NOT_WATER_SEPARATED"], errors)
        ledger["adjudications"][0]["ifRule"] = "EXTERNAL_POLITY_POLICY"
        self.assertEqual(audit.check(_document(), ledger)["errors"], [])

    def test_source_backed_verdicts_require_a_source_evidence_ref(self):
        for verdict, rule, extra in (
            ("HISTORICAL_EXCLAVE", "EXCLAVE_KEEP", {}),
            ("PARENT_MISASSIGNMENT", "MISASSIGNMENT_PENDING_PARENT_LEDGER", {"proposedParent": {"nameCh": "乙郡"}}),
            ("EXTERNAL_POLITY", "EXTERNAL_POLITY_POLICY", {}),
        ):
            with self.subTest(verdict=verdict):
                ledger = _ledger([_row(verdict=verdict, ifRule=rule, defectNote=None, **extra)])
                with self.assertRaisesRegex(ValueError, "source-backed evidence"):
                    audit.validate_ledger(ledger)
                ledger["adjudications"][0]["evidenceRefs"].append(
                    "shiliao:後漢書/卷110 郡國志「甲縣」"
                )
                audit.validate_ledger(ledger)

    def test_misassignment_needs_proposed_parent_and_unknown_needs_searched(self):
        ledger = _ledger([_row(
            verdict="PARENT_MISASSIGNMENT", ifRule="MISASSIGNMENT_PENDING_PARENT_LEDGER",
            evidenceRefs=["chgis:hvd_1 source note「屬乙郡」"], defectNote=None,
        )])
        with self.assertRaisesRegex(ValueError, "proposedParent"):
            audit.validate_ledger(ledger)
        ledger = _ledger([_row(verdict="UNKNOWN", ifRule="UNKNOWN_PRESERVE", defectNote=None)])
        with self.assertRaisesRegex(ValueError, "searched"):
            audit.validate_ledger(ledger)

    def test_placeholder_proposed_parent_is_rejected(self):
        """A synthesiser that could not resolve the parent must fail the gate, not ship a stub.

        `{"nameCh": "?"}` satisfies "non-empty string" while asserting nothing, so the
        earlier rule let an unresolved parent through as if it had been adjudicated.
        """
        for stub in ("?", "??", "-", "TBD", "tbd", "unknown", "UNKNOWN", "미상", "  ?  "):
            with self.subTest(stub=stub):
                ledger = _ledger([_row(
                    verdict="PARENT_MISASSIGNMENT",
                    ifRule="MISASSIGNMENT_PENDING_PARENT_LEDGER",
                    evidenceRefs=["chgis:hvd_1 source note「屬乙郡」"], defectNote=None,
                    proposedParent={"nameCh": stub},
                )])
                with self.assertRaisesRegex(ValueError, "proposedParent"):
                    audit.validate_ledger(ledger)

    def test_proposed_parent_commandery_id_must_exist_in_the_map(self):
        """A parent id that no commandery record carries is a typo, not a finding."""
        document = _document()
        ledger = self._misassigned({"nameCh": "乙郡", "commanderyId": "PARENT-9999"})
        result = audit.check(document, ledger)
        self.assertTrue(
            any("PARENT-9999" in e for e in result["errors"]), result["errors"],
        )

    def _misassigned(self, proposed):
        """Make the first fixture row a PARENT_MISASSIGNMENT carrying `proposed`.

        Searching the fixture for such a row and skipping when none was found meant the
        rule was never exercised: the fixture has no PARENT_MISASSIGNMENT row, so the
        test reported "OK (skipped)" instead of guarding anything.
        """
        ledger = self.full_ledger()
        ledger["adjudications"][0] = _row(
            verdict="PARENT_MISASSIGNMENT",
            ifRule="MISASSIGNMENT_PENDING_PARENT_LEDGER",
            evidenceRefs=["chgis:hvd_1 source note「屬乙郡」"],
            defectNote=None,
            proposedParent=proposed,
        )
        return ledger

    def test_parent_without_commandery_id_still_has_to_name_real_commanderies(self):
        """A piece that splits across several 220 CE commanderies has no single parent id,
        so the id is optional. That must not become a hole: omitting it once let a row
        skip the "does this parent exist" check entirely."""
        document = _document()
        result = audit.check(document, self._misassigned({"nameCh": "架空郡·乙郡"}))
        self.assertTrue(
            any("架空郡" in e for e in result["errors"]), result["errors"],
        )

    def test_parent_without_commandery_id_must_name_at_least_one_commandery(self):
        document = _document()
        result = audit.check(document, self._misassigned({"nameCh": "본체 서쪽 어딘가"}))
        self.assertTrue(
            any("NAMES_NO_COMMANDERY" in e for e in result["errors"]), result["errors"],
        )

    def test_multi_parent_row_with_only_real_commanderies_passes(self):
        document = _document()
        result = audit.check(document, self._misassigned({"nameCh": "甲郡(a·b)·乙郡(c)"}))
        self.assertEqual(
            [e for e in result["errors"] if "PARENT" in e or "COMMANDERY" in e], [],
        )

    def test_name_drift_is_reported(self):
        """The names are the half of a row a human actually reads. Leaving them
        uncompared meant a row could label the right component with another one's
        commandery and counties and still pass."""
        document = _document()
        for field, wrong in (("unitNameCh", "乙郡"), ("memberNamesCh", ["엉뚱한 縣"])):
            with self.subTest(field):
                ledger = self.full_ledger()
                ledger["adjudications"][0][field] = wrong
                result = audit.check(document, ledger)
                self.assertTrue(
                    any("NAME_DRIFT" in e for e in result["errors"]), result["errors"],
                )

    def test_jurisdiction_rows_carry_their_province_names(self):
        """inventory() used to emit memberNamesCh=[] for every JURISDICTION row, which
        made any comparison of that field vacuous for the county half of the ledger."""
        components = audit.inventory(_document())
        county = [c for c in components if c["unitKind"] == "JURISDICTION"]
        self.assertTrue(county)
        for comp in county:
            with self.subTest(comp["componentKey"]):
                self.assertEqual(len(comp["memberNamesCh"]), len(comp["memberIds"]))
                self.assertTrue(all(comp["memberNamesCh"]))

    def test_seat_drift_is_reported(self):
        document = _document()
        ledger = self.full_ledger()
        ledger["adjudications"][0]["holdsSeat"] = not ledger["adjudications"][0]["holdsSeat"]
        result = audit.check(document, ledger)
        self.assertTrue(any("SEAT_DRIFT" in e for e in result["errors"]), result["errors"])

    def test_geometry_defect_needs_a_defect_note(self):
        ledger = _ledger([_row(defectNote=None)])
        with self.assertRaisesRegex(ValueError, "defectNote"):
            audit.validate_ledger(ledger)

    def test_grid_only_verdicts_need_a_map_evidence_ref(self):
        """GEOMETRY_DEFECT and WATER_SEPARATED assert something about this grid. A row
        citing only 史料 asserts nothing the geometry PR downstream can reshape from."""
        for verdict, rule in (
            ("GEOMETRY_DEFECT", "DEFECT_PRESERVE_PENDING_GEOMETRY_PR"),
            ("WATER_SEPARATED", "WATER_ROUTE_ONLY"),
        ):
            with self.subTest(verdict):
                ledger = _ledger([_row(
                    verdict=verdict, ifRule=rule,
                    evidenceRefs=["shiliao:後漢書/卷110 郡國志「清河國，高帝置」"],
                )])
                with self.assertRaisesRegex(ValueError, "map:"):
                    audit.validate_ledger(ledger)

    def test_evidence_ref_needs_content_after_its_prefix(self):
        ledger = _ledger([_row(
            verdict="HISTORICAL_EXCLAVE", ifRule="EXCLAVE_KEEP", defectNote=None,
            evidenceRefs=["shiliao:", "map:han-tiles owner grid - P-f"],
        )])
        with self.assertRaisesRegex(ValueError, "no citation"):
            audit.validate_ledger(ledger)

    def test_effective_window_must_be_ordered(self):
        ledger = _ledger([_row(effectiveFrom=280, effectiveTo=220)])
        with self.assertRaisesRegex(ValueError, "effective"):
            audit.validate_ledger(ledger)

    def test_review_block_is_validated(self):
        for label, review in (
            ("undeclared state", {"state": "MADE_UP", "votes": [{"lens": "source", "refuted": False, "reason": "r"}]}),
            ("no votes", {"state": "UPHELD", "votes": []}),
            ("not an object", ["UPHELD"]),
            ("vote missing reason", {"state": "UPHELD", "votes": [{"lens": "source", "refuted": False}]}),
        ):
            with self.subTest(label):
                with self.assertRaisesRegex(ValueError, "review"):
                    audit.validate_ledger(_ledger([_row(review=review)]))

    def test_review_block_that_matches_the_declared_vocabulary_passes(self):
        review = {"state": "UPHELD", "votes": [
            {"lens": "source", "refuted": False, "reason": "index 대조 통과"},
        ]}
        self.assertEqual(len(audit.validate_ledger(_ledger([_row(review=review)]))), 1)

    def test_if_rule_must_match_verdict(self):
        ledger = _ledger([_row(ifRule="EXCLAVE_KEEP")])
        with self.assertRaisesRegex(ValueError, "does not fit verdict"):
            audit.validate_ledger(ledger)

    def test_policy_flags_are_mandatory(self):
        for flag in ("noAutomaticRepair", "noProximityReparenting", "noRepresentativeColorFill"):
            with self.subTest(flag=flag):
                ledger = self.full_ledger()
                ledger["policy"][flag] = False
                with self.assertRaisesRegex(ValueError, "policy"):
                    audit.validate_ledger(ledger)

    def test_duplicate_component_key_is_rejected(self):
        ledger = _ledger([_row(), _row()])
        with self.assertRaisesRegex(ValueError, "repeats componentKey"):
            audit.validate_ledger(ledger)

    def test_machine_local_absolute_path_is_rejected(self):
        for field, row in (
            ("rationale", _row(rationale="근거 파일 /Users/someone/checkout/data/map/han-tiles.json 을 다시 읽었다")),
            ("defectNote", _row(defectNote="/home/runner/work/repo/data/map/han-tiles.json 에서 재현")),
            ("evidenceRefs", _row(evidenceRefs=["map:han-tiles C:\\work\\repo\\data\\map\\han-tiles.json 3셀"])),
        ):
            with self.subTest(field):
                with self.assertRaisesRegex(ValueError, "machine-local path"):
                    audit.validate_ledger(_ledger([row]))

    def test_repo_relative_path_is_accepted(self):
        row = _row(rationale="data/map/han-tiles.json 의 owner 격자를 다시 세었다")
        self.assertEqual(len(audit.validate_ledger(_ledger([row]))), 1)

    def test_corrected_row_must_park_its_overruled_argument(self):
        """A row the refuters overturned has two arguments in it: the one that lost and
        the one that stands. Left in a single `rationale`, the withdrawn text reads as the
        row's current position — on PARENT-0169#1 it said 「사료 근거는 없으므로
        PARENT_MISASSIGNMENT 로 보지 않는다」 above a PARENT_MISASSIGNMENT verdict, and two
        independent citation readers scored the row against the argument it had dropped."""
        review = {"state": "CORRECTED_BY_2_REFUTERS", "votes": [
            {"lens": "source", "refuted": True, "reason": "최초 판정의 전제가 무너진다"},
            {"lens": "chronology", "refuted": True, "reason": "기준연도 소속이 다르다"},
        ]}
        with self.assertRaisesRegex(ValueError, "overruledArgument"):
            audit.validate_ledger(_ledger([_row(review=review)]))

    def test_corrected_row_with_a_parked_argument_passes(self):
        review = {"state": "CORRECTED_BY_2_REFUTERS", "votes": [
            {"lens": "source", "refuted": True, "reason": "최초 판정의 전제가 무너진다"},
            {"lens": "chronology", "refuted": True, "reason": "기준연도 소속이 다르다"},
            {"lens": "geography", "refuted": False, "reason": "격자 사실은 재현된다"},
        ]}
        row = _row(review=review, overruledArgument="[철회] 최초에는 형상 결함으로 보았다.")
        self.assertEqual(len(audit.validate_ledger(_ledger([row]))), 1)

    def test_overruled_argument_needs_a_review_to_explain_it(self):
        """The field records an argument some lens lost. With no review block beside it
        there is no record of who argued it or how the vote went."""
        row = _row(overruledArgument="[기각된 도전] 소속이 틀렸다는 주장.")
        with self.assertRaisesRegex(ValueError, "overruledArgument"):
            audit.validate_ledger(_ledger([row]))

    def test_review_state_must_match_the_vote_tally(self):
        """The state is a claim about the votes sitting next to it. Unchecked, the ledger
        carried two TIEBREAK_RESOLVED rows nobody had refuted, a CORRECTED_BY_2_REFUTERS
        with three refuters, and an INHERITED row that had in fact been judged and
        overturned on its own votes."""
        def review(state, refuted, total=3):
            return {"state": state, "votes": [
                {"lens": f"L{i}", "refuted": i < refuted, "reason": "r"} for i in range(total)
            ]}
        for label, rv, extra in (
            ("upheld though the refuters were the majority", review("UPHELD", 2), {}),
            ("corrected by 2 with three refuters", review("CORRECTED_BY_2_REFUTERS", 3),
             {"overruledArgument": "x"}),
            ("corrected by 3 with two refuters", review("CORRECTED_BY_3_REFUTERS", 2),
             {"overruledArgument": "x"}),
            ("inherited yet refuted on its own votes", review("INHERITED", 2), {}),
            ("tiebreak with nothing to break", review("TIEBREAK_RESOLVED", 0), {}),
        ):
            with self.subTest(label):
                with self.assertRaisesRegex(ValueError, r"review\.state"):
                    audit.validate_ledger(_ledger([_row(review=rv, **extra)]))

    def test_review_state_that_matches_its_tally_passes(self):
        review = {"state": "CORRECTED_BY_2_REFUTERS", "votes": [
            {"lens": "source", "refuted": True, "reason": "r"},
            {"lens": "chronology", "refuted": True, "reason": "r"},
            {"lens": "geography", "refuted": False, "reason": "r"},
        ]}
        row = _row(review=review, overruledArgument="[철회된 최초 판정] …")
        self.assertEqual(len(audit.validate_ledger(_ledger([row]))), 1)

    def test_committed_review_states_match_their_tallies(self):
        ledger = json.loads(audit.DEFAULT_LEDGER.read_text(encoding="utf-8"))
        for row in ledger["adjudications"]:
            rv = row.get("review") or {}
            refuted = sum(1 for v in rv.get("votes", []) if v["refuted"])
            with self.subTest(row["componentKey"], state=rv.get("state")):
                if rv.get("state") == "CORRECTED_BY_2_REFUTERS":
                    self.assertEqual(refuted, 2)
                elif rv.get("state") == "CORRECTED_BY_3_REFUTERS":
                    self.assertEqual(refuted, 3)
                elif rv.get("state") in ("UPHELD", "INHERITED"):
                    self.assertLessEqual(refuted * 2, len(rv.get("votes", [])))

    def test_rationale_may_not_keep_the_refutation_delimiter(self):
        """`[반박]` is the seam this ledger writes between a withdrawn argument and the one
        that replaced it. Finding it inside `rationale` means the two are still glued
        together in the field a reader takes as the row's position."""
        review = {"state": "CORRECTED_BY_2_REFUTERS", "votes": [
            {"lens": "source", "refuted": True, "reason": "최초 판정의 전제가 무너진다"},
            {"lens": "chronology", "refuted": True, "reason": "기준연도 소속이 다르다"},
            {"lens": "geography", "refuted": False, "reason": "격자 사실은 재현된다"},
        ]}
        row = _row(
            review=review,
            overruledArgument="[철회] 최초 판정",
            rationale="형상 결함이다 [반박] source: 소속이 틀렸다",
        )
        with self.assertRaisesRegex(ValueError, "refutation delimiter"):
            audit.validate_ledger(_ledger([row]))

    def test_committed_corrected_rows_separate_the_two_arguments(self):
        """The committed ledger, not a fixture: every overturned row states one position."""
        ledger = json.loads(audit.DEFAULT_LEDGER.read_text(encoding="utf-8"))
        corrected = [r for r in ledger["adjudications"]
                     if r.get("review", {}).get("state", "").startswith("CORRECTED_BY_")]
        self.assertTrue(corrected, "no corrected rows to check")
        for row in corrected:
            with self.subTest(row["componentKey"]):
                self.assertTrue(row.get("overruledArgument"))
                self.assertNotIn("[반박]", row["rationale"])

    def test_check_does_not_mutate_the_map_document(self):
        document = _document()
        before = copy.deepcopy(document)
        audit.check(document, self.full_ledger())
        self.assertEqual(document, before)


class ValidationLayerTest(unittest.TestCase):
    """Negative cases for the type/enum/identity rules. Without these the whole layer
    could be deleted rule by rule with the suite staying green, because the committed
    ledger is well-formed and never exercises a single one of them."""

    CASES = (
        ("schemaVersion", {"schemaVersion": 2}, None, "schemaVersion"),
        ("ledgerId", {"ledgerId": "other"}, None, "ledgerId"),
        ("referenceYear", {"referenceYear": 280}, None, "referenceYear"),
        ("policy flag off", {"policy": {"noAutomaticRepair": False,
                                        "noProximityReparenting": True,
                                        "noRepresentativeColorFill": True}}, None, "policy"),
        ("unknown row key", None, {"extra": 1}, "invalid keys"),
        ("unitKind", None, {"unitKind": "PROVINCE"}, "unitKind"),
        ("empty unitId", None, {"unitId": ""}, "non-empty string"),
        ("empty rationale", None, {"rationale": ""}, "non-empty string"),
        ("cellCount as string", None, {"cellCount": "1"}, "positive integer"),
        ("cellCount zero", None, {"cellCount": 0}, "positive integer"),
        ("memberIds unsorted", None, {"memberIds": ["J2", "J1"]}, "sorted"),
        ("memberIds empty", None, {"memberIds": []}, "sorted"),
        ("holdsSeat as int", None, {"holdsSeat": 1}, "boolean"),
        ("verdict", None, {"verdict": "PROBABLY_FINE"}, "verdict is unknown"),
        ("confidence", None, {"confidence": "VERY_HIGH"}, "confidence is unknown"),
        ("effectiveFrom as string", None, {"effectiveFrom": "220"}, "integer year"),
        ("evidenceRefs empty", None, {"evidenceRefs": []}, "non-empty string array"),
        ("proposedParent id type", None, {
            "verdict": "PARENT_MISASSIGNMENT",
            "ifRule": "MISASSIGNMENT_PENDING_PARENT_LEDGER",
            "defectNote": None,
            "evidenceRefs": ["chgis:hvd_1 source note「屬乙郡」"],
            "proposedParent": {"nameCh": "乙郡", "commanderyId": 7},
        }, "commanderyId"),
    )

    def test_each_rule_rejects_its_own_violation(self):
        for label, ledger_patch, row_patch, message in self.CASES:
            with self.subTest(label):
                ledger = _ledger([_row(**(row_patch or {}))])
                ledger.update(ledger_patch or {})
                with self.assertRaisesRegex(ValueError, message):
                    audit.validate_ledger(ledger)


class CliTest(unittest.TestCase):
    """The CI step runs the CLI and trusts its exit code; nothing proved that contract."""

    def _write(self, name: str, payload: object) -> pathlib.Path:
        path = pathlib.Path(self.tmp.name) / name
        path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
        return path

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.tiles = self._write("tiles.json", _document())
        self.full = [
            _row(), _row(**ISLAND), _row(**{**COUNTY_PIECE, "defectNote": "P-f"}),
        ]

    def _ledger_path(self, rows: list[dict]) -> pathlib.Path:
        return self._write("ledger.json", _ledger(rows))

    def test_check_exits_zero_when_every_component_is_adjudicated(self):
        code = audit.main(["--check", "--tiles", str(self.tiles),
                           "--ledger", str(self._ledger_path(self.full))])
        self.assertEqual(code, 0)

    def test_check_exits_one_on_a_coverage_gap(self):
        code = audit.main(["--check", "--tiles", str(self.tiles),
                           "--ledger", str(self._ledger_path(self.full[:-1]))])
        self.assertEqual(code, 1)

    def test_check_exits_one_on_an_invalid_ledger(self):
        rows = [dict(r) for r in self.full]
        rows[0]["cellCount"] = 0
        code = audit.main(["--check", "--tiles", str(self.tiles),
                           "--ledger", str(self._ledger_path(rows))])
        self.assertEqual(code, 1)

    def test_check_exits_one_when_the_ledger_is_missing(self):
        code = audit.main(["--check", "--tiles", str(self.tiles),
                           "--ledger", str(pathlib.Path(self.tmp.name) / "absent.json")])
        self.assertEqual(code, 1)


class CommittedDataTest(unittest.TestCase):
    def test_every_disconnected_component_in_the_committed_map_is_adjudicated(self):
        document = json.loads(audit.DEFAULT_TILES.read_text(encoding="utf-8"))
        self.assertTrue(audit.DEFAULT_LEDGER.exists(), audit.DEFAULT_LEDGER.relative_to(ROOT))
        ledger = json.loads(audit.DEFAULT_LEDGER.read_text(encoding="utf-8"))
        result = audit.check(document, ledger)
        self.assertEqual(result["errors"], [])
        self.assertGreater(result["componentCount"], 0)
        self.assertEqual(result["adjudicatedCount"], result["componentCount"])

    def test_committed_misassignments_name_a_real_commandery(self):
        document = json.loads(audit.DEFAULT_TILES.read_text(encoding="utf-8"))
        known = {c["nameCh"] for c in document["commanderyRecords"]}
        ids = {c["id"] for c in document["commanderyRecords"]}
        ledger = json.loads(audit.DEFAULT_LEDGER.read_text(encoding="utf-8"))
        for row in audit.validate_ledger(ledger):
            if row["verdict"] != "PARENT_MISASSIGNMENT":
                continue
            proposed = row["proposedParent"]
            with self.subTest(row["componentKey"]):
                self.assertNotIn(proposed["nameCh"].strip(), audit.PLACEHOLDER_PARENTS)
                if proposed.get("commanderyId") is not None:
                    self.assertIn(proposed["commanderyId"], ids)
                    self.assertIn(proposed["nameCh"], known)

    def test_committed_multi_parent_rows_name_real_commanderies(self):
        document = json.loads(audit.DEFAULT_TILES.read_text(encoding="utf-8"))
        ledger = json.loads(audit.DEFAULT_LEDGER.read_text(encoding="utf-8"))
        result = audit.check(document, ledger)
        offenders = [e for e in result["errors"] if "COMMANDERY" in e or "PROPOSED_PARENT" in e]
        self.assertEqual(offenders, [])

    def test_committed_ledger_carries_no_machine_local_path(self):
        raw = audit.DEFAULT_LEDGER.read_text(encoding="utf-8")
        offenders = sorted(set(audit.MACHINE_LOCAL_PATH.findall(raw)))
        self.assertEqual(offenders, [], f"{len(offenders)} machine-local paths in the ledger")

    def test_committed_ledger_verdicts_with_source_claims_cite_the_index_or_chgis(self):
        ledger = json.loads(audit.DEFAULT_LEDGER.read_text(encoding="utf-8"))
        for row in audit.validate_ledger(ledger):
            if row["verdict"] in audit.SOURCE_BACKED_VERDICTS:
                self.assertTrue(
                    any(r.startswith(("shiliao:", "chgis:")) for r in row["evidenceRefs"]),
                    row["componentKey"],
                )


if __name__ == "__main__":
    unittest.main()
