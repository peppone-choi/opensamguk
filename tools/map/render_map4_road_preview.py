"""Render the map4 overview with antialiased coast and curved display roads.

The road and county topology remains the exact raster. Curves are a zoomed-out
display treatment, not additional passable cells or a historical trace.
"""

import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "reports/opensamguk/tasks/assets/map4-road-network.png"
PALETTE = {
    48: (210, 224, 227), 49: (223, 217, 188), 50: (143, 151, 132),
    51: (166, 206, 215), 52: (166, 206, 215), 53: (224, 204, 161),
    54: (180, 172, 143), 55: (199, 193, 161), 56: (175, 183, 153),
    57: (237, 235, 223),
}


def point(pair):
    return float(pair[1]), float(pair[0])


def smooth(points):
    """Round raster corners without changing the road's underlying cells."""
    if len(points) < 3:
        return points
    # A world map needs a broader cartographic bend than a one-cell corner.
    # Keep endpoints fixed so this display stroke meets the exact gate/seat.
    unique = [points[0]]
    for value in points[1:]:
        if value != unique[-1]:
            unique.append(value)
    if len(unique) >= 7:
        radius = 18
        unique = [unique[0]] + [
            (sum(value[0] for value in unique[max(0, i - radius):min(len(unique), i + radius + 1)]) /
             len(unique[max(0, i - radius):min(len(unique), i + radius + 1)]),
             sum(value[1] for value in unique[max(0, i - radius):min(len(unique), i + radius + 1)]) /
             len(unique[max(0, i - radius):min(len(unique), i + radius + 1)]))
            for i in range(1, len(unique) - 1)
        ] + [unique[-1]]
    for _ in range(2):
        if len(unique) < 3:
            break
        next_points = [unique[0]]
        for a, b in zip(unique, unique[1:]):
            next_points.extend(((0.75 * a[0] + 0.25 * b[0], 0.75 * a[1] + 0.25 * b[1]),
                                (0.25 * a[0] + 0.75 * b[0], 0.25 * a[1] + 0.75 * b[1])))
        next_points.append(unique[-1])
        unique = next_points
    return unique


def main():
    tiles = json.loads((ROOT / "data/map/han-tiles.json").read_text())
    roads = json.loads((ROOT / "data/map/han-land-roads-v1.json").read_text())
    rows, cols = tiles["_meta"]["rows"], tiles["_meta"]["cols"]
    raw = np.frombuffer("".join(tiles["terrain"]).encode("ascii"), dtype=np.uint8).reshape(rows, cols)
    rgb = np.zeros((rows, cols, 3), dtype=np.uint8)
    for code, color in PALETTE.items():
        rgb[raw == code] = color
    base = Image.fromarray(rgb, "RGB")
    # Blur the sea/land edge only; dry terrain colour and the actual tile grid
    # remain untouched in the authoritative data.
    sea = Image.fromarray((np.isin(raw, [48, 51, 52]) * 255).astype(np.uint8), "L")
    coast = sea.filter(ImageFilter.GaussianBlur(2.2))
    base = Image.composite(Image.new("RGB", base.size, PALETTE[48]), base, coast)
    base = base.filter(ImageFilter.GaussianBlur(2.4))
    road_layer = Image.new("RGBA", base.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(road_layer)
    for layer in ("local", "trunk", "historical"):
        for edge in roads["edges"]:
            if edge["status"] != "BUILT":
                continue
            historical = bool(edge["historicalRouteIds"])
            if layer == "historical" and not historical or layer == "trunk" and (historical or not edge["overviewTrunk"]) or layer == "local" and (historical or edge["overviewTrunk"]):
                continue
            cells = edge["fromTrail"] + [[edge["toCell"]["row"], edge["toCell"]["col"]]] + list(reversed(edge["toTrail"]))
            pts = smooth([point(cell) for cell in cells])
            if len(pts) > 1:
                fill, width = {"local": ((129, 94, 62, 190), 4), "trunk": ((117, 72, 38, 230), 5),
                               "historical": ((191, 68, 22, 240), 7)}[layer]
                draw.line(pts, fill=fill, width=width, joint="curve")
    base = Image.alpha_composite(base.convert("RGBA"), road_layer).convert("RGB")
    base = base.resize((cols // 2, rows // 2), Image.Resampling.LANCZOS)
    draw = ImageDraw.Draw(base)
    # Some province junctions have no city record. Mark every built road's
    # endpoints so the line ends read as a destination or interchange.
    junctions = set()
    for edge in roads["edges"]:
        if edge["status"] == "BUILT":
            junctions.add(tuple(edge["fromTrail"][0]))
            junctions.add(tuple(edge["toTrail"][0]))
    for row, col in junctions:
        x, y = col / 2, row / 2
        draw.ellipse((x - 1.15, y - 1.15, x + 1.15, y + 1.15), fill=(91, 67, 47))
    for city in tiles["cities"]:
        x, y = city["col"] / 2, city["row"] / 2
        draw.ellipse((x - 1.75, y - 1.75, x + 1.75, y + 1.75), fill=(83, 55, 34))
    font_path = Path("/System/Library/Fonts/AppleSDGothicNeo.ttc")
    font = ImageFont.truetype(str(font_path), 17) if font_path.exists() else ImageFont.load_default()
    small = ImageFont.truetype(str(font_path), 14) if font_path.exists() else ImageFont.load_default()
    draw.rectangle((16, 16, 620, 100), fill=(248, 246, 237), outline=(107, 101, 86))
    draw.text((26, 23), "4배 지도 · 지형과 도로 구상", font=font, fill=(39, 39, 34))
    draw.line((28, 54, 67, 54), fill=(157, 117, 75), width=2)
    draw.text((77, 44), "지형 기반 간선·지방길", font=small, fill=(39, 39, 34))
    draw.line((265, 54, 304, 54), fill=(191, 97, 35), width=3)
    draw.text((314, 44), "사료 확인 통로*", font=small, fill=(39, 39, 34))
    draw.ellipse((478, 49, 484, 55), fill=(83, 55, 34))
    draw.text((491, 44), "치소·도로 접점", font=small, fill=(39, 39, 34))
    draw.text((26, 72), "* 통로의 존재는 사료 근거, 격자 경로는 지형 추정", font=small, fill=(78, 73, 65))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    base.save(OUT, optimize=True)
    print(OUT, base.size, OUT.stat().st_size)


if __name__ == "__main__":
    main()
