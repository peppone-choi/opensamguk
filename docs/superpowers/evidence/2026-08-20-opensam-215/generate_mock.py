#!/usr/bin/env python3
from __future__ import annotations

import json
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[4]
DATA = ROOT / "data/map/han-tiles.json"
OUT = Path(__file__).resolve().parent

WIDTH = 1440
HEIGHT = 900
MAP_BOX = (62, 160, 1378, 820)

FONT_PATH = Path("/System/Library/Fonts/AppleSDGothicNeo.ttc")

SURFACE = {
    0: (25, 54, 72),
    1: (100, 121, 75),
    2: (98, 92, 84),
    5: (182, 154, 98),
    6: (139, 121, 81),
    7: (129, 112, 91),
    8: (124, 118, 80),
}

HYDRO = {
    3: (63, 120, 148),
    4: (43, 92, 120),
}

OWNER_HUES = [
    (198, 91, 75),
    (78, 138, 112),
    (89, 108, 176),
]


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    index = 8 if bold else 0
    return ImageFont.truetype(str(FONT_PATH), size=size, index=index)


def expand_rle(rle: list[list[int]], cells: int) -> list[int]:
    values: list[int] = []
    for value, count in rle:
        values.extend([value] * count)
    if len(values) != cells:
        raise ValueError(f"RLE expands to {len(values)}, expected {cells}")
    return values


def brighten(rgb: tuple[int, int, int], amount: int) -> tuple[int, int, int]:
    return tuple(min(255, channel + amount) for channel in rgb)


def terrain_source(data: dict) -> Image.Image:
    cols = data["_meta"]["cols"]
    rows = data["_meta"]["rows"]
    image = Image.new("RGB", (cols, rows))
    pixels = image.load()
    for row, encoded in enumerate(data["terrain"]):
        for col, raw in enumerate(encoded):
            code = int(raw)
            color = SURFACE[code] if code in SURFACE else HYDRO[code]
            accent = False
            if code == 0:
                accent = (col + 2 * row) % 17 == 0
            elif code == 1:
                accent = (col * 13 + row * 7) % 43 == 0
            elif code == 2:
                accent = (col - row) % 11 == 0
            elif code == 5:
                accent = (col * 5 + row * 11) % 31 == 0
            elif code == 6:
                accent = (col + row) % 13 == 0
            elif code == 7:
                accent = ((col % 19) in (0, 1)) and ((row % 19) in (0, 1))
            elif code == 8:
                accent = (2 * col + row) % 9 == 0
            elif code in HYDRO:
                accent = (col + row) % 13 == 0
            pixels[col, row] = brighten(color, 9) if accent else color
    return image


def owner_band(data: dict, owner: int, fallback_col: int) -> int:
    if owner < 0:
        return -1
    seat_col = data["juns"][owner]["col"] if owner < len(data["juns"]) else fallback_col
    return 0 if seat_col < 340 else 1 if seat_col < 420 else 2


def owner_source(data: dict, seat_owner: list[int]) -> Image.Image:
    cols = data["_meta"]["cols"]
    rows = data["_meta"]["rows"]
    image = Image.new("RGBA", (cols, rows), (0, 0, 0, 0))
    pixels = image.load()
    terrain = data["terrain"]
    for row in range(rows):
        for col in range(cols):
            owner = seat_owner[row * cols + col]
            if owner < 0 or terrain[row][col] == "0":
                continue
            hue_index = owner_band(data, owner, col)
            red, green, blue = OWNER_HUES[hue_index]
            pixels[col, row] = (red, green, blue, 52)
    return image


def iso_inverse(scale: float, ox: float, oy: float) -> tuple[float, float, float, float, float, float]:
    return (
        1 / (2 * scale),
        1 / scale,
        -(ox + 2 * oy) / (2 * scale),
        -1 / (2 * scale),
        1 / scale,
        (ox - 2 * oy) / (2 * scale),
    )


def project(col: float, row: float, scale: float, ox: float, oy: float) -> tuple[float, float]:
    return ((col - row) * scale + ox, (col + row) * scale / 2 + oy)


def unproject(x: float, y: float, scale: float, ox: float, oy: float) -> tuple[float, float]:
    return (
        ((x - ox) + 2 * (y - oy)) / (2 * scale),
        (-(x - ox) + 2 * (y - oy)) / (2 * scale),
    )


def draw_boundaries(
    draw: ImageDraw.ImageDraw,
    data: dict,
    seat_owner: list[int],
    scale: float,
    ox: float,
    oy: float,
    map_origin: tuple[int, int],
    jun_width: int,
) -> None:
    cols = data["_meta"]["cols"]
    rows = data["_meta"]["rows"]
    mx, my = map_origin
    color = (225, 210, 163, 170)
    for row in range(rows):
        base = row * cols
        for col in range(cols):
            owner = seat_owner[base + col]
            if owner < 0:
                continue
            cx, cy = project(col, row, scale, ox, oy)
            right_owner = seat_owner[base + col + 1] if col + 1 < cols else -1
            if right_owner >= 0 and right_owner != owner:
                draw.line(
                    [(mx + cx + scale, my + cy), (mx + cx, my + cy + scale / 2)],
                    fill=color,
                    width=jun_width,
                )
            bottom_owner = seat_owner[base + cols + col] if row + 1 < rows else -1
            if bottom_owner >= 0 and bottom_owner != owner:
                draw.line(
                    [(mx + cx, my + cy + scale / 2), (mx + cx - scale, my + cy)],
                    fill=color,
                    width=jun_width,
                )


def draw_country_boundaries(
    draw: ImageDraw.ImageDraw,
    data: dict,
    seat_owner: list[int],
    scale: float,
    ox: float,
    oy: float,
) -> None:
    cols = data["_meta"]["cols"]
    rows = data["_meta"]["rows"]
    segments: list[tuple[tuple[float, float], tuple[float, float], int]] = []
    for row in range(rows):
        base = row * cols
        for col in range(cols):
            owner = seat_owner[base + col]
            band = owner_band(data, owner, col)
            if band < 0:
                continue
            cx, cy = project(col, row, scale, ox, oy)
            right_owner = seat_owner[base + col + 1] if col + 1 < cols else -1
            right_band = owner_band(data, right_owner, col + 1)
            if right_band >= 0 and right_band != band:
                segments.append(((cx + scale, cy), (cx, cy + scale / 2), band))
            bottom_owner = seat_owner[base + cols + col] if row + 1 < rows else -1
            bottom_band = owner_band(data, bottom_owner, col)
            if bottom_band >= 0 and bottom_band != band:
                segments.append(((cx, cy + scale / 2), (cx - scale, cy), band))
    for start, end, _ in segments:
        draw.line((start, end), fill="#111315C7", width=3)
    for start, end, band in segments:
        draw.line((start, end), fill=(*OWNER_HUES[band], 209), width=1)


def city_tier(city: dict) -> int | None:
    if city["kind"] == "EXTERNAL_PLACE":
        return None
    level = city["level"]
    return 1 if level == 5 else 2 if level == 6 else 3 if level == 7 else 4


def city_label(city: dict) -> str:
    name = city["name"]
    return name[:-1] if len(name) > 1 and name.endswith("현") else name


def draw_marker(
    draw: ImageDraw.ImageDraw,
    x: float,
    y: float,
    tier: int | None,
    external: bool,
    selected: bool = False,
    owner_index: int = 1,
) -> None:
    if external:
        r = 6
        draw.polygon([(x, y - r), (x + r, y), (x, y + r), (x - r, y)], fill="#D9705D", outline="#1A1714")
        draw.line([(x - 3, y), (x + 3, y)], fill="#F8EBD0", width=1)
        return
    assert tier is not None
    radii = {1: 3, 2: 5, 3: 7, 4: 9}
    r = radii[tier]
    owner = OWNER_HUES[owner_index % len(OWNER_HUES)]
    fill = tuple(round(channel * 0.72) for channel in owner)
    if tier == 1:
        draw.ellipse((x - r, y - r, x + r, y + r), fill=fill, outline="#F1E5C8")
    else:
        draw.polygon([(x, y - r), (x + r, y), (x, y + r), (x - r, y)], fill=fill, outline="#17130F")
        if tier >= 3:
            rr = r + 3
            draw.ellipse((x - rr, y - rr, x + rr, y + rr), outline="#F1DFB2", width=2)
        if tier == 4:
            rr = r + 6
            draw.ellipse((x - rr, y - rr, x + rr, y + rr), outline="#D5B85A", width=2)
    if selected:
        rr = r + 10
        draw.arc((x - rr, y - rr, x + rr, y + rr), 20, 340, fill="#FFFFFF", width=2)


def panel_base(title: str, subtitle: str) -> tuple[Image.Image, ImageDraw.ImageDraw]:
    image = Image.new("RGBA", (WIDTH, HEIGHT), "#0A0B0C")
    draw = ImageDraw.Draw(image, "RGBA")
    draw.rectangle((0, 0, WIDTH, 52), fill="#121416")
    draw.line((0, 51, WIDTH, 51), fill="#34383C", width=1)
    draw.text((28, 17), "오픈삼국", font=font(21, True), fill="#D7B327")
    draw.text((150, 19), "181년 1월 상순", font=font(15), fill="#A5A8AA")
    draw.rounded_rectangle((MAP_BOX[0], 76, 170, 114), radius=8, fill="#24282D", outline="#40464D")
    draw.text((82, 87), "← 돌아가기", font=font(14, True), fill="#F3F3F1")
    draw.text((MAP_BOX[0], 122), title, font=font(27, True), fill="#E0BD32")
    draw.text((MAP_BOX[0] + 168, 128), subtitle, font=font(13), fill="#92989D")
    draw.rounded_rectangle(MAP_BOX, radius=10, fill="#101315", outline="#3C4247", width=1)
    return image, draw


def render_map_layer(
    data: dict,
    terrain: Image.Image,
    owners: Image.Image,
    scale: float,
    center: tuple[float, float],
    box: tuple[int, int, int, int],
) -> tuple[Image.Image, float, float]:
    left, top, right, bottom = box
    width = right - left
    height = bottom - top
    center_col, center_row = center
    ox = width / 2 - (center_col - center_row) * scale
    oy = height / 2 - (center_col + center_row) * scale / 2
    matrix = iso_inverse(scale, ox, oy)
    base = terrain.transform((width, height), Image.Transform.AFFINE, matrix, resample=Image.Resampling.BILINEAR)
    tint = owners.transform((width, height), Image.Transform.AFFINE, matrix, resample=Image.Resampling.NEAREST)
    return Image.alpha_composite(base.convert("RGBA"), tint), ox, oy


def draw_zoom_controls(draw: ImageDraw.ImageDraw) -> None:
    x = MAP_BOX[2] - 54
    for index, glyph in enumerate(("+", "-")):
        y = MAP_BOX[3] - 104 + index * 44
        draw.rounded_rectangle((x, y, x + 38, y + 38), radius=6, fill="#1A1E21", outline="#4A5158")
        draw.text((x + 12, y + 6), glyph, font=font(23, True), fill="#F0E9D7")


def draw_minimap(
    image: Image.Image,
    data: dict,
    terrain: Image.Image,
    owners: Image.Image,
    seat_owner: list[int],
    main_view: tuple[float, float, float, int, int],
) -> None:
    box = (MAP_BOX[2] - 246, MAP_BOX[1] + 18, MAP_BOX[2] - 18, MAP_BOX[1] + 174)
    draw = ImageDraw.Draw(image, "RGBA")
    draw.rounded_rectangle(box, radius=8, fill="#0D1114E8", outline="#77705F", width=1)
    inner = (box[0] + 9, box[1] + 27, box[2] - 9, box[3] - 9)
    cols, rows = data["_meta"]["cols"], data["_meta"]["rows"]
    scale = min((inner[2] - inner[0]) / (cols + rows), 2 * (inner[3] - inner[1]) / (cols + rows))
    mini_owners = owners.copy()
    mini_owners.putalpha(owners.getchannel("A").point(lambda alpha: round(alpha * 0.6)))
    layer, ox, oy = render_map_layer(data, terrain, mini_owners, scale, (cols / 2, rows / 2), inner)
    image.alpha_composite(layer, (inner[0], inner[1]))
    border = Image.new("RGBA", (inner[2] - inner[0], inner[3] - inner[1]), (0, 0, 0, 0))
    draw_country_boundaries(ImageDraw.Draw(border, "RGBA"), data, seat_owner, scale, ox, oy)
    image.alpha_composite(border, inner[:2])
    draw.text((box[0] + 10, box[1] + 7), "축소 지도", font=font(12, True), fill="#E9DFC7")
    main_scale, main_ox, main_oy, main_width, main_height = main_view
    cell_corners = [
        unproject(x, y, main_scale, main_ox, main_oy)
        for x, y in ((0, 0), (main_width, 0), (main_width, main_height), (0, main_height))
    ]
    viewport_points = [project(col, row, scale, ox, oy) for col, row in cell_corners]
    viewport = Image.new("RGBA", (inner[2] - inner[0], inner[3] - inner[1]), (0, 0, 0, 0))
    ImageDraw.Draw(viewport, "RGBA").polygon(viewport_points, outline="#F6E8B8", width=2)
    image.alpha_composite(viewport, inner[:2])
    draw.text((box[0] + 11, box[3] - 24), "뷰포트 • 클릭으로 이동", font=font(10), fill="#A7ADB0")


def draw_legend(draw: ImageDraw.ImageDraw) -> None:
    x = MAP_BOX[0] + 18
    y = MAP_BOX[3] - 254
    w = 410
    h = 234
    draw.rounded_rectangle((x, y, x + w, y + h), radius=8, fill="#0D1114E8", outline="#77705F")
    draw.text((x + 14, y + 10), "범례", font=font(14, True), fill="#F0E7D1")
    toggle_x = x + 58
    for label, width in (("소유 ✓", 48), ("국경 ✓", 48), ("郡계 ✓", 48), ("지명 ✓", 48), ("축소 ✓", 50)):
        draw.rounded_rectangle((toggle_x, y + 7, toggle_x + width, y + 27), radius=5, fill="#283039", outline="#57616A")
        draw.text((toggle_x + 6, y + 10), label, font=font(9, True), fill="#E7EAE8")
        toggle_x += width + 5
    terrain_items = [
        ("SEA", 0, "바다"), ("PLAIN", 1, "평야"), ("HILL", 8, "구릉"),
        ("BASIN", 7, "분지"), ("PLATEAU", 6, "고원"), ("DESERT", 5, "사막"),
        ("MOUNTAIN", 2, "산지"),
    ]
    for index, (_, code, label) in enumerate(terrain_items):
        col = index % 4
        row = index // 4
        sx = x + 14 + col * 96
        sy = y + 37 + row * 25
        color = SURFACE[code]
        draw.rectangle((sx, sy, sx + 18, sy + 14), fill=color, outline=brighten(color, 22))
        draw.text((sx + 24, sy - 1), label, font=font(11), fill="#D5D9D7")
    hy = y + 91
    draw.text((x + 14, hy), "수계", font=font(11, True), fill="#AEB4B8")
    draw.line((x + 57, hy + 8, x + 83, hy + 8), fill="#3F7894", width=4)
    draw.text((x + 90, hy), "강", font=font(11), fill="#D5D9D7")
    draw.rectangle((x + 130, hy + 2, x + 148, hy + 15), fill="#2B5C78")
    draw.text((x + 156, hy), "호수", font=font(11), fill="#D5D9D7")
    draw.line((x + 218, hy + 8, x + 248, hy + 8), fill="#E1D2A3", width=2)
    draw.text((x + 255, hy), "郡 경계", font=font(11), fill="#D5D9D7")
    oy = y + 120
    draw.text((x + 14, oy), "보이는 세력", font=font(10, True), fill="#AEB4B8")
    for index, (color, label) in enumerate(zip(OWNER_HUES, ("세력 A", "세력 B", "세력 C"))):
        sx = x + 102 + index * 91
        draw.rounded_rectangle((sx, oy + 1, sx + 16, oy + 15), radius=3, fill=(*color, 190))
        draw.text((sx + 21, oy), label, font=font(9), fill="#D8DDDB")
    my = y + 151
    draw.text((x + 14, my), "도시", font=font(10, True), fill="#AEB4B8")
    for index, (tier, label) in enumerate(((1, "현5"), (2, "군6"), (3, "주7"), (4, "주요8"))):
        cx = x + 81 + index * 70
        cy = my + 9
        draw_marker(draw, cx, cy, tier, False, owner_index=1)
        draw.text((cx + 13, my + 1), label, font=font(9), fill="#D8DDDB")
    draw_marker(draw, x + 372, my + 9, None, True)
    draw.text((x + 385, my + 1), "외", font=font(9), fill="#D8DDDB")
    draw.text((x + 14, y + 183), "지형=명도·질감 / 소유=고정 명도의 색상", font=font(10), fill="#B7BCBE")
    draw.text((x + 14, y + 204), "※ 세력 A/B/C는 인코딩 표본이며 실제 220년 지배 주장이 아님", font=font(9), fill="#D7B889")


def overview(data: dict, terrain: Image.Image, owners: Image.Image, seat_owner: list[int]) -> Image.Image:
    image, draw = panel_base("세계 지도", "OPENSAM-215 시각 방향 • L0 천하 조망")
    cols, rows = data["_meta"]["cols"], data["_meta"]["rows"]
    inner = (MAP_BOX[0] + 1, MAP_BOX[1] + 1, MAP_BOX[2] - 1, MAP_BOX[3] - 1)
    scale = min((inner[2] - inner[0]) / (cols + rows), 2 * (inner[3] - inner[1]) / (cols + rows)) * 0.94
    center = (cols / 2, rows / 2)
    layer, ox, oy = render_map_layer(data, terrain, owners, scale, center, inner)
    image.alpha_composite(layer, (inner[0], inner[1]))
    boundary = Image.new("RGBA", (inner[2] - inner[0], inner[3] - inner[1]), (0, 0, 0, 0))
    boundary_draw = ImageDraw.Draw(boundary, "RGBA")
    draw_boundaries(boundary_draw, data, seat_owner, scale, ox, oy, (0, 0), 1)
    draw_country_boundaries(boundary_draw, data, seat_owner, scale, ox, oy)
    image.alpha_composite(boundary, inner[:2])
    for city in data["cities"]:
        tier = city_tier(city)
        external = city["kind"] == "EXTERNAL_PLACE"
        if not external and tier not in (3, 4):
            continue
        x, y = project(city["col"], city["row"], scale, ox, oy)
        x += inner[0]
        y += inner[1]
        if inner[0] <= x <= inner[2] and inner[1] <= y <= inner[3]:
            owner_index = 0 if city["col"] < 340 else 1 if city["col"] < 420 else 2
            draw_marker(draw, x, y, tier, external, owner_index=owner_index)
    draw.rounded_rectangle((MAP_BOX[0] + 18, MAP_BOX[1] + 18, MAP_BOX[0] + 370, MAP_BOX[1] + 52), radius=5, fill="#0D1114D9")
    draw.text((MAP_BOX[0] + 30, MAP_BOX[1] + 27), "ENCODING SAMPLE • 실제 세력 소유 데이터 아님", font=font(11, True), fill="#E8C88E")
    draw_legend(draw)
    draw_minimap(image, data, terrain, owners, seat_owner, (scale, ox, oy, inner[2] - inner[0], inner[3] - inner[1]))
    draw_zoom_controls(draw)
    return image


def local(data: dict, terrain: Image.Image, owners: Image.Image, seat_owner: list[int]) -> Image.Image:
    image, draw = panel_base("세계 지도", "OPENSAM-215 시각 방향 • L2 군 전략")
    inner = (MAP_BOX[0] + 1, MAP_BOX[1] + 1, MAP_BOX[2] - 1, MAP_BOX[3] - 1)
    center = (376.0, 233.0)
    scale = 3.15
    layer, ox, oy = render_map_layer(data, terrain, owners, scale, center, inner)
    image.alpha_composite(layer, (inner[0], inner[1]))
    boundary = Image.new("RGBA", (inner[2] - inner[0], inner[3] - inner[1]), (0, 0, 0, 0))
    boundary_draw = ImageDraw.Draw(boundary, "RGBA")
    draw_boundaries(boundary_draw, data, seat_owner, scale, ox, oy, (0, 0), 2)
    draw_country_boundaries(boundary_draw, data, seat_owner, scale, ox, oy)
    image.alpha_composite(boundary, inner[:2])

    legend_box = (MAP_BOX[0] + 12, MAP_BOX[3] - 260, MAP_BOX[0] + 434, MAP_BOX[3] - 12)
    minimap_box = (MAP_BOX[2] - 252, MAP_BOX[1] + 12, MAP_BOX[2] - 12, MAP_BOX[1] + 180)
    zoom_box = (MAP_BOX[2] - 60, MAP_BOX[3] - 110, MAP_BOX[2] - 10, MAP_BOX[3] - 10)
    occupied: list[tuple[float, float, float, float]] = [legend_box, minimap_box, zoom_box]
    marker_boxes: list[tuple[float, float, float, float]] = []

    def reserve(box: tuple[float, float, float, float]) -> bool:
        if any(not (box[2] < other[0] or box[0] > other[2] or box[3] < other[1] or box[1] > other[3]) for other in occupied):
            return False
        occupied.append(box)
        return True

    shown = []
    for city in data["cities"]:
        x, y = project(city["col"], city["row"], scale, ox, oy)
        x += inner[0]
        y += inner[1]
        if not (inner[0] + 10 <= x <= inner[2] - 10 and inner[1] + 10 <= y <= inner[3] - 10):
            continue
        tier = city_tier(city)
        external = city["kind"] == "EXTERNAL_PLACE"
        if not external and tier == 1 and not city["seat"]:
            continue
        selected = city["name"].startswith("낙양")
        owner_index = 0 if city["col"] < 340 else 1 if city["col"] < 420 else 2
        draw_marker(draw, x, y, tier, external, selected, owner_index)
        marker_boxes.append((x - 12, y - 12, x + 12, y + 12))
        if external or tier in (2, 3, 4) or selected:
            priority = 100 if selected else 90 if tier == 4 else 80 if tier == 3 else 70 if external else 60
            shown.append((priority, city, x, y))
    shown.sort(key=lambda item: (-item[0], (item[2] - WIDTH / 2) ** 2 + (item[3] - HEIGHT / 2) ** 2))
    for _, city, x, y in shown:
        label = city_label(city)
        text_font = font(13 if city["level"] >= 8 else 11, city["level"] >= 8)
        bbox = draw.textbbox((0, 0), label, font=text_font)
        tw = bbox[2] - bbox[0]
        tx = x - tw / 2
        ty = y - (33 if city["level"] >= 8 else 25)
        label_box = (tx - 5, ty - 3, tx + tw + 5, ty + 20)
        if reserve(label_box):
            draw.rounded_rectangle((tx - 3, ty - 1, tx + tw + 3, ty + 17), radius=2, fill="#0B0C0DB8")
            draw.text((tx, ty), label, font=text_font, fill="#FFF9EA")

    occupied.extend(marker_boxes)
    ordered_juns = sorted(
        data["juns"],
        key=lambda jun: (jun["col"] - center[0]) ** 2 + (jun["row"] - center[1]) ** 2,
    )
    for jun in ordered_juns:
        x, y = project(jun["col"], jun["row"], scale, ox, oy)
        x += inner[0]
        y += inner[1]
        if inner[0] + 40 < x < inner[2] - 40 and inner[1] + 45 < y < inner[3] - 45:
            label = jun["name"]
            bbox = draw.textbbox((0, 0), label, font=font(13, True))
            tw = bbox[2] - bbox[0]
            label_box = (x - tw / 2 - 8, y + 7, x + tw / 2 + 8, y + 31)
            if reserve(label_box):
                draw.rounded_rectangle((x - tw / 2 - 5, y + 9, x + tw / 2 + 5, y + 28), radius=3, fill="#10131599")
                draw.text((x - tw / 2, y + 10), label, font=font(13, True), fill="#E5D7AE")

    draw_legend(draw)
    draw_minimap(image, data, terrain, owners, seat_owner, (scale, ox, oy, inner[2] - inner[0], inner[3] - inner[1]))
    draw_zoom_controls(draw)
    return image


def specimen() -> Image.Image:
    image = Image.new("RGBA", (1440, 760), "#0A0B0C")
    draw = ImageDraw.Draw(image, "RGBA")
    draw.text((54, 42), "후한 지도 시각 토큰 표본", font=font(30, True), fill="#E2BF34")
    draw.text((54, 84), "7 지형 명도·질감 / 2 수계 / 소유 색상 / 4단계 마커", font=font(15), fill="#A8ADB0")
    items = [
        ("SEA", 0, "바다", "#193648", "8 px wave"),
        ("PLAIN", 1, "평야", "#64794B", "sparse grain"),
        ("HILL", 8, "구릉", "#7C7650", "short ticks"),
        ("BASIN", 7, "분지", "#81705B", "contour corners"),
        ("PLATEAU", 6, "고원", "#8B7951", "13 px hatch"),
        ("DESERT", 5, "사막", "#B69A62", "sand stipple"),
        ("MOUNTAIN", 2, "산지", "#625C54", "11 px ridge"),
    ]
    for index, (name, code, ko, value, texture) in enumerate(items):
        x = 54 + (index % 4) * 330
        y = 140 + (index // 4) * 145
        base = SURFACE[code]
        draw.rounded_rectangle((x, y, x + 300, y + 112), radius=8, fill="#15181B", outline="#3C4247")
        draw.rectangle((x + 14, y + 14, x + 112, y + 98), fill=base)
        for k in range(8):
            if code in (0, 6, 2):
                draw.line((x + 18, y + 22 + 10 * k, x + 108, y + 22 + 10 * k), fill=(*brighten(base, 14), 130), width=1)
            else:
                px = x + 20 + ((k * 29) % 82)
                py = y + 20 + ((k * 17) % 72)
                draw.ellipse((px, py, px + 2, py + 2), fill=brighten(base, 20))
        draw.text((x + 128, y + 18), f"{ko}  {name}", font=font(15, True), fill="#F0E8D5")
        draw.text((x + 128, y + 48), value, font=font(13), fill="#C8CDCF")
        draw.text((x + 128, y + 73), texture, font=font(11), fill="#92999D")

    y = 442
    draw.rounded_rectangle((54, y, 746, y + 236), radius=8, fill="#15181B", outline="#3C4247")
    draw.text((72, y + 18), "소유 색상 표본 • 인지 명도 고정", font=font(17, True), fill="#F0E8D5")
    for i, (color, label) in enumerate(zip(OWNER_HUES, ("주홍", "옥색", "남색"))):
        x = 72 + i * 210
        draw.rounded_rectangle((x, y + 62, x + 178, y + 116), radius=7, fill=color)
        draw.text((x + 12, y + 76), f"{label}  #{color[0]:02X}{color[1]:02X}{color[2]:02X}", font=font(13, True), fill="#FFF9EE")
    draw.text((72, y + 142), "소유 면 20% / 국경 82% / 반드시 암색 할로와 쌍으로 사용", font=font(12), fill="#BEC4C6")
    draw.text((72, y + 172), "지형은 명도·질감, 소유는 색상으로 분리한다.", font=font(13, True), fill="#E3CB91")

    x = 782
    draw.rounded_rectangle((x, y, 1386, y + 236), radius=8, fill="#15181B", outline="#3C4247")
    draw.text((x + 18, y + 18), "도시 마커 4단계 • 데이터 level 5–8", font=font(17, True), fill="#F0E8D5")
    for tier, label in ((1, "현 5"), (2, "군 6"), (3, "주 7"), (4, "주요 8")):
        cx = x + 70 + (tier - 1) * 142
        cy = y + 102
        draw_marker(draw, cx, cy, tier, False, owner_index=1)
        draw.text((cx - 22, cy + 27), label, font=font(12, True), fill="#DDE1DF")
    draw.text((x + 18, y + 162), "EXTERNAL_PLACE level 4는 4단계 밖 예외 기호", font=font(12), fill="#D6B47E")
    draw_marker(draw, x + 43, y + 204, None, True)
    draw.text((x + 62, y + 194), "이민족 거점 • 항상 노출 대상", font=font(12), fill="#C7CDCF")
    return image


def main() -> None:
    data = json.loads(DATA.read_text(encoding="utf-8"))
    cols = data["_meta"]["cols"]
    rows = data["_meta"]["rows"]
    seat_owner = expand_rle(data["seatOwner"], cols * rows)
    terrain = terrain_source(data)
    owners = owner_source(data, seat_owner)

    files = {
        "han-map-direction-overview.png": overview(data, terrain, owners, seat_owner),
        "han-map-direction-local.png": local(data, terrain, owners, seat_owner),
        "han-map-token-specimen.png": specimen(),
    }
    for name, image in files.items():
        target = OUT / name
        image.convert("RGB").save(target, optimize=True)
        print(f"wrote {target.relative_to(ROOT)} {image.width}x{image.height}")


if __name__ == "__main__":
    main()
