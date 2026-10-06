#!/usr/bin/env python3
"""Reviewed stable-ID lifecycle and classification decisions for CHGIS place rows."""

import json
from pathlib import Path


DEFAULT_LEDGER = (
    Path(__file__).resolve().parents[2]
    / "data" / "curated" / "han" / "han-place-stable-id-adjudications-v1.json"
)
EXPECTED_PHYSICAL_PLACE_IDS = {
    "82709", "85083", "82687", "83034",
    "210345", "210359", "87534", "210496", "210522", "210537",
    "210769", "210791", "33427", "87611", "33425", "210466",
}
EXPECTED_TRACKED_SOURCES = (
    ("administrative-units", "data/curated/han/administrative-units.json", "7c559d19ff0b7fc8ff43433c5305d87902166e069855d71cd957de5a6c929f64", "COMMITTED"),
    ("chgis-pref-dbf", "data/chgis-source/v6_time_pref_pts_utf_wgs84.dbf", "02419ecdf0fc848d852be984c4bd6f24e119e336a19c7f50743106f2a1dec3f1", "VIRTUAL_GITIGNORED_SOURCE"),
    ("chgis-cnty-dbf", "data/chgis-source/v6_time_cnty_pts_utf_wgs84.dbf", "e782572a2af83fa246d608ffb13729835d535f3c010eee79ce0545f5430eb616", "VIRTUAL_GITIGNORED_SOURCE"),
    ("hs-018", "data/corpus/hs-018.txt", "9a713507adf784312ba033929cffc05ffbf101f2e9496f85fb2f20506aaee005", "VIRTUAL_CORPUS_SNAPSHOT"),
    ("hs-011", "data/corpus/hs-011.txt", "ad4d5e760d3bb9759409398df1814677339403b3b31b37b48228e707a969c563", "VIRTUAL_CORPUS_SNAPSHOT"),
)
EXPECTED_EVIDENCE_REFS = {
    "82709": (("chgis-cnty-dbf", "SYS_ID=82709"), ("hs-018", "line=254"), ("hs-011", "lines=12,27")),
    "85083": (("chgis-cnty-dbf", "SYS_ID=85083"), ("administrative-units", 'member=[111,"東郡",14]')),
    "82687": (("chgis-cnty-dbf", "SYS_ID=82687"), ("administrative-units", 'member=[112,"南陽郡",29]')),
    "83034": (("chgis-cnty-dbf", "SYS_ID=83034"), ("administrative-units", 'member=[110,"汝南郡",29]')),
    "210345": (("chgis-pref-dbf", "SYS_ID=210345"), ("administrative-units", "canonicalGroup=常山國")),
    "210359": (("chgis-pref-dbf", "SYS_ID=210359"), ("administrative-units", "canonicalGroup=趙國")),
    "87534": (("chgis-pref-dbf", "SYS_ID=87534"), ("administrative-units", "canonicalGroup=中山國")),
    "210496": (("chgis-pref-dbf", "SYS_ID=210496"), ("administrative-units", "canonicalGroup=齊國")),
    "210522": (("chgis-pref-dbf", "SYS_ID=210522"), ("administrative-units", "canonicalGroup=北海國")),
    "210537": (("chgis-pref-dbf", "SYS_ID=210537"), ("administrative-units", "canonicalGroup=琅邪國")),
    "210769": (("chgis-pref-dbf", "SYS_ID=210769"), ("administrative-units", "canonicalGroup=梁國")),
    "210791": (("chgis-pref-dbf", "SYS_ID=210791"), ("administrative-units", "canonicalGroup=陳國")),
    "33427": (("chgis-pref-dbf", "SYS_ID=33427"), ("administrative-units", "canonicalGroup=下邳國")),
    "87611": (("chgis-pref-dbf", "SYS_ID=87611"), ("administrative-units", "sourceGroupName=河間")),
    "33425": (("chgis-pref-dbf", "SYS_ID=33425"), ("administrative-units", "canonicalGroup=彭城國")),
    "210466": (("chgis-pref-dbf", "SYS_ID=210466"), ("administrative-units", "canonicalGroup=樂安國")),
}


def _require_exact_keys(value, expected, where):
    if not isinstance(value, dict):
        raise ValueError(f"{where}: object required")
    actual = set(value)
    expected = set(expected)
    if actual != expected:
        raise ValueError(
            f"{where}: exact keys required; "
            f"missing={sorted(expected - actual)}, extra={sorted(actual - expected)}"
        )


def _require_int(value, where):
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{where}: integer required")


def validate_ledger(ledger):
    """Validate the complete reviewed ledger and reject schema drift fail-closed."""
    _require_exact_keys(
        ledger,
        {"schemaVersion", "ledgerId", "baselineYear", "trackedSources", "adjudications"},
        "ledger",
    )
    if ledger["schemaVersion"] != 1:
        raise ValueError("ledger.schemaVersion must be 1")
    if ledger["ledgerId"] != "han-place-stable-id-adjudications-v1":
        raise ValueError("ledger.ledgerId mismatch")
    if ledger["baselineYear"] != 220:
        raise ValueError("ledger.baselineYear must be 220")
    tracked_sources = ledger["trackedSources"]
    if not isinstance(tracked_sources, list):
        raise ValueError("ledger.trackedSources: array required")
    normalized_sources = []
    for index, source in enumerate(tracked_sources):
        where = f"ledger.trackedSources[{index}]"
        _require_exact_keys(source, {"sourceId", "path", "sha256", "availability"}, where)
        normalized_sources.append(
            (source["sourceId"], source["path"], source["sha256"], source["availability"])
        )
    if tuple(normalized_sources) != EXPECTED_TRACKED_SOURCES:
        raise ValueError("ledger.trackedSources: exact reviewed tracked source provenance required")
    if not isinstance(ledger["adjudications"], list):
        raise ValueError("ledger.adjudications: array required")

    entries = {}
    for index, entry in enumerate(ledger["adjudications"]):
        where = f"ledger.adjudications[{index}]"
        _require_exact_keys(
            entry,
            {
                "physicalPlaceId", "sourceLayer", "sourceIdentity", "classification",
                "reviewedActiveRange", "outsideRangeDisposition", "evidenceRefs",
            },
            where,
        )
        place_id = entry["physicalPlaceId"]
        if not isinstance(place_id, str) or not place_id.isdigit():
            raise ValueError(f"{where}.physicalPlaceId: decimal string required")
        if place_id in entries:
            raise ValueError(f"{where}.physicalPlaceId: duplicate {place_id}")
        if entry["sourceLayer"] not in {"pref", "cnty"}:
            raise ValueError(f"{where}.sourceLayer: pref or cnty required")

        identity = entry["sourceIdentity"]
        _require_exact_keys(
            identity, {"nameCh", "nameFt", "typeCh", "begYr", "endYr"},
            f"{where}.sourceIdentity",
        )
        for key in ("nameCh", "nameFt", "typeCh"):
            if not isinstance(identity[key], str) or not identity[key]:
                raise ValueError(f"{where}.sourceIdentity.{key}: nonempty string required")
        for key in ("begYr", "endYr"):
            _require_int(identity[key], f"{where}.sourceIdentity.{key}")
        if identity["begYr"] > identity["endYr"]:
            raise ValueError(f"{where}.sourceIdentity: inverted range")

        classification = entry["classification"]
        _require_exact_keys(
            classification, {"kind", "level", "outputNameCh", "outputNameFt"},
            f"{where}.classification",
        )
        expected_level = {"COUNTY": 5, "KINGDOM": 6}.get(classification["kind"])
        if expected_level is None or classification["level"] != expected_level:
            raise ValueError(f"{where}.classification: kind/level mismatch")
        for key in ("outputNameCh", "outputNameFt"):
            if not isinstance(classification[key], str) or not classification[key]:
                raise ValueError(f"{where}.classification.{key}: nonempty string required")

        active = entry["reviewedActiveRange"]
        if active is not None:
            _require_exact_keys(active, {"begYr", "endYr"}, f"{where}.reviewedActiveRange")
            for key in ("begYr", "endYr"):
                _require_int(active[key], f"{where}.reviewedActiveRange.{key}")
            if active["begYr"] > active["endYr"]:
                raise ValueError(f"{where}.reviewedActiveRange: inverted range")
            if not (
                identity["begYr"] <= active["begYr"]
                and active["endYr"] <= identity["endYr"]
            ):
                raise ValueError(
                    f"{where}.reviewedActiveRange must stay inside sourceIdentity range"
                )
        expected_disposition = "DROP_OUT_OF_PERIOD" if active else "USE_SOURCE_RANGE"
        if entry["outsideRangeDisposition"] != expected_disposition:
            raise ValueError(f"{where}.outsideRangeDisposition mismatch")
        refs = entry["evidenceRefs"]
        if not isinstance(refs, list) or not refs:
            raise ValueError(f"{where}.evidenceRefs: nonempty array required")
        normalized_refs = []
        for ref_index, ref in enumerate(refs):
            ref_where = f"{where}.evidenceRefs[{ref_index}]"
            _require_exact_keys(ref, {"sourceId", "locator"}, ref_where)
            normalized_refs.append((ref["sourceId"], ref["locator"]))
        if tuple(normalized_refs) != EXPECTED_EVIDENCE_REFS.get(place_id):
            raise ValueError(f"{where}.evidenceRefs: exact reviewed evidence required")
        entries[place_id] = entry

    if set(entries) != EXPECTED_PHYSICAL_PLACE_IDS:
        raise ValueError(
            "ledger.adjudications: exact reviewed physicalPlaceId set required; "
            f"missing={sorted(EXPECTED_PHYSICAL_PLACE_IDS - set(entries))}, "
            f"extra={sorted(set(entries) - EXPECTED_PHYSICAL_PLACE_IDS)}"
        )
    return entries


def load_adjudications(path=DEFAULT_LEDGER):
    with open(path, encoding="utf-8") as fh:
        return validate_ledger(json.load(fh))


def adjudicate_record(row, layer, year, entries):
    entry = entries.get(str(row["SYS_ID"]))
    if entry is None:
        return None
    identity = entry["sourceIdentity"]
    actual = (
        layer, row["NAME_CH"], row["NAME_FT"], row["TYPE_CH"],
        int(row["BEG_YR"]), int(row["END_YR"]),
    )
    expected = (
        entry["sourceLayer"],
        identity["nameCh"],
        identity["nameFt"],
        identity["typeCh"],
        identity.get("begYr", int(row["BEG_YR"])),
        identity.get("endYr", int(row["END_YR"])),
    )
    if actual != expected:
        raise ValueError(
            f"stable-ID identity mismatch for {row['SYS_ID']}: expected={expected!r}, actual={actual!r}"
        )
    active = entry.get("reviewedActiveRange")
    beg_yr = active["begYr"] if active else int(row["BEG_YR"])
    end_yr = active["endYr"] if active else int(row["END_YR"])
    classification = entry["classification"]
    return {
        "include": beg_yr <= year <= end_yr,
        "begYr": beg_yr,
        "endYr": end_yr,
        **classification,
    }
