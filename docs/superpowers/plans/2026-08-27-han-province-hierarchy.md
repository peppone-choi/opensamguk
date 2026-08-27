# Han Province Hierarchy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `han`'s mixed city/commandery territory model with 3-5 isometric tactical provinces per county, scenario-year county-commandery hierarchy, separate settlement/site symbols, and one province graph shared by ownership, movement, supply, and rendering.

**Architecture:** Reviewed historical manifests and the existing 768×669 source grid feed a deterministic Python partitioner. It emits connected province IDs, cardinal adjacency, effective administrative relations, and one RGB24 province-only PNG. Kotlin resolves the scenario-year hierarchy and stores world-scoped province ownership while adapting legacy city consumers. The React canvas renders every cell and symbol with the existing 2:1 isometric transform, derives higher borders from metadata, and uses the same province topology hash as backend movement and supply.

**Tech Stack:** Python 3 standard library, JSON/TSV source manifests, Kotlin 2.1, Spring Boot, PostgreSQL/Flyway, React 19, TypeScript 5.7, Canvas 2D, JUnit 5, unittest, Vitest/Testing Library, Playwright.

**Spec:** `docs/superpowers/specs/2026-08-27-han-province-hierarchy-design.md`

## Global Constraints

- The first production partition averages 3-5 tactical provinces per sourced county; its deterministic baseline target is 88 covered raster cells per province.
- Every covered land cell belongs to exactly one connected non-empty province; sea/outside is ID `0` only.
- RGB24 stores only `provinceId + 1`. County, commandery, nation, settlement rank, and site kind never occupy pixel bits.
- The PNG is baked once for `han`; scenario-year hierarchy changes metadata only.
- Every supported-year province has exactly one county/direct-territory parent, and every commandery/kingdom has at least one child.
- Shared cardinal edge means movement and supply adjacency. Diagonal-only contact does not. No shared-edge threshold may delete an adjacency.
- Settlement rank 1-5, administrative roles, and site kind are independent.
- Cells, boundaries, and symbols use the existing 2:1 `cellToScreen`/`screenToCell` projection.
- Nation color fills territory and may color a small flag or ownership ring; it never fills icon bodies or glows.
- Historical claims use verbatim traditional-Chinese quotes from `shiliao`; a zero hit remains `UNKNOWN in this corpus`.
- Existing `settlementId`/city IDs remain stable during migration. Non-`han` maps retain legacy `CityConst.path` and level semantics.
- Daemon writes flow through `ChangeRecorder` and `JdbcFlushExecutor`; no JPA write path is introduced.
- Generated artifacts contain no timestamp, absolute path, or unstable iteration order.
- Frozen tests are not deleted or weakened. Behavior changes receive new focused tests and explicit migration assertions.
- Every implementation commit ends with `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

---

### Task 1: Historical Manifest and Schema Foundation

**Files:**
- Create: `data/map/han-administrative-history.json`
- Create: `data/map/han-strategic-sites.json`
- Create: `data/map/han-province-id-registry.tsv`
- Create: `tools/map/han_province_model.py`
- Create: `tools/map/tests/test_han_province_model.py`

**Interfaces:**
- Produces `load_administrative_history(path) -> AdministrativeHistory`.
- Produces `load_strategic_sites(path) -> tuple[StrategicSite, ...]`.
- Produces `resolve_administrative_hierarchy(history, year) -> AdministrativeHierarchySnapshot`.
- `HierarchySnapshot` exposes `province_to_county`, `county_to_commandery`, `county_seats`, and `roles_by_settlement` as insertion-stable mappings.
- Evidence records require `book`, `volume`, `section`, `quote`, `grade`, `claim`, and `locationConfidence`.

- [ ] **Step 1: Write failing manifest-contract tests**

```python
def test_effective_relations_resolve_one_parent_per_child(self):
    history = history_fixture(
        relations=[
            relation("province:1", "county:a", 184, 220),
            relation("province:1", "county:b", 220, None),
        ]
    )
    self.assertEqual(resolve_relations(history.relations, 219)["province:1"], "county:a")
    self.assertEqual(resolve_relations(history.relations, 220)["province:1"], "county:b")

def test_overlap_and_childless_commandery_are_rejected(self):
    with self.assertRaisesRegex(ValueError, "overlapping parents"):
        validate_history(overlap_fixture())
    with self.assertRaisesRegex(ValueError, "has no active child"):
        resolve_administrative_hierarchy(childless_fixture(), 220)

def test_evidence_requires_verbatim_quote_and_grade(self):
    with self.assertRaisesRegex(ValueError, "quote"):
        load_strategic_sites(write_fixture({"evidence": [{"book": "三國志"}]}))
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `python3 -m unittest tools.map.tests.test_han_province_model -v`

Expected: import failure for missing `tools.map.han_province_model`.

- [ ] **Step 3: Implement immutable model loaders and effective-range validation**

```python
@dataclass(frozen=True)
class EffectiveRelation:
    child_id: str
    parent_id: str
    effective_from: int
    effective_to: int | None

    def active(self, year: int) -> bool:
        return self.effective_from <= year and (self.effective_to is None or year < self.effective_to)

@dataclass(frozen=True)
class Evidence:
    book: str
    volume: str
    section: str
    quote: str
    grade: str
    claim: str
    location_confidence: str
```

Reject unknown enum values, duplicate stable IDs, empty quotes, invalid ranges, overlapping active parents, multiple active seats, and unsupported references. Preserve JSON array order; sort only where the schema explicitly specifies a tie-break.

- [ ] **Step 4: Add reviewed initial records and direct-territory policy**

Seed the strategic manifest with sourced entries for `定軍山`, `劍閣`, `街亭`, `祁山`, and `五丈原`, using the exact quotes recorded in the spec. Add `陽安關` with its 《三國志》卷44 quote. Keep `陽平關` as a separate alias claim with `NOVEL` evidence until a geographic identity claim is added.

For each current commandery without a sourced child county at a supported year, add an explicit `DIRECT_TERRITORY` child named `<군국명> 직할령`, `provenanceStatus: PROVISIONAL`, and a non-empty evidence-gap note. Do not create a historical county name.

- [ ] **Step 5: Run validation and commit**

```bash
python3 -m unittest tools.map.tests.test_han_province_model -v
python3 -m tools.map.han_province_model \
  --history data/map/han-administrative-history.json \
  --sites data/map/han-strategic-sites.json \
  --years 184,190,200,208,220,234,263,280
git add data/map/han-administrative-history.json data/map/han-strategic-sites.json \
  data/map/han-province-id-registry.tsv tools/map/han_province_model.py \
  tools/map/tests/test_han_province_model.py
git commit -m "feat: define Han province history manifests" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

Expected: all listed years resolve without childless commanderies, multiple seats, or overlapping
administrative relations. Province-parent completeness is gated after Task 2 creates province IDs.

---

### Task 2: Deterministic Tactical Province Partitioner

**Files:**
- Create: `tools/map/build_han_province_grid.py`
- Create: `tools/map/tests/test_build_han_province_grid.py`
- Create through the generator only: `data/map/han-province-assignments.json`
- Modify: `data/map/han-tiles.json` through the generator only
- Modify: `tools/map/build_tile_grid.py`

**Interfaces:**
- Consumes current terrain, place coverage, county/direct-territory mapping, strategic-site anchors, and `han-province-id-registry.tsv`.
- Produces `partition_parent(cells, mandatory_seeds, target_area=88) -> list[ProvinceRegion]`.
- Produces `provinceOwner` row-major RLE, `provinces[]`, `adjacency.province[]`, and `_meta.provinceSchemaVersion = 2` in `han-tiles.json`.
- Produces effective-dated province-to-county/direct-territory relations in
  `han-province-assignments.json`; the administrative source manifest remains hand-reviewed and
  generator-independent.
- `provinces[]` entries contain `id`, `name`, `kind`, `parentAtReferenceYear`, `anchor`, `coveredCells`, `terrainCounts`, `settlementId`, and `siteId`.

- [ ] **Step 1: Write failing split, connectivity, density, and determinism tests**

```python
def test_partition_is_connected_and_stays_inside_parent(self):
    regions = partition_parent(rectangle(20, 20), [seed(2, 2), seed(17, 17)], target_area=88)
    self.assertEqual(set().union(*(r.cells for r in regions)), rectangle(20, 20))
    self.assertTrue(all(is_cardinally_connected(r.cells) for r in regions))

def test_site_seed_gets_its_own_province(self):
    regions = partition_parent(rectangle(12, 12), [seat_seed(2, 2), site_seed("定軍山", 9, 9)], 88)
    site = next(r for r in regions if r.site_id == "site:dingjunshan")
    self.assertIn((9, 9), site.cells)
    self.assertEqual(site.kind, "NATURAL_STRATEGIC")

def test_shared_cardinal_edge_creates_adjacency_even_for_one_edge(self):
    self.assertEqual(derive_adjacency([0, 1], cols=2, rows=1), {(0, 1): 1})
```

- [ ] **Step 2: Run and verify RED**

Run: `python3 -m unittest tools.map.tests.test_build_han_province_grid -v`

Expected: import failure for missing partitioner.

- [ ] **Step 3: Implement seeded terrain-weighted partitioning**

Use `baseCount = clamp(round(parentCellCount / 88), 1, 12)`. Insert mandatory seat/site seeds first. Select remaining seeds by maximum minimum grid cost, tie-breaking by `(cost desc, row, col, stableSeedId)`. Grow with a priority queue keyed by `(cost, seedOrder, row, col)`. Repair disconnected fragments by moving each fragment to the cardinal neighbor with the longest shared boundary, then lowest province ID.

```python
TERRAIN_COST = {
    "PLAIN": 10, "HILL": 13, "BASIN": 11, "PLATEAU": 14,
    "DESERT": 18, "MOUNTAIN": 22, "RIVER": 16, "LAKE": 24,
}
```

The cost changes region shape only. It never creates or removes movement adjacency after partitioning.

- [ ] **Step 4: Add stable ID registry behavior**

Reuse the registry ID for an unchanged `(parentStableId, seedStableId)` key. Append new IDs at the end. Fail if one key maps to two IDs, one ID maps to two keys, or an emitted region has no registry entry. Never sort and renumber existing entries.

- [ ] **Step 5: Generate and audit the real map**

```bash
python3 tools/map/build_han_province_grid.py --input data/map/han-tiles.json \
  --history data/map/han-administrative-history.json \
  --sites data/map/han-strategic-sites.json \
  --registry data/map/han-province-id-registry.tsv --write
python3 tools/map/build_han_province_grid.py --input data/map/han-tiles.json \
  --history data/map/han-administrative-history.json \
  --sites data/map/han-strategic-sites.json \
  --registry data/map/han-province-id-registry.tsv --check
```

Expected: 332,914 covered cells remain covered; every emitted province is connected and non-empty; mean provinces per sourced county is between 3.0 and 5.0; each accepted site has exactly one province; the second command reports byte-identical output.

- [ ] **Step 6: Run tests and commit**

```bash
python3 -m unittest tools.map.tests.test_build_han_province_grid -v
python3 -m unittest tools.map.tests.test_han_tiles_adjacency_matches_owner -v
git add tools/map/build_han_province_grid.py tools/map/build_tile_grid.py \
  tools/map/tests/test_build_han_province_grid.py data/map/han-tiles.json \
  data/map/han-province-assignments.json data/map/han-province-id-registry.tsv
git commit -m "feat: partition Han counties into tactical provinces" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Province-Only RGB24 Asset

**Files:**
- Modify: `tools/map/build_province_map.py`
- Modify: `tools/map/tests/test_build_province_map.py`
- Modify: `docker/game-api.Dockerfile`

**Interfaces:**
- Consumes `provinceOwner`; no longer consumes `seatOwner` for encoding.
- `encode_identity(province_id: int) -> tuple[int, int, int]` stores `province_id + 1` big-endian.
- `decode_identity(rgb) -> int | None` returns `None` only for `(0,0,0)`.
- Metadata schema version becomes `2` and includes `registrySha256` and `topologySha256`.

- [ ] **Step 1: Replace codec expectations with failing RGB24 tests**

```python
def test_rgb24_contains_only_province_identity(self):
    self.assertEqual(encode_identity(0), (0, 0, 1))
    self.assertEqual(encode_identity(0x00FFFE), (1, 0, 0xFF))
    self.assertEqual(decode_identity((0, 0, 0)), None)
    self.assertEqual(decode_identity((1, 0, 0xFF)), 0x00FFFE)
```

Also assert that changing only administrative memberships leaves PNG bytes unchanged and changes only the catalog/hierarchy hash.

- [ ] **Step 2: Run and verify RED**

Run: `python3 -m unittest tools.map.tests.test_build_province_map -v`

Expected: old two-field codec assertions fail.

- [ ] **Step 3: Implement province-only encoding and metadata**

Reject IDs outside `0..16_777_214`, missing registry IDs, duplicate catalog IDs, and decoded dimensions that differ from `han-tiles.json`. Preserve canonical PNG chunks and deterministic stored-DEFLATE behavior.

- [ ] **Step 4: Generate/package/check and commit**

```bash
python3 -m unittest tools.map.tests.test_build_province_map -v
python3 tools/map/build_province_map.py --input data/map/han-tiles.json \
  --output-dir build/generated-map --map-code han
python3 tools/map/build_province_map.py --input data/map/han-tiles.json \
  --output-dir build/generated-map --map-code han --check
docker build -f docker/game-api.Dockerfile -t opensamguk/game-api:han-province-v2 .
git add tools/map/build_province_map.py tools/map/tests/test_build_province_map.py docker/game-api.Dockerfile
git commit -m "feat: encode Han tactical province identities" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Kotlin Catalog, Topology, and Scenario-Year Resolver

**Files:**
- Create: `common/src/main/kotlin/opensamguk/common/map/ProvinceCatalog.kt`
- Create: `common/src/main/kotlin/opensamguk/common/map/ProvinceTopologySnapshot.kt`
- Create: `common/src/main/kotlin/opensamguk/common/map/HanHierarchyResolver.kt`
- Create: `common/src/main/kotlin/opensamguk/common/constants/HanSettlementRankAdapter.kt`
- Create: `common/src/test/kotlin/opensamguk/common/map/HanHierarchyResolverTest.kt`
- Modify: `tools/scenario/build_han_world.py`
- Modify: `infra/src/main/resources/map/han.json`
- Modify: `common/src/main/kotlin/opensamguk/common/constants/HanCityConst.kt` through the generator only

**Interfaces:**

```kotlin
data class ProvinceEdge(val a: Int, val b: Int, val sharedEdges: Int, val mode: EdgeMode)
data class ProvinceTopologySnapshot(
    val revision: String,
    val neighbors: Map<Int, List<ProvinceEdge>>,
    val settlementProvince: Map<Int, Int>,
)
data class HanHierarchySnapshot(
    val year: Int,
    val provinceToCounty: Map<Int, String>,
    val countyToCommandery: Map<String, String>,
    val countySeats: Map<String, Int>,
    val rolesBySettlement: Map<Int, Set<AdministrativeRole>>,
)
```

- [ ] **Step 1: Write failing resolver and graph tests**

Assert exclusive `effectiveTo`, rejection of overlapping parentage, cardinal adjacency symmetry, stable neighbor order `(otherProvinceId ascending)`, one active child per province, and one active seat per county.

- [ ] **Step 2: Run and verify RED**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --tests '*HanHierarchyResolverTest'`

Expected: compile failure for missing map types.

- [ ] **Step 3: Implement catalog types and resolver**

The loader receives already parsed generated JSON and returns immutable `LinkedHashMap` snapshots. Validation exceptions carry a stable code such as `PROVINCE_PARENT_MISSING`, `PROVINCE_PARENT_OVERLAP`, `COUNTY_SEAT_MULTIPLE`, or `COMMANDERY_CHILDLESS` plus the offending stable ID.

- [ ] **Step 4: Replace mixed Han level semantics in the scenario generator**

`build_han_world.py` emits:

```json
"meta": {
  "provinceId": 314,
  "settlementRank": 4,
  "settlementType": "FORTIFIED",
  "magistrateClass": "LING",
  "roles": ["COUNTY_SEAT", "COMMANDERY_SEAT"]
}
```

Keep legacy `level` populated through a `HanSettlementRankAdapter` during compatibility, but remove `영현`/`장현` and administrative-seat status from numeric size comparison. Do not change `CityConst.levelMap` for che/miniche.

```kotlin
object HanSettlementRankAdapter {
    fun legacyLevelFor(rank: Int, type: HanSettlementType): Int
}
```

- [ ] **Step 5: Regenerate, test, and commit**

```bash
python3 tools/scenario/build_han_world.py --check
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test
git add common/src/main/kotlin/opensamguk/common/map common/src/test/kotlin/opensamguk/common/map \
  tools/scenario/build_han_world.py infra/src/main/resources/map/han.json \
  common/src/main/kotlin/opensamguk/common/constants/HanCityConst.kt \
  common/src/main/kotlin/opensamguk/common/constants/HanSettlementRankAdapter.kt
git commit -m "feat: resolve Han hierarchy by scenario year" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Catalog Delivery and Hash Agreement

**Files:**
- Modify: `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/TerrainMapController.kt`
- Modify: `app/game-api/src/test/kotlin/opensamguk/gameapi/controller/TerrainMapControllerTest.kt`
- Create: `web/shared/src/hanMapCatalog.ts`
- Create: `web/game/__tests__/hanMapCatalog.test.ts`
- Modify: `web/shared/src/index.ts`

**Interfaces:**
- Add `GET /api/map/province-catalog?mapCode=han&year=220`.
- Response includes `schemaVersion`, `year`, `topologyRevision`, `provinceToCounty`, `countyToCommandery`, settlements, sites, roles, and icon metadata.
- The endpoint uses safe map-code/year parsing, ETag, immutable source hash, `304`, and `404` without fallback.
- `loadHanMapCatalog(url) -> Promise<HanMapCatalog>` rejects schema, year, ID, and topology-revision mismatches.

- [ ] **Step 1: Write failing API and TypeScript decoder tests**

Cover correct year resolution, `184` versus `220` hierarchy difference fixture, unsafe map codes, out-of-range years, ETag/304, missing catalog, duplicate province IDs, orphan parents, and a topology hash different from the PNG metadata.

- [ ] **Step 2: Run and verify RED**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests '*TerrainMapControllerTest'
corepack pnpm --dir web/game test -- hanMapCatalog.test.ts
```

- [ ] **Step 3: Implement endpoint and strict browser decoder**

Do not resolve historical data in the browser. The server returns the selected immutable snapshot. The client validates and indexes it into `Map<number, ProvinceRecord>` and `Map<number, Set<number>>` without changing insertion order.

- [ ] **Step 4: Run, commit, and record hashes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests '*TerrainMapControllerTest'
corepack pnpm --dir web/game test -- hanMapCatalog.test.ts
git add app/game-api/src/main/kotlin/opensamguk/gameapi/controller/TerrainMapController.kt \
  app/game-api/src/test/kotlin/opensamguk/gameapi/controller/TerrainMapControllerTest.kt \
  web/shared/src/hanMapCatalog.ts web/shared/src/index.ts web/game/__tests__/hanMapCatalog.test.ts
git commit -m "feat: serve resolved Han province catalogs" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: World-Scoped Province State and Settlement Mapping

**Files:**
- Create: `infra/src/main/resources/db/migration/V45__han_province_state.sql`
- Create: `logic/src/main/kotlin/opensamguk/logic/domain/ProvinceState.kt`
- Create: `infra/src/main/kotlin/opensamguk/infra/persistence/ProvinceStateRowMapper.kt`
- Modify: `infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt`
- Modify: `infra/src/main/kotlin/opensamguk/infra/seed/ScenarioImporter.kt`
- Create: `infra/src/test/kotlin/opensamguk/infra/persistence/ProvinceStateFlushIT.kt`
- Modify: `infra/src/test/kotlin/opensamguk/infra/seed/ScenarioImporterIT.kt`

**Interfaces:**

```sql
CREATE TABLE province_state (
    world_id integer NOT NULL REFERENCES world_state(id) ON DELETE CASCADE,
    province_id integer NOT NULL,
    owner_nation_id integer NOT NULL DEFAULT 0,
    controller_nation_id integer NOT NULL DEFAULT 0,
    supply_state integer NOT NULL DEFAULT 1,
    revision bigint NOT NULL DEFAULT 0,
    meta jsonb NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (world_id, province_id)
);
ALTER TABLE city ADD COLUMN province_id integer;
CREATE UNIQUE INDEX city_world_province_settlement_uq
    ON city(world_id, province_id) WHERE province_id IS NOT NULL;
```

- [ ] **Step 1: Write failing migration, seed, and flush tests**

Assert composite world isolation, one settlement per province, neutral seed for provinces without settlements, inheritance of initial nation from the mapped settlement, idempotent scenario reseed, and JDBC delta flush of owner/controller/supply/revision.

- [ ] **Step 2: Run and verify RED**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :infra:test --tests '*ProvinceStateFlushIT' --tests '*ScenarioImporterIT'`

- [ ] **Step 3: Implement migration, mapper, seed, and flush path**

```kotlin
data class ProvinceState(
    val provinceId: Int,
    val ownerNationId: Int,
    val controllerNationId: Int,
    val supplyState: Int,
    val revision: Long,
    val meta: Map<String, Any?> = linkedMapOf(),
)
```

Seed every catalog province. For a settlement province, copy the settlement city's initial nation; for other provinces, resolve the owning county seat's nation; if the county is contested or neutral, seed nation `0`. Store the chosen rule in `meta.seedSource`.

- [ ] **Step 4: Run architecture and integration gates, then commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :infra:test :app:game-engine:test
git add infra/src/main/resources/db/migration/V45__han_province_state.sql \
  logic/src/main/kotlin/opensamguk/logic/domain/ProvinceState.kt \
  infra/src/main/kotlin/opensamguk/infra/persistence/ProvinceStateRowMapper.kt \
  infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt \
  infra/src/main/kotlin/opensamguk/infra/seed/ScenarioImporter.kt \
  infra/src/test/kotlin/opensamguk/infra/persistence/ProvinceStateFlushIT.kt \
  infra/src/test/kotlin/opensamguk/infra/seed/ScenarioImporterIT.kt
git commit -m "feat: persist Han tactical province state" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Province Topology for Distance, Movement, and Supply

**Files:**
- Create: `logic/src/main/kotlin/opensamguk/logic/map/TerritoryRouteService.kt`
- Create: `logic/src/main/kotlin/opensamguk/logic/world/UpdateProvinceSupply.kt`
- Create: `logic/src/test/kotlin/opensamguk/logic/map/TerritoryRouteServiceTest.kt`
- Create: `logic/src/test/kotlin/opensamguk/logic/world/UpdateProvinceSupplyTest.kt`
- Modify: `logic/src/main/kotlin/opensamguk/logic/world/UpdateCitySupply.kt`
- Modify: `logic/src/main/kotlin/opensamguk/logic/world/CalcCityDistance.kt`
- Modify: `logic/src/main/kotlin/opensamguk/logic/actions/war/CheChulbyeong.kt`

**Interfaces:**

```kotlin
interface TerritoryRouteService {
    fun shortestPath(fromProvinceId: Int, toProvinceId: Int, access: (Int) -> Boolean): List<Int>?
    fun reachable(originProvinceId: Int, access: (Int) -> Boolean): Set<Int>
}
data class ProvinceSupplyResult(val suppliedProvinceIds: Set<Int>, val discoveryOrder: List<Int>)
```

- [ ] **Step 1: Write failing cardinal-route and supply tests**

Assert that every shared-edge pair can route in both directions, diagonal-only pairs cannot, a one-edge contact is retained, hostile control blocks the configured traversal without deleting topology, capitals seed in ascending nation ID order, neighbors visit ascending province ID order, and the same topology revision produces the same discovery list.

- [ ] **Step 2: Run and verify RED**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test \
  --tests '*TerritoryRouteServiceTest' --tests '*UpdateProvinceSupplyTest'
```

- [ ] **Step 3: Implement the province graph and compatibility projections**

For `mapName == "han"`, convert a settlement destination to its province, route across tactical provinces, and project supply back to each settlement's containing province. For every other map, retain the existing `CityConst.path` behavior byte-for-byte.

`CheChulbyeong` stores the selected `routeProvinceIds` and `topologyRevision` in its durable intent/last-turn metadata. If the revision differs at execution, return a typed stale-route denial rather than recomputing silently.

- [ ] **Step 4: Run focused and frozen regressions, then commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test \
  --tests '*TerritoryRouteServiceTest' --tests '*UpdateProvinceSupplyTest' \
  --tests '*UpdateCitySupplyTest' --tests '*CalcCityDistanceTest' --tests '*CheChulbyeong*'
git add logic/src/main/kotlin/opensamguk/logic/map \
  logic/src/main/kotlin/opensamguk/logic/world/UpdateProvinceSupply.kt \
  logic/src/main/kotlin/opensamguk/logic/world/UpdateCitySupply.kt \
  logic/src/main/kotlin/opensamguk/logic/world/CalcCityDistance.kt \
  logic/src/main/kotlin/opensamguk/logic/actions/war/CheChulbyeong.kt \
  logic/src/test/kotlin/opensamguk/logic/map logic/src/test/kotlin/opensamguk/logic/world/UpdateProvinceSupplyTest.kt
git commit -m "feat: route Han movement and supply by province" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Province Ownership Read Model and Tactical Selection

**Files:**
- Create: `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/ProvinceStateController.kt`
- Create: `app/game-api/src/test/kotlin/opensamguk/gameapi/controller/ProvinceStateControllerTest.kt`
- Modify: `web/game/components/game/MapViewer.tsx`
- Modify: `web/gateway/components/MapPreview.tsx`
- Modify: `web/game/__tests__/MapViewer.props.test.tsx`
- Modify: `web/gateway/__tests__/MapPreview.iso.test.tsx`

**Interfaces:**
- Add `GET /api/world/{worldId}/provinces` returning `topologyRevision` and ordered live province states.
- `MapViewer` passes `catalog`, `provinceStates`, `scenarioYear`, and `onProvinceSelect(provinceId)` to `HanMapCanvas`.
- Existing city selection remains secondary and resolves from selected province metadata.

- [ ] **Step 1: Write failing world-scope, stale-hash, and prop-wiring tests**

Cover two worlds with the same province IDs but different owners, unauthorized world reads, stable ordering, topology revision mismatch, gateway preview's neutral state, and game map's live state.

- [ ] **Step 2: Run and verify RED**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests '*ProvinceStateControllerTest'
corepack pnpm --dir web/game test -- MapViewer.props.test.tsx
corepack pnpm --dir web/gateway test -- MapPreview.iso.test.tsx
```

- [ ] **Step 3: Implement read model and prop wiring**

Never infer province ownership from city colors in the browser. Preview without a world renders neutral province states; a live world uses the world-scoped endpoint. Reject a topology revision mismatch before enabling province commands.

- [ ] **Step 4: Run and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests '*ProvinceStateControllerTest'
corepack pnpm --dir web/game test -- MapViewer.props.test.tsx
corepack pnpm --dir web/gateway test -- MapPreview.iso.test.tsx
git add app/game-api/src/main/kotlin/opensamguk/gameapi/controller/ProvinceStateController.kt \
  app/game-api/src/test/kotlin/opensamguk/gameapi/controller/ProvinceStateControllerTest.kt \
  web/game/components/game/MapViewer.tsx web/gateway/components/MapPreview.tsx \
  web/game/__tests__/MapViewer.props.test.tsx web/gateway/__tests__/MapPreview.iso.test.tsx
git commit -m "feat: expose live Han province ownership" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Isometric Symbol Asset Pipeline and Province Renderer

**Files:**
- Create in a separate `opensamguk-images` task worktree: `game/map/han/icons/source/*.png`
- Create in `opensamguk-images`: `game/map/han/icons/candidates/<symbol>/*.png`
- Create in `opensamguk-images`: `game/map/han/icons/curation/style-board/meta.json`
- Create in `opensamguk-images`: `game/map/han/icons/curation/style-board/curation.json`
- Create in `opensamguk-images`: `game/map/han/icons/manifest.json`
- Create in `opensamguk-images`: `game/map/han/icons/previews/style-board.png`
- Create in `opensamguk-images`: `game/map/han/terrain/source/*.png`
- Create in `opensamguk-images`: `game/map/han/terrain/manifest.json`
- Create in `opensamguk-images`: `game/map/han/terrain/previews/seam-matrix.png`
- Create in `opensamguk-images`: `tools/build-han-map-icons.py`
- Create in `opensamguk-images`: `tools/test-build-han-map-icons.py`
- Create in `opensamguk-images`: `tools/test-han-isometric-seams.py`
- Create deployment export in `opensamguk`: `web/game/public/map/han/icons/han-map-symbols.png`
- Create deployment export in `opensamguk`: `web/game/public/map/han/icons/han-map-symbol-masks.png`
- Create deployment export in `opensamguk`: `web/game/public/map/han/icons/han-map-symbols.json`
- Create deployment export in `opensamguk`: `web/game/public/map/han/terrain/han-terrain-tiles.png`
- Create deployment export in `opensamguk`: `web/game/public/map/han/terrain/han-terrain-tiles.json`
- Create generated map data in `opensamguk`: `data/map/han-waterways.json`
- Modify: `tools/map/build_terrain_grid.py`
- Modify: `web/shared/src/provinceMap.ts`
- Create: `web/shared/src/hanMapSymbols.ts`
- Modify: `web/shared/src/HanMapCanvas.tsx`
- Modify: `web/shared/src/isoMap.ts`
- Modify: `web/game/__tests__/provinceMap.test.ts`
- Create: `web/game/__tests__/hanMapSymbols.test.ts`
- Modify: `web/game/__tests__/HanMapCanvas.interaction.test.tsx`
- Modify: `web/game/__tests__/HanMapCanvas.test.ts`

**Interfaces:**

```ts
export type SymbolKind =
  | 'settlement' | 'fortress' | 'pass' | 'port' | 'mountain'
  | 'ford' | 'battlefield' | 'shrine' | 'monument';

export interface IsoMapSymbol {
  id: string;
  provinceId: number;
  col: number;
  row: number;
  kind: SymbolKind;
  settlementRank?: 1 | 2 | 3 | 4 | 5;
  siteRank?: 1 | 2 | 3;
  roles: AdministrativeRole[];
}

export interface HanWaterwayEdge {
  riverId: string;
  fromCell: [number, number];
  toCell: [number, number];
  flow: 'forward' | 'reverse' | 'unknown';
  widthClass: 1 | 2 | 3;
  navigable: boolean;
  crossing: 'NONE' | 'FORD' | 'BRIDGE';
}
```

- [ ] **Step 1: Create the isolated image-source task and write failing compiler tests**

From the metarepo root, run:

```bash
bin/start-task opensamguk-images han-map-isometric-icons main 'map icon atlas sprite manifest'
```

Reuse that worktree if it already exists. The tests assert transparent RGBA masters, a common
ground-contact baseline, declared 2:1 footprint, allowed source dimensions, unique logical IDs,
manifest-order atlas packing, byte-identical rebuilds, and SHA-256 entries matching every source
and export. They also assert identical edge-socket pixels for every legal terrain neighbor pair,
complete channel/shore mask coverage, and that nation color cannot escape a declared flag cloth or
emblem mask.

```python
def test_manifest_locks_isometric_camera_and_baseline(self):
    manifest = load_manifest(FIXTURE)
    self.assertEqual(manifest["projection"], "isometric-2to1")
    self.assertEqual(manifest["camera"], "three-quarter-fixed")
    self.assertTrue(all(icon["footpoint"][1] > 0 for icon in manifest["icons"]))

def test_rebuild_is_byte_identical(self):
    self.assertEqual(build_fixture(FIXTURE), build_fixture(FIXTURE))

def test_banner_tint_is_confined_to_declared_masks(self):
    self.assertEqual(tinted_pixels(FIXTURE) - declared_banner_mask(FIXTURE), set())
```

- [ ] **Step 2: Generate, curate, and approve one style board before the family**

Use the built-in ImageGen path to create raw still candidates for four symbol representatives:
rank-1 settlement, rank-5 metropolis, pass, and 定軍山, plus one ownership-standard row showing
local pennant, county/commandery-seat standard, and capital `牙旗`/`纛`. The `sprite-gen` component-row generator
is reserved for animated row strips; these map symbols use its standalone still-candidate workflow.
AI is used only for raw candidates. The prompt locks neutral Han-era materials, a 2:1 isometric
ground footprint, fixed three-quarter camera, upper-left light, a chroma-ready background, no text,
no flags, no national color, and no watermark.

Group the four candidate families under `candidates/<symbol>/`, add a sibling `meta.json` containing
the shared isometric tile and footpoint, then import them with `unpack_atlas_run.py --pngs-dir`.
Launch `sprite-gen curation --lang ko`, compare the candidates against its isometric ground grid,
and save selection/alignment in `curation.json`. Export selected stills only through
`export_curated_pngs.py`; never install files directly from pre-curation `frames/`.

Also compose `game/map/han/icons/previews/style-board.png`. Record the final prompt, provider,
generated source hashes, selection decision, `sprite-gen` version, camera, light direction, palette,
tile geometry, and footpoint in `manifest.json`. Do not generate the full family until this board is
explicitly approved.

Treat the standards as layered assets: neutral pole/finial/cord pixels, a cloth-color mask, and an
emblem mask. Record `shiliao` textual evidence for period terms and function. If exact appearance is
not established, mark it `VISUAL_RECONSTRUCTION` and record any museum or archaeological visual
reference separately. No modern national-flag rectangle or baked faction name is accepted.

- [ ] **Step 3: Produce masters and deterministic atlas exports**

Generate or edit against the approved style reference for settlement ranks 1-5 and the required
site kinds. Run every candidate family through the same `--pngs-dir` curation and curated-PNG export
path. Keep each final master as a transparent PNG in `opensamguk-images`; never leave a
project-referenced asset only in a provider output directory.

The compiler may alpha-validate, trim transparent padding, normalize the footpoint, downsample with
one fixed Lanczos filter, pack in manifest order, and hash. It may not repaint masters. It emits the
atlas and runtime manifest into the `opensamguk` deployment-export paths listed above.

- [ ] **Step 4: Build joined terrain, river, and bank tile families**

Create a small seamless base family for plain, hill, mountain, desert, plateau, basin, lake, and sea.
Create mask-driven overlays for channel ends, straights, bends, T-junctions, crosses, sources,
confluences, mouths, exposed banks, and inner/outer shore corners. Fords, bridges, ports, rapids,
and waterfalls remain overlays. All pieces use the same 2:1 sockets and upper-left light.

Extend `build_terrain_grid.py` to emit `han-waterways.json`: stable river IDs and cardinal hydrology
edges with flow, width class, navigation, and crossing metadata. Width class 1 renders a connected
channel overlay; classes 2-3 deterministically dilate the skeleton into a multi-cell water footprint
and derive shore masks. The province raster is not punched out by this visual footprint.

Render a seam matrix covering every legal neighbor pair, all connection masks, width transitions,
and large-river confluences at DPR 1 and 2. Reject alpha cracks, double banks, mismatched waterlines,
broken flow continuity, and any gameplay river crossing without a hydrology edge.

- [ ] **Step 5: Run the image-source checks and commit the image repository separately**

```bash
python3 tools/test-build-han-map-icons.py
python3 tools/test-han-isometric-seams.py
python3 tools/build-han-map-icons.py --check
python3 tools/check-license-boundaries.py
git add game/map/han/icons game/map/han/terrain tools/build-han-map-icons.py \
  tools/test-build-han-map-icons.py tools/test-han-isometric-seams.py
git commit -m "feat: add isometric Han map symbol masters" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

The image repository commit and tag/export authorization remain separate from the `opensamguk`
code commit. Do not push or tag either repository without explicit user authorization.

- [ ] **Step 6: Write failing decode, border, icon, LOD, seam, river, and hit tests**

Assert RGB24 province decode, province edge path from diamond corners, county/commandery edge derivation from the year snapshot, no baked commandery field, neutral symbol body under two nation colors, settlement rank size monotonicity, role overlays without rank mutation, mountain/pass silhouettes distinct from castles, flag tint confined to its cloth/emblem masks, mask-driven terrain joins, continuous large-river banks, and `screenToCell` province-first hit resolution.

Add an isometric geometry assertion:

```ts
expect(cellToScreen(3, 1, { scale: 10, ox: 0, oy: 0 })).toEqual([20, 20]);
expect(symbolFootpoint({ col: 3, row: 1 }, view)).toEqual([20, 20]);
expect(diamondCorners(3, 1, view)).toEqual([[20, 15], [30, 20], [20, 25], [10, 20]]);
```

- [ ] **Step 7: Run and verify RED**

```bash
corepack pnpm --dir web/game test -- provinceMap.test.ts hanMapSymbols.test.ts HanMapCanvas.interaction.test.tsx HanMapCanvas.test.ts
```

- [ ] **Step 8: Implement province-only decode and hierarchy-derived paths**

Cache `provinceEdges` by PNG hash. Cache county/commandery paths by `(topologyRevision, hierarchyYear)`. Compose political fill only when province ownership/color changes. Zoom, pan, hover, and animation frames reuse prepared canvases and paths.

- [ ] **Step 9: Load isometric atlases and add semantic zoom**

Load the atlas through `han-map-symbols.json` and draw sprites with their manifest footpoint anchored
to `cellToScreen(col,row)`. Use the neutral pixels from the approved masters unchanged. Apply nation
color only to a separate flag/ring Canvas layer. Administrative roles, selection, event, danger, and
supply remain code-native overlays. Use the four LOD bands from the spec; selected and hovered
symbols override label collision but not projection.

Load the terrain atlas and `han-waterways.json` before political fills. Resolve a deterministic
channel and shoreline mask per visible cell; render large-river water surfaces below province fills
and their banks above the fill. Political color remains translucent over terrain and never colors
water, bank, bridge, ford, or icon pixels.

For ownership, draw the neutral flag base at the symbol's declared banner socket, tint only the
cloth mask, and draw the polity emblem through the emblem mask. Use local, administrative-seat, or
capital standard geometry according to the resolved role; color alone is never the only distinction.

- [ ] **Step 10: Preserve pointer-centered wheel zoom and province selection**

Keep `MAX_CSS_SCALE = 32`. Wheel zoom preserves the cell under the pointer. Clicking resolves the province from the ID raster first, then optional settlement/site details. The DOM accessibility layer exposes province name, county, commandery, settlement/site, owner, controller, and supply as text.

- [ ] **Step 11: Run frontend gates and commit the deployment consumer**

```bash
corepack pnpm --dir web/game test
corepack pnpm --dir web/game typecheck
corepack pnpm --dir web/gateway test
corepack pnpm --dir web/gateway typecheck
git add web/shared/src/provinceMap.ts web/shared/src/hanMapSymbols.ts \
  web/shared/src/HanMapCanvas.tsx web/shared/src/isoMap.ts \
  web/game/public/map/han/icons/han-map-symbols.png \
  web/game/public/map/han/icons/han-map-symbol-masks.png \
  web/game/public/map/han/icons/han-map-symbols.json \
  web/game/public/map/han/terrain/han-terrain-tiles.png \
  web/game/public/map/han/terrain/han-terrain-tiles.json data/map/han-waterways.json \
  tools/map/build_terrain_grid.py \
  web/game/__tests__/provinceMap.test.ts web/game/__tests__/hanMapSymbols.test.ts \
  web/game/__tests__/HanMapCanvas.interaction.test.tsx web/game/__tests__/HanMapCanvas.test.ts
git commit -m "feat: render isometric Han provinces and sites" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: End-to-End Validation, Visual QA, and Cutover Report

**Files:**
- Create: `tools/map/tests/test_han_province_invariants.py`
- Create: `web/game/e2e/han-province-map.spec.ts`
- Modify: `docs/superpowers/specs/2026-08-26-static-province-id-map-design.md`
- Modify: `docs/superpowers/specs/2026-08-20-han-map-visual-design.md`
- Modify: `.ai/decisions.md`
- Create outside the Git repository: `reports/opensamguk/tasks/2026-08-27-han-province-hierarchy.md`

**Interfaces:**
- The invariant audit emits machine-readable counts and hashes plus a human summary.
- Playwright captures overview, regional, county, and close LOD screenshots at DPR 1 and 2.
- The task report records results, commits, verification, historical-source queries, and remaining risks.

- [ ] **Step 1: Write the exhaustive invariant audit**

The audit must assert:

```python
self.assertEqual(covered_pixel_count, 332_914)
self.assertEqual(orphan_provinces, [])
self.assertEqual(disconnected_provinces, [])
self.assertEqual(asymmetric_adjacencies, [])
self.assertEqual(childless_commanderies_by_year, {})
self.assertGreaterEqual(mean_provinces_per_county, 3.0)
self.assertLessEqual(mean_provinces_per_county, 5.0)
self.assertIn("site:dingjunshan", site_to_province)
```

For every cardinal province adjacency, assert a topology edge exists and both movement and supply graph adapters expose it. Explicit maritime edges are audited separately.

- [ ] **Step 2: Add E2E interaction and screenshot assertions**

Cover wheel zoom at the pointer, drag pan, province hover/select, settlement secondary selection, neutral icon bodies across ownership changes, distinct province/county/commandery borders, 정군산/검각 symbol display, no straight all-adjacency road web, and nonblank rendering after repeated zoom/pan.

- [ ] **Step 3: Mark superseded clauses and record the new ADR**

In the 2026-08-26 spec, mark the two-field PNG codec and city-as-province binding as superseded by this spec while retaining its delivery/cache/failure decisions. In the 2026-08-20 visual spec, mark numeric administrative marker tiers as superseded while retaining palette/accessibility guidance. Add an ADR-LITE entry naming province state, scenario-year hierarchy, RGB24 encoding, 3-5 density, historical evidence policy, and the isometric projection contract.

- [ ] **Step 4: Run complete verification**

```bash
python3 -m unittest discover -s tools/map/tests -p 'test_*.py' -v
python3 tools/map/build_han_province_grid.py --input data/map/han-tiles.json \
  --history data/map/han-administrative-history.json \
  --sites data/map/han-strategic-sites.json \
  --registry data/map/han-province-id-registry.tsv --check
python3 tools/map/build_province_map.py --input data/map/han-tiles.json \
  --output-dir build/generated-map --map-code han --check
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test
corepack pnpm --dir web/game test
corepack pnpm --dir web/game typecheck
corepack pnpm --dir web/gateway test
corepack pnpm --dir web/gateway typecheck
corepack pnpm --dir web/game exec playwright test e2e/han-province-map.spec.ts
tools/agent-system/check.py --strict --base origin/main
```

Verify Gradle output contains `BUILD SUCCESSFUL` and inspect `**/build/test-results/test/*.xml`; do not trust wrapper exit code alone.

- [ ] **Step 5: Write the metarepo report and commit repository documentation/tests**

The report contains:

```text
result: province count, covered cells, mean/median/max per county, site province count
commits: task commit hashes in order
verification: exact commands, test counts, XML result, PNG/catalog/topology hashes
historical evidence: query terms, verbatim quotes, source grades, UNKNOWN terms
visual QA: screenshot paths for four LODs at DPR 1/2
remaining risks: disputed locations, provisional direct territories, balance values not frozen
```

```bash
git add tools/map/tests/test_han_province_invariants.py web/game/e2e/han-province-map.spec.ts \
  docs/superpowers/specs/2026-08-26-static-province-id-map-design.md \
  docs/superpowers/specs/2026-08-20-han-map-visual-design.md .ai/decisions.md
git commit -m "test: verify Han province hierarchy cutover" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

Expected: all gates pass, screenshots show isometric cells and isometric symbols, and the report records no unresolved invariant failure.

---

## Execution Order and Review Gates

Execute tasks strictly in order. Tasks 1-3 establish immutable geographic identity. Task 4 freezes the shared contracts. Tasks 5-8 add delivery and live state. Task 9 changes presentation only after catalog/state hashes agree. Task 10 is the release gate.

After each task:

1. run the listed focused tests;
2. inspect `git diff --check` and `git status --short`;
3. obtain an independent review of spec compliance and code quality;
4. resolve every `fix-required` finding before the task commit;
5. do not push, open a PR, merge, deploy, or promote without explicit user authorization.
