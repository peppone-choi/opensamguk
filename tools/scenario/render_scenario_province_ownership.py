#!/usr/bin/env python3
"""Render exact scenario province ownership for visual evidence review."""

from __future__ import annotations

import argparse
import html
import json
from pathlib import Path
import sys
from typing import Any, Mapping, Sequence

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.scenario.province_ownership_materializer import ProvinceAssignment


OUTSIDE_RGB = (0, 0, 0)
UNOWNED_RGB = (48, 52, 50)
BORDER_RGB = (20, 22, 21)


def nation_fill_rgb(value: str) -> tuple[int, int, int]:
    source = tuple(int(value[index:index + 2], 16) for index in (1, 3, 5))
    neutral = (78, 76, 68)
    return tuple(round(channel * 0.72 + base * 0.28) for channel, base in zip(source, neutral, strict=True))


def _owner_grid(map_doc: Mapping[str, Any]) -> list[int]:
    result: list[int] = []
    for province_index, count in map_doc["owner"]:
        result.extend([province_index] * count)
    expected = int(map_doc["_meta"]["cols"]) * int(map_doc["_meta"]["rows"])
    if len(result) != expected:
        raise ValueError(f"owner RLE covers {len(result)} cells, expected {expected}")
    return result


def render_map(
    map_doc: Mapping[str, Any],
    assignments: Sequence[ProvinceAssignment],
    nation_colors: Mapping[int, str],
    *,
    scale: int = 3,
    draw_borders: bool = True,
) -> Image.Image:
    if scale < 1:
        raise ValueError("scale must be positive")
    cols = int(map_doc["_meta"]["cols"])
    rows = int(map_doc["_meta"]["rows"])
    grid = _owner_grid(map_doc)
    records = map_doc["provinceRecords"]
    by_id = {row.province_id: row for row in assignments}
    if set(by_id) != {row["id"] for row in records}:
        raise ValueError("assignment IDs must exactly match the province catalog")
    fills = [
        UNOWNED_RGB if by_id[record["id"]].owner_nation_id is None
        else nation_fill_rgb(nation_colors[by_id[record["id"]].owner_nation_id])
        for record in records
    ]
    image = Image.new("RGB", (cols * scale, rows * scale), OUTSIDE_RGB)
    draw = ImageDraw.Draw(image)
    for position, province_index in enumerate(grid):
        if province_index < 0:
            continue
        row, col = divmod(position, cols)
        left, top = col * scale, row * scale
        draw.rectangle((left, top, left + scale - 1, top + scale - 1), fill=fills[province_index])

    if draw_borders and scale >= 3:
        for position, province_index in enumerate(grid):
            if province_index < 0:
                continue
            row, col = divmod(position, cols)
            left, top = col * scale, row * scale
            neighbours = (
                (-1 if row == 0 else grid[position - cols], "top"),
                (-1 if row == rows - 1 else grid[position + cols], "bottom"),
                (-1 if col == 0 else grid[position - 1], "left"),
                (-1 if col == cols - 1 else grid[position + 1], "right"),
            )
            for neighbour, side in neighbours:
                if neighbour == province_index:
                    continue
                if side == "top":
                    draw.line((left, top, left + scale - 1, top), fill=BORDER_RGB)
                elif side == "bottom":
                    draw.line((left, top + scale - 1, left + scale - 1, top + scale - 1), fill=BORDER_RGB)
                elif side == "left":
                    draw.line((left, top, left, top + scale - 1), fill=BORDER_RGB)
                else:
                    draw.line((left + scale - 1, top, left + scale - 1, top + scale - 1), fill=BORDER_RGB)
    return image


def _assignment(row: Mapping[str, Any]) -> ProvinceAssignment:
    return ProvinceAssignment(
        scenario_code=row["scenarioCode"],
        province_id=row["provinceId"],
        owner_nation_id=row["ownerNationId"],
        owner_nation_key=row["ownerNationKey"],
        controller_city_id=row["controllerCityId"],
        winning_claim_id=row["winningClaimId"],
        claim_trace=tuple(row["claimTrace"]),
        basis_type=row["basisType"],
        evidence_ids=tuple(row["evidenceIds"]),
        confidence=row["confidence"],
        rationale=row["rationale"],
    )


def render_gallery(output_dir: Path, *, scale: int = 3) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    map_doc = json.loads((ROOT / "data/map/han-tiles.json").read_text(encoding="utf-8"))
    ownership = json.loads(
        (ROOT / "data/map/han-scenario-province-ownership-v1.json").read_text(encoding="utf-8")
    )
    cards: list[str] = []
    thumbnails: list[tuple[int, Image.Image]] = []
    for scenario in ownership["scenarios"]:
        code = scenario["scenarioCode"]
        source = json.loads(
            (ROOT / f"data/extracted/scenario/scenario_{code}.json").read_text(encoding="utf-8")
        )
        colors = {row["id"]: row["color"] for row in source["nations"]}
        rows = tuple(_assignment(row) for row in scenario["assignments"])
        image = render_map(map_doc, rows, colors, scale=scale)
        filename = f"scenario-{code}.png"
        image.save(output_dir / filename, optimize=True)
        evidence_filename = f"scenario-{code}-evidence.json"
        (output_dir / evidence_filename).write_text(
            json.dumps(scenario, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        thumbnails.append((code, image.resize((384, 335), Image.Resampling.NEAREST)))
        cards.append(
            f'<article><h2>{code} · {html.escape(source["title"])}</h2>'
            f'<p>{source["startYear"]}년 · owned '
            f'{sum(row.owner_nation_id is not None for row in rows):,} / {len(rows):,}</p>'
            f'<a href="{filename}"><img src="{filename}" alt="scenario {code} political map"></a>'
            f'<p><a href="{evidence_filename}">province evidence JSON</a></p></article>'
        )

    overview = Image.new("RGB", (384 * 3, 335 * 5), (24, 25, 24))
    for index, (_, thumbnail) in enumerate(thumbnails):
        overview.paste(thumbnail, ((index % 3) * 384, (index // 3) * 335))
    overview.save(output_dir / "overview.png", optimize=True)
    (output_dir / "index.html").write_text(
        "<!doctype html><meta charset=\"utf-8\"><title>OpenSamguk scenario ownership review</title>"
        "<style>body{background:#171817;color:#eee;font:15px system-ui;margin:24px}"
        "main{display:grid;grid-template-columns:repeat(auto-fit,minmax(360px,1fr));gap:20px}"
        "article{background:#242624;padding:14px;border:1px solid #555}img{width:100%;image-rendering:pixelated}"
        "a{color:#e8ca78}h1,h2{margin:.2em 0}</style>"
        "<h1>OpenSamguk · exact province ownership review</h1>"
        "<p>Black is outside the playable mask; charcoal is explicit unowned territory. No colour interpolation.</p>"
        f"<main>{''.join(cards)}</main>",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--scale", type=int, default=3)
    args = parser.parse_args()
    render_gallery(args.output_dir.resolve(), scale=args.scale)
    print(f"wrote {args.output_dir.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
