#!/usr/bin/env python3
"""후한 군현 지도의 지형 격자를 만든다 — 사료 좌표 + Natural Earth 해안선.

`data/map/han-places.json` 이 저장한 등적 투영을 **역산**해 canonical
768×669 각 셀의 실제
경위도를 구하고, Natural Earth(public domain) 로 바다·호수·하천을 굽는다. 그 다음
각 육지 셀을 가장 가까운 縣에 배정(보로노이)하고, 郡治 사이의 도로를 Gabriel 그래프로
유도한다. 인접을 손으로 적지 않는다 — 좌표가 이미 그것을 말하고 있다.

산출 `data/map/terrain-grid.json` (미커밋, ADR-LITE-039):
    terrain  projection rows×cols  0 바다 · 1 평지 · 2 산지 · 3 하천 · 4 호소
    owner    projection rows×cols  각 셀의 縣 인덱스(-1 = 바다)
    roads    郡治 간 간선 목록

용법:  python3 tools/map/build_terrain_grid.py [--grid 768] [--preview]

`--grid`는 새 투영을 만드는 옵션이 아니라 상류 `han-places.json`의 projection
cols를 확인하는 계약이다. 비정본 연구 격자는 같은 값을 명시해 사용한다.
"""
import argparse, heapq, json, math, os, sys
from collections import Counter, deque
from dataclasses import asdict
import numpy as np

try:
    from tools.map.han_place_merge_runtime import apply_reviewed_merges
    from tools.map.han_temporal_parent_runtime import (
        ReviewedTemporalSeedOverride,
        apply_reviewed_temporal_parents,
        load_reviewed_temporal_parent_context,
    )
    from tools.map.province_quality import (
        ProvinceQualityPolicy,
        measure_province_shapes,
        validate_province_quality,
    )
    from tools.map.external_province_systems import load_external_province_displays
    from tools.map.world_province_geometry import (
        ParentRegionRecord,
        ProvinceSeed,
        build_province_geometry,
    )
except ModuleNotFoundError:  # pragma: no cover - direct script compatibility
    from han_place_merge_runtime import apply_reviewed_merges
    from han_temporal_parent_runtime import (
        ReviewedTemporalSeedOverride,
        apply_reviewed_temporal_parents,
        load_reviewed_temporal_parent_context,
    )
    from province_quality import (
        ProvinceQualityPolicy,
        measure_province_shapes,
        validate_province_quality,
    )
    from external_province_systems import load_external_province_displays
    from world_province_geometry import (
        ParentRegionRecord,
        ProvinceSeed,
        build_province_geometry,
    )

PLACES = 'data/map/han-places.json'
JUNGUO = 'data/map/junguozhi.json'
NE = 'data/natural-earth'
OUT = 'data/map/terrain-grid.json'
MODERN_ADMIN = 'data/modern-admin/geoBoundaries-CGAZ-ADM2.geojson'
PROVINCE_SHAPE_EXCEPTIONS = 'data/curated/map/province-shape-exceptions-v1.json'
LEGACY_GAMEPLAY_TILES = 'data/map/han-780-v1-tiles.json'

SEA, PLAIN, MOUNTAIN, RIVER, LAKE, DESERT, PLATEAU, BASIN, HILL = range(9)

# 1급(郡治) 치소 kind. 郡/國은 같은 lv6 치소다 — 郡國志 卷113 「凡郡、國百五」.
# 尹·翊·風(三輔)은 郡의 장관 관직명일 뿐이라 COMMANDERY 로 합류한다(卷117 百官志
# 「司隸所部郡七…更以河南郡爲尹」). build_han_places.py/build_external_places.py
# 의 kind 값과 맞춘다.
SEAT_KINDS = {'COMMANDERY', 'KINGDOM'}

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

    def __init__(self, p, n=None):
        self.k, self.x0, self.y1 = p['k'], p['x0'], p['y1']
        self.pad, self.cell = p['pad'], p['cell']
        self.cols, self.rows = p['cols'], p['rows']

    def to_cell(self, lon, lat):
        gx = (lon * self.k - self.x0 + self.pad) / self.cell
        gy = (self.y1 + self.pad - lat) / self.cell
        return gx, gy

    def cell_lonlat(self, gx, gy):
        """셀 중심(+0.5)의 경위도. 격자→지리 방향이라 래스터라이즈에 이걸 쓴다."""
        lon = ((gx + 0.5) * self.cell - self.pad + self.x0) / self.k
        lat = self.y1 + self.pad - (gy + 0.5) * self.cell
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
    img = Image.new('L', (proj.cols, proj.rows), 0)
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


def rasterize_feature_labels(features, proj, bbox):
    """Rasterize non-overlapping CGAZ polygons to one stable feature-index grid."""
    from PIL import Image, ImageDraw
    img = Image.new('I', (proj.cols, proj.rows), 0)
    draw = ImageDraw.Draw(img)
    holes = []
    for feature_index, feature in enumerate(features):
        for ring, is_hole in poly_rings(feature['geometry'], bbox):
            gx, gy = proj.to_cell(ring[:, 0], ring[:, 1])
            points = [(float(x), float(y)) for x, y in
                      zip(np.clip(gx, -1e4, 1e4), np.clip(gy, -1e4, 1e4))]
            if is_hole:
                holes.append(points)
            else:
                draw.polygon(points, fill=feature_index + 1)
    for points in holes:
        draw.polygon(points, fill=0)
    return np.asarray(img, dtype=np.int32) - 1


def _zone_distances(zone_grid, seed_zones):
    """Return deterministic adjacency distance from any zone containing a seed."""
    zones = sorted(int(value) for value in np.unique(zone_grid) if value >= 0)
    adjacent = {zone: set() for zone in zones}
    for left, right in ((zone_grid[:, :-1], zone_grid[:, 1:]),
                        (zone_grid[:-1, :], zone_grid[1:, :])):
        pairs = np.stack((left.ravel(), right.ravel()), axis=1)
        for a, b in np.unique(pairs, axis=0):
            a, b = int(a), int(b)
            if a >= 0 and b >= 0 and a != b:
                adjacent[a].add(b)
                adjacent[b].add(a)
    distance = {zone: 1_000_000 for zone in zones}
    queue = deque()
    for zone in sorted(seed_zones):
        if zone in distance:
            distance[zone] = 0
            queue.append(zone)
    while queue:
        zone = queue.popleft()
        for neighbor in sorted(adjacent[zone]):
            if distance[neighbor] > distance[zone] + 1:
                distance[neighbor] = distance[zone] + 1
                queue.append(neighbor)
    return distance


def _split_large_zones(zone_grid, max_cells=600):
    """Split oversized fallback zones into connected, compact BFS catchments."""
    output = np.full(zone_grid.shape, -1, dtype=np.int32)
    provenance = []
    next_zone = 0
    rows, cols = zone_grid.shape
    for source_zone in sorted(int(value) for value in np.unique(zone_grid) if value >= 0):
        remaining = zone_grid == source_zone
        coordinates = np.argwhere(remaining)
        height, width = np.ptp(coordinates, axis=0) + 1
        aspect_parts = math.ceil((max(height, width) / min(height, width)) / 4.0)
        desired_parts = max(math.ceil(len(coordinates) / max_cells), aspect_parts)
        local_cap = min(max_cells, math.ceil(len(coordinates) / desired_parts))
        part = 0
        while np.any(remaining):
            start_row, start_col = map(int, np.argwhere(remaining)[0])
            queue = deque([(start_row, start_col)])
            queued = {(start_row, start_col)}
            cells = []
            while queue and len(cells) < local_cap:
                row, col = queue.popleft()
                if not remaining[row, col]:
                    continue
                cells.append((row, col))
                for next_row, next_col in (
                    (row - 1, col), (row, col - 1),
                    (row, col + 1), (row + 1, col),
                ):
                    candidate = (next_row, next_col)
                    if (0 <= next_row < rows and 0 <= next_col < cols
                            and remaining[next_row, next_col]
                            and candidate not in queued):
                        queued.add(candidate)
                        queue.append(candidate)
            for row, col in cells:
                output[row, col] = next_zone
                remaining[row, col] = False
            provenance.append((source_zone, part))
            next_zone += 1
            part += 1
    return output, provenance


def _assign_unowned_islands(parent_owner, political_land, places, jun_of):
    """Give every disconnected land component the nearest reviewed parent polity."""
    effective = parent_owner.copy()
    effective[~political_land] = -1
    missing = political_land & (effective < 0)
    seen = np.zeros(missing.shape, dtype=bool)
    rows, cols = missing.shape
    sources = [
        (int(place['gy']), int(place['gx']), int(jun_of[index]))
        for index, place in enumerate(places)
    ]
    for start_row, start_col in np.argwhere(missing):
        start_row, start_col = int(start_row), int(start_col)
        if seen[start_row, start_col]:
            continue
        component = []
        queue = deque([(start_row, start_col)])
        seen[start_row, start_col] = True
        while queue:
            row, col = queue.popleft()
            component.append((row, col))
            for next_row, next_col in (
                (row - 1, col - 1), (row - 1, col), (row - 1, col + 1),
                (row, col - 1), (row, col + 1),
                (row + 1, col - 1), (row + 1, col), (row + 1, col + 1),
            ):
                if (0 <= next_row < rows and 0 <= next_col < cols
                        and missing[next_row, next_col] and not seen[next_row, next_col]):
                    seen[next_row, next_col] = True
                    queue.append((next_row, next_col))
        center_row = sum(row for row, _ in component) / len(component)
        center_col = sum(col for _, col in component) / len(component)
        parent_index = min(
            sources,
            key=lambda source: (
                (source[0] - center_row) ** 2 + (source[1] - center_col) ** 2,
                source[2],
            ),
        )[2]
        for row, col in component:
            effective[row, col] = parent_index
    return effective


def build_world_provinces(terrain, proj, bbox, places, place_ids, jun_of, jun_names,
                          seat_owner, modern_features):
    """Intersect pinned ADM2 geometry with reviewed 220 parent ownership."""
    labels = rasterize_feature_labels(modern_features, proj, bbox)
    parent_count = len(jun_names)
    political_land = (terrain != SEA) & (terrain != LAKE)
    parent_owner = _assign_unowned_islands(
        seat_owner, political_land, places, jun_of
    )
    # A zone is one modern ADM2 polygon clipped by one reviewed historical parent.
    zone_grid = np.where(
        political_land & (parent_owner >= 0) & (labels >= 0),
        labels * parent_count + parent_owner,
        -1,
    ).astype(np.int32)
    # Coastal simplification gaps remain geometry-derived fallback zones per parent.
    gap_base = len(modern_features) * parent_count
    gaps = political_land & (parent_owner >= 0) & (labels < 0)
    zone_grid[gaps] = gap_base + parent_owner[gaps]
    zone_grid, zone_provenance = _split_large_zones(zone_grid)

    external_displays = load_external_province_displays()
    parent_records = []
    for parent_index, name in enumerate(jun_names):
        members = [places[index] for index, value in enumerate(jun_of)
                   if int(value) == parent_index]
        systems = {
            external_displays[member['id']].administrative_system
            for member in members if member.get('id') in external_displays
            and external_displays[member['id']].review_state == 'APPROVED'
        }
        external = bool(members) and all(member.get('kind') == 'EXTERNAL_PLACE' for member in members)
        parent_system = next(iter(systems)) if len(systems) == 1 else (
            'EXTERNAL_POLITY' if external else 'HAN_COMMANDERY'
        )
        parent_records.append(ParentRegionRecord(
            id=f'PARENT-{parent_index:04d}', display_name=name,
            administrative_system=parent_system,
            name_ch=name,
        ))
    province_seeds = []
    occupied_seed_cells = set()
    for index, (place, place_id) in enumerate(zip(places, place_ids)):
        # Jun/kingdom/province seats are parent metadata and map markers, not
        # county-level province seeds.  Keeping both levels as seeds creates
        # duplicate pseudo-provinces at co-located seats.
        if place.get('kind') not in {'COUNTY', 'EXTERNAL_PLACE'}:
            continue
        parent_index = int(jun_of[index])
        external = place.get('kind') == 'EXTERNAL_PLACE'
        display = external_displays.get(place_id)
        seed_row, seed_col = int(place['gy']), int(place['gx'])
        if not (0 <= seed_row < zone_grid.shape[0] and 0 <= seed_col < zone_grid.shape[1]
                and zone_grid[seed_row, seed_col] >= 0
                and (seed_row, seed_col) not in occupied_seed_cells):
            candidates = np.argwhere(
                (parent_owner == parent_index) & political_land & (zone_grid >= 0)
            )
            candidates = [
                cell for cell in candidates
                if (int(cell[0]), int(cell[1])) not in occupied_seed_cells
            ]
            if len(candidates):
                seed_row, seed_col = map(int, min(
                    candidates,
                    key=lambda cell: (
                        (int(cell[0]) - seed_row) ** 2 + (int(cell[1]) - seed_col) ** 2,
                        int(cell[0]), int(cell[1]),
                    ),
                ))
        occupied_seed_cells.add((seed_row, seed_col))
        province_seeds.append(ProvinceSeed(
            id=place_id,
            display_name=(display.canonical_name if display else
                          place.get('nameFt') or place.get('nameCh') or place_id),
            name_ch=(display.name_ch if display else place.get('nameCh') or ''),
            administrative_system=(display.administrative_system if display else
                                   'EXTERNAL_POLITY' if external else 'HAN_COMMANDERY'),
            kind=('SETTLEMENT' if external else 'COUNTY'),
            parent_region_id=f'PARENT-{parent_index:04d}',
            row=seed_row, col=seed_col, city_index=index,
            geometry_basis='HISTORICAL_SEAT_ADAPTED',
            confidence=(display.confidence if display else 'REVIEWED'),
        ))
    seed_zones = {
        int(zone_grid[seed.row, seed.col]) for seed in province_seeds
        if 0 <= seed.row < zone_grid.shape[0] and 0 <= seed.col < zone_grid.shape[1]
        and zone_grid[seed.row, seed.col] >= 0
    }
    distances = _zone_distances(zone_grid, seed_zones)
    admin_rows = []
    for zone in sorted(distances):
        source_zone, part_index = zone_provenance[zone]
        if source_zone >= gap_base:
            parent_index = source_zone - gap_base
            source_id = f'TERRAIN-GAP-{parent_index:04d}'
        else:
            feature_index, parent_index = divmod(source_zone, parent_count)
            properties = modern_features[feature_index].get('properties') or {}
            source_id = f"{properties.get('shapeID') or feature_index}-P{parent_index:04d}"
        source_id = f'{source_id}-S{part_index:03d}'
        admin_rows.append({
            'id': source_id,
            'parentRegionId': f'PARENT-{parent_index:04d}',
            'mask': (zone_grid, zone),
            'order': distances[zone],
            'mergeAreaCap': 1100,
        })
    result = build_province_geometry(
        terrain, proj, province_seeds, parent_records, admin_rows, {}
    )
    uncovered = political_land & (result.owner < 0)
    if np.any(uncovered):
        raise ValueError(f'world province build left {int(uncovered.sum())} land cells uncovered')
    quality = measure_province_shapes(result.owner, result.province_records)
    exception_document = json.load(open(PROVINCE_SHAPE_EXCEPTIONS))
    validate_province_quality(
        quality,
        ProvinceQualityPolicy(
            max_components=32,
            max_aspect_ratio=5.0,
            min_aspect_area=32,
            min_fill_ratio=0.10,
            min_fill_area=100,
            max_area=1250,
            max_parent_median_ratio=1_000.0,
            corridor_min_length=10_000,
        ),
        exception_document['exceptions'],
        map_version='han-world-v2',
    )
    return result, quality, parent_owner


def stroke_lines(grid, proj, path, bbox, value, only_land=True):
    """하천은 선이라 채울 수 없다. 변을 촘촘히 샘플링해 지나는 셀을 찍는다."""
    lo_lon, hi_lon, lo_lat, hi_lat = bbox
    h, w = grid.shape
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
                ok = (xs >= 0) & (xs < w) & (ys >= 0) & (ys < h)
                xs, ys = xs[ok], ys[ok]
                if only_land:
                    ok = grid[ys, xs] != SEA                 # 하천이 바다를 덮으면 해안이 망가진다
                    xs, ys = xs[ok], ys[ok]
                grid[ys, xs] = value


# ── 도로: 지형 통행비용 위의 최소비용 경로 ──────────────────────────────────
# 이전 판은 郡治 점들의 Gabriel 그래프를 직선으로 이었다. 지형을 아예 안 봐서 秦嶺·大巴山을
# 자로 그은 듯 관통하고, 강만 건너도 해로로 찍혔다. 실제 후한 도로는 잔도와 협곡을 따라
# 났다 — 관중과 한중은 陳倉道·褒斜道 같은 몇 갈래로만 통했다. 그래서 그래프가 아니라
# 비용을 고친다. 지형에 통행비용을 주고 최소비용 경로를 뽑으면 길이 알아서 고개를 찾는다.
#
# 비용은 셀 하나를 지나는 값이다. 평지 10 을 1일 행군으로 읽으면 산지는 5배 든다.
# 하천은 도하 지점이 드물다는 뜻이고, 호수·바다는 육로가 아예 못 지난다.
INF = float('inf')
LAND_COST = {PLAIN: 10, HILL: 18, BASIN: 13, PLATEAU: 24, DESERT: 34,
             RIVER: 90, MOUNTAIN: 120, LAKE: INF, SEA: INF}
# 수로. 漕運은 육운보다 쌌다 — 하천을 바다보다 싸게 매겨 내륙 물길이 먼저 잡히게 한다.
# 바다·강·호수를 한 망으로 둔다. 실제로도 조운은 강에서 운하로, 다시 연안으로 이어졌다.
WATER_COST = {RIVER: 8, LAKE: 10, SEA: 12}
# 길이 상한(셀). 빈 땅에서는 縣 하나의 통행권이 수백 km 로 뻗어 사막을 가로지르는
# 가짜 간선이 생긴다. 셀 하나가 약 5km 이니 지선 250km · 간선 800km 를 넘으면 길이 아니다.
MAX_CELLS = {'LOCAL': 24, 'MAIN': 160, 'WATER': 200}
DIAG = [(-1, -1, 1.414), (0, -1, 1.0), (1, -1, 1.414), (-1, 0, 1.0),
        (1, 0, 1.0), (-1, 1, 1.414), (0, 1, 1.0), (1, 1, 1.414)]


def cost_field(terrain, table):
    c = np.full(terrain.shape, INF)
    for t, v in table.items():
        c[terrain == t] = v
    return c


def multi_dijkstra(cost, sources):
    """모든 출발점에서 동시에 번진다. dist 와 어느 출발점 소속인지(label)를 함께 낸다.

    라벨이 만나는 자리가 곧 두 郡治의 통행 경계다 — 직선거리 보로노이가 아니라
    실제 통행비용 보로노이라 산맥이 경계를 갈라준다.
    """
    h, w = cost.shape
    dist = np.full((h, w), INF)
    label = np.full((h, w), -1, dtype=np.int32)
    parent = np.full((h, w, 2), -1, dtype=np.int32)
    pq = []
    for k, (x, y) in enumerate(sources):
        if cost[y, x] == INF:                     # 항구·섬 治所가 바다칸에 걸린 경우
            continue
        dist[y, x] = 0.0
        label[y, x] = k
        heapq.heappush(pq, (0.0, x, y))
    while pq:
        d, x, y = heapq.heappop(pq)
        if d > dist[y, x]:
            continue
        for dx, dy, w8 in DIAG:
            nx, ny = x + dx, y + dy
            if not (0 <= nx < w and 0 <= ny < h):
                continue
            c = cost[ny, nx]
            if c == INF:
                continue
            nd = d + c * w8
            if nd < dist[ny, nx]:
                dist[ny, nx] = nd
                label[ny, nx] = label[y, x]
                parent[ny, nx] = (x, y)
                heapq.heappush(pq, (nd, nx, ny))
    return dist, label, parent


def touching_pairs(label):
    """라벨이 맞닿는 이웃쌍 = 인접한 郡治. 맞닿은 셀 수도 함께 센다."""
    pairs = Counter()
    for a, b in ((label[:, :-1], label[:, 1:]), (label[:-1, :], label[1:, :])):
        m = (a >= 0) & (b >= 0) & (a != b)
        for u, v in zip(a[m].tolist(), b[m].tolist()):
            pairs[(u, v) if u < v else (v, u)] += 1
    return pairs


def astar(cost, start, goal):
    """최소비용 경로의 셀 목록. 휴리스틱은 남은 칸수 × 최저비용이라 최적성을 깨지 않는다."""
    h, w = cost.shape
    cheapest = float(np.min(cost[np.isfinite(cost)]))
    (sx, sy), (gx, gy) = start, goal
    if cost[sy, sx] == INF or cost[gy, gx] == INF:
        return None
    g = {(sx, sy): 0.0}
    prev = {}
    pq = [(0.0, 0.0, sx, sy)]
    while pq:
        _, gc, x, y = heapq.heappop(pq)
        if (x, y) == (gx, gy):
            path = [(x, y)]
            while (x, y) in prev:
                x, y = prev[(x, y)]
                path.append((x, y))
            return path[::-1]
        if gc > g.get((x, y), INF):
            continue
        for dx, dy, w8 in DIAG:
            nx, ny = x + dx, y + dy
            if not (0 <= nx < w and 0 <= ny < h):
                continue
            c = cost[ny, nx]
            if c == INF:
                continue
            ng = gc + c * w8
            if ng < g.get((nx, ny), INF):
                g[(nx, ny)] = ng
                prev[(nx, ny)] = (x, y)
                heapq.heappush(pq, (ng + max(abs(gx - nx), abs(gy - ny)) * cheapest, ng, nx, ny))
    return None


def walk_back(parent, cell):
    """부모 포인터를 따라 출발점까지 되짚는다. 다익스트라가 이미 최소비용 경로를 남겨놨다."""
    path = [cell]
    x, y = cell
    while True:
        px, py = parent[y, x]
        if px < 0:
            return path
        x, y = int(px), int(py)
        path.append((x, y))


def boundary_edges(dist, label, min_touch):
    """라벨이 맞닿는 쌍마다 가장 싸게 넘어가는 경계 셀 한 쌍을 고른다.

    두 영역이 만나는 자리 중 dist 합이 최소인 곳이 곧 두 治所를 잇는 최소비용 경로가
    지나는 자리다. 그 셀에서 양쪽으로 부모를 되짚으면 A* 를 다시 돌 필요가 없다.
    """
    best, touch = {}, Counter()
    for A, B, off in ((label[:, :-1], label[:, 1:], (1, 0)),
                      (label[:-1, :], label[1:, :], (0, 1))):
        dA = dist[:, :-1] if off[0] else dist[:-1, :]
        dB = dist[:, 1:] if off[0] else dist[1:, :]
        m = (A >= 0) & (B >= 0) & (A != B) & np.isfinite(dA) & np.isfinite(dB)
        ys, xs = np.nonzero(m)
        for y, x in zip(ys.tolist(), xs.tolist()):
            u, v = int(A[y, x]), int(B[y, x])
            key = (u, v) if u < v else (v, u)
            touch[key] += 1
            score = float(dA[y, x] + dB[y, x])
            if key not in best or score < best[key][0]:
                ca, cb = (x, y), (x + off[0], y + off[1])
                best[key] = (score, ca, cb) if u < v else (score, cb, ca)
    return {k: v for k, v in best.items() if touch[k] >= min_touch}


def field_roads(cost, seats, ids, min_touch, kind, tier):
    """한 번의 다익스트라로 인접관계와 경로를 동시에 낸다 — 간선 수가 많을 때 쓴다."""
    dist, label, parent = multi_dijkstra(cost, seats)
    roads = []
    for (i, j), (score, ca, cb) in boundary_edges(dist, label, min_touch).items():
        path = walk_back(parent, ca)[::-1] + walk_back(parent, cb)
        roads.append({'a': ids[i], 'b': ids[j], 'kind': kind, 'tier': tier,
                      'cost': round(score, 1), 'cells': [[x, y] for x, y in path]})
    return roads


def coast_distance(terrain):
    """각 물칸이 뭍에서 몇 칸 떨어졌나. 다중출발 BFS 한 번이면 된다."""
    h, w = terrain.shape
    d = np.full((h, w), 1 << 20, dtype=np.int32)
    land = terrain != SEA
    d[land] = 0
    ys, xs = np.nonzero(land)
    frontier = list(zip(xs.tolist(), ys.tolist()))
    step = 0
    while frontier:
        step += 1
        nxt = []
        for x, y in frontier:
            for dx, dy, _ in DIAG:
                nx, ny = x + dx, y + dy
                if 0 <= nx < w and 0 <= ny < h and d[ny, nx] > step:
                    d[ny, nx] = step
                    nxt.append((nx, ny))
        frontier = nxt
    return d


def nearest(cost, x, y, radius):
    """그 지점에서 가장 가까운 통행 가능 칸. 治所의 외항·나루를 잡는 데 쓴다."""
    h, w = cost.shape
    if cost[y, x] != INF:
        return (x, y)
    for r in range(1, radius + 1):
        for dx in range(-r, r + 1):
            for dy in range(-r, r + 1):
                if max(abs(dx), abs(dy)) != r:
                    continue
                nx, ny = x + dx, y + dy
                if 0 <= nx < w and 0 <= ny < h and cost[ny, nx] != INF:
                    return (nx, ny)
    return None


def build_roads(terrain, pts, hubs):
    """간선(郡治 사이)과 지선(縣 사이). 縣이 건물 슬롯이면 부대는 縣 사이를 행군한다 —
    郡治끼리만 이으면 그 사이 縣들은 길 없는 땅이 된다."""
    src = [(int(pts[i][0]), int(pts[i][1])) for i in hubs]
    land = cost_field(terrain, LAND_COST)
    for (x, y) in pts:                          # 治所 칸 자체는 항상 밟을 수 있어야 한다
        x, y = int(x), int(y)
        if land[y, x] == INF:
            land[y, x] = LAND_COST[PLAIN]
    ldist, label, lpar = multi_dijkstra(land, src)
    roads, land_cost = [], {}
    for (i, j), touch in touching_pairs(label).items():
        if touch < 3:                           # 한두 칸 스치는 건 경계 잡음이지 인접이 아니다
            continue
        path = astar(land, src[i], src[j])
        if path is None or len(path) > MAX_CELLS['MAIN']:
            continue
        c = sum(land[y, x] for x, y in path)
        land_cost[(i, j)] = c
        roads.append({'a': hubs[i], 'b': hubs[j], 'kind': 'LAND', 'tier': 'MAIN',
                      'cost': round(c, 1), 'cells': [[x, y] for x, y in path]})

    # 수로. 물칸에 닿는 治所는 모두 나루를 갖는다 — 郡治만 잇던 예전 판은 長江 연변
    # 縣들이 강을 코앞에 두고도 물길이 없었다.
    water = cost_field(terrain, WATER_COST)
    # 연안 가산. 후한의 항해는 연안항해였다 — 먼바다로 나갈수록 비싸게 만들면 항로가
    # 해안을 따라 붙는다. 균일 비용이면 다익스트라가 먼바다를 지그재그로 가로지른다.
    off = coast_distance(terrain)
    water += np.minimum(off, 24) * 2.0
    ports, port_of = [], []
    for k in hubs:
        px, py = pts[k]
        near = nearest(water, int(px), int(py), 10)
        if near:
            ports.append(near)
            port_of.append(k)
    _, wlabel, _ = multi_dijkstra(water, ports)
    for (i, j) in touching_pairs(wlabel):
        if touching_pairs(wlabel)[(i, j)] < 3:
            continue
        path = astar(water, ports[i], ports[j])
        if path is None or len(path) > MAX_CELLS['WATER']:
            continue
        roads.append({'a': port_of[i], 'b': port_of[j], 'kind': 'WATER', 'tier': 'WATER',
                      'cost': round(sum(water[y, x] for x, y in path), 1),
                      'cells': [[x, y] for x, y in path]})

    return thin(dedupe(roads)), label


def dedupe(roads, keep=0.3):
    """이미 깔린 길 위를 덧칠하는 구간을 버린다. 끝점 쌍만 비교하면 A-C 간선이 지나가는
    길에 A-B 가 그대로 겹쳐 깔린다 — 쌍은 달라도 밟는 셀이 같다. 싼 길부터 훑는다."""
    used = {'LAND': set(), 'WATER': set()}
    out = []
    for r in sorted(roads, key=lambda r: r['cost']):
        seen = used[r['kind']]
        cells = [tuple(c) for c in r['cells']]
        if sum(1 for c in cells if c not in seen) / len(cells) < keep:
            continue
        seen.update(cells)
        out.append(r)
    return out


def thin(roads, cap={'MAIN': 4, 'WATER': 3, 'LOCAL': 2}, local_cost=900):
    """길을 줄로 만든다. 인접한 治所를 전부 이으면 델로네 그물이 나오는데, 후한의 縣은
    격자로 엮여 있지 않았다 — 골짜기 길 하나에 줄줄이 꿰여 있었다.

    싼 길부터 훑으며 각 治所의 차수를 상한까지만 채운다. 그러면 길이 끊긴 조각이
    생기므로, 마지막에 조각을 잇는 가장 싼 길만 차수를 무시하고 되살린다(최소신장숲).
    """
    deg, par = Counter(), {}

    def find(a):
        while par.setdefault(a, a) != a:
            par[a] = par[par[a]] = par[par[a]]
            a = par[a]
        return a

    kept, spare = [], []
    for r in sorted(roads, key=lambda r: (r['tier'] != 'MAIN', r['cost'])):
        a, b, k = r['a'], r['b'], r['tier']
        # 縣 사이 지선은 이웃이라는 이유만으로 놓지 않는다. 멀거나 험한 쌍은 길이 아니라
        # 보로노이가 이웃이라고 부른 것뿐이다 — 간선으로 돌아가는 게 실제 통행이었다.
        if k == 'LOCAL' and r['cost'] > local_cost:
            continue
        if deg[(a, k)] >= cap[k] or deg[(b, k)] >= cap[k]:
            spare.append(r)
            continue
        deg[(a, k)] += 1
        deg[(b, k)] += 1
        par[find((a, k))] = find((b, k))
        kept.append(r)
    for r in sorted(spare, key=lambda r: r['cost']):     # 끊긴 조각만 되살린다
        x, y = find((r['a'], r['tier'])), find((r['b'], r['tier']))
        if x == y:
            continue
        par[x] = y
        kept.append(r)
    return kept


def adjacency(label, min_shared_edges=1):
    """소유 격자에서 인접을 뽑는다. 이동은 길이 아니라 영역 인접으로 판정한다 —
    治所를 길로 다 이으면 델로네 그물이 되고, 그건 후한의 도로망이 아니라 삼각분할이다.

    공유한 격자변이 하나라도 있으면 인접한다. 육상 이동의 정본은 별도 육로가
    아니라 이 프로빈스 접경 그래프이므로 짧은 경계를 잡음으로 제거하지 않는다.
    郡 요약 그래프처럼 보조 산출물은 호출자가 더 큰 최소값을 지정할 수 있다.
    """
    return [{'a': a, 'b': b, 'cells': n}
            for (a, b), n in sorted(touching_pairs(label).items()) if n >= min_shared_edges]


def ambiguous_seeds(junguo):
    """두 郡 이상이 물고 있는 좌표. 이름 비정이 한 지점을 나눠 가진 자리다."""
    dup = {}
    for jun in junguo:
        for c in jun['counties']:
            if c.get('lat'):
                dup.setdefault((c['lon'], c['lat']), set()).add(jun['name'])
    return {k for k, v in dup.items() if len(v) > 1}


def fold_to_jun(places, proj, junguo):
    """縣을 郡國志가 적어둔 소속 郡으로 접는다.

    郡 영역을 "가장 가까운 郡治"로 자르면 사료와 절반밖에 안 맞는다 — 郡國志가 河南尹
    소속 縣 16개를 이름으로 다 적어놨는데 거리로 추측하는 꼴이다. 소속은 읽어오고,
    영역은 그 縣들이 가진 셀의 합집합으로 만든다.

    맞춤은 좌표가 먼저다. 郡國志의 縣 이름과 CHGIS 이름은 자주 어긋난다(雒陽/洛陽 같은
    한대-후대 표기 차이). 이름은 같은 거리의 후보를 가를 때만 쓴다.
    """
    import math

    # 이체자. CHGIS 표기와 郡國志 표기가 갈리면 같은 郡이 둘로 쪼개져 서로의 땅에
    # 위요지를 만든다(郁林/鬱林, 河間/河閒). 접기 전에 한 글자로 맞춘다.
    # 이체자 정규화. 鉅=巨(鉅鹿郡), 牁=柯(牂牁郡), 嶲=巂(越巂郡) 를 빼면 같은 郡이
    # CHGIS 표기 차이만으로 둘로 갈라져 지도에 두 번 찍힌다.
    var = str.maketrans('郁閒雒鴈沇涼峕竝恒鬴鉅牁嶲', '鬱間洛雁兗凉時并弘釜巨柯巂')

    def norm(n):
        return (n or '').translate(var).rstrip('縣县國国道邑侯郡尹屬國属国')

    by_name = {}
    for i, pl in enumerate(places):
        by_name.setdefault(norm(pl.get('nameFt') or pl.get('nameCh')), []).append(i)

    # 서로 다른 郡의 縣이 **똑같은** 좌표를 물고 있으면 비정 실패다 — 이름으로 찾다가 한
    # 지점을 둘이 나눠 가진 것이다(常山國 真定 = 中山國 恒山, 鉅鹿郡 章 = 東平國 章).
    # 그대로 접으면 남의 郡 한복판에 위요지가 생긴다. 모르는 것을 소속으로 바꾸지 않는다.
    ambiguous = ambiguous_seeds(junguo)

    jun_of = [-1] * len(places)
    seat_of, names = {}, []
    # 郡國志가 이름으로 적어둔 縣에 실제로 붙은 점. 이 집합만이 "사료에 실린 縣"이고,
    # 나머지 CHGIS 점은 좌표만 있고 소속·이름 근거가 없어 게임 거점으로 못 쓴다.
    zhi: set[int] = set()
    bound = unbound = 0
    for jn, jun in enumerate(junguo):
        names.append(jun['name'])
        for c in jun['counties']:
            if not c.get('lat'):
                continue
            if (c['lon'], c['lat']) in ambiguous:
                continue
            # 郡 점은 縣 후보가 아니다. 이름이 안 맞는 縣을 좌표만 보고 근처 아무 점에나
            # 붙이는 폴백이 郡 점을 집어가면(定襄郡 점이 이웃 郡의 縣이 되는 식) 그 郡은
            # 제 이름으로 승격될 기회를 잃고 지도에서 통째로 사라진다. 郡 자리는 아래
            # 이름 매칭 블록이 따로 잡는다.
            cand = [i for i in by_name.get(norm(c['name']), [])
                    if places[i].get('kind') not in SEAT_KINDS]
            pool = cand if cand else [i for i in range(len(places))
                                      if places[i].get('kind') not in SEAT_KINDS]
            ranked = sorted(
                ((math.hypot((places[i]['lon'] - c['lon']) * math.cos(math.radians(c['lat'])),
                             places[i]['lat'] - c['lat']) * 111.0, i) for i in pool))
            # 동명이인 縣이 흔하다(新安·安陽·豐…). 가장 가까운 후보가 이미 다른 郡에
            # 물려 있으면 다음 후보로 넘어간다 — 먼저 잡은 郡이 이기고 끝내면 뒤에 온
            # 郡은 제 縣을 영영 못 찾는다.
            lim = 120 if cand else 30
            free = next((x for x in ranked if x[0] < lim and jun_of[x[1]] < 0), None)
            best, bd = (free if free else (ranked[0] if ranked else (1e9, -1)))[::-1]
            if best >= 0 and bd < lim:
                bound += 1
                zhi.add(best)
                if jun_of[best] < 0:
                    jun_of[best] = jn
                if c['name'] == jun.get('seat'):
                    seat_of[jn] = best
            else:
                unbound += 1
    print(f'郡國志 소속 결합: 縣 {bound}/{bound + unbound} 결합 · 미결합 {unbound}',
          file=sys.stderr)

    # CHGIS 의 郡 治所는 郡國志에도 같은 이름으로 있다. 이름으로 붙이지 않으면 같은 郡이
    # 둘로 쪼개져 영역이 반씩 갈린다.
    jun_ix = {n: k for k, n in enumerate(names)}
    misplaced = set()
    for i, pl in enumerate(places):
        if jun_of[i] >= 0 or pl.get('kind') not in SEAT_KINDS:
            continue
        # 정확히 같은 이름이 먼저다. norm() 은 꼬리의 郡/國/屬國을 다 떼기 때문에
        # 廣漢屬國 → '廣漢' → 廣漢郡 으로 무너진다. 그러면 屬國은 제 郡을 잃고 모군에
        # 흡수돼 지도에서 사라진다(廣漢·張掖·遼東 세 屬國이 그렇게 없어졌다).
        pn = pl.get('nameFt') or pl.get('nameCh')
        k = jun_ix.get(pn)
        if k is None:
            k = jun_ix.get(norm(pn))
        if k is None:
            for n, kk in jun_ix.items():
                if norm(n) == norm(pn):
                    k = kk
                    break
        if k is None:
            continue
        # CHGIS 의 郡 점이 제 郡의 縣들과 동떨어진 자리에 찍히는 일이 있다(中山郡 점이
        # 常山國 治所 바로 옆에 있다). 그대로 붙이면 남의 郡 한복판에 두 번째 핵이 생겨
        # 위요지가 된다.
        #
        # 판정에 임계값을 쓰지 않는다. 郡國志가 이미 그 郡의 治所 縣을 찾아줬다면
        # CHGIS 의 郡 점은 **필요 없다** — 소속은 사료에서 읽어온다는 이 파일의 원칙
        # 그대로다. 그때 CHGIS 점을 덧대면 핵이 둘이 되고, 그 점이 어긋나 있으면
        # 남의 郡 한복판에 위요지가 된다. 治所를 못 찾은 郡에서만 CHGIS 점이 근거다.
        # 다만 그 治所 縣이 **실제로 이 郡에 붙어 있을 때만** 그렇다. 동명 縣 쟁탈에서
        # 治所를 다른 郡에 뺏겼으면(遼東屬國 治 昌遼가 遼東郡에 먹히는 식) 이 郡은 縣이
        # 하나도 없어 통째로 지워진다. 그때는 CHGIS/외부 점이 유일한 근거다.
        if k in seat_of and jun_of[seat_of[k]] == k:
            # 제 郡이 아니라고 판정했다고 **독립 郡**으로 승격시키면 안 된다. 아래 소국
            # 승격 규칙이 그걸 하려 들기 때문에 여기서 표시해 둔다 — 이 점은 그냥
            # 가장 가까운 郡에 흡수된다.
            misplaced.add(i)
            continue
        jun_of[i] = k

    # 郡國志에 붙지 못한 郡 점은 저마다 郡이 된다 — 兩者다.
    #   · kind=COMMANDERY  : 郡國志에 이름이 없거나(屬國·후한말 이치) 治所 縣이 좌표
    #                        미해결인 정식 郡. 이걸 빼면 遼東屬國처럼 실재한 郡이 지도에서
    #                        사라진다(hub 만 보면 郡治 161 → 118 로 줄었다).
    #   · hub=True         : 漢 밖 소국. level 은 lv4('이') 고정이라 여기 쓰지 않고
    #                        build_external_places.py 의 HUB 표시만 본다.
    #
    # 그 郡의 治所는 곁에 같은 이름의 縣이 있으면 그 縣이다. CHGIS 는 郡 점과 治所 縣
    # 점을 따로 싣기 때문에(樂平郡·樂平縣) 郡 점을 治所로 두면 같은 자리에 점이 둘
    # 찍히고 성 이름이 '樂平郡'이 된다 — 성 이름은 治所 縣 이름이라는 규칙에도 어긋난다.
    def _stem(n):
        return n.rstrip('县縣國国郡州道邑') or n

    twin_of = {}
    for i, pl in enumerate(places):
        if pl.get('kind') == 'COUNTY':
            twin_of.setdefault(_stem(pl.get('nameCh') or ''), []).append(i)
    seated = set(seat_of.values())
    def _twin(i, pl, want_seated):
        """곁(0.25°)에 있는 같은 이름의 縣. want_seated 로 이미 治所인 것만/아닌 것만."""
        return next((j for j in twin_of.get(_stem(pl.get('nameCh') or ''), [])
                     if (j in seated) == want_seated
                     and abs(places[j]['lon'] - pl['lon']) <= 0.25
                     and abs(places[j]['lat'] - pl['lat']) <= 0.25), None)

    for i, pl in enumerate(places):
        if (jun_of[i] < 0 and i not in misplaced and pl.get('kind') != 'PROVINCE'
                and (pl.get('hub') or pl.get('kind') in SEAT_KINDS)):
            # 그 이름의 縣이 이미 다른 郡의 治所라면, 이 CHGIS 郡 점은 그 郡을 다른
            # 이름으로 한 번 더 적은 것이다(蜀郡屬國 = 漢嘉郡, 陳國 = 陳郡). 승격하면
            # 같은 자리에 郡이 둘 생긴다 — 승격하지 말고 그 郡에 흡수시킨다.
            dup = _twin(i, pl, True)
            if dup is not None:
                jun_of[i] = jun_of[dup]
                continue
            jun_of[i] = len(names)
            names.append(pl.get('nameFt') or pl.get('nameCh'))
            seat_of[jun_of[i]] = i
            twin = _twin(i, pl, False)
            if twin is not None:
                jun_of[twin] = jun_of[i]
                seat_of[jun_of[i]] = twin
                seated.add(twin)
    # 郡國志가 잡아준 治所가 CHGIS 의 郡 점인 경우도 있다(陳國 治가 '陳郡' 점으로 잡혔다).
    # 그러면 성 이름이 '陳郡'이 되고 바로 옆 陳縣 점과 둘로 보인다. 같은 이름의 縣이
    # 곁에 있으면 그쪽이 治所다.
    for k, i in list(seat_of.items()):
        if places[i].get('kind') not in SEAT_KINDS:
            continue
        twin = _twin(i, places[i], False)
        if twin is not None:
            jun_of[twin] = k
            seat_of[k] = twin
            seated.discard(i)
            seated.add(twin)

    # 郡國志에 없는 縣(CHGIS 에만 있는 것)은 독립 郡으로 두지 않는다 — 그러면 郡이
    # 900개가 된다. 가장 가까운 소속 縣의 郡에 붙인다.
    known = [i for i, v in enumerate(jun_of) if v >= 0]
    for i, v in enumerate(jun_of):
        if v >= 0:
            continue
        pl = places[i]
        j = min(known, key=lambda k: (places[k]['lon'] - pl['lon']) ** 2
                + (places[k]['lat'] - pl['lat']) ** 2)
        jun_of[i] = jun_of[j]
    # 縣이 하나도 안 붙은 郡(좌표 미해결뿐인 郡)은 지운다. 빈 영역을 남기면 인접 계산이
    # 없는 郡을 이웃으로 센다.
    live = sorted({v for v in jun_of if v >= 0})
    remap = {jn: k for k, jn in enumerate(live)}
    jun_of = [remap[v] for v in jun_of]
    members = {}
    for i, v in enumerate(jun_of):
        members.setdefault(v, []).append(i)
    hubs, out_names = [], []
    for jn in live:
        k = remap[jn]
        seat = seat_of.get(jn)
        hubs.append(seat if seat is not None and jun_of[seat] == k else members[k][0])
        out_names.append(names[jn])
    return np.array(jun_of, dtype=np.int32), hubs, out_names, sorted(zhi)


def align_hubs_to_legacy_gameplay(places, jun_of, hubs, jun_names,
                                  legacy_tiles_path=LEGACY_GAMEPLAY_TILES):
    """Keep the 774-city gameplay seat catalog stable in the v2 geometry.

    Parent boundaries are rebuilt from historical ownership, but the active
    game still uses the immutable han-780-v1 city catalog.  Reuse its reviewed
    seat place IDs when those places remain members of the same parent; new
    external parents retain the seat inferred by ``fold_to_jun``.
    """
    if not os.path.exists(legacy_tiles_path):
        return list(hubs)
    legacy = json.load(open(legacy_tiles_path, encoding='utf-8'))
    legacy_cities = legacy.get('cities')
    legacy_juns = legacy.get('juns')
    if not isinstance(legacy_cities, list) or not isinstance(legacy_juns, list):
        raise ValueError('legacy gameplay tiles must contain cities and juns')
    legacy_seat_by_parent = {}
    for jun in legacy_juns:
        seat = jun.get('seat') if isinstance(jun, dict) else None
        if (type(seat) is int and 0 <= seat < len(legacy_cities)
                and isinstance(legacy_cities[seat], dict)):
            legacy_seat_by_parent[jun.get('nameCh')] = legacy_cities[seat].get('id')
    place_by_id = {place.get('id'): index for index, place in enumerate(places)}
    aligned = list(hubs)
    for parent_index, parent_name in enumerate(jun_names):
        place_index = place_by_id.get(legacy_seat_by_parent.get(parent_name))
        if place_index is not None and int(jun_of[place_index]) == parent_index:
            aligned[parent_index] = place_index
    return aligned


def cross_by_path(cost, pts, edges, terrain):
    """인접한 두 治所를 실제로 오갈 때 강을 건너느냐로 도하를 판정한다.

    경계 셀의 지형으로 보면 거의 걸리지 않는다 — 통행비용 보로노이는 강을 국경으로 삼지
    않고 郡 안을 관통하게 두기 때문이다. 그래서 실제로는 강을 건너야 오가는 郡 쌍인데
    경계는 마른 땅이다. 판정할 것은 국경의 지형이 아니라 통로가 물을 지나느냐다.
    """
    for e in edges:
        a, b = (int(pts[e['a']][0]), int(pts[e['a']][1])), (int(pts[e['b']][0]), int(pts[e['b']][1]))
        path = astar(cost, a, b)
        if path is None:
            e['cross'] = 'NONE'                 # 육로가 아예 없다 — 배로만 간다
            continue
        wet = [(x, y) for x, y in path if terrain[y, x] in (RIVER, LAKE)]
        e['cross'] = 'RIVER' if wet else 'LAND'
        if wet:
            e['ford'] = list(wet[len(wet) // 2])   # 나루는 도하 구간 한가운데다
    return edges


def _boundary_cells(label):
    """라벨이 갈리는 자리와 그 셀 좌표. 어느 지형 위에서 갈리는지 봐야 도하를 알 수 있다."""
    h, w = label.shape
    for y in range(h):
        for x in range(w - 1):
            a, b = int(label[y, x]), int(label[y, x + 1])
            if a >= 0 and b >= 0 and a != b:
                yield (a, b), (x, y)
    for y in range(h - 1):
        for x in range(w):
            a, b = int(label[y, x]), int(label[y + 1, x])
            if a >= 0 and b >= 0 and a != b:
                yield (a, b), (x, y)


def fords(terrain, roads):
    """육로가 하천을 지나는 칸 = 나루터(津). 길이 강에 끊기면 거기가 도하 지점이다.

    실제 후한 지리서도 나루를 따로 적었다 — 孟津·白馬津·延津처럼 이름이 남은 도하점이
    전쟁의 길목이었다. 도로를 다 깐 뒤 지형과 겹쳐 뽑으면 따로 지어낼 필요가 없다.
    """
    seen = Counter()
    for r in roads:
        if r['kind'] != 'LAND':
            continue
        for x, y in r['cells']:
            if terrain[y, x] in (RIVER, LAKE):
                seen[(x, y)] += 1
    return [{'col': x, 'row': y, 'roads': c} for (x, y), c in sorted(seen.items())]


def validate_requested_grid(places_document, requested_grid):
    """Require the CLI grid contract to match the upstream projection exactly."""
    if not isinstance(places_document, dict) or not isinstance(
            places_document.get('projection'), dict):
        raise ValueError('han-places projection object is required')
    projection = places_document['projection']
    cols = projection.get('cols')
    rows = projection.get('rows')
    if isinstance(cols, bool) or not isinstance(cols, int):
        raise ValueError('han-places projection cols must be an integer')
    if cols != requested_grid:
        raise ValueError(
            f'requested grid {requested_grid} does not match han-places projection cols {cols}'
        )
    if places_document.get('grid') != cols or places_document.get('cols') != cols:
        raise ValueError('han-places grid/cols must equal projection cols')
    if places_document.get('rows') != rows:
        raise ValueError('han-places rows must equal projection rows')


def derive_commandery_ownership(
        *, field, places, jun_of, jun_names, junguo, proj,
        temporal_seed_overrides=()):
    """Build commandery Voronoi from already-reviewed physical source labels."""
    cost = np.asarray(field)
    if cost.ndim != 2:
        raise ValueError('commandery ownership cost field must be two-dimensional')
    membership = np.asarray(jun_of)
    if (membership.ndim != 1 or len(membership) != len(places)
            or membership.dtype.kind not in 'iu'):
        raise ValueError('commandery membership must label every physical place')
    if np.any(membership < 0) or np.any(membership >= len(jun_names)):
        raise ValueError('commandery membership contains an unknown jun index')
    if len(set(jun_names)) != len(jun_names):
        raise ValueError('commandery names must be unique')

    reviewed_by_cell, reviewed_by_child = {}, {}
    for override in temporal_seed_overrides:
        if not isinstance(override, ReviewedTemporalSeedOverride):
            raise ValueError('reviewed temporal seed override has an invalid identity')
        place_index = override.place_index
        if not 0 <= place_index < len(places):
            raise ValueError('reviewed temporal seed references an unknown physical place')
        if int(membership[place_index]) != override.parent_index:
            raise ValueError('reviewed temporal seed disagrees with pre-Dijkstra membership')
        place = places[place_index]
        cell = (int(place['gx']), int(place['gy']))
        coordinate = (place.get('lon'), place.get('lat'))
        if not all(type(value) in (int, float) for value in coordinate):
            raise ValueError('reviewed temporal seed requires an exact physical coordinate')
        if cell in reviewed_by_cell or override.junguozhi_child_name in reviewed_by_child:
            raise ValueError('reviewed temporal seed identity must be unique')
        reviewed_by_cell[cell] = override
        reviewed_by_child[override.junguozhi_child_name] = (
            coordinate, cell, override,
        )

    reviewed_consumed = {override.place_index: 0 for override in temporal_seed_overrides}

    ambiguous = ambiguous_seeds(junguo)
    jsrc, jlab = [], []
    jun_name_ix = {name: index for index, name in enumerate(jun_names)}
    for jun in junguo:
        for county in jun['counties']:
            if not county.get('lat') or (county['lon'], county['lat']) in ambiguous:
                continue
            parent_index = jun_name_ix.get(jun['name'])
            if parent_index is None:
                continue
            gx, gy = proj.to_cell(county['lon'], county['lat'])
            cell = (int(gx), int(gy))
            reviewed = reviewed_by_child.get(county.get('name'))
            if reviewed is not None:
                reviewed_coordinate, reviewed_cell, override = reviewed
                if jun.get('name') != override.initial_source_parent_name:
                    raise ValueError(
                        'reviewed temporal seed has a mismatched initial source parent'
                    )
                if (county['lon'], county['lat']) != reviewed_coordinate:
                    raise ValueError('reviewed temporal seed has a mismatched 郡國志 coordinate')
                if not (0 <= cell[1] < cost.shape[0] and 0 <= cell[0] < cost.shape[1]):
                    raise ValueError('reviewed temporal seed projects outside terrain bounds')
                if cell != reviewed_cell:
                    raise ValueError(
                        'reviewed temporal seed projection mismatches its physical cell'
                    )
                reviewed_consumed[override.place_index] += 1
                if reviewed_consumed[override.place_index] != 1:
                    raise ValueError('reviewed temporal seed must be consumed exactly once')
                parent_index = override.parent_index
            elif cell in reviewed_by_cell:
                raise ValueError('conflicting 郡國志 identity occupies a reviewed temporal seed')
            if 0 <= cell[1] < cost.shape[0] and 0 <= cell[0] < cost.shape[1]:
                jsrc.append(cell)
                jlab.append(parent_index)

    if any(count != 1 for count in reviewed_consumed.values()):
        raise ValueError('reviewed temporal seed must be consumed exactly once')

    cell_lab = {}
    for place_index, place in enumerate(places):
        cell = (int(place['gx']), int(place['gy']))
        cell_lab.setdefault(cell, int(membership[place_index]))
    for cell, parent_index in zip(jsrc, jlab):
        cell_lab[cell] = parent_index
    src, lab = list(cell_lab), list(cell_lab.values())
    _, raw, _ = multi_dijkstra(cost, src)
    table = np.array(lab + [-1], dtype=np.int32)
    return table[np.where(raw >= 0, raw, len(lab))].astype(np.int32)


def finalize_reviewed_merge_state(
        *, terrain, places, owner, seat_owner, jun_of, hubs, jun_names,
        zhi_places, ledger=None):
    """Apply stable-ID merges, then derive every graph from compact arrays."""
    state = apply_reviewed_merges(
        places=places,
        owner=owner,
        seat_owner=seat_owner,
        jun_of=jun_of,
        hubs=hubs,
        jun_names=jun_names,
        zhi_places=zhi_places,
        ledger=ledger,
    )
    pts = np.array(
        [[place['gx'], place['gy']] for place in state['places']], dtype=float
    )
    roads, _ = build_roads(terrain, pts, state['hubs'])
    ford_list = fords(terrain, roads)

    land_field = cost_field(terrain, LAND_COST)
    for x, y in pts:
        x, y = int(x), int(y)
        if land_field[y, x] == INF:
            land_field[y, x] = LAND_COST[PLAIN]
    county_edges = adjacency(state['owner'], min_shared_edges=1)
    commandery_edges = cross_by_path(
        land_field,
        pts,
        adjacency(state['seatOwner'], min_shared_edges=3),
        terrain,
    )
    ford_list += [
        {'col': ford[0], 'row': ford[1], 'roads': 0}
        for ford in (edge['ford'] for edge in commandery_edges if 'ford' in edge)
    ]
    state.update({
        'pts': pts,
        'roads': roads,
        'fords': ford_list,
        'adjacency': {
            'county': county_edges,
            'commandery': commandery_edges,
        },
    })
    return state


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument(
        '--grid', type=int, default=768,
        help='verify upstream grid columns (canonical default: 768; explicit alternatives are research/synthetic only)',
    )
    ap.add_argument('--preview', action='store_true', help='PNG 미리보기도 그린다')
    a = ap.parse_args()

    if not os.path.exists(PLACES):
        sys.exit(f'{PLACES} 가 없다. 먼저 tools/map/build_han_places.py 를 돌려라.')
    with open(PLACES, encoding='utf-8') as fh:
        hp = json.load(fh)
    try:
        validate_requested_grid(hp, a.grid)
    except ValueError as exc:
        sys.exit(str(exc))
    try:
        temporal_context = load_reviewed_temporal_parent_context()
    except ValueError as exc:
        sys.exit(str(exc))
    if type(hp.get('year')) is not int or hp['year'] != temporal_context.reference_year:
        sys.exit(
            f"han-places year {hp.get('year')!r} does not match reviewed temporal "
            f"reference year {temporal_context.reference_year}"
        )
    proj = Proj(hp['projection'])
    n, rows = proj.cols, proj.rows

    lo_lon, lo_lat = proj.cell_lonlat(0, rows - 1)
    hi_lon, hi_lat = proj.cell_lonlat(n - 1, 0)
    bbox = (lo_lon - 1, hi_lon + 1, lo_lat - 1, hi_lat + 1)
    print(f'격자 {n}×{rows} · 경도 {lo_lon:.2f}~{hi_lon:.2f} · 위도 {lo_lat:.2f}~{hi_lat:.2f}',
          file=sys.stderr)

    terrain = np.zeros((rows, n), dtype=np.uint8)               # 기본 바다
    land = rasterize(json.load(open(f'{NE}/ne_50m_land.geojson'))['features'], proj, bbox)
    terrain[land] = PLAIN
    land_cells = int(land.sum())

    # ── 지형: Natural Earth 의 실제 지리구역 폴리곤 ──
    # 표고 래스터 대신 이름 붙은 구역(秦嶺·大別山·華北平原·四川盆地·黃土高原·河西走廊…)을 쓴다.
    # 지형선 자체는 아니지만 사료 태그로 찍는 네모 얼룩과 달리 실제 경계를 따른다.
    region = np.full((rows, n), -1, dtype=np.int16)
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

    # ── 소유: 각 육지 셀을 가장 "가까운" 縣에. 직선거리가 아니라 통행비용이다 ──
    # 유클리드로 자르면 경계가 강도 산도 무시한 채 직선으로 뻗는다. 실제 郡界는 능선과
    # 물줄기를 탔다 — 그쪽이 넘어가기 비싼 자리이기 때문이다. 같은 비용장을 쓰면
    # 경계가 저절로 秦嶺·大巴山·長江을 따라간다.
    pts = np.array([[p['gx'], p['gy']] for p in hp['places']], dtype=float)
    field = cost_field(terrain, LAND_COST)
    for (x, y) in pts:
        x, y = int(x), int(y)
        if field[y, x] == INF:
            field[y, x] = LAND_COST[PLAIN]
    _, owner, _ = multi_dijkstra(field, [(int(x), int(y)) for x, y in pts])
    owner = owner.astype(np.int16)

    # ── 郡: 소속은 郡國志에서 읽고, 영역은 그 縣들이 가진 셀의 합집합으로 만든다 ──
    junguo = json.load(open(JUNGUO))['places'] if os.path.exists(JUNGUO) else []
    jun_of, hubs, jun_names, zhi_places = fold_to_jun(hp['places'], proj, junguo)
    temporal = apply_reviewed_temporal_parents(
        places=hp['places'],
        jun_of=jun_of,
        jun_names=jun_names,
        year=hp['year'],
        context=temporal_context,
        require_reference_year=True,
    )
    jun_of = temporal['junOf']
    hubs = align_hubs_to_legacy_gameplay(hp['places'], jun_of, hubs, jun_names)
    # 郡 영역은 縣 소유를 접기만 해선 사료와 100% 안 맞는다. 郡國志가 적은 縣 좌표와
    # CHGIS 좌표가 한두 셀 어긋나면 그 縣이 이웃 郡 땅에 떨어진다. 두 좌표를 **모두**
    # 제 郡의 소스로 넣으면 그 사이가 전부 그 郡이 되어 어긋남이 사라진다.
    # 두 郡의 縣이 **똑같은** 좌표를 물고 있으면 그건 비정 실패다 — 이름으로 찾다가 한
    # 지점을 둘이 나눠 가진 것이다(常山國 真定 = 中山國 恒山). 그 점을 그대로 씨앗으로
    # 쓰면 남의 郡 한복판에 6칸짜리 위요지가 생긴다. 모르는 것을 경계로 바꾸지 않는다.
    seat_label = derive_commandery_ownership(
        field=field,
        places=hp['places'],
        jun_of=jun_of,
        jun_names=jun_names,
        junguo=junguo,
        proj=proj,
        temporal_seed_overrides=temporal['seedOverrides'],
    )
    # All raw physical seeds have now participated in the reviewed temporal Voronoi
    # and historical folding. Apply reviewed merge transforms at this boundary,
    # then derive every downstream index and graph from the compact state.
    merged = finalize_reviewed_merge_state(
        terrain=terrain,
        places=hp['places'],
        owner=owner,
        seat_owner=seat_label,
        jun_of=jun_of,
        hubs=hubs,
        jun_names=jun_names,
        zhi_places=zhi_places,
    )
    pts = merged['pts']
    owner = merged['owner']
    seat_label = merged['seatOwner']
    hubs = merged['hubs']
    jun_of = merged['junOf']
    jun_names = merged['junNames']
    zhi_places = merged['zhiPlaces']
    roads = merged['roads']
    ford_list = merged['fords']
    adj_c = merged['adjacency']['county']
    adj_m = merged['adjacency']['commandery']

    if not os.path.exists(MODERN_ADMIN):
        sys.exit(
            f'{MODERN_ADMIN} 가 없다. tools/map/fetch_modern_admin_boundaries.py 를 먼저 돌려라.'
        )
    modern_features = json.load(open(MODERN_ADMIN, encoding='utf-8'))['features']
    province_result, province_quality, parent_owner = build_world_provinces(
        terrain=terrain, proj=proj, bbox=bbox, places=merged['places'],
        place_ids=merged['placeIds'], jun_of=jun_of, jun_names=jun_names,
        seat_owner=seat_label, modern_features=modern_features,
    )
    owner = province_result.owner.astype(np.int16)
    seat_label = parent_owner.astype(np.int16)
    adj_c = adjacency(owner, min_shared_edges=1)

    province_records = [{
        'id': record.id, 'displayName': record.display_name, 'nameCh': record.name_ch,
        'administrativeSystem': record.administrative_system, 'kind': record.kind,
        'parentRegionId': record.parent_region_id, 'cityIndex': record.city_index,
        'geometryBasis': record.geometry_basis, 'confidence': record.confidence,
    } for record in province_result.province_records]
    parent_regions = [{
        'id': record.id, 'displayName': record.display_name, 'nameCh': record.name_ch,
        'administrativeSystem': record.administrative_system,
    } for record in province_result.parent_regions]
    province_audit = [{
        'kind': decision.kind, 'sourceFeatureId': decision.source_feature_id,
        'provinceIds': list(decision.province_ids),
    } for decision in province_result.audit.decisions]
    province_quality_rows = [asdict(metric) for metric in province_quality.metrics]

    legend = ['SEA', 'PLAIN', 'MOUNTAIN', 'RIVER', 'LAKE', 'DESERT', 'PLATEAU', 'BASIN', 'HILL']
    counts = {name: int((terrain == i).sum()) for i, name in enumerate(legend)}
    json.dump({
        'grid': n, 'cols': n, 'rows': rows, 'year': hp.get('year'), 'projection': hp['projection'],
        'legend': {str(i): name for i, name in enumerate(legend)},
        'counts': counts, 'terrain': terrain.tolist(), 'owner': owner.tolist(),
        'provinceRecords': province_records, 'parentRegions': parent_regions,
        'provinceAudit': province_audit, 'provinceQuality': province_quality_rows,
        'region': region.tolist(), 'regionNames': names,
        'placeIds': merged['placeIds'],
        'hubs': hubs, 'junOf': jun_of.tolist(), 'junNames': jun_names,
        # 郡國志에 이름이 실린 縣(=게임 거점 후보). 나머지 점은 배경이다.
        'zhiPlaces': zhi_places,
        'roads': roads, 'fords': ford_list,
        # 이동 그래프. 길이 아니라 영역 인접이다 — 도로는 간선만 남겨 통행 보정에 쓴다.
        'adjacency': {'county': adj_c, 'commandery': adj_m},
        'seatOwner': seat_label.tolist(),
        'parentOwner': parent_owner.tolist(),
        'source': 'Natural Earth 50m (public domain) + CHGIS V6 좌표(재배포 금지, ADR-LITE-039)',
    }, open(OUT, 'w'), separators=(',', ':'))

    print(f'육지 {land_cells} 셀 · ' + ' · '.join(f'{k} {v}' for k, v in counts.items()),
          file=sys.stderr)
    kinds = Counter(r['kind'] for r in roads)
    ac, am = adj_c, adj_m
    print(f'郡治 {len(hubs)} · 간선 {kinds["LAND"]} · 수로 {kinds["WATER"]} · '
          f'나루터 {len(ford_list)} · 인접 縣 {len(ac)}/郡 {len(am)} '
          f'(도하 {sum(1 for e in am if e["cross"] == "RIVER")}) → {OUT}', file=sys.stderr)

    if a.preview:
        preview(terrain, pts, hubs, roads, seat_label)


def preview(terrain, pts, hubs, roads, seat_label=None):
    from PIL import Image, ImageDraw
    pal = {SEA: (38, 62, 92), PLAIN: (126, 143, 92), MOUNTAIN: (108, 96, 84),
           RIVER: (74, 122, 158), LAKE: (60, 100, 140), DESERT: (196, 178, 130),
           PLATEAU: (150, 132, 104), BASIN: (140, 152, 104), HILL: (132, 130, 92)}
    scale = 4
    rows, n = terrain.shape
    img = Image.new('RGB', (n, rows))
    img.putdata([pal[v] for v in terrain.reshape(-1)])
    img = img.resize((n * scale, rows * scale), Image.NEAREST)
    d = ImageDraw.Draw(img)
    if seat_label is not None:      # 郡 경계 — 이동은 이 영역의 인접으로 판정한다
        e = (seat_label[:, 1:] != seat_label[:, :-1])
        f = (seat_label[1:, :] != seat_label[:-1, :])
        for y, x in zip(*np.nonzero(e)):
            d.rectangle([(x + 1) * scale - 1, y * scale, (x + 1) * scale, (y + 1) * scale],
                        fill=(40, 30, 30))
        for y, x in zip(*np.nonzero(f)):
            d.rectangle([x * scale, (y + 1) * scale - 1, (x + 1) * scale, (y + 1) * scale],
                        fill=(40, 30, 30))
    for r in roads:
        d.line([(x * scale + scale // 2, y * scale + scale // 2) for x, y in r['cells']],
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
