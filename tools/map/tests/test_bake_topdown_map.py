"""탑다운 지도 굽기: 조각 형식 · 한 값 조각 · 개관 최빈 · 프로세스 수 결정성 · 城 겹침 · 구역 덮기 · 관 성벽 틈.

와룡전 캐시 없이 돈다: 시험이 작은 가짜 키트(단색 타일 · 최소 통계)와 작은 설계 층 export 를 만든다.
"""
import gzip
import hashlib
import json
import tempfile
import unittest
from contextlib import contextmanager
from types import SimpleNamespace
from pathlib import Path

import numpy as np
from PIL import Image

from tools.map import bake_topdown_map as B

H, W = 260, 300          # 조각 2×2: (0,0) 256×256 · (1,0) 256×44 · (0,1) 4×256 · (1,1) 4×44
WATER, LAND_A, LAND_B = 40, 0, 1


def make_kit(d: Path) -> None:
    rows, cols = 9, 32
    idx = np.ones((rows * 16, cols * 16), np.uint8)
    for t in (WATER, 41):
        r, c = divmod(t, cols); idx[r * 16:(r + 1) * 16, c * 16:(c + 1) * 16] = 3       # 물 타일(물 비율 1)
    Image.fromarray(idx).save(d / "kit-index.png")
    Image.fromarray(np.stack([idx * 10] * 3, -1)).save(d / "kit.png")
    nxt = iter(range(150, 288))
    ids = lambda keys: {k: next(nxt) for k in keys}
    part = ("tl", "tr", "bl", "br", "wn", "ws", "ww", "we", "gn", "gs", "gw", "ge")
    lookup = dict(
        river=ids(("E", "ES", "ESW", "EW", "N", "NE", "NES", "NESW", "NEW", "NS", "NSW", "NW", "S", "SW", "W")),
        road=ids(("ES", "ESW", "EW", "NE", "NES", "NESW", "NEW", "NS", "NSW", "NW", "SW")),
        bridge=ids(("ferryE", "ferryN", "ferryS", "ferryW", "narrowEW", "narrowNS", "wideE", "wideMid", "wideN", "wideS", "wideW")),
        castle=dict(big=ids(part), small=ids(part), garden=next(nxt), house=next(nxt), infield=[next(nxt), next(nxt)],
                    keep=next(nxt), street=next(nxt)),
        village=next(nxt), roofTiles=[],
        variants=dict(desert={str(LAND_A): 280}, plateau={str(LAND_A): 281}))
    g = lookup["pass"] = {}
    g["NS"] = dict(gate=[next(nxt), next(nxt), next(nxt)], wall=next(nxt), endWest=next(nxt), endEast=next(nxt))
    g["EW"] = dict(gate=[next(nxt), next(nxt), next(nxt)], wall=next(nxt), endNorth=next(nxt), endSouth=next(nxt))
    (d / "catalog.json").write_text(json.dumps(dict(atlasColumns=cols, kit=dict(count=288, lookup=lookup))))
    order = ["full", "n4", "c", "cls8", "c4", "c1"]
    base = {"L": [[LAND_A, 3], [LAND_B, 1]], "W": [[WATER, 1]], "r": [[2, 1]]}
    stats = dict(order=order, terrain={k: {} for k in order}, facet={k: {} for k in order},
                 tileFacet=[[112, "T"], [114, "S"], [122, "E"], [130, "P"]])
    stats["terrain"]["c1"] = dict(base, M=[[112, 1]])
    stats["facet"]["c1"] = dict(base, T=[[112, 1]], S=[[114, 1]], E=[[122, 1]], P=[[130, 1]])
    (d / "synth-stats.json.gz").write_bytes(gzip.compress(json.dumps(stats).encode(), mtime=0))


def make_export(d: Path) -> None:
    ground = np.ones((H, W), np.uint8); ground[:, 200:] = 0; ground[256:, :] = 9
    relief = np.zeros((H, W), np.uint8)
    relief[50:71, 50:58] = 2; relief[50:71, 63:71] = 2           # 관 A 양옆 능선(성벽 끝이 산에 닿는다)
    roads = np.zeros((H, W), np.uint8)
    roads[40:81, 60] = 1                                          # 관 A 를 지나는 남북 길
    roads[50:71, 66] = 1                                          # 관 A 동쪽 능선을 넘는 샛길(PASS_BYPASS_ROAD)
    roads[140:181, 60] = 1                                        # 관 B(능선 없음)를 지나는 남북 길
    roads[120, 30:97] = 1                                         # 城 C 서쪽 성문으로 오는 길
    owner = np.zeros((H, W), np.uint16); owner[:256, :100] = 1; owner[:256, 100:200] = 2
    layers = dict(ground=ground, relief=relief, facets=np.zeros((H, W), np.uint8), landcover=np.zeros((H, W), np.uint8),
                  riverWidth=np.zeros((H, W), np.uint8), riverTier=np.zeros((H, W), np.uint8), roads=roads, owner=owner)
    files = {}
    for k, a in layers.items():
        Image.fromarray(a).save(d / f"map-design-{k}.png")
        blob = (d / f"map-design-{k}.png").read_bytes()
        files[k] = dict(file=f"map-design-{k}.png", dtype=str(a.dtype), sha256=hashlib.sha256(blob).hexdigest(),
                        bytes=len(blob), rawSha256=hashlib.sha256(a.astype("<u2" if a.dtype == np.uint16 else "u1").tobytes()).hexdigest())
    trails = [[(row, 60) for row in range(40, 81)], [(row, 66) for row in range(50, 71)],
              [(row, 60) for row in range(140, 181)], [(120, col) for col in range(30, 97)]]
    edges = [dict(edgeId=f"fixture:{i}", status="BUILT", cells=[[col, row] for row, col in trail])
             for i, trail in enumerate(trails)]
    (d / "map-design-manifest.json").write_text(json.dumps(dict(schemaVersion=2, mapRelease="fixture-map",
        inputFingerprint={}, shape=[H, W], files=files, cityCells={}, roadEdges=edges)))


CITIES = [  # (id, 이름, 등급, 행, 열, provinceId)
    (1, "관A", 3, 60, 60, 0), (2, "관B", 3, 160, 60, 0), (3, "성C", 6, 120, 100, 1), (4, "성D", 5, 124, 104, 1), (5, "현E", 11, 200, 150, 1)]


def make_repo(d: Path) -> dict:
    tiles = dict(
        cities=[dict(id=f"S{c[0]}", row=c[3], col=c[4]) for c in CITIES],
        provinceRecords=[dict(id="P0", displayName="갑구역", jurisdictionId="J0"), dict(id="P1", displayName="을구역", jurisdictionId="J1")],
        jurisdictionRecords=[dict(id="J0", displayName="갑현", kind="COUNTY", commanderyId="PARENT-0000", seatPlaceId="S1"),
                             dict(id="J1", displayName="을현", kind="COUNTY", commanderyId="PARENT-0000", seatPlaceId="S3")],
        commanderyRecords=[dict(id="PARENT-0000", displayName="갑군", kind="COMMANDERY", seatJurisdictionId="J1")],
        parentRegions=[dict(id="PARENT-0000")])
    raw = json.dumps(tiles, ensure_ascii=False).encode()
    world = dict(cities=[dict(id=c[0], name=c[1], level=c[2], provinceId=c[5], spatialProvinceId=f"S{c[0]}", meta=dict(isSeat=c[0] == 3))
                         for c in CITIES])
    docs = dict(hanTiles=raw, world=json.dumps(world, ensure_ascii=False).encode(),
                juIndex=json.dumps(dict(byTerrainSha256={hashlib.sha256(raw).hexdigest(): ["사예"]}), ensure_ascii=False).encode(),
                placements=json.dumps(dict(placements=[dict(cityId=5, to=[201, 151])])).encode(),
                economy=json.dumps(dict(jurisdictions=[dict(cityId=3, households=5000)])).encode(),
                roads=json.dumps(dict(edges=[])).encode(), dem=b"fixture DEM")
    paths = {}
    for k, b in docs.items():
        (d / f"{k}.json").write_bytes(b); paths[k] = d / f"{k}.json"
    return paths


def read_planes(blob: bytes, n=B.CHUNK):
    raw = gzip.decompress(blob)
    tile = np.frombuffer(raw[:n * n * 2], "<u2").reshape(n, n); prov = np.frombuffer(raw[n * n * 2:], "<u2").reshape(n, n)
    return raw, tile, prov


class BakeFixture(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(); root = Path(cls.tmp.name)
        for sub in ("kit", "export", "repo"):
            (root / sub).mkdir()
        make_kit(root / "kit"); make_export(root / "export"); cls.repo = make_repo(root / "repo")
        path = root / "export/map-design-manifest.json"
        manifest = json.loads(path.read_bytes())
        manifest["inputFingerprint"] = dict(hanTilesSha256=B.sha256(cls.repo["hanTiles"].read_bytes()),
            worldJsonSha256=B.sha256(cls.repo["world"].read_bytes()), roadsSha256=B.sha256(cls.repo["roads"].read_bytes()),
            demSha256=B.sha256(cls.repo["dem"].read_bytes()),
            designJsonSha256={"data/curated/han/map-design/fixture.json":B.sha256(cls.repo["placements"].read_bytes())})
        cls.repo["data/curated/han/map-design/fixture.json"] = cls.repo["placements"]
        path.write_text(json.dumps(manifest))
        cls.root = root
        cls.man = B.bake(root / "export", root / "kit", root / "out1", workers=1, log=lambda *_: None, repo=cls.repo)
        cls.out = root / "out1"

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def chunk(self, cx, cy):
        return next(c for c in self.man["chunks"] if (c["cx"], c["cy"]) == (cx, cy))

    def defects(self):
        return json.loads((self.out / "defects.json").read_text())["defects"]


class ChunkFormatTest(BakeFixture):
    def test_chunk_file_is_gzip_mtime0_with_two_little_endian_planes(self):
        ent = self.chunk(1, 0)
        blob = (self.out / ent["file"]).read_bytes()
        self.assertEqual(b"\x00\x00\x00\x00", blob[4:8])                        # gzip mtime = 0
        self.assertEqual(hashlib.sha256(blob).hexdigest(), ent["sha256"])
        raw, tile, prov = read_planes(blob)
        self.assertEqual(2 * 2 * B.CHUNK * B.CHUNK, len(raw))
        self.assertEqual(ent["rawSha256"], hashlib.sha256(raw).hexdigest())
        self.assertEqual(bytes([WATER, 0]), raw[0:2])                            # 첫 칸 = 바다 타일 40, 리틀엔디언
        self.assertEqual(b"\xff\xff", raw[2 * 44:2 * 45])                        # 지도 밖(열 300) = 65535
        self.assertTrue((tile[:, :44] == WATER).all() and (tile[:, 44:] == B.UNDRAWN).all())
        self.assertTrue((prov == 0).all())
        _, tile0, prov0 = read_planes((self.out / self.chunk(0, 0)["file"]).read_bytes())
        self.assertEqual(1, prov0[10, 10]); self.assertEqual(2, prov0[10, 150]); self.assertEqual(0, prov0[10, 220])
        self.assertTrue((tile0[:, :200] != B.UNDRAWN).all())                    # 땅 · 바다는 모두 그린다

    def test_undrawn_chunks_are_uniform_without_file(self):
        for key in ((0, 1), (1, 1)):
            ent = self.chunk(*key)
            self.assertEqual(dict(tile=B.UNDRAWN, province=0), ent["uniform"])
            self.assertNotIn("file", ent)
            self.assertFalse((self.out / f"grid/L0/{key[0]}_{key[1]}.bin.gz").exists())
        self.assertIn("file", self.chunk(0, 0))

    def test_manifest_shape_inputs_and_overview(self):
        m = self.man
        self.assertEqual(dict(cols=W, rows=H), m["shape"])
        self.assertEqual("topdown-bake", m["artifactId"]); self.assertEqual(64, len(m["bakeId"])); self.assertEqual(12, len(m["kitId"]))
        self.assertIn("export/owner", m["inputs"]); self.assertIn("repo/hanTiles", m["inputs"]); self.assertIn("kit/catalog.json", m["inputs"])
        ov = m["overview"]; self.assertEqual((W // 4, H // 4, 4), (ov["cols"], ov["rows"], ov["block"]))
        raw = gzip.decompress((self.out / ov["file"]).read_bytes())
        self.assertEqual(2 * 2 * (W // 4) * (H // 4), len(raw))


class OverviewModeTest(unittest.TestCase):
    def test_block_mode_ignores_undrawn_and_breaks_ties_to_smaller_id(self):
        U = B.UNDRAWN
        tile = np.full((4, 16), U, np.uint16); prov = np.zeros((4, 16), np.uint16)
        tile[:, 0:4] = [[7, 7, 3, 3], [7, 7, 3, 3], [9, 9, 9, 9], [U, U, U, U]]       # 7·3·9 네 번씩 → 3
        tile[:, 4:8] = U; tile[0, 4] = 12                                                 # 65535 15개 + 12 → 12
        tile[:, 12:16] = [[5, 5, 5, 5]] * 4
        prov[:, 0:4] = [[0, 0, 4, 4], [0, 0, 4, 4], [2, 2, 2, 2], [2, 2, 2, 2]]           # 2 여덟 번
        prov[:, 4:8] = [[6, 6, 1, 1], [6, 6, 1, 1], [0, 0, 0, 0], [0, 0, 0, 0]]           # 0 여덟 번(0 도 센다)
        prov[:, 8:12] = [[3, 3, 5, 5], [3, 3, 5, 5], [3, 3, 5, 5], [3, 3, 5, 5]]          # 3·5 동률 → 3
        lt, lp = B.l2_mode(tile, prov)
        self.assertEqual([3, 12, U, 5], lt[0].tolist())
        self.assertEqual([2, 0, 3, 0], lp[0].tolist())


class DeterminismTest(BakeFixture):
    def test_worker_count_does_not_change_the_bake(self):
        man2 = B.bake(self.root / "export", self.root / "kit", self.root / "out2", workers=2, log=lambda *_: None, repo=self.repo)
        strip = lambda m: [{k: v for k, v in c.items()} for c in m["chunks"]]
        self.assertEqual(strip(self.man), strip(man2))
        for k in ("overview", "places", "defects"):
            self.assertEqual(self.man[k]["sha256"], man2[k]["sha256"])
        self.assertEqual(self.man["bakeId"], man2["bakeId"])


class FootprintTest(BakeFixture):
    def test_overlap_resolution_largest_first_shrinks_by_two(self):
        cities = [dict(id=9, level=10, row=50, col=70), dict(id=1, level=9, row=50, col=50), dict(id=2, level=7, row=50, col=58),
                  dict(id=3, level=11, row=50, col=56)]
        fp = B.resolve_footprints(cities)
        self.assertEqual({1: 13, 2: 3, 9: 3, 3: 1}, fp)     # 9칸 城은 13칸 城과 겹쳐 7 → 5 → 3, 1칸은 줄이지 않는다
        self.assertEqual({3: 7, 4: 1}, {k: v for k, v in self.fixture_spans().items() if k in (3, 4)})

    def fixture_spans(self):
        places = json.loads(gzip.decompress((self.out / "places.json.gz").read_bytes()))
        return {c["id"]: c["footprint"]["span"] for c in places["cities"]}

    def test_province_plane_takes_the_castle_province_under_its_footprint(self):
        _, _, prov = read_planes((self.out / self.chunk(0, 0)["file"]).read_bytes())
        self.assertTrue((prov[117:124, 97:104] == 2).all())      # 城 C(구역 1 → 값 2)가 경계(열 100)를 넘는다
        self.assertEqual(1, prov[120, 95]); self.assertEqual(1, prov[116, 98])
        self.assertEqual(1, prov[59, 60])                         # 관 성문 칸: 관 A 의 구역(0 → 1)
        cross = [d for d in self.defects() if d["code"] == "CITY_FOOTPRINT_CROSSES_PROVINCE"]
        self.assertEqual([(3, 21, [1])], [(d["cityId"], d["cells"], d["otherProvinces"]) for d in cross])

    def test_places_schema_footprints_and_sites(self):
        places = json.loads(gzip.decompress((self.out / "places.json.gz").read_bytes()))
        self.assertEqual(2, places["provinceCount"]); self.assertEqual([[0, 0, 0], [1, 0, 0]], places["provinceAdmin"])
        c = {x["id"]: x for x in places["cities"]}
        self.assertEqual(dict(originCol=97, originRow=117, span=7, innerSpan=3), c[3]["footprint"])
        self.assertEqual([100, 120], c[3]["roofCell"]); self.assertEqual("W", c[3]["gates"]); self.assertEqual(5000, c[3]["households"])
        self.assertEqual([151, 201], c[5]["cell"]); self.assertEqual("county", c[5]["site"])          # 옮긴 城, [col,row]
        self.assertIsNone(c[3]["site"]); self.assertIsNone(c[5]["roofCell"])
        self.assertEqual(3, places["commanderies"][0]["seatCityId"]); self.assertEqual(0, places["commanderies"][0]["commanderyNo"])
        labels = {l["id"]: l for l in places["labels"]}
        self.assertEqual(("commanderySeat", 6e5 + 500), (labels["city:3"]["kind"], labels["city:3"]["priority"]))
        self.assertEqual(("pass", 250000), (labels["city:1"]["kind"], labels["city:1"]["priority"]))
        self.assertEqual("ju", labels["ju:0"]["kind"]); self.assertEqual("사예", labels["ju:0"]["text"])


class PassTest(BakeFixture):
    def test_wall_open_is_reported_only_for_the_pass_without_ridges(self):
        opened = [d for d in self.defects() if d["code"] == "PASS_WALL_OPEN"]
        self.assertEqual([2, 2], [d["cityId"] for d in opened])
        self.assertEqual({"limit"}, {d["reason"] for d in opened})
        places = json.loads(gzip.decompress((self.out / "places.json.gz").read_bytes()))
        p = {x["cityId"]: x for x in places["passes"]}
        self.assertEqual("NS", p[1]["orientation"])
        self.assertEqual([[60, 59], [60, 60], [60, 61]], p[1]["gateCells"])
        self.assertEqual([[59, 60], [58, 60], [61, 60], [62, 60]], p[1]["wallCells"])
        self.assertEqual(10, len(p[2]["wallCells"]))

    def test_road_over_the_ridge_is_a_bypass(self):
        bypass = [d for d in self.defects() if d["code"] == "PASS_BYPASS_ROAD"]
        self.assertEqual([(1, [[66, 60]])], [(d["cityId"], d["roadCells"]) for d in bypass])

    def test_wall_open_detection_on_a_window(self):
        K = B.Kit(self.root / "kit")
        h, w = 30, 30
        win = dict(t=np.ones((h, w), np.uint8), relief=np.zeros((h, w), np.uint8), tier=np.zeros((h, w), np.int16),
                   width=np.zeros((h, w), np.int16), landcover=np.zeros((h, w), np.uint8), road=np.zeros((h, w), bool))
        win["road"][:, 15] = True; win["relief"][10:20, 18:22] = 2      # 동쪽만 산(성벽 끝 다음 칸 = 열 18)
        cities = [dict(id=7, level=3, row=15, col=15)]
        st = B.build_structure(win, cities, B.resolve_footprints(cities), 0, 0, K)
        got = [(d["cityId"], d["next"], d["reason"]) for d in B.pass_defects(st) if d["code"] == "PASS_WALL_OPEN"]
        self.assertEqual([(7, [9, 15], "limit")], got)                 # 서쪽 5칸(열 14 … 10) 뒤 열 9 가 들판


class SourceAndProtocolTest(unittest.TestCase):
    def test_full_sha_identity_uses_recursive_ascii_key_order(self):
        identity = dict(mapRelease="fixture-map", kitVersion="kit-pin", formatVersion=1,
                        inputFingerprint=dict(z=2, a=dict(z=4, a=3)))
        literal = b'{"formatVersion":1,"inputFingerprint":{"a":{"a":3,"z":4},"z":2},"kitVersion":"kit-pin","mapRelease":"fixture-map"}'
        self.assertEqual(hashlib.sha256(literal).hexdigest(), B.bake_id(identity))
        for key, value in [("mapRelease", "new-map"), ("kitVersion", "new-kit"), ("formatVersion", 2),
                           ("inputFingerprint", {"a":3})]:
            self.assertNotEqual(B.bake_id(identity), B.bake_id(dict(identity, **{key:value})))

    def test_partial_region_and_kit_catalog_change_identity(self):
        man = dict(mapRelease="fixture-map", inputFingerprint=dict(demSha256="abc"))
        kit = SimpleNamespace(kit_version="same-short-id-with-immutable-source")
        full = B.bake_identity(man, {"kit/catalog.json":"a"}, kit)
        self.assertNotEqual(B.bake_id(full), B.bake_id(B.bake_identity(man, {"kit/catalog.json":"b"}, kit)))
        self.assertNotEqual(B.bake_id(full), B.bake_id(B.bake_identity(man, {"kit/catalog.json":"a"}, kit, [0,4,0,4])))

    def test_ordered_edge_direction_determines_diagonal_connector(self):
        shape = (4,4); zero = np.zeros(shape,np.uint8)
        layers = dict(ground=np.ones(shape,np.uint8), relief=zero, riverTier=zero, riverWidth=zero,
                      landcover=zero, owner=zero)
        edges = [dict(edgeId="diagonal", status="BUILT", cells=[[0,1],[1,0]])]
        road = B.export_window_inputs(layers, edges)["road"]
        self.assertTrue(road[1,1]); self.assertFalse(road[0,0])
        with self.assertRaisesRegex(ValueError,"non-adjacent"):
            B.export_window_inputs(layers,[dict(edgeId="jump", status="BUILT",cells=[[0,0],[3,3]])])

    def test_large_water_contact_is_allowed_but_narrow_river_and_road_are_open(self):
        cls = np.full((3,7),"L",dtype="<U1"); road=np.zeros(cls.shape,bool)
        p=dict(cityId=9,cy=1,cx=3,orientation="NS",gate=[(0,3),(1,3),(2,3)],
               walls=[[(1,2),(1,1)],[(1,4),(1,5)]],stops=[((1,0),(0,-1)),((1,6),(0,1))])
        st=dict(cls=cls,road=road,castle=np.zeros(cls.shape,bool),passes=[p])
        cls[1,0]="W"; cls[1,6]="M"
        self.assertEqual([],B.pass_defects(st))
        self.assertTrue(all(check["accepted"] for check in B.pass_endpoint_checks(st)))
        cls[1,0]="r"
        self.assertEqual("narrow-river",B.pass_defects(st)[0]["reason"])
        cls[1,0]="W"; road[1,0]=True
        self.assertEqual("road",B.pass_defects(st)[0]["reason"])

    def test_administrative_seat_uses_explicit_place_not_first_city_or_game_flag(self):
        tiles=dict(provinceRecords=[dict(jurisdictionId="J"),dict(jurisdictionId="J")],
                   jurisdictionRecords=[dict(id="J",seatPlaceId="seat")],
                   commanderyRecords=[dict(id="C",displayName="갑군",seatJurisdictionId="J",jurisdictionIds=["J"])])
        world=dict(cities=[dict(id=42,name="첫성",provinceId=0,physicalPlaceRef="place:first",meta=dict(isSeat=True)),
                           dict(id=100,name="치소",provinceId=1,physicalPlaceRef="place:seat",meta=dict(isSeat=False))])
        result=B.seat_audit(tiles,world)
        self.assertEqual([100],result["administrativeCityIds"]); self.assertEqual([42],result["gameCityIds"])
        self.assertEqual([],result["intersection"])
        world["cities"].append(dict(id=101,name="중복자리",provinceId=1,physicalPlaceRef="place:seat",meta={}))
        self.assertIsNone(B.seat_audit(tiles,world)["commanderies"][0]["seatCityId"])

    def test_to_segment_keeps_original_direction_after_connected_trajectory_reversal(self):
        layers = {key: np.zeros((8, 8), np.uint8) for key in
                  ("ground", "relief", "riverTier", "riverWidth", "landcover", "owner")}
        edge = dict(edgeId="two-segments", status="BUILT", fromTrail=[[1, 1], [2, 2]],
                    toTrail=[[4, 4], [3, 3]], cells=[[1, 1], [2, 2], [3, 3], [4, 4]])
        road = B.export_window_inputs(layers, [edge])["road"]
        self.assertTrue(road[4, 3]); self.assertFalse(road[3, 4])


class IntegrityTest(BakeFixture):
    @contextmanager
    def manifest_edit(self):
        path=self.out/"manifest.json"; original=path.read_bytes(); manifest=json.loads(original)
        try:
            yield manifest
        finally:
            path.write_bytes(original)

    def write_manifest(self,manifest):
        (self.out/"manifest.json").write_text(json.dumps(manifest))

    def test_check_accepts_current_fixture_and_unknown_households_stay_null(self):
        self.assertEqual([],B.check(self.root/"export",self.root/"kit",self.out,log=lambda *_:None,repo=self.repo))
        places=json.loads(gzip.decompress((self.out/"places.json.gz").read_bytes()))
        city=next(c for c in places["cities"] if c["id"]==5)
        label=next(l for l in places["labels"] if l["id"]=="city:5")
        self.assertIsNone(city["households"]); self.assertIsNone(label["priorityHouseholds"])
        self.assertTrue(next(c for c in places["cities"] if c["id"]==3)["isAdministrativeSeat"])

    def test_raw_hash_and_sample_removal_are_rejected(self):
        with self.manifest_edit() as manifest:
            manifest["overview"]["rawSha256"]="0"*64
            manifest["files"]= [dict(file=e["file"],sha256=e["sha256"],bytes=e["bytes"],rawSha256=e["rawSha256"],
                compression="gzip" if e["file"].endswith(".gz") else "none") for e in B.output_entries(manifest)]
            manifest["sampleChunks"]=[]; self.write_manifest(manifest)
            errors=B.check(self.root/"export",self.root/"kit",self.out,log=lambda *_:None,repo=self.repo)
            self.assertTrue(any("raw file fingerprint" in error for error in errors))
            self.assertTrue(any("sample selection" in error for error in errors))

    def test_grid_mutation_with_refreshed_file_checksums_fails_rebake(self):
        entry=self.chunk(0,0); path=self.out/entry["file"]; original=path.read_bytes()
        try:
            with self.manifest_edit() as manifest:
                raw=bytearray(gzip.decompress(original)); raw[:2]=b"\x63\x00"; blob=B.gz(bytes(raw)); path.write_bytes(blob)
                chunk=next(c for c in manifest["chunks"] if (c["cx"],c["cy"])==(0,0))
                chunk.update(sha256=B.sha256(blob),bytes=len(blob),rawSha256=B.sha256(bytes(raw)))
                entry=next(f for f in manifest["files"] if f["file"]==chunk["file"])
                entry.update(sha256=chunk["sha256"],bytes=chunk["bytes"],rawSha256=chunk["rawSha256"])
                self.write_manifest(manifest)
                errors=B.check(self.root/"export",self.root/"kit",self.out,log=lambda *_:None,repo=self.repo)
                self.assertTrue(any("표본 조각 0_0" in error for error in errors),errors)
        finally:
            path.write_bytes(original)

    def test_source_fingerprint_mutation_is_rejected_before_baking(self):
        path=self.root/"export/map-design-manifest.json"; original=path.read_bytes()
        try:
            manifest=json.loads(original); manifest["inputFingerprint"]["demSha256"]="0"*64
            path.write_text(json.dumps(manifest))
            errors=B.check(self.root/"export",self.root/"kit",self.out,log=lambda *_:None,repo=self.repo)
            self.assertTrue(any("current dem" in error for error in errors),errors)
        finally:
            path.write_bytes(original)


class PackagingTest(BakeFixture):
    def test_package_is_identical_idempotent_and_never_overwrites(self):
        root = self.root / "published"
        target = B.package_bundle(self.root / "export", self.root / "kit", self.out, root,
                                  log=lambda *_: None, repo=self.repo)
        self.assertEqual(self.man["bakeId"], target.name)
        self.assertEqual((self.out / "manifest.json").read_bytes(), (target / "manifest.json").read_bytes())
        self.assertEqual(target, B.package_bundle(self.root / "export", self.root / "kit", self.out, root,
                                                 log=lambda *_: None, repo=self.repo))
        (target / "defects.json").write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "already exists"):
            B.package_bundle(self.root / "export", self.root / "kit", self.out, root,
                             log=lambda *_: None, repo=self.repo)
        self.assertEqual(b"tampered", (target / "defects.json").read_bytes())

    def test_partial_output_cannot_be_packaged(self):
        out = self.root / "partial"
        B.bake(self.root / "export", self.root / "kit", out, workers=1, region=[0, 4, 0, 4],
               log=lambda *_: None, repo=self.repo)
        with self.assertRaisesRegex(ValueError, "partial bake"):
            B.package_bundle(self.root / "export", self.root / "kit", out, self.root / "no-partial",
                             log=lambda *_: None, repo=self.repo)

    def test_absent_published_artifacts_are_skipped_but_bad_present_artifacts_are_red(self):
        root = self.root / "bad-published"
        self.assertEqual(77, B.check_published(self.root / "export", self.root / "kit", root, log=lambda *_: None))
        root.mkdir(); (root / "invalid-id").mkdir()
        self.assertEqual(1, B.check_published(self.root / "export", self.root / "kit", root, log=lambda *_: None))


if __name__ == "__main__":
    unittest.main()
