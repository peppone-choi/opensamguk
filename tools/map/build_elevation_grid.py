#!/usr/bin/env python3
"""후한 지도 격자의 표고(DEM)를 만든다 — NOAA ETOPO1 → han-world 투영.

지형 분류(build_terrain_grid.py)는 Natural Earth 지리구역 폴리곤에서 나오므로
표고값이 없다. 아이소 렌더러(iso2d 스프라이트 · iso3d glTF)는 타일당 정수 단차를
요구한다. 이 도구가 그 단차의 유일한 출처다.

출처: NOAA NCEI ETOPO1 (Ice Surface), ERDDAP griddap `etopo180`.
      미국 연방정부 저작물로 퍼블릭 도메인이다. 제3자 게임 에셋이 아니다.

투영은 data/map/han-tiles.json `_meta.projection` 을 그대로 역산한다. 새 투영을
정의하지 않는다 — 지형·소유 격자와 셀이 어긋나면 렌더러가 조용히 틀어진다.

    gx = (lon * k - x0 + pad) / cell
    gy = (y1 + pad - lat) / cell

산출물
    web/game/public/map/elevation/<mapCode>-metres.png   768×669 16bit, 오프셋 32768
    web/game/public/map/elevation/<mapCode>-levels.png   192×167 8bit, 계단 레벨
    web/game/public/map/elevation/manifest.json          출처·해시·사다리

원본 CSV 는 data/map/dem-cache/ 에 캐시한다(gitignored). 재실행은 네트워크를 다시
타지 않는다.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import numpy as np
from PIL import Image

ERDDAP = 'https://coastwatch.pfeg.noaa.gov/erddap/griddap/etopo180.csv'
DATASET = 'etopo180'
DATASET_TITLE = 'NOAA NCEI ETOPO1 Ice Surface, 1 arc-minute'
DATASET_LICENSE = 'public domain (U.S. federal government work)'

# 원본 1 arc-minute 를 2칸씩 건너뛴다. 2' = 0.0333도로 목표 셀 0.0469도보다 촘촘하다.
SOURCE_STRIDE = 2
SOURCE_STEP_DEG = SOURCE_STRIDE / 60.0

# 계단 사다리(m). 화면 한 단이 32px 이므로 단이 많으면 절벽만 남는다.
# 화북 평원 1, 황토고원 3, 티베트 6 이 되도록 잡았다.
LEVEL_LADDER = [0, 200, 500, 1000, 2000, 3500]

CACHE_DIR = 'data/map/dem-cache'
OUT_DIR = 'web/game/public/map/elevation'
TILES = 'data/map/han-tiles.json'

METRE_PNG_OFFSET = 32768


class Proj:
    """build_terrain_grid.py 의 Proj 와 같은 식. 여기서는 역산만 쓴다."""

    def __init__(self, p):
        self.k = p['k']
        self.x0 = p['x0']
        self.y1 = p['y1']
        self.pad = p['pad']
        self.cell = p['cell']
        self.cols = p['cols']
        self.rows = p['rows']

    def to_lonlat(self, gx, gy):
        lon = (gx * self.cell + self.x0 - self.pad) / self.k
        lat = self.y1 + self.pad - gy * self.cell
        return lon, lat

    def bounds(self):
        lon0, lat0 = self.to_lonlat(0.0, 0.0)
        lon1, lat1 = self.to_lonlat(float(self.cols), float(self.rows))
        return min(lon0, lon1), max(lon0, lon1), min(lat0, lat1), max(lat0, lat1)


def fetch_band(lat_lo, lat_hi, lon_lo, lon_hi, path, retries=4):
    """ERDDAP 위도 밴드 하나를 CSV 로 받아 캐시한다."""
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return path
    query = 'altitude[(%.6f):%d:(%.6f)][(%.6f):%d:(%.6f)]' % (
        lat_lo, SOURCE_STRIDE, lat_hi, lon_lo, SOURCE_STRIDE, lon_hi)
    url = ERDDAP + '?' + urllib.parse.quote(query, safe='[]():,.-')
    last = None
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(url, timeout=180) as response:
                payload = response.read()
            if not payload.startswith(b'latitude'):
                raise ValueError('unexpected ERDDAP payload head: %r' % payload[:80])
            tmp = path + '.part'
            with open(tmp, 'wb') as handle:
                handle.write(payload)
            os.replace(tmp, path)
            return path
        except (urllib.error.URLError, ValueError, TimeoutError) as error:
            last = error
            time.sleep(2 + 3 * attempt)
    raise SystemExit('ERDDAP 밴드 실패 %.2f..%.2f: %s' % (lat_lo, lat_hi, last))


def parse_csv(paths):
    """캐시된 CSV 들을 (lats, lons, grid) 로 조립한다. 행 순서를 가정하지 않는다."""
    lat_values, lon_values, records = set(), set(), []
    for path in paths:
        with open(path, 'r', encoding='utf-8') as handle:
            handle.readline()  # 헤더
            handle.readline()  # 단위
            for line in handle:
                if not line.strip():
                    continue
                lat_text, lon_text, alt_text = line.rstrip('\n').split(',')
                if alt_text == '' or alt_text == 'NaN':
                    continue
                lat = float(lat_text)
                lon = float(lon_text)
                lat_values.add(lat)
                lon_values.add(lon)
                records.append((lat, lon, float(alt_text)))
    lats = np.array(sorted(lat_values), dtype=np.float64)
    lons = np.array(sorted(lon_values), dtype=np.float64)
    lat_index = {value: i for i, value in enumerate(lats)}
    lon_index = {value: i for i, value in enumerate(lons)}
    grid = np.full((lats.size, lons.size), np.nan, dtype=np.float32)
    for lat, lon, alt in records:
        grid[lat_index[lat], lon_index[lon]] = alt
    missing = int(np.isnan(grid).sum())
    if missing:
        # ERDDAP 는 결측을 잘 내지 않는다. 나오면 최근접으로 메우고 수를 남긴다.
        flat = grid.reshape(-1)
        holes = np.flatnonzero(np.isnan(flat))
        good = np.flatnonzero(~np.isnan(flat))
        flat[holes] = flat[good[np.searchsorted(good, holes).clip(0, good.size - 1)]]
        grid = flat.reshape(grid.shape)
    return lats, lons, grid, missing


def sample_projection(lats, lons, grid, proj):
    """han-world 격자 셀 중심에서 DEM 을 겹선형 보간한다."""
    cols, rows = proj.cols, proj.rows
    gx = np.arange(cols, dtype=np.float64) + 0.5
    gy = np.arange(rows, dtype=np.float64) + 0.5
    lon_of_col = (gx * proj.cell + proj.x0 - proj.pad) / proj.k
    lat_of_row = proj.y1 + proj.pad - gy * proj.cell

    # 원본 축은 등간격이다. 인덱스를 직접 계산한다.
    lat_step = (lats[-1] - lats[0]) / (lats.size - 1)
    lon_step = (lons[-1] - lons[0]) / (lons.size - 1)
    fy = (lat_of_row - lats[0]) / lat_step
    fx = (lon_of_col - lons[0]) / lon_step
    fy = np.clip(fy, 0, lats.size - 1.0001)
    fx = np.clip(fx, 0, lons.size - 1.0001)

    y0 = np.floor(fy).astype(np.int64)
    x0 = np.floor(fx).astype(np.int64)
    ty = (fy - y0)[:, None]
    tx = (fx - x0)[None, :]

    g00 = grid[np.ix_(y0, x0)]
    g01 = grid[np.ix_(y0, x0 + 1)]
    g10 = grid[np.ix_(y0 + 1, x0)]
    g11 = grid[np.ix_(y0 + 1, x0 + 1)]
    top = g00 * (1 - tx) + g01 * tx
    bottom = g10 * (1 - tx) + g11 * tx
    return (top * (1 - ty) + bottom * ty).astype(np.float32)


def block_reduce_mean(array, group):
    """rasterGroup 배수로 평균낸다.

    남는 가장자리 행·열은 버린다. 애셋 매니페스트(iso2d·iso3d)가 tileGrid 를
    192×167 로 못박아 뒀고 669/4 = 167.25 이므로, 마지막 부분 행을 채워 168 로
    만들면 렌더러 인덱스가 한 줄씩 어긋난다. 버리는 쪽이 계약과 맞다.
    """
    rows, cols = array.shape
    out_rows = rows // group
    out_cols = cols // group
    cropped = array[:out_rows * group, :out_cols * group]
    blocks = cropped.reshape(out_rows, group, out_cols, group)
    return blocks.mean(axis=(1, 3)).astype(np.float32), out_rows, out_cols


def quantise(metres, ladder):
    """표고(m) → 계단 레벨. ladder[i] 이상이면 레벨 i+1 이다."""
    level = np.zeros(metres.shape, dtype=np.uint8)
    for index, threshold in enumerate(ladder):
        level[metres >= threshold] = index + 1
    return level


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b''):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--tiles', default=TILES)
    parser.add_argument('--out-dir', default=OUT_DIR)
    parser.add_argument('--cache-dir', default=CACHE_DIR)
    parser.add_argument('--map-code', default='han-world-v3')
    parser.add_argument('--bands', type=int, default=8, help='ERDDAP 위도 분할 수')
    parser.add_argument('--offline', action='store_true',
                        help='캐시만 쓰고 네트워크를 타지 않는다')
    args = parser.parse_args()

    with open(args.tiles, 'r', encoding='utf-8') as handle:
        meta = json.load(handle)['_meta']
    proj = Proj(meta['projection'])
    lon_lo, lon_hi, lat_lo, lat_hi = proj.bounds()
    # 겹선형 보간이 가장자리에서 잘리지 않도록 한 셀 여유를 준다.
    margin = SOURCE_STEP_DEG * 2
    lon_lo, lon_hi = lon_lo - margin, lon_hi + margin
    lat_lo, lat_hi = lat_lo - margin, lat_hi + margin
    print('격자 %d×%d · lon %.4f..%.4f · lat %.4f..%.4f'
          % (proj.cols, proj.rows, lon_lo, lon_hi, lat_lo, lat_hi))

    os.makedirs(args.cache_dir, exist_ok=True)
    os.makedirs(args.out_dir, exist_ok=True)

    edges = np.linspace(lat_lo, lat_hi, args.bands + 1)
    paths = []
    for index in range(args.bands):
        band_lo = float(edges[index])
        band_hi = float(edges[index + 1]) - (SOURCE_STEP_DEG if index < args.bands - 1 else 0.0)
        path = os.path.join(args.cache_dir, '%s-b%02d.csv' % (DATASET, index))
        if not os.path.exists(path):
            if args.offline:
                raise SystemExit('캐시 없음(offline): %s' % path)
            print('  밴드 %d/%d  lat %.3f..%.3f' % (index + 1, args.bands, band_lo, band_hi))
            fetch_band(band_lo, band_hi, lon_lo, lon_hi, path)
        paths.append(path)

    lats, lons, source, missing = parse_csv(paths)
    print('원본 %d×%d (lat %.4f..%.4f, lon %.4f..%.4f) 결측 %d'
          % (lats.size, lons.size, lats[0], lats[-1], lons[0], lons[-1], missing))

    metres = sample_projection(lats, lons, source, proj)
    print('투영 후 %d×%d · 최저 %.0fm · 최고 %.0fm'
          % (metres.shape[1], metres.shape[0], metres.min(), metres.max()))

    group = int(meta.get('rasterGroup') or 4)
    tile_metres, tile_rows, tile_cols = block_reduce_mean(metres, group)
    levels = quantise(tile_metres, LEVEL_LADDER)
    print('타일 격자 %d×%d (rasterGroup %d) · 레벨 0..%d'
          % (tile_cols, tile_rows, group, int(levels.max())))

    metre_png = os.path.join(args.out_dir, '%s-metres.png' % args.map_code)
    clipped = np.clip(np.round(metres) + METRE_PNG_OFFSET, 0, 65535).astype('<u2')
    Image.frombytes('I;16', (clipped.shape[1], clipped.shape[0]),
                    clipped.tobytes()).save(metre_png, optimize=True)

    level_png = os.path.join(args.out_dir, '%s-levels.png' % args.map_code)
    Image.fromarray(levels, mode='L').save(level_png, optimize=True)

    histogram = {int(value): int(count) for value, count
                 in zip(*np.unique(levels, return_counts=True))}
    manifest = {
        'schemaVersion': 1,
        'artifactId': 'han-elevation-v1',
        'mapCode': args.map_code,
        'generator': 'tools/map/build_elevation_grid.py',
        'note': ('표고는 지형 분류와 독립된 두 번째 축이다. 지형 클래스는 Natural Earth '
                 '지리구역 폴리곤에서, 표고는 ETOPO1 에서 온다. 서로를 보정하지 않는다.'),
        'source': {
            'dataset': DATASET,
            'title': DATASET_TITLE,
            'endpoint': ERDDAP,
            'license': DATASET_LICENSE,
            'sourceStrideArcMinutes': SOURCE_STRIDE,
            'retrieved': time.strftime('%Y-%m-%d'),
        },
        'projection': meta['projection'],
        'rasterGroup': group,
        'metreGrid': {
            'file': os.path.basename(metre_png),
            'cols': int(metres.shape[1]),
            'rows': int(metres.shape[0]),
            'encoding': 'uint16 PNG, metres = value - %d' % METRE_PNG_OFFSET,
            'min': int(round(float(metres.min()))),
            'max': int(round(float(metres.max()))),
            'sha256': sha256_file(metre_png),
        },
        'levelGrid': {
            'file': os.path.basename(level_png),
            'cols': int(tile_cols),
            'rows': int(tile_rows),
            'ladderMetres': LEVEL_LADDER,
            'encoding': 'uint8 PNG; level i means metres >= ladder[i-1]; 0 is below sea level',
            'histogram': histogram,
            'sha256': sha256_file(level_png),
        },
        'limitations': [
            '현대 지형이다. 후한대 황하 하도·운하·해안선 변화는 반영돼 있지 않다.',
            'ETOPO1 은 빙상 표면(Ice Surface) 판이다.',
            '레벨 사다리는 화면 단차용이며 고도 구간의 지리학적 표준이 아니다.',
        ],
    }
    manifest_path = os.path.join(args.out_dir, 'manifest.json')
    with open(manifest_path, 'w', encoding='utf-8') as handle:
        json.dump(manifest, handle, ensure_ascii=False, indent=1)
        handle.write('\n')

    print('썼다:')
    for path in (metre_png, level_png, manifest_path):
        print('  %s  %d bytes' % (path, os.path.getsize(path)))
    print('레벨 분포:', histogram)
    return 0


if __name__ == '__main__':
    sys.exit(main())
