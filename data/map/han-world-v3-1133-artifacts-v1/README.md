# Han world v3 release: 1133 identities

This immutable release snapshot contains nine strategic loader inputs and five ownership/supply companions. The catalog records each original path, raw SHA-256 and byte length, and deterministic gzip blob hash (level 9, mtime 0). `sourceBaseCommit` records the reviewed source checkout.

Do not regenerate this directory in place when future cities or names change — register a new release identity. **Boundaries are the exception since ADR-LITE-063:** the resolver selects a release by its city-id set, so a boundary change that keeps the same 1,133 cities cannot get a new identity; it is re-pinned in place with a dated section below and every world pinned to 1133 must be reset. The 832/835, 846, 848 and 1098 catalogs remain unchanged.

What changed from 1098 — no province is left without a city-bearing jurisdiction (user directive 2026-09-17):

- Folds (`cityless-jurisdiction-fold-decisions-v1` → `cityless-jurisdiction-folds-v1`, the last han-tiles stage): 11 city-less jurisdictions hand their provinces to the jurisdiction of the same place's city — 杜→杜陵(5), 鄄良→鄄城(285), 益都→益(382), 㡉縣→㡉侯國(917), 贊→陰(411), 新平郡 漆→漆(80), 毗陵典農校尉→毗陵(848), 汶山郡→蜀郡 廣都(632, the only touching city jurisdiction), 章武郡 東平舒→河閒 東平舒(191), and the two duplicate cities below. Geometry and parent regions are unchanged; the four post-郡國志 commanderies left without jurisdictions keep their rows with an empty `jurisdictionIds` and a null seat.
- Duplicate cities withdrawn: 977 巴郡 漢昌 (CHGIS 44621) duplicated 579, and 989 北地郡 富平 (CHGIS 70523, the 永初 寄治) duplicated 627. Both jurisdictions fold into the kept city's county.
- 977, 989 and 1099–1133: 37 external-settlement cities (`w5-external-settlement-route-claim`, REVIEWED_SOURCE_CLAIM on `data/map/external-places.json` records) for 夫餘·高句麗·沃沮·濊·三韓·伽耶·倭·夷洲·流求·烏桓·鮮卑·南匈奴·西羌·白馬氐·哀牢·山越. The two freed numbers are reused through registry rebinding so ids stay contiguous.
- Province 1304 at the Yalu mouth moves from 卒本 to 遼東 西安平 (`provinceTransfers`): 漢書 地理志 places 西安平 where the 馬訾水 enters the sea.
- Island commanderies are joined by the sourced sea links already used by v2 (`SEA_LINKS`), and water-separated city groups (一大國·伊都國) link to their commandery seat.

Provinces stay 1,594 and province indices are unchanged; jurisdictions 1,144 → 1,133; every province resolves to its own jurisdiction's city (`province-city-attribution-v1`: 1,594 `OWN_COUNTY_SEAT`).

Runtime loading verifies the pinned catalog hash, complete expected path set and every raw/compressed hash and length before the strategic loader validates cross-file identity and topology. World selection must use the complete city roster plus all persisted spatial pins, never only the city count.

## 2026-09-17 lowland terrain re-pin (ADR-LITE-058)

Re-pinned in place by user decision. City, province and jurisdiction identities, owner grids, adjacency, coordinates, `han-world-v3.json` and the Kotlin constant snapshots are byte-identical; only dry-land terrain classes changed (1,332 MOUNTAIN/HILL cells inside 19 named lowlands became PLAIN/BASIN, `data/curated/han/lowland-terrain-decisions-v1.json`). A parallel identifier is impossible because the resolver selects a variant by its city-id set.

Six blobs moved together with `catalog.json` and `Han1133Artifacts.CATALOG_SHA256`: `han-tiles.json`, `han-water-topology-v1.json`, `water-topology-adjudications-v1.json`, `han-strategic-topology-manifest-v1.json`, `han-world-v3-manifest-v1.json` (each only re-pins the han-tiles hash) and `han-scenario-province-ownership-v1.json` (`mapSha256`). Blobs are gzip level 9, mtime 0; the eight untouched blobs were reproduced byte-for-byte with the same method before the six were written.

`sourceBaseCommit` is the main commit this work started from, not a branch commit that a squash merge would orphan; blob hashes are the identity. The commandery `cross`/`ford` annotations inside han-tiles were not re-derived for the new terrain (ADR-LITE-058, Not done).

Cost: blob hashes feed `StrategicTopology.contentHash`, so a world already pinned to 1133 fails to load until it is reset.

## 2026-09-18 river route re-pin (ADR-LITE-060)

Re-pinned in place by user decision (2026-09-18, "진행해": put the river waterways into the game the same way the 13 sea routes work). City, province and jurisdiction identities, `han-tiles.json`, owner grids, the typed strategic topology (`han-water-topology-v1.json`, its ledger and manifest), coordinates and the gate index are byte-identical. What changed is the city graph: three river connections between evidence-backed ports — 江州(572)↔夷陵(401), 夷陵(401)↔樊口(1037), 樊口(1037)↔濡須口(1072) — derived from `portLinks` of the reviewed waterway ledger (`data/curated/han/waterway-network-adjudications-v1.json` → `data/map/han-waterway-network-v1.json`). They are listed in `seaRoutes` with `kind: "RIVER"` (the existing 13 rows gained `kind: "SEA"`), and like the sea routes they also carry supply (`canonicalGroup: "RIVER_ROUTE"` rows in the commandery supply links). A connection costs what every other connection costs; no cost or capacity was invented.

Three blobs moved together with `catalog.json` and `Han1133Artifacts.CATALOG_SHA256`:

| path | old sha256 | new sha256 |
| --- | --- | --- |
| `infra/src/main/resources/map/han-world-v3.json` | `da990c88…10aa8a` | `afaf2fb7…99d89f` |
| `data/map/han-world-v3-manifest-v1.json` | `fcf4c8ff…2cf3fb` | `c318f1e1…399233` |
| `data/map/han-commandery-supply-links-v1.json` | `cc8284e9…711891` | `705416d8…ab569` |

`catalog.json` `6bf882f5…9bc362` → `3301ccfe…5f2e59`. `runtime-constants.json` `629b6095…63fdc9` → `5bd8890f…00e974` (the `HanWorldV31133CityConst` snapshot was re-taken from the regenerated `HanWorldV3CityConst`: source `015c2730…` → `674b9052…`, snapshot `547c5661…` → `7cdbcf2b…`; the gate index snapshot is unchanged). The eleven untouched blobs were reproduced byte-for-byte with the same method (gzip level 9, mtime 0) before the three were written — `tools/map/repin_han_1133_bundle.py` does this and its `--check` is now part of `check_han_tiles_coupled.py`.

`sourceBaseCommit` is the main commit this work started from (`cd9d93ed`). 846/848/1098 bundles are byte-identical.

Cost: blob hashes feed `StrategicTopology.contentHash`, so a world already pinned to 1133 fails to load until it is reset.

## 2026-09-18 geography-first re-cut + river routes (ADR-LITE-063, GH #806 + #826)

One in-place re-pin for two works, by user decision (the bundled reset of 2026-09-18). City identities (the 1,133 ids) are unchanged; **province identities and indices are not**:

- County borders inside every commandery were re-cut to the cities' real positions (`partition_counties_by_location`, a new han-tiles stage between the frontier counties and the strategic-site carve; ledger `county-location-partition-v1`). Provinces 1,594 → **1,331** (1,258 county/cityless + 73 strategic sites), 457 old ids retired (the ledger lists them), surviving ids keep their records but not always their index. Q1 (a city's real cell lies in its own jurisdiction) 651/1,131 → 1,131/1,131 with ledger exceptions.
- River routes: the waterway ledger now has 江陵·沙羨(夏口)·建業 as ports, so the river-route table is 江州–夷陵–江陵–沙羨–樊口–濡須口–建業 (6 `RIVER` rows; previously 3).
- `HanStrategicTopologyJson.landCountByRoster[1133]` 1,594 → 1,331. The 上庸 conflict-allowlist rows (1100·1110) were retired — 上庸縣 is one province now.

| path | old sha256 | new sha256 |
| --- | --- | --- |
| `data/map/han-tiles.json` | `ba08098a…1566a0` | `1b19cc34…d85f99` |
| `data/map/han-water-topology-v1.json` | `70452cdf…af336c` | `02c71f78…7b4e7e` |
| `data/curated/han/water-topology-adjudications-v1.json` | `f4e2dba2…811deb` | `897aa6a4…8039a4` |
| `data/map/han-strategic-topology-manifest-v1.json` | `50752b94…39e817` | `eae9ceff…1e08f7` |
| `infra/src/main/resources/map/han-world-v3.json` | `afaf2fb7…99d89f` | `d5009b95…fba58e` |
| `data/map/han-world-v3-manifest-v1.json` | `c318f1e1…399233` | `832db18c…2dce28` |
| `data/curated/han/route-node-selection-v1.json` | `0921d9e7…bcbaa6` | `696866fd…112eb9` |
| `data/curated/han/route-node-migration-v1.json` | `2d4aad83…87cacc` | `bad2cd42…910c20` |
| `data/map/han-scenario-province-ownership-v1.json` | `39583f1a…cc5e6b` | `f649a265…cb3374` |
| `data/map/han-scenario-jurisdiction-conflict-allowlist-v1.json` | `824aafbb…d74819` | `cd8bb52f…b55350` |
| `data/map/han-commandery-supply-links-v1.json` | `705416d8…3ab569` | `ef8aac93…228d34` |

`catalog.json` `3301ccfe…5f2e59` → `c102b6a9…ef04aa`. `runtime-constants.json` `5bd8890f…00e974` → `fa2e424e…70daac` (`HanWorldV31133CityConst` snapshot re-taken). `sourceBaseCommit` `bdf5ed8e` (origin/main at the time). 846/848/1098 bundles are byte-identical.

Cost: every world pinned to 1133 fails to load until it is reset — and because province ids and indices changed, persisted `province_control` is not migratable. Reset, do not migrate.

