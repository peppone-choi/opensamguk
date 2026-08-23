#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

from junguozhi_contract import (
    EXPECTED_GROUP_COUNT,
    EXPECTED_UNIT_COUNT,
    CatalogContractError,
    build_catalog,
    render_catalog,
)

ROOT = Path(__file__).resolve().parents[2]
CORPUS = ROOT / "data" / "corpus"


def main() -> None:
    parser = argparse.ArgumentParser()
    output = parser.add_mutually_exclusive_group()
    output.add_argument("--out", type=Path, help="write canonical JSON to this path")
    output.add_argument("--check", type=Path, help="fail if this canonical JSON has drifted")
    args = parser.parse_args()

    try:
        catalog = build_catalog(CORPUS)
    except CatalogContractError as error:
        raise SystemExit(f"catalog audit failed: {error}") from error

    rendered = render_catalog(catalog)
    if args.out is not None:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(rendered, encoding="utf-8")
    if args.check is not None:
        if not args.check.exists():
            raise SystemExit(f"catalog drift: missing {args.check}")
        if args.check.read_text(encoding="utf-8") != rendered:
            raise SystemExit(f"catalog drift: regenerate {args.check}")

    print(
        f"PASS groups={catalog['detectedGroupCount']}/{EXPECTED_GROUP_COUNT} "
        f"units={catalog['detectedUnitCount']}/{EXPECTED_UNIT_COUNT} "
        f"types={catalog['unitTypeCounts']}"
    )
    for mismatch in catalog["declaredVsEnumeratedMismatches"]:
        print(
            "SOURCE_MISMATCH "
            f"volume={mismatch['sourceVolume']} group={mismatch['canonicalGroup']} "
            f"declared={mismatch['declaredCities']} "
            f"enumerated={mismatch['enumeratedUnits']}"
        )


if __name__ == "__main__":
    main()
