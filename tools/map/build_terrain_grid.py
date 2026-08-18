#!/usr/bin/env python3
"""후한 군현 지도의 지형 격자를 만든다 — 사료 좌표 + Natural Earth 해안선.

`data/map/han-places.json` 이 저장한 등적 투영을 **역산**해 256×256 각 셀의 실제
경위도를 구하고, Natural Earth(public domain) 로 바다·호수·하천을 굽는다. 그 다음
각 육지 셀을 가장 가까운 縣에 배정(보로노이)하고, 郡治 사이의 도로를 Gabriel 그래프로
유도한다. 인접을 손으로 적지 않는다 — 좌표가 이미 그것을 말하고 있다.

산출 `data/map/terrain-grid.json` (미커밋, ADR-LITE-039):
    terrain  256×256  0 바다 · 1 평지 · 2 산지 · 3 하천 · 4 호소
    owner    256×250  각 셀의 縣 인덱스(-1 = 바다)
    roads    郡治 간 간선 목록

용법:  python3 tools/map/build_terrain_grid.py [--grid 256] [--preview]
"""
import argparse, json, math, os, sys
import numpy as np

PLACES = 'data/map/han-places.json'
JUNGUO = 'data/map/junguozhi.json'
NE = 'data/natural-earth'
OUT = 'data/map/terrain-grid.json'

SEA, PLAIN, MOUNTAIN, RIVER, LAKE, DESERT, PLATEAU, BASIN, HILL = range(9)

# Natural Earth 지리구역 → 지형. 칠하는 순서가 곧 우선순위다(뒤가 위를 덮는다).
# 넓은 바탕(고원·분지·평원)을 먼저 깔고 산맥·사막을 얹는다. 사서 태그는 마지막에 온다.
REGION_TERRAIN = [
    ('Plateau', PLATEAU), ('Basin', BASIN), ('Plain', PLAIN), ('Lowland', PLAIN),
    ('Foothills', HILL), ('Valley', PLAIN), ('Range/mtn', MOUNTAIN),
    ('Gorge', MOUNTAIN), ('Desert', DESERT),
]


# ── 투영 역산 ────────────────────────────────────────────────────────────────
class Proj:
    """build_han_places.py 의 등적 투영. pad 는 저장돼 있지 않으나 span 에서 되짚는다.

    저장된 span 은 이미 pad*2 를 포함하고(span_raw*1.04), x0·y1 은 pad 이전 값이다.
    따라서 pad = span/1.04*0.02 로 정확히 복원된다 — 근사가 아니다.
    """

    def __init__(self, p, n):
        self.k, self.x0, self.y1, self.span, self.n = p['k'], p['x0'], p['y1'], p['span'], n
        self.pad = self.span / 1.04 * 0.02

    def to_cell(self, lon, lat):
        gx = (lon * self.k - self.x0 + self.pad) / self.span * self.n
        gy = (self.y1 - lat + self.pad) / self.span * self.n
        return gx, gy

    def cell_lonlat(self, gx, gy):
        """셀 중심(+0.5)의 경위도. 격자→지리 방향이라 래스터라이즈에 이걸 쓴다."""
        lon = ((gx + 0.5) / self.n * self.span - self.pad + self.x0) / self.k
        lat = self.y1 + self.pad - (gy + 0.5) / self.n * self.span
        return lon, lat


# ── GeoJSON 래스터라이즈 ─────────────────────────────────────────────────────
def poly_rings(geom, bbox):
    """(외곽선 배열, 구멍인가). GeoJSON Polygon 의 첫 링은 외곽, 나머지는 구멍이다."""
    lo_lon, hi_lon, lo_lat, hi_lat = bbox
    polys = geom['coordinates'] if geom['type'] == 'MultiPolygon' else [geom['coordinates']]
    for poly in polys:
        for idx, ring in enumerate(poly):
            a = np.asarray(ring, dtype=float)
            if a.ndim != 2 or len(a) < 3:
                continue
            if (a[:, 0].max() < lo_lon or a[:, 0].min() > hi_lon
                    or a[:, 1].max() < lo_lat or a[:, 1].min() > hi_lat):
                continue
            yield a, idx > 0


def rasterize(features, proj, bbox):
    """폴리곤들을 격자 마스크로 굽는다.

    직접 짠 스캔라인은 거대 폴리곤(티베트고원·히말라야)에서 짝수-홀수 판정이 깨져
    가로 줄무늬를 만들었다. PIL 의 polygon 채우기는 링 단위로 정확하고 훨씬 짧다.
    외곽을 모두 칠한 뒤 구멍을 지운다 — 순서를 섞으면 구멍이 도로 메워진다.
    """
    from PIL import Image, ImageDraw
    img = Image.new('L', (proj.n, proj.n), 0)
    d = ImageDraw.Draw(img)
    holes = []
    for feat in features:
        for a, is_hole in poly_rings(feat['geometry'], bbox):
            gx, gy = proj.to_cell(a[:, 0], a[:, 1])
            pts = [(float(x), float(y)) for x, y in
                   zip(np.clip(gx, -1e4, 1e4), np.clip(gy, -1e4, 1e4))]
            (holes.append(pts) if is_hole else d.polygon(pts, fill=1))
    for pts in holes:
        d.polygon(pts, fill=0)
    return np.asarray(img, dtype=bool)


def stroke_lines(grid, proj, path, bbox, value, only_land=True):
    """하천은 선이라 채울 수 없다. 변을 촘촘히 샘플링해 지나는 셀을 찍는다."""
    lo_lon, hi_lon, lo_lat, hi_lat = bbox
    n = proj.n
    for feat in json.load(open(path))['features']:
        g = feat['geometry']
        lines = g['coordinates'] if g['type'] == 'MultiLineString' else [g['coordinates']]
        for line in lines:
            a = np.asarray(line, dtype=float)
            if (a[:, 0].max() < lo_lon or a[:, 0].min() > hi_lon
                    or a[:, 1].max() < lo_lat or a[:, 1].min() > hi_lat):
                continue
            gx, gy = proj.to_cell(a[:, 0], a[:, 1])
            d = np.hypot(np.diff(gx), np.diff(gy))
            for i in range(len(d)):
                steps = max(2, int(d[i] * 2) + 1)            # 셀 간격의 절반으로 샘플 — 끊김 방지
                t = np.linspace(0, 1, steps)
                xs = (gx[i] + t * (gx[i + 1] - gx[i])).astype(int)
                ys = (gy[i] + t * (gy[i + 1] - gy[i])).astype(int)
                ok = (xs >= 0) & (xs < n) & (ys >= 0) & (ys < n)
                xs, ys = xs[ok], ys[ok]
                if only_land:
                    ok = grid[ys, xs] != SEA                 # 하천이 바다를 덮으면 해안이 망가진다
                    xs, ys = xs[ok], ys[ok]
                grid[ys, xs] = value


# ── 도로: Gabriel 그래프 ─────────────────────────────────────────────────────
def gabriel(pts):
    """두 점을 지름으로 하는 원 안에 제3의 점이 없으면 간선.

    삼각분할보다 성기고 최근접이웃보다 촘촘해 도로망의 밀도가 사람 손 없이 맞는다.
    scipy 없이 O(n^3) 로 푼다 — 郡治가 100여 개뿐이라 이게 가장 짧은 코드다.
    """
    p = np.asarray(pts, dtype=float)
    n = len(p)
    edges = []
    for i in range(n):
        for j in range(i + 1, n):
            mid, r2 = (p[i] + p[j]) / 2, ((p[i] - p[j]) ** 2).sum() / 4
            d2 = ((p - mid) ** 2).sum(axis=1)
            d2[i] = d2[j] = np.inf
            if (d2 > r2 - 1e-9).all():
                edges.append((i, j))
    return edges


def _sea_run(terrain, p, q):
    """두 지점을 잇는 직선이 지나는 바다 셀 수. 육로/해로 판정에 쓴다."""
    steps = max(2, int(np.hypot(*(p - q))) * 2)
    t = np.linspace(0, 1, steps)
    xs = np.clip((p[0] + t * (q[0] - p[0])).astype(int), 0, terrain.shape[1] - 1)
    ys = np.clip((p[1] + t * (q[1] - p[1])).astype(int), 0, terrain.shape[0] - 1)
    return int((terrain[ys, xs] == SEA).sum() / 2)      # 샘플이 셀당 2회라 되돌린다


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--grid', type=int, default=256)
    ap.add_argument('--preview', action='store_true', help='PNG 미리보기도 그린다')
    a = ap.parse_args()

    if not os.path.exists(PLACES):
        sys.exit(f'{PLACES} 가 없다. 먼저 tools/map/build_han_places.py 를 돌려라.')
    hp = json.load(open(PLACES))
    n = a.grid
    proj = Proj(hp['projection'], n)

    lo_lon, lo_lat = proj.cell_lonlat(0, n - 1)
    hi_lon, hi_lat = proj.cell_lonlat(n - 1, 0)
    bbox = (lo_lon - 1, hi_lon + 1, lo_lat - 1, hi_lat + 1)
    print(f'격자 {n}×{n} · 경도 {lo_lon:.2f}~{hi_lon:.2f} · 위도 {lo_lat:.2f}~{hi_lat:.2f}',
          file=sys.stderr)

    terrain = np.zeros((n, n), dtype=np.uint8)               # 기본 바다
    land = rasterize(json.load(open(f'{NE}/ne_50m_land.geojson'))['features'], proj, bbox)
    terrain[land] = PLAIN
    land_cells = int(land.sum())

    # ── 지형: Natural Earth 의 실제 지리구역 폴리곤 ──
    # 표고 래스터 대신 이름 붙은 구역(秦嶺·大別山·華北平原·四川盆地·黃土高原·河西走廊…)을 쓴다.
    # 지형선 자체는 아니지만 사료 태그로 찍는 네모 얼룩과 달리 실제 경계를 따른다.
    region = np.full((n, n), -1, dtype=np.int16)
    names = []
    geo = f'{NE}/ne_10m_geography_regions_polys.geojson'
    if os.path.exists(geo):
        feats = json.load(open(geo))['features']
        for cls, value in REGION_TERRAIN:
            for f in feats:
                pr = f['properties']
                if pr.get('FEATURECLA') != cls:
                    continue
                m = rasterize([f], proj, bbox) & land
                if not m.any():
                    continue
                terrain[m] = value
                region[m] = len(names)
                names.append({'name': pr.get('NAME_EN') or pr.get('NAME'),
                              'zh': pr.get('NAME_ZHT') or pr.get('NAME_ZH'), 'cls': cls})

    terrain[rasterize(json.load(open(f'{NE}/ne_50m_lakes.geojson'))['features'], proj, bbox)] = LAKE
    stroke_lines(terrain, proj, f'{NE}/ne_50m_rivers_lake_centerlines.geojson', bbox, RIVER)

    # ── 사료가 산·관문을 말한 곳은 지리구역이 놓쳤어도 산지로 올린다 ──
    # 郡國志의 有X山·有X關 주석. 평지로 남은 셀만 올려 실제 구역 경계를 덮지 않는다.
    seeds = []
    if os.path.exists(JUNGUO):
        for jun in json.load(open(JUNGUO))['places']:
            for c in jun['counties']:
                if c.get('lat') and any(t in ('MOUNTAIN', 'PASS') for t in c.get('tags', [])):
                    seeds.append(proj.to_cell(c['lon'], c['lat']))
    for gx, gy in seeds:
        x, y = int(gx), int(gy)
        sub = terrain[max(0, y - 1):y + 2, max(0, x - 1):x + 2]
        sub[sub == PLAIN] = HILL

    # ── 소유: 각 육지 셀을 가장 가까운 縣에 ──
    pts = np.array([[p['gx'], p['gy']] for p in hp['places']], dtype=float)
    ys, xs = np.nonzero(terrain != SEA)
    owner = np.full((n, n), -1, dtype=np.int16)
    for s in range(0, len(xs), 4096):                        # 65k×1.1k 를 한 번에 잡으면 메모리가 터진다
        cx, cy = xs[s:s + 4096], ys[s:s + 4096]
        d = (cx[:, None] - pts[None, :, 0]) ** 2 + (cy[:, None] - pts[None, :, 1]) ** 2
        owner[cy, cx] = d.argmin(axis=1)

    # ── 도로: 郡治(level>=6) 사이 ──
    hubs = [i for i, p in enumerate(hp['places']) if p.get('level', 5) >= 6]
    roads = []
    for i, j in gabriel(pts[hubs]):
        src, dst = hubs[i], hubs[j]
        sea = _sea_run(terrain, pts[src], pts[dst])
        # 순수 기하 그래프는 발해를 육로로 잇는다. 바다를 2셀 넘게 지나면 육로가 아니다 —
        # 버리지 않고 해로로 표시한다. 후한도 요동을 뱃길로 오갔다.
        roads.append({'a': src, 'b': dst, 'kind': 'SEA' if sea > 2 else 'LAND',
                      'len': round(float(np.hypot(*(pts[src] - pts[dst]))), 2)})

    legend = ['SEA', 'PLAIN', 'MOUNTAIN', 'RIVER', 'LAKE', 'DESERT', 'PLATEAU', 'BASIN', 'HILL']
    counts = {name: int((terrain == i).sum()) for i, name in enumerate(legend)}
    json.dump({
        'grid': n, 'year': hp.get('year'), 'projection': hp['projection'],
        'legend': {str(i): name for i, name in enumerate(legend)},
        'counts': counts, 'terrain': terrain.tolist(), 'owner': owner.tolist(),
        'region': region.tolist(), 'regionNames': names,
        'hubs': hubs, 'roads': roads,
        'source': 'Natural Earth 50m (public domain) + CHGIS V6 좌표(재배포 금지, ADR-LITE-039)',
    }, open(OUT, 'w'), separators=(',', ':'))

    print(f'육지 {land_cells} 셀 · ' + ' · '.join(f'{k} {v}' for k, v in counts.items()),
          file=sys.stderr)
    land_roads = sum(1 for r in roads if r['kind'] == 'LAND')
    print(f'郡治 {len(hubs)} · 육로 {land_roads} · 해로 {len(roads) - land_roads} → {OUT}',
          file=sys.stderr)

    if a.preview:
        preview(terrain, pts, hubs, roads, n)


def preview(terrain, pts, hubs, roads, n):
    from PIL import Image, ImageDraw
    pal = {SEA: (38, 62, 92), PLAIN: (126, 143, 92), MOUNTAIN: (108, 96, 84),
           RIVER: (74, 122, 158), LAKE: (60, 100, 140), DESERT: (196, 178, 130),
           PLATEAU: (150, 132, 104), BASIN: (140, 152, 104), HILL: (132, 130, 92)}
    scale = 4
    img = Image.new('RGB', (n, n))
    img.putdata([pal[v] for v in terrain.reshape(-1)])
    img = img.resize((n * scale, n * scale), Image.NEAREST)
    d = ImageDraw.Draw(img)
    for r in roads:
        (x1, y1), (x2, y2) = pts[r['a']], pts[r['b']]
        d.line([(x1 * scale, y1 * scale), (x2 * scale, y2 * scale)],
               fill=(110, 180, 200) if r['kind'] == 'SEA' else (196, 168, 112), width=2)
    for i, (x, y) in enumerate(pts):
        big = i in hubs
        r = 4 if big else 1
        d.ellipse([x * scale - r, y * scale - r, x * scale + r, y * scale + r],
                  fill=(230, 90, 70) if big else (225, 215, 195))
    out = 'data/map/preview.png'
    img.save(out)
    print(f'미리보기 → {out}', file=sys.stderr)


if __name__ == '__main__':
    main()
