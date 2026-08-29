# 220 World Province Rebuild Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the active `han` terrain/province asset with a deterministic world-wide province grid that prefers reviewed 220-era boundaries, adapts pinned modern ADM2 geometry where historical polygons are absent, and renders non-Han polities without fictitious commandery/county labels.

**Architecture:** Introduce a pure province-geometry layer between terrain rasterization and tile serialization. It ingests historical seeds plus a pinned geoBoundaries CGAZ ADM2 snapshot, emits stable province and parent-region records independently of city indices, and fails closed on coverage and shape anomalies. Existing `han-780-v1` compatibility assets remain immutable while the default `han` assets and web tooltip model move to the generic province hierarchy.

**Tech Stack:** Python 3.12, NumPy, Pillow, GeoJSON, Kotlin/JUnit 5, TypeScript/React/Vitest, Gradle, Docker, geoBoundaries CGAZ ADM2 v6.0.0 (CC BY 4.0).

**Spec:** `docs/superpowers/specs/2026-08-29-world-province-rebuild-design.md`

## Global Constraints

- Scenario year is exactly `220`.
- Historical boundary > historical seat adapted to modern geometry > modern ADM2 fallback > terrain-derived fill.
- Every non-water cell has exactly one province; every province has one stable ID and one parent-region ID.
- `han-780-v1` resources and legacy snapshot loading remain byte-stable.
- Han-installed commanderies outside China still use Han labels; other external polities never receive fabricated `군/현` suffixes.
- Modern names remain provenance only and are not exposed as historical tooltip text.
- geoBoundaries attribution, release, source URL, and SHA-256 are shipped with the derived asset.
- All generated IDs and grids are deterministic and order-independent.

---

### Task 1: Pin and fetch the modern administrative boundary input

**Files:**
- Create: `data/curated/map/modern-admin-boundaries-v1.json`
- Create: `tools/map/fetch_modern_admin_boundaries.py`
- Create: `tools/map/tests/test_fetch_modern_admin_boundaries.py`
- Modify: `.gitignore`
- Modify: `tools/map/han_tiles_contract.py`
- Modify: `tools/map/han_tiles_protected_orchestrator.py`

**Interfaces:**
- Produces: `load_boundary_recipe(path: Path) -> BoundaryRecipe`
- Produces: `fetch_boundary_archive(recipe: BoundaryRecipe, output: Path) -> BoundaryArtifact`
- Produces restricted input role `MODERN_ADMIN_ADM2` at `data/modern-admin/geoBoundaries-CGAZ-ADM2.geojson`

- [ ] **Step 1: Write manifest and fetcher contract tests**

```python
def test_recipe_pins_release_license_and_digest(tmp_path: Path):
    recipe = load_boundary_recipe(RECIPE)
    assert recipe.release == "6.0.0"
    assert recipe.license == "CC BY 4.0"
    assert recipe.level == "ADM2"
    assert len(recipe.sha256) == 64

def test_fetch_rejects_digest_drift(tmp_path: Path, fake_download):
    recipe = replace(load_boundary_recipe(RECIPE), sha256="0" * 64)
    with pytest.raises(ValueError, match="SHA-256"):
        fetch_boundary_archive(recipe, tmp_path / "adm2.geojson")
```

- [ ] **Step 2: Run the focused test and confirm it fails**

Run: `python3 -m unittest tools.map.tests.test_fetch_modern_admin_boundaries -v`
Expected: FAIL because the loader and fetcher do not exist.

- [ ] **Step 3: Implement the pinned recipe and downloader**

The recipe must contain exact keys `schemaVersion`, `dataset`, `release`, `level`, `product`, `url`, `sha256`, `license`, `attribution`, and `retrievedAt`. The fetcher streams to a sibling `.part`, verifies SHA-256, validates a GeoJSON `FeatureCollection`, then atomically renames the file. Add `data/modern-admin/` to `.gitignore` and add `MODERN_ADMIN_ADM2` to restricted build roles and the `TERRAIN_GRID` stage.

- [ ] **Step 4: Fetch and verify the pinned artifact**

Run: `python3 tools/map/fetch_modern_admin_boundaries.py --recipe data/curated/map/modern-admin-boundaries-v1.json --output data/modern-admin/geoBoundaries-CGAZ-ADM2.geojson`
Expected: one verified GeoJSON artifact and a printed SHA-256 identical to the recipe.

- [ ] **Step 5: Run source and contract tests**

Run: `python3 -m unittest tools.map.tests.test_fetch_modern_admin_boundaries tools.map.tests.test_han_tiles_contract tools.map.tests.test_han_tiles_protected_orchestrator -v`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add .gitignore data/curated/map/modern-admin-boundaries-v1.json tools/map/fetch_modern_admin_boundaries.py tools/map/han_tiles_contract.py tools/map/han_tiles_protected_orchestrator.py tools/map/tests/test_fetch_modern_admin_boundaries.py
git commit -m "feat(map): pin modern ADM2 boundary input"
```

### Task 2: Build the generic province geometry model

**Files:**
- Create: `tools/map/world_province_geometry.py`
- Create: `tools/map/tests/test_world_province_geometry.py`

**Interfaces:**
- Consumes: terrain `np.ndarray`, projected historical seeds, ADM2 GeoJSON features, and historical polygon masks.
- Produces: `ProvinceSeed`, `ProvinceRecord`, `ParentRegionRecord`, `ProvinceBuildResult`.
- Produces: `build_province_geometry(terrain, projection, seeds, parent_regions, admin_features, historical_masks) -> ProvinceBuildResult`.

- [ ] **Step 1: Write failing priority, merge, split, and connectivity tests**

```python
def test_historical_mask_wins_over_modern_polygon():
    result = build_fixture(historical_owner={(1, 1): "P-H"}, modern_owner="ADM-A")
    assert result.owner[1, 1] == result.index_of("P-H")

def test_two_seeds_inside_one_admin_polygon_split_without_leaking():
    result = build_fixture(seeds=[seed("P-A", 1, 2), seed("P-B", 5, 2)])
    assert set(result.owner[:, :7].ravel()) == {result.index_of("P-A"), result.index_of("P-B")}
    assert np.all(result.owner[:, 7:] == -1)

def test_seedless_polygon_merges_with_same_parent_neighbor():
    result = build_fixture(seedless_middle=True)
    assert result.audit.decisions[0].kind == "MERGE_SEEDLESS"
```

- [ ] **Step 2: Run tests and confirm missing module failure**

Run: `python3 -m unittest tools.map.tests.test_world_province_geometry -v`
Expected: FAIL with `ModuleNotFoundError`.

- [ ] **Step 3: Implement immutable records and historical-mask overlay**

Implement frozen dataclasses, stable lexical ID ordering, polygon rasterization through the existing projection contract, and write-once historical mask ownership.

- [ ] **Step 4: Implement ADM2 assignment, constrained Dijkstra split, and seedless merge**

Only traverse cells inside the current ADM2 polygon and parent-region mask. Tie-break by stable province ID. A seedless polygon merges to the lowest-cost adjacent province in the same parent; if no same-parent neighbor exists, create a `DIRECT_TERRITORY` record owned by that parent.

- [ ] **Step 5: Implement island assignment and audit records**

Record `PRESERVE_HISTORICAL`, `ASSIGN_SINGLE_SEED`, `SPLIT_MULTI_SEED`, `MERGE_SEEDLESS`, `CREATE_DIRECT_TERRITORY`, and `ASSIGN_ISLAND` decisions with source feature ID and resulting province IDs.

- [ ] **Step 6: Run geometry tests**

Run: `python3 -m unittest tools.map.tests.test_world_province_geometry -v`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add tools/map/world_province_geometry.py tools/map/tests/test_world_province_geometry.py
git commit -m "feat(map): add hierarchical province geometry builder"
```

### Task 3: Add province shape quality gates

**Files:**
- Create: `tools/map/province_quality.py`
- Create: `tools/map/tests/test_province_quality.py`
- Create: `data/curated/map/province-shape-exceptions-v1.json`

**Interfaces:**
- Produces: `measure_province_shapes(owner: np.ndarray, records: Sequence[ProvinceRecord]) -> ProvinceQualityReport`.
- Produces: `validate_province_quality(report, policy, exceptions) -> None`.

- [ ] **Step 1: Write failing synthetic anomaly tests**

```python
def test_rejects_disconnected_mainland():
    with pytest.raises(ValueError, match="disconnected"):
        validate_fixture([[1, 1, 0, 1, 1]])

def test_rejects_one_cell_corridor():
    with pytest.raises(ValueError, match="corridor"):
        validate_fixture(CORRIDOR_OWNER)

def test_rejects_region_area_outlier_without_exception():
    with pytest.raises(ValueError, match="area outlier"):
        validate_fixture(AREA_OUTLIER_OWNER)
```

- [ ] **Step 2: Run tests and confirm failure**

Run: `python3 -m unittest tools.map.tests.test_province_quality -v`
Expected: FAIL because quality functions do not exist.

- [ ] **Step 3: Implement metrics and fail-closed policy**

Measure land-cell area, 4-neighbor component count, bounding-box aspect ratio, fill ratio, perimeter compactness, minimum neck width, and parent-region median area ratio. Default failures: component count > 1 except declared islands; aspect ratio > 4; fill ratio < 0.20; area > 8 times parent median; corridor length >= 8 at width <= 2.

- [ ] **Step 4: Add exact-key exception loader**

Exceptions require `provinceId`, `metric`, `reason`, `evidence`, `effectiveMapVersion`; wildcard IDs and open-ended versions are rejected.

- [ ] **Step 5: Run quality tests**

Run: `python3 -m unittest tools.map.tests.test_province_quality -v`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add tools/map/province_quality.py tools/map/tests/test_province_quality.py data/curated/map/province-shape-exceptions-v1.json
git commit -m "test(map): enforce province shape quality"
```

### Task 4: Integrate generic provinces into Han tile generation

**Files:**
- Modify: `tools/map/build_terrain_grid.py`
- Modify: `tools/map/build_tile_grid.py`
- Modify: `tools/map/build_province_map.py`
- Modify: `tools/map/han_tiles_contract.py`
- Modify: `tools/map/han_tiles_protected_orchestrator.py`
- Create: `tools/map/tests/test_world_province_build_integration.py`
- Modify: `tools/map/tests/test_build_province_map.py`
- Modify: `tools/map/tests/test_han_tiles_contract.py`

**Interfaces:**
- `terrain-grid.json` adds `provinceRecords`, `parentRegions`, `provinceAudit`, `provinceQuality`.
- `owner` values index `provinceRecords`; `parentOwner` replaces generic use of `seatOwner` while `seatOwner` is emitted as a compatibility alias for `han-780-v1` only.
- Province identity PNG encodes `provinceIndex` and `parentRegionIndex` with the existing 12/8 bit layout.

- [ ] **Step 1: Add a failing integration fixture**

Assert that a Han county, a Samhan polity, and a seedless external ADM2 polygon produce three distinct province records, complete land coverage, correct `administrativeSystem`, and matching PNG round-trip identities.

- [ ] **Step 2: Run integration tests and confirm schema failure**

Run: `python3 -m unittest tools.map.tests.test_world_province_build_integration tools.map.tests.test_build_province_map -v`
Expected: FAIL because generic records and `parentOwner` are absent.

- [ ] **Step 3: Replace global owner Dijkstra in `build_terrain_grid.py`**

Load the pinned ADM2 GeoJSON and history snapshot, call `build_province_geometry`, validate it with `province_quality`, and preserve road generation from settlement coordinates. Do not allow road labels to overwrite province identities.

- [ ] **Step 4: Serialize generic records in `build_tile_grid.py`**

Emit exact fields `id`, `displayName`, `nameCh`, `administrativeSystem`, `kind`, `parentRegionId`, `cityIndex`, `geometryBasis`, and `confidence`. Keep city records unchanged and map optional `cityIndex` explicitly.

- [ ] **Step 5: Update province PNG and protected contracts**

Rename metadata fields to `parentRegionBits` and `parentRegionIdentities`; accept the old `commanderyBits` metadata only for immutable compatibility assets. Add source roles and output summary counts for direct territories, ADM2 features, audit decisions, and quality failures.

- [ ] **Step 6: Run generator and contract tests**

Run: `python3 -m unittest tools.map.tests.test_world_province_build_integration tools.map.tests.test_build_province_map tools.map.tests.test_han_tiles_contract tools.map.tests.test_han_tiles_protected_orchestrator -v`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add tools/map/build_terrain_grid.py tools/map/build_tile_grid.py tools/map/build_province_map.py tools/map/han_tiles_contract.py tools/map/han_tiles_protected_orchestrator.py tools/map/tests
git commit -m "feat(map): generate world provinces from historical and ADM2 geometry"
```

### Task 5: Render generic hierarchy and external polity tooltips

**Files:**
- Modify: `web/shared/src/HanMapCanvas.tsx`
- Modify: `web/shared/src/provinceMap.ts`
- Modify: `web/shared/src/index.ts`
- Modify: `web/game/lib/types.ts`
- Modify: `web/game/__tests__/provinceMap.test.ts`
- Modify: `web/game/__tests__/HanMapCanvas.interaction.test.tsx`
- Modify: `web/gateway/__tests__/MapPreview.iso.test.tsx`

**Interfaces:**
- Produces: `ProvinceRecordDto`, `ParentRegionRecordDto`, `formatProvinceTooltip(record, parent) -> string`.
- Ownership binding consumes `cityIndex?: number` and falls back to parent-region ownership for direct territories.

- [ ] **Step 1: Write failing tooltip and ownership tests**

```typescript
expect(formatProvinceTooltip(hanCounty, lang)).toBe('낙랑군 조선현');
expect(formatProvinceTooltip(samhanPolity, lang)).toBe('변한 · 구야국');
expect(formatProvinceTooltip(goguryeoCapital, lang)).toBe('고구려 · 국내성');
expect(formatProvinceTooltip(directTerritory, lang)).not.toMatch(/군|현/);
```

Also assert that a direct territory without `cityIndex` inherits the parent region's deterministic runtime owner and never becomes transparent.

- [ ] **Step 2: Run web tests and confirm failure**

Run: `pnpm --dir web/game test -- provinceMap.test.ts HanMapCanvas.interaction.test.tsx`
Expected: FAIL because the generic hierarchy is not decoded.

- [ ] **Step 3: Implement generic decode and ownership binding**

Replace county-array indexing with `provinceRecords[provinceIndex]`; build parent ownership from explicitly linked city records; retain legacy decode for `han-780-v1` tiles lacking generic records.

- [ ] **Step 4: Implement administrative-system tooltip templates**

Use exact templates from the spec and keep modern source names out of UI DTOs. Hover remains province-cell based.

- [ ] **Step 5: Smooth display contours without changing hit geometry**

Build a display-only contour path from province edges using deterministic collinear simplification and one Chaikin pass. Continue using the unsmoothed identity raster for fill and hover.

- [ ] **Step 6: Run web tests**

Run: `pnpm --dir web/game test -- provinceMap.test.ts HanMapCanvas.interaction.test.tsx && pnpm --dir web/gateway test -- MapPreview.iso.test.tsx`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add web/shared/src web/game/lib/types.ts web/game/__tests__ web/gateway/__tests__/MapPreview.iso.test.tsx
git commit -m "feat(map): render generic province hierarchy"
```

### Task 6: Curate external administrative systems and the supplied Samhan seeds

**Files:**
- Create: `data/curated/han/external-province-seeds-v1.json`
- Create: `data/curated/han/external-administrative-systems-v1.json`
- Modify: `tools/map/build_external_places.py`
- Modify: `tools/map/build_han_places.py`
- Create: `tools/map/tests/test_external_province_seeds.py`

**Interfaces:**
- Produces reviewed seed fields `id`, `canonicalName`, `nameCh`, `administrativeSystem`, `parentRegionId`, `lon`, `lat`, `effectiveFrom`, `effectiveTo`, `confidence`, `sources`.
- Runtime activation requires `reviewState == "APPROVED"`; uncertain image-only candidates remain `PENDING` and do not create duplicate gameplay provinces.

- [ ] **Step 1: Write failing schema and chronology tests**

Assert that `狗邪國`, `安邪國`, `斯盧國`, `目支國`, and currently reviewed Gaya centers resolve to non-Han systems, while `樂浪郡` and `帶方郡` remain `HAN_COMMANDERY`. Reject later Gaya aliases as 220 canonical names.

- [ ] **Step 2: Run tests and confirm failure**

Run: `python3 -m unittest tools.map.tests.test_external_province_seeds -v`
Expected: FAIL because curated seed manifests do not exist.

- [ ] **Step 3: Create exact-key manifests from currently reviewed runtime places**

Move existing supported external points into the manifest, preserve their coordinates and source basis, add source references to the two supplied Samhan location maps, and keep unreadable or disputed labels `PENDING`. Do not infer color legend semantics from the screenshots.

- [ ] **Step 4: Load approved seeds in map builders**

Replace hard-coded display-system inference with manifest lookup. Preserve existing external place IDs and append new approved IDs only.

- [ ] **Step 5: Run external and place contracts**

Run: `python3 -m unittest tools.map.tests.test_external_province_seeds tools.map.tests.test_han_places_tier_classification tools.map.tests.test_seat_source_contract -v`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add data/curated/han/external-province-seeds-v1.json data/curated/han/external-administrative-systems-v1.json tools/map/build_external_places.py tools/map/build_han_places.py tools/map/tests/test_external_province_seeds.py
git commit -m "feat(map): curate external province systems and seeds"
```

### Task 7: Materialize and activate the replacement map assets

**Files:**
- Modify: `data/map/han-tiles.json`
- Create: `data/map/han-world-v2-manifest.json`
- Create: `infra/src/main/resources/map/han-world-v2.json`
- Modify: `logic/src/main/kotlin/opensamguk/logic/world/CityConstRegistry.kt`
- Modify: `docker/game-api.Dockerfile`
- Create: `infra/src/main/kotlin/db/migration/V47__activate_han_world_v2.kt`
- Create: `infra/src/test/kotlin/opensamguk/infra/persistence/V47HanWorldV2MigrationTest.kt`
- Modify: `app/game-engine/src/test/kotlin/opensamguk/engine/boot/ActiveWorldMapValidatorTest.kt`

**Interfaces:**
- New active map name: `han-world-v2`.
- Existing default terrain alias `han` serves the same replacement tile/province bytes.
- Existing persisted `han-780-v1` worlds remain pinned and loadable.

- [ ] **Step 1: Write failing registry, resource, and migration tests**

Assert `CityConstRegistry.of("han-world-v2")` resolves, its playable city set matches the generated map resource, new worlds activate `han-world-v2`, and existing `han-780-v1` worlds are unchanged.

- [ ] **Step 2: Run tests and confirm failure**

Run: `./gradlew :logic:test :infra:test --tests '*CityConstRegistryTest' --tests '*V47HanWorldV2MigrationTest' --tests '*ActiveWorldMapValidatorTest'`
Expected: FAIL because `han-world-v2` is unknown.

- [ ] **Step 3: Run the protected map pipeline**

Run: `python3 tools/map/han_tiles_protected_orchestrator.py build --year 220 --grid 768`
Expected: regenerated `han-tiles.json`, zero land coverage holes, zero unapproved quality failures, and a complete attestation including `MODERN_ADMIN_ADM2`.

- [ ] **Step 4: Generate the versioned map and manifest**

Copy the generated playable map JSON to `infra/src/main/resources/map/han-world-v2.json`, record all source/output hashes in `data/map/han-world-v2-manifest.json`, and keep `han-780-v1` files untouched.

- [ ] **Step 5: Register and activate the map version**

Register `han-world-v2` against the current playable city catalog. Migration V47 changes only worlds using unversioned `han` or the current default map; it must not rewrite `han-780-v1` compatibility worlds.

- [ ] **Step 6: Package versioned and default aliases**

Generate `han-provinces.png` and `han-world-v2-provinces.png`; copy `han-tiles.json`, `han-world-v2-tiles.json`, and both PNGs into the game-api image.

- [ ] **Step 7: Run map and runtime tests**

Run: `python3 -m unittest discover -s tools/map/tests -p 'test_*.py' && ./gradlew :logic:test :infra:test :app:game-engine:test :app:game-api:test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add data/map/han-tiles.json data/map/han-world-v2-manifest.json infra logic docker app
git commit -m "feat(map): activate rebuilt world province map"
```

### Task 8: End-to-end verification, report, integration, and deployment

**Files:**
- Create: `reports/world-province-rebuild.md`
- Create in metarepo after completion: `reports/opensamguk/tasks/2026-08-29-world-province-rebuild.md`

**Interfaces:**
- Produces release evidence: commit hashes, source hashes, shape metrics, visual screenshots, test commands, deployment operation ID, reset result, and residual risks.

- [ ] **Step 1: Run deterministic rebuild comparison**

Run the protected build twice from clean intermediates and compare SHA-256 of `han-tiles.json`, `han-world-v2-tiles.json`, province PNGs, quality report, and audit ledger.
Expected: identical hashes.

- [ ] **Step 2: Run full repository checks**

Run: `./gradlew check && pnpm --dir web/game test && pnpm --dir web/gateway test`
Expected: PASS.

- [ ] **Step 3: Perform browser visual QA**

Start the local stack, inspect China, Korea, Manchuria, Mongolia, Japan, and Vietnam at multiple zooms, and record screenshots proving complete land fill, non-rectangular boundaries, and correct Han/external tooltip templates.

- [ ] **Step 4: Write the repository and metarepo reports**

Record result, commits, verification, source attribution, measured largest/aspect/connected-component outliers, deployment evidence, and remaining risks. The metarepo report is mandatory even if deployment fails.

- [ ] **Step 5: Commit reports and push the branch**

```bash
git add reports/world-province-rebuild.md
git commit -m "docs: report world province rebuild"
git push -u origin work/opensamguk/world-province-rebuild
```

- [ ] **Step 6: Open, verify, and merge the PR**

Create the PR with the spec, plan, source attribution, test evidence, and before/after metrics. Wait for required checks, resolve only verified failures, then merge without deleting unrelated worktrees.

- [ ] **Step 7: Deploy, reset, and bring the server back up**

Use the repository's durable deployment operation, wait for completion with `tools/ops/wait_deployer_operation.sh`, invoke the documented server reset workflow, wait for the server to become healthy, and verify the active map is `han-world-v2` with replacement terrain and province ETags.

- [ ] **Step 8: Final production smoke test**

Verify login, map preview, province hover, city selection, one movement precheck, active map version, and server health. Record exact production evidence in the metarepo report.
