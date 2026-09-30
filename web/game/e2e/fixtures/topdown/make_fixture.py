#!/usr/bin/env python3
"""탑다운 지도 e2e용 합성 키트 · 굽기(프로젝트가 만든 단색 타일, 원작 그림 없음). 다시 만들 때만 돌린다."""

import gzip
import hashlib
import io
import json
from pathlib import Path

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
KIT = HERE / "kit"
BAKE = HERE / "bake"
COLS, ROWS, CHUNK = 3072, 2676, 256
NO_TILE = 0xFFFF
PALETTE = [(0, 0, 0), (200, 40, 40), (40, 160, 60), (40, 80, 200), (230, 200, 60), (255, 128, 0), (120, 60, 20), (200, 200, 200),
           (90, 90, 90), (255, 255, 255), (0, 128, 128), (128, 0, 128), (60, 60, 160), (160, 220, 160), (250, 220, 180), (20, 20, 60)]
TILES = [1, 2, 4]  # 타일 0 빨강 · 1 초록 · 2 노랑(가운데 8×8은 지붕 역할)


def png(array: np.ndarray, mode: str) -> bytes:
    buffer = io.BytesIO()
    Image.fromarray(array, mode).save(buffer, format="PNG", optimize=False, compress_level=9)
    return buffer.getvalue()


def gz(data: bytes) -> bytes:
    buffer = io.BytesIO()
    with gzip.GzipFile(fileobj=buffer, mode="wb", mtime=0, filename="") as out:
        out.write(data)
    return buffer.getvalue()


def main() -> None:
    KIT.mkdir(exist_ok=True)
    (BAKE / "grid" / "L0").mkdir(parents=True, exist_ok=True)
    index = np.zeros((16, 32 * 16), np.uint8)
    for t, colour in enumerate(TILES):
        cell = np.full((16, 16), colour, np.uint8)
        if t == 2:
            cell[4:12, 4:12] = colour | (1 << 4)
        index[:, t * 16 : (t + 1) * 16] = cell
    (KIT / "kit-index.png").write_bytes(png(index, "L"))
    rgb = np.array(PALETTE, np.uint8)
    for size in (8, 4, 2, 1):
        mip = np.zeros((size, 32 * size, 3), np.uint8)
        for t, colour in enumerate(TILES):
            mip[:, t * size : (t + 1) * size] = rgb[colour]
        (KIT / f"kit-mip{size}.png").write_bytes(png(mip, "RGB"))
    (KIT / "palettes.json").write_text(json.dumps({"schemaVersion": 1, "dayBank": 1, "banks": [PALETTE, PALETTE]}) + "\n")
    sites = np.zeros((16, 64, 4), np.uint8)
    sites[2:14, :, :] = (220, 220, 220, 255)
    sites_roles = np.zeros((16, 64), np.uint8)
    sites_roles[4:12, :] = 1
    (KIT / "sites.png").write_bytes(png(sites, "RGBA"))
    (KIT / "sites-roles.png").write_bytes(png(sites_roles, "L"))
    flags = np.zeros((16, 32, 4), np.uint8)
    flags[0:16, 1:3] = (90, 60, 30, 255)
    flags[3:12, 3:15] = (200, 40, 40, 255)
    flags[3:12, 19:31] = (200, 40, 40, 255)
    flag_roles = np.zeros((16, 32), np.uint8)
    flag_roles[3:12, 3:15] = 1
    flag_roles[3:12, 19:31] = 1
    (KIT / "flags.png").write_bytes(png(flags, "RGBA"))
    (KIT / "flags-roles.png").write_bytes(png(flag_roles, "L"))

    # 조각 (5,3): 왼쪽 반 빨강 · 구역 1, 오른쪽 반 초록 · 구역 2, (1400, 900)에 지붕 타일
    tiles = np.zeros((CHUNK, CHUNK), np.uint16)
    tiles[:, 128:] = 1
    provinces = np.ones((CHUNK, CHUNK), np.uint16)
    provinces[:, 128:] = 2
    tiles[900 - 768, 1400 - 1280] = 2
    raw = tiles.astype("<u2").tobytes() + provinces.astype("<u2").tobytes()
    chunk_file = "grid/L0/5_3.bin.gz"
    (BAKE / chunk_file).write_bytes(gz(raw))
    overview_tiles = np.full((669, 768), NO_TILE, np.uint16)
    overview_prov = np.zeros((669, 768), np.uint16)
    overview_tiles[192:256, 320:352] = 0
    overview_tiles[192:256, 352:384] = 1
    overview_prov[192:256, 320:352] = 1
    overview_prov[192:256, 352:384] = 2
    overview = overview_tiles.astype("<u2").tobytes() + overview_prov.astype("<u2").tobytes()
    (BAKE / "grid" / "L2.bin.gz").write_bytes(gz(overview))
    places = {
        "schemaVersion": 1, "provinceCount": 2, "provinceAdmin": [[0, 0, 0], [1, 0, 0]],
        "counties": [{"id": "J1", "name": "시험현", "kind": "COUNTY", "cityId": 1}, {"id": "J2", "name": "옆현", "kind": "COUNTY", "cityId": None}],
        "commanderies": [{"id": "C1", "name": "시험군", "kind": "COMMANDERY", "seatCityId": 1}],
        "ju": [{"name": "시험주", "anchor": [1408, 896]}],
        "cities": [{"id": 1, "name": "시험현", "level": 10, "cell": [1400, 900], "provinceIndex": 0, "countyIndex": 0, "commanderyIndex": 0,
                    "isSeat": True, "footprint": {"originCol": 1399, "originRow": 899, "span": 3, "innerSpan": 0},
                    "roofCell": [1400, 900], "gates": "", "site": None, "households": 1000}],
        "passes": [],
        "labels": [{"id": "city:1", "text": "시험현", "kind": "commanderySeat", "anchor": [1400, 900], "priority": 400000, "footprintSpan": 3}],
    }
    places_bytes = gz((json.dumps(places, ensure_ascii=False, sort_keys=True, separators=(",", ":"))).encode())
    (BAKE / "places.json.gz").write_bytes(places_bytes)
    chunks = [{"cx": 5, "cy": 3, "file": chunk_file, "sha256": hashlib.sha256((BAKE / chunk_file).read_bytes()).hexdigest()},
              {"cx": 6, "cy": 3, "uniform": {"tile": 1, "province": 2}}]
    manifest = {
        "schemaVersion": 1, "artifactId": "topdown-bake", "bakeId": "e2e-fixture", "shape": {"cols": COLS, "rows": ROWS},
        "chunkSize": CHUNK, "kitId": "e2e-fixture", "inputs": {}, "chunks": chunks,
        "overview": {"file": "grid/L2.bin.gz", "sha256": hashlib.sha256((BAKE / "grid" / "L2.bin.gz").read_bytes()).hexdigest(),
                     "cols": 768, "rows": 669, "block": 4},
        "places": {"file": "places.json.gz", "sha256": hashlib.sha256(places_bytes).hexdigest()},
    }
    (BAKE / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
