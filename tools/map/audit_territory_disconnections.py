#!/usr/bin/env python3
"""Check that every disconnected commandery/county component has a reviewed adjudication.

The committed Han map has commanderies and counties whose cells form more than one
connected component. Some are historical exclaves or islands, some are grid defects,
some are counties filed under the wrong commandery. None of them may be "repaired"
by proximity or by painting a representative colour. This checker enforces that
the reviewed ledger ``data/curated/han/territory-disconnection-adjudications-v1.json``
covers each secondary component exactly once, that each verdict carries the kind
of evidence it needs, and that no ledger row has gone stale against the grid.

It is read-only with respect to ``han-tiles.json``.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter, defaultdict, deque
from pathlib import Path
from typing import Iterable, Mapping

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_TILES = ROOT / "data" / "map" / "han-tiles.json"
DEFAULT_LEDGER = (
    ROOT / "data" / "curated" / "han" / "territory-disconnection-adjudications-v1.json"
)

COMPONENT_KEY = re.compile(r"^[A-Za-z0-9_-]+@(0|[1-9][0-9]*):(0|[1-9][0-9]*)$")
r"""``unit@col:row`` — the unit plus its fragment's topmost-leftmost cell.

Pinned as a rule because the old positional form ``unit#rank`` is still what a
human reaches for, and a hand-written ``PARENT-0053#2`` would otherwise be accepted
as a plain string and then simply fail to match any component — reported as one
STALE_ROW, indistinguishable from a fragment that was legitimately repaired away.

Each coordinate is a canonical decimal: ``0``, or a nonzero digit followed by digits.
``\d+`` would also admit ``C1@05:02``, which reads as the same cell a human meant but is
not the string :func:`_anchor` emits, so it would clear this door and then land as that
very same ambiguous STALE_ROW. Leading zeros are refused here rather than downstream.
"""

LEDGER_ID = "han-territory-disconnection-adjudications-v1"
REFERENCE_YEAR = 220
VERDICTS = frozenset({
    "HISTORICAL_EXCLAVE",
    "PARENT_MISASSIGNMENT",
    "GEOMETRY_DEFECT",
    "WATER_SEPARATED",
    "EXTERNAL_POLITY",
    "UNKNOWN",
})
CONFIDENCES = frozenset({"HIGH", "MEDIUM", "LOW"})
IF_RULES = frozenset({
    "EXCLAVE_KEEP",
    "WATER_ROUTE_ONLY",
    "EXTERNAL_POLITY_POLICY",
    "DEFECT_PRESERVE_PENDING_GEOMETRY_PR",
    "MISASSIGNMENT_PENDING_PARENT_LEDGER",
    "UNKNOWN_PRESERVE",
})
# Each verdict may only carry the IF rule that describes how the game treats it.
VERDICT_IF_RULES = {
    "HISTORICAL_EXCLAVE": {"EXCLAVE_KEEP"},
    "WATER_SEPARATED": {"WATER_ROUTE_ONLY"},
    "EXTERNAL_POLITY": {"EXTERNAL_POLITY_POLICY", "WATER_ROUTE_ONLY"},
    "GEOMETRY_DEFECT": {"DEFECT_PRESERVE_PENDING_GEOMETRY_PR"},
    "PARENT_MISASSIGNMENT": {"MISASSIGNMENT_PENDING_PARENT_LEDGER"},
    "UNKNOWN": {"UNKNOWN_PRESERVE"},
}
SOURCE_EVIDENCE_PREFIXES = ("shiliao:", "chgis:", "http://", "https://")
# Verdicts that describe this grid rather than the record, so they must cite the grid.
GRID_BACKED_VERDICTS = frozenset({"GEOMETRY_DEFECT", "WATER_SEPARATED"})
ALL_EVIDENCE_PREFIXES = SOURCE_EVIDENCE_PREFIXES + ("map:", "repo:", "searched:")


def _cited(ref: str, prefixes: tuple[str, ...]) -> bool:
    """A citation is the prefix AND something behind it. `startswith` alone accepted a
    bare "shiliao:" as a source, which can never be looked up again."""
    return any(ref.startswith(p) and ref[len(p):].strip() for p in prefixes)


# The review record is what the plan leans on when it says every verdict was attacked
# before it shipped. Left unvalidated it accepted any JSON, so a row could claim it had
# been upheld with no lens having run.
REVIEW_STATES = frozenset({
    "UPHELD", "INHERITED", "TIEBREAK_RESOLVED", "CONTESTED_NO_CONSENSUS",
    "CORRECTED_BY_2_REFUTERS", "CORRECTED_BY_3_REFUTERS", "UNVERIFIED", "UNDER_VERIFIED",
})
VOTE_KEYS = frozenset({"lens", "refuted", "reason"})

# 郡 / 國 / 尹 / 屬國 are the shapes a commandery name takes in the map.
RE_COMMANDERY_NAME = re.compile(r"[\u4e00-\u9fff]{1,3}(?:屬國|[郡國尹])")

# A path rooted in whoever's checkout produced the row cannot be followed by the next
# reader, and it leaks the author's directory layout into committed data. Rows cite the
# repository, so paths are repo-relative. Session scratchpads under /tmp, /private/tmp
# and /var/folders are worse than a home directory: they are deleted, so the "재현
# 스크립트" they name is unreachable even on the machine that wrote it. `projects/…` is
# a path into the surrounding meta-repo and does not resolve from a checkout of this one.
MACHINE_LOCAL_PATH = re.compile(
    r"(?:/Users/|/home/|/root/|/private/tmp/|/tmp/|/var/folders/|[A-Za-z]:\\)[^\s\"',)）]*"
    r"|(?<![\w/])projects/opensamguk/[^\s\"',)）]*")

# A PARENT_MISASSIGNMENT row exists to name the parent a later PR must move the piece
# to. A stub that merely occupies the field asserts nothing, so it is not an answer —
# an unresolved parent must keep the gate red rather than ship as if adjudicated.
PLACEHOLDER_PARENTS = frozenset({
    "?", "??", "???", "-", "--", "n/a", "na", "tbd", "todo", "fixme",
    "unknown", "미상", "미정", "확인필요", "未詳", "未定",
})
MAP_EVIDENCE_PREFIX = "map:"
# Verdicts that assert something about history must cite a source, not the grid.
SOURCE_BACKED_VERDICTS = frozenset({
    "HISTORICAL_EXCLAVE", "PARENT_MISASSIGNMENT", "EXTERNAL_POLITY",
})
WATER_TERRAIN = frozenset({"SEA", "LAKE", "RIVER"})
# A water-separated piece may also touch the off-map edge (the Japanese islands do),
# but it must touch real water somewhere: an all-OUT_OF_SCOPE boundary is not water.
WATER_BOUNDARY_ALLOWED = WATER_TERRAIN | {"OUT_OF_SCOPE"}
ROW_KEYS = frozenset({
    "unitKind", "unitId", "unitNameCh", "componentKey", "cellCount", "memberIds",
    "holdsSeat", "verdict", "confidence", "effectiveFrom", "effectiveTo", "ifRule",
    "evidenceRefs", "rationale", "memberNamesCh", "review",
})
# `memberNamesCh` and `review` were optional here once. Both were load-bearing, and being
# optional made the checks that read them skippable: a row that omitted `memberNamesCh`
# lost the county-name drift comparison, and one that omitted `review` lost every vote and
# tally rule at once. A row nobody reviewed is not adjudicated, so both are required.
OPTIONAL_ROW_KEYS = frozenset({
    "proposedParent", "defectNote", "searched",
    "fragmentLedgerRef", "followUp", "overruledArgument",
})

# An overturned row holds two arguments: the one the refuters broke and the one that
# replaced it. `rationale` is the row's current position, so the withdrawn one lives
# here instead of being glued on after a delimiter.
REFUTATION_DELIMITER = "[반박]"


# --------------------------------------------------------------------------- grid


def _expand(runs: object, size: int, label: str) -> list[int]:
    if not isinstance(runs, list):
        raise ValueError(f"{label} must be RLE runs")
    values: list[int] = []
    for index, run in enumerate(runs):
        if (
            not isinstance(run, list) or len(run) != 2
            or type(run[0]) is not int or type(run[1]) is not int or run[1] <= 0
        ):
            raise ValueError(f"{label}[{index}] must be [integer, positive count]")
        values.extend([run[0]] * run[1])
    if len(values) != size:
        raise ValueError(f"{label} length does not match rows * cols")
    return values


def _components(grid: list[int], cols: int, rows: int) -> dict[int, list[list[int]]]:
    """4-connected components per non-negative grid value, largest first."""
    seen = [False] * len(grid)
    result: dict[int, list[list[int]]] = defaultdict(list)
    for start in range(len(grid)):
        if seen[start] or grid[start] < 0:
            continue
        value = grid[start]
        queue = deque([start])
        seen[start] = True
        cells: list[int] = []
        while queue:
            i = queue.popleft()
            cells.append(i)
            y, x = divmod(i, cols)
            for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
                if 0 <= ny < rows and 0 <= nx < cols:
                    j = ny * cols + nx
                    if not seen[j] and grid[j] == value:
                        seen[j] = True
                        queue.append(j)
        result[value].append(cells)
    for value in result:
        result[value].sort(key=lambda cells: (-len(cells), min(cells)))
    return result


def _boundary(cells: list[int], grid: list[int], terrain: str, legend: Mapping[str, str],
              cols: int, rows: int) -> tuple[Counter, Counter]:
    """(neighbouring grid values by shared edges, negative-cell terrain names)."""
    own = set(cells)
    land: Counter = Counter()
    negative: Counter = Counter()
    for i in cells:
        y, x = divmod(i, cols)
        for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
            if not (0 <= ny < rows and 0 <= nx < cols):
                continue
            j = ny * cols + nx
            if j in own:
                continue
            if grid[j] < 0:
                negative[legend.get(terrain[j], terrain[j])] += 1
            else:
                land[grid[j]] += 1
    return land, negative


def _anchor(cells: list[int], cols: int) -> str:
    """The component's topmost-leftmost cell, as ``col:row``.

    This is the component's identity. It is a function of that component's own cells and
    nothing else, which is the whole point: the previous key was ``f"{unit}#{rank}"`` with
    rank an index into the unit's components sorted by size, so repairing one fragment
    renumbered every smaller sibling onto its neighbour's key. The gate still reported
    drift, but the obvious repair — make the row match the grid — moved a fragment's
    sourced rationale onto a different piece of land, silently.

    Uniqueness is by construction: components of one unit are disjoint, so no two of them
    share a minimum cell. The anchor moves only when the fragment's own top-left corner
    changes, and CELL_DRIFT / MEMBER_DRIFT / NAME_DRIFT already speak for content changes.
    """
    lowest = min(cells)
    return f"{lowest % cols}:{lowest // cols}"


def inventory(document: Mapping) -> list[dict]:
    """Every secondary component of every disconnected commandery and county."""
    meta = document["_meta"]
    cols, rows = meta["cols"], meta["rows"]
    legend = {str(k): str(v) for k, v in meta["terrainLegend"].items()}
    terrain = "".join(document["terrain"])
    if len(terrain) != cols * rows:
        raise ValueError("terrain length does not match rows * cols")
    owner = _expand(document["owner"], cols * rows, "owner")
    parent = _expand(document["parentOwner"], cols * rows, "parentOwner")
    provinces = document["provinceRecords"]
    jurisdictions = {row["id"]: row for row in document["jurisdictionRecords"]}
    commanderies = {row["id"]: row for row in document["commanderyRecords"]}
    parent_ids = [row["id"] for row in document["parentRegions"]]
    province_jurisdiction = [row["jurisdictionId"] for row in provinces]
    jurisdiction_order = sorted(jurisdictions)
    jurisdiction_index = {jid: i for i, jid in enumerate(jurisdiction_order)}
    jgrid = [jurisdiction_index[province_jurisdiction[o]] if o >= 0 else -1 for o in owner]

    rows_out: list[dict] = []
    for value, comps in _components(parent, cols, rows).items():
        if len(comps) < 2:
            continue
        cid = parent_ids[value]
        seat = commanderies[cid].get("seatJurisdictionId")
        member_sets = [sorted({province_jurisdiction[owner[i]] for i in cells}) for cells in comps]
        # The body is the piece holding the seat; a seat-less commandery's body is its
        # largest piece. Every other piece, including a seat-less largest piece, is judged.
        body_rank = next((rank for rank, m in enumerate(member_sets) if seat in m), 0)
        for rank, cells in enumerate(comps):
            if rank == body_rank:
                continue
            members = member_sets[rank]
            holds_seat = seat in members
            land, negative = _boundary(cells, parent, terrain, legend, cols, rows)
            rows_out.append({
                "unitKind": "COMMANDERY", "unitId": cid,
                "unitNameCh": commanderies[cid].get("nameCh"),
                "componentKey": f"{cid}@{_anchor(cells, cols)}", "cellCount": len(cells),
                "memberIds": members,
                "memberNamesCh": [jurisdictions[j].get("nameCh") for j in members],
                "holdsSeat": holds_seat,
                "landNeighbourIds": sorted(parent_ids[k] for k in land),
                "negativeBoundary": dict(sorted(negative.items())),
            })
    for value, comps in _components(jgrid, cols, rows).items():
        if len(comps) < 2:
            continue
        jid = jurisdiction_order[value]
        for rank, cells in enumerate(comps[1:], start=1):
            land, negative = _boundary(cells, jgrid, terrain, legend, cols, rows)
            rows_out.append({
                "unitKind": "JURISDICTION", "unitId": jid,
                "unitNameCh": jurisdictions[jid].get("nameCh"),
                "componentKey": f"{jid}@{_anchor(cells, cols)}", "cellCount": len(cells),
                "memberIds": sorted({provinces[owner[i]]["id"] for i in cells}),
                "memberNamesCh": [
                    rec.get("nameCh") for rec in sorted(
                        {provinces[owner[i]]["id"]: provinces[owner[i]] for i in cells}.values(),
                        key=lambda rec: rec["id"],
                    )
                ],
                "holdsSeat": False,
                "landNeighbourIds": sorted(jurisdiction_order[k] for k in land),
                "negativeBoundary": dict(sorted(negative.items())),
            })
    rows_out.sort(key=lambda r: (r["unitKind"], r["unitId"], r["componentKey"]))
    return rows_out


# ------------------------------------------------------------------------- ledger


def _row_text(row: object, path: str = "") -> Iterable[tuple[str, str]]:
    """Every string anywhere in a row, flattened as (field path, text) pairs.

    Naming the five fields that happened to hold prose left `followUp`, `proposedParent`
    and anything added later unscanned, so the CI gate would have passed a machine-local
    path that the separate raw-file test caught.
    """
    if isinstance(row, str):
        yield path or "(row)", row
    elif isinstance(row, Mapping):
        for key, value in row.items():
            yield from _row_text(value, f"{path}.{key}" if path else str(key))
    elif isinstance(row, list):
        for i, value in enumerate(row):
            yield from _row_text(value, f"{path}[{i}]")


def validate_ledger(document: object) -> list[dict]:
    if not isinstance(document, Mapping):
        raise ValueError("ledger must be an object")
    if document.get("schemaVersion") != 1:
        raise ValueError("ledger schemaVersion must be 1")
    if document.get("ledgerId") != LEDGER_ID:
        raise ValueError(f"ledger ledgerId must be {LEDGER_ID}")
    if document.get("referenceYear") != REFERENCE_YEAR:
        raise ValueError(f"ledger referenceYear must be {REFERENCE_YEAR}")
    policy = document.get("policy")
    if not isinstance(policy, Mapping) or not all(
        policy.get(flag) is True
        for flag in ("noAutomaticRepair", "noProximityReparenting", "noRepresentativeColorFill")
    ):
        raise ValueError("ledger policy must forbid automatic repair, proximity reparenting and colour fill")
    if_rules = document.get("ifRules")
    if not isinstance(if_rules, Mapping) or set(if_rules) != IF_RULES or not all(
        isinstance(v, str) and v for v in if_rules.values()
    ):
        raise ValueError("ledger ifRules must describe exactly the known IF rules")
    rows = document.get("adjudications")
    if not isinstance(rows, list):
        raise ValueError("ledger adjudications must be an array")
    review_states = document.get("reviewStates")
    if not isinstance(review_states, Mapping) or set(review_states) != REVIEW_STATES or not all(
        isinstance(v, str) and v for v in review_states.values()
    ):
        raise ValueError("ledger reviewStates must describe exactly the known review states")
    seen: set[str] = set()
    for index, row in enumerate(rows):
        label = f"adjudications[{index}]"
        if not isinstance(row, Mapping):
            raise ValueError(f"{label} must be an object")
        keys = set(row)
        if not ROW_KEYS <= keys or not keys <= ROW_KEYS | OPTIONAL_ROW_KEYS:
            raise ValueError(f"{label} has invalid keys: {sorted(keys ^ ROW_KEYS)}")
        if row["unitKind"] not in {"COMMANDERY", "JURISDICTION"}:
            raise ValueError(f"{label} has invalid unitKind")
        for key in ("unitId", "unitNameCh", "componentKey", "rationale"):
            if not isinstance(row[key], str) or not row[key]:
                raise ValueError(f"{label}.{key} must be a non-empty string")
        if not COMPONENT_KEY.match(row["componentKey"]):
            raise ValueError(
                f"{label}.componentKey {row['componentKey']!r} is not unit@col:row"
            )
        for field, text in _row_text(row):
            found = MACHINE_LOCAL_PATH.search(text)
            if found:
                raise ValueError(
                    f"{label}.{field} carries a machine-local path {found.group(0)!r}; "
                    "cite the repository-relative path instead"
                )
        if type(row["cellCount"]) is not int or row["cellCount"] <= 0:
            raise ValueError(f"{label}.cellCount must be a positive integer")
        if not isinstance(row["memberIds"], list) or not row["memberIds"] or not all(
            isinstance(m, str) and m for m in row["memberIds"]
        ) or row["memberIds"] != sorted(set(row["memberIds"])):
            raise ValueError(f"{label}.memberIds must be a sorted, unique, non-empty string array")
        if not isinstance(row["memberNamesCh"], list) or not all(
            isinstance(n, str) and n for n in row["memberNamesCh"]
        ) or len(row["memberNamesCh"]) != len(row["memberIds"]):
            raise ValueError(
                f"{label}.memberNamesCh must be a string array naming each of the "
                f"{len(row['memberIds'])} memberIds"
            )
        if not isinstance(row["holdsSeat"], bool):
            raise ValueError(f"{label}.holdsSeat must be a boolean")
        if row["verdict"] not in VERDICTS:
            raise ValueError(f"{label}.verdict is unknown: {row['verdict']}")
        if row["confidence"] not in CONFIDENCES:
            raise ValueError(f"{label}.confidence is unknown")
        for key in ("effectiveFrom", "effectiveTo"):
            if row[key] is not None and type(row[key]) is not int:
                raise ValueError(f"{label}.{key} must be an integer year or null")
        if (
            row["effectiveFrom"] is not None
            and row["effectiveTo"] is not None
            and row["effectiveFrom"] > row["effectiveTo"]
        ):
            raise ValueError(
                f"{label} effective window {row['effectiveFrom']}..{row['effectiveTo']} is inverted"
            )
        if row["ifRule"] not in VERDICT_IF_RULES[row["verdict"]]:
            raise ValueError(f"{label}.ifRule {row['ifRule']} does not fit verdict {row['verdict']}")
        refs = row["evidenceRefs"]
        if not isinstance(refs, list) or not refs or not all(isinstance(r, str) and r for r in refs):
            raise ValueError(f"{label}.evidenceRefs must be a non-empty string array")
        for i, ref in enumerate(refs):
            prefix = next((p for p in ALL_EVIDENCE_PREFIXES if ref.startswith(p)), None)
            if prefix is not None and not ref[len(prefix):].strip():
                raise ValueError(
                    f"{label}.evidenceRefs[{i}] is the bare prefix {prefix!r} with no citation behind it"
                )
        if row["verdict"] in SOURCE_BACKED_VERDICTS and not any(
            _cited(r, SOURCE_EVIDENCE_PREFIXES) for r in refs
        ):
            raise ValueError(f"{label} ({row['verdict']}) needs a source-backed evidence ref")
        # A verdict about this grid has to point at this grid. Citing only 史料 leaves the
        # geometry PR downstream with no observation to reshape from.
        if row["verdict"] in GRID_BACKED_VERDICTS and not any(
            _cited(r, (MAP_EVIDENCE_PREFIX,)) for r in refs
        ):
            raise ValueError(
                f"{label} ({row['verdict']}) needs a {MAP_EVIDENCE_PREFIX} evidence ref "
                "recording what the grid actually shows"
            )
        if row["verdict"] == "PARENT_MISASSIGNMENT":
            proposed = row.get("proposedParent")
            if not isinstance(proposed, Mapping) or not isinstance(proposed.get("nameCh"), str) or not proposed["nameCh"]:
                raise ValueError(f"{label} (PARENT_MISASSIGNMENT) needs proposedParent.nameCh")
            name = proposed["nameCh"].strip()
            if not name or name.casefold() in PLACEHOLDER_PARENTS:
                raise ValueError(
                    f"{label}.proposedParent.nameCh {proposed['nameCh']!r} is a placeholder, "
                    "not an adjudicated parent"
                )
            parent_id = proposed.get("commanderyId")
            if parent_id is not None and not (isinstance(parent_id, str) and parent_id):
                raise ValueError(f"{label}.proposedParent.commanderyId must be a non-empty string or absent")
        if row["verdict"] == "GEOMETRY_DEFECT" and not (
            isinstance(row.get("defectNote"), str) and row["defectNote"]
        ):
            raise ValueError(f"{label} (GEOMETRY_DEFECT) needs a defectNote")
        if row["verdict"] == "UNKNOWN" and not (
            isinstance(row.get("searched"), list) and row["searched"]
        ):
            raise ValueError(f"{label} (UNKNOWN) must record what was searched")
        review = row["review"]
        if not isinstance(review, Mapping):
            raise ValueError(f"{label}.review must be an object")
        if review.get("state") not in REVIEW_STATES:
            raise ValueError(f"{label}.review.state is unknown: {review.get('state')!r}")
        votes = review.get("votes")
        if not isinstance(votes, list) or not votes:
            raise ValueError(f"{label}.review.votes must be a non-empty array")
        for i, vote in enumerate(votes):
            if not isinstance(vote, Mapping) or not VOTE_KEYS <= set(vote):
                raise ValueError(
                    f"{label}.review.votes[{i}] needs {sorted(VOTE_KEYS)}"
                )
            if not isinstance(vote["refuted"], bool):
                raise ValueError(f"{label}.review.votes[{i}].refuted must be a boolean")
            for key in ("lens", "reason"):
                if not isinstance(vote[key], str) or not vote[key].strip():
                    raise ValueError(f"{label}.review.votes[{i}].{key} must be a non-empty string")
        # The state is a claim about the votes printed right beside it. Left uncompared,
        # the ledger carried two TIEBREAK_RESOLVED rows every lens had upheld, a
        # CORRECTED_BY_2_REFUTERS with three refuters, and an INHERITED row that had in
        # fact been judged on its own votes and overturned.
        refuted = sum(1 for vote in votes if vote["refuted"])
        state, tally = review["state"], f"{refuted} of {len(votes)} refuted"
        if state == "CORRECTED_BY_2_REFUTERS" and refuted != 2:
            raise ValueError(f"{label}.review.state says 2 refuters but {tally}")
        if state == "CORRECTED_BY_3_REFUTERS" and refuted != 3:
            raise ValueError(f"{label}.review.state says 3 refuters but {tally}")
        if state in ("UPHELD", "INHERITED") and refuted * 2 > len(votes):
            raise ValueError(f"{label}.review.state is {state} but {tally}")
        if state == "TIEBREAK_RESOLVED" and not refuted:
            raise ValueError(
                f"{label}.review.state is {state} but {tally} — no split to break"
            )
        overruled = row.get("overruledArgument")
        corrected = str(review.get("state", "")).startswith("CORRECTED_BY_")
        if corrected and not (isinstance(overruled, str) and overruled.strip()):
            # Without this the losing argument stays in `rationale`, where it reads as the
            # row's position. On PARENT-0169#1 that made the row say 「사료 근거는 없으므로
            # PARENT_MISASSIGNMENT 로 보지 않는다」 above a PARENT_MISASSIGNMENT verdict, and
            # two independent citation readers graded it against the argument it had dropped.
            raise ValueError(
                f"{label}.review.state is {review['state']} but the row carries no "
                f"overruledArgument holding the argument that was overturned"
            )
        if overruled is not None and not (isinstance(overruled, str) and overruled.strip()):
            raise ValueError(f"{label}.overruledArgument must be a non-empty string")
        if REFUTATION_DELIMITER in row["rationale"]:
            raise ValueError(
                f"{label}.rationale still contains the refutation delimiter "
                f"{REFUTATION_DELIMITER!r}; the overturned argument belongs in overruledArgument"
            )
        if row["componentKey"] in seen:
            raise ValueError(f"{label} repeats componentKey {row['componentKey']}")
        seen.add(row["componentKey"])
    return list(rows)


# -------------------------------------------------------------------------- check


def check(document: Mapping, ledger: Mapping) -> dict:
    rows = validate_ledger(ledger)
    components = inventory(document)
    by_key = {c["componentKey"]: c for c in components}
    errors: list[str] = []
    covered: set[str] = set()
    for row in rows:
        key = row["componentKey"]
        comp = by_key.get(key)
        if comp is None:
            errors.append(f"STALE_ROW {key}: no such disconnected component in the grid")
            continue
        covered.add(key)
        if comp["unitKind"] != row["unitKind"] or comp["unitId"] != row["unitId"]:
            errors.append(f"UNIT_MISMATCH {key}")
        if comp["memberIds"] != row["memberIds"]:
            errors.append(f"MEMBER_DRIFT {key}: grid {comp['memberIds']} != ledger {row['memberIds']}")
        if comp["cellCount"] != row["cellCount"]:
            errors.append(f"CELL_DRIFT {key}: grid {comp['cellCount']} != ledger {row['cellCount']}")
        if comp["holdsSeat"] != row["holdsSeat"]:
            errors.append(f"SEAT_DRIFT {key}")
        # The names are what a human reads; leaving them uncompared let a row label the
        # right component with another one's commandery and counties.
        # Both fields are required row keys, so read them directly: guarding this with
        # `field in row` let a row drop memberNamesCh and skip its own county-name check.
        for field in ("unitNameCh", "memberNamesCh"):
            if comp[field] != row[field]:
                errors.append(
                    f"NAME_DRIFT {key}.{field}: grid {comp[field]!r} != ledger {row[field]!r}"
                )
        # Keyed on the IF rule, not the verdict: any row telling the game "this piece
        # is reachable only by water" is recomputed against the grid, including an
        # EXTERNAL_POLITY island that carries WATER_ROUTE_ONLY.
        if row["ifRule"] == "WATER_ROUTE_ONLY" and comp["landNeighbourIds"]:
            errors.append(f"NOT_WATER_SEPARATED {key}: land neighbours {comp['landNeighbourIds']}")
        boundary = set(comp["negativeBoundary"])
        if row["ifRule"] == "WATER_ROUTE_ONLY" and not (
            boundary & WATER_TERRAIN and boundary <= WATER_BOUNDARY_ALLOWED
        ):
            errors.append(f"NOT_WATER_BOUNDED {key}: negative boundary {comp['negativeBoundary']}")
    known_commanderies = {
        c["id"]: c.get("nameCh") for c in document.get("commanderyRecords", [])
    }
    known_names = {name for name in known_commanderies.values() if name}
    for row in rows:
        proposed = row.get("proposedParent")
        if row["verdict"] != "PARENT_MISASSIGNMENT" or not isinstance(proposed, Mapping):
            continue
        parent_id = proposed.get("commanderyId")
        if parent_id is None:
            # A piece split across several 220 CE commanderies has no single parent id,
            # so the id is optional. Left as a bare `continue`, that made the id the ONLY
            # thing tying a proposed parent to the map: omit it and the row could name
            # anything. Every 郡/國 the name mentions must be one the map actually has.
            named = RE_COMMANDERY_NAME.findall(proposed["nameCh"])
            if not named:
                errors.append(
                    f"PARENT_NAMES_NO_COMMANDERY {row['componentKey']}: "
                    f"{proposed['nameCh']!r} names no 郡/國 and carries no commanderyId"
                )
                continue
            unknown = [n for n in named if n not in known_names]
            if unknown:
                errors.append(
                    f"UNKNOWN_PROPOSED_PARENT {row['componentKey']}: "
                    f"{', '.join(unknown)} not in commanderyRecords"
                )
            continue
        if parent_id not in known_commanderies:
            errors.append(
                f"UNKNOWN_PROPOSED_PARENT {row['componentKey']}: "
                f"commanderyId {parent_id} is not in commanderyRecords"
            )
        elif known_commanderies[parent_id] != proposed["nameCh"]:
            errors.append(
                f"PROPOSED_PARENT_NAME_MISMATCH {row['componentKey']}: "
                f"{parent_id} is {known_commanderies[parent_id]!r}, ledger says {proposed['nameCh']!r}"
            )
    for key, comp in by_key.items():
        if key not in covered:
            errors.append(
                f"UNADJUDICATED {key} ({comp['unitKind']} {comp['unitNameCh']} "
                f"{comp['cellCount']} cells, members {comp['memberNamesCh'] or comp['memberIds']})"
            )
    verdicts = Counter(row["verdict"] for row in rows if row["componentKey"] in by_key)
    return {
        "componentCount": len(components),
        "adjudicatedCount": len(covered),
        "verdictCounts": dict(sorted(verdicts.items())),
        "errors": sorted(errors),
    }


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def parse_args(argv: Iterable[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--tiles", type=Path, default=DEFAULT_TILES)
    parser.add_argument("--ledger", type=Path, default=DEFAULT_LEDGER)
    parser.add_argument("--check", action="store_true", help="exit 1 on any coverage or drift error")
    parser.add_argument("--inventory", action="store_true", help="print the component inventory as JSON")
    return parser.parse_args(list(argv) if argv is not None else None)


def _display(path: Path) -> str:
    """Repo-relative when it is inside the repo, absolute otherwise. `relative_to(ROOT)`
    alone raised ValueError for a --ledger outside the checkout, so the "ledger missing"
    branch crashed instead of returning 1 — the exact path a red probe uses."""
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def main(argv: Iterable[str] | None = None) -> int:
    args = parse_args(argv)
    document = _load(args.tiles)
    if args.inventory:
        print(json.dumps(inventory(document), ensure_ascii=False, indent=1))
        return 0
    if not args.ledger.exists():
        print(f"ledger missing: {_display(args.ledger)}")
        return 1
    try:
        result = check(document, _load(args.ledger))
    except ValueError as exc:
        print(f"ledger invalid: {exc}")
        return 1
    print(
        f"disconnected components {result['componentCount']} | adjudicated {result['adjudicatedCount']} "
        f"| verdicts {result['verdictCounts']}"
    )
    for error in result["errors"]:
        print(f"  {error}")
    if args.check and result["errors"]:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
