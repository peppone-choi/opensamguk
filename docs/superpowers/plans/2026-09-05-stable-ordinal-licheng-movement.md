# Stable Ordinal and 盧縣↔歷城 Movement Implementation Plan

> **For Codex:** Execute with `superpowers:test-driven-development`. Preserve the committed `han`
> 774-node and `han-780-v1` recovery artifacts. Do not solve the defect by adding a hand-written
> 盧–歷城 edge. Run independent review and `superpowers:verification-before-completion` before issue
> updates.

**Goal:** Make commandery and runtime route-node identities append-only, bind/reparent 歷城 correctly,
materialize it as a new Han V2 runtime node without renumbering existing nodes, and derive the valid
盧縣 `45098` ↔ 歷城縣 `45022` move from canonical spatial adjacency.

**Architecture:** Introduce registries for stable commandery and route-node identities. Build the new
`han-world-v2` runtime map from reviewed route-node selection rather than sorted array positions.
Translate spatial-province adjacency through explicit province/place/route-node maps; never compare
province ordinals with city ordinals. Existing legacy artifacts stay addressable and unchanged.

**Tech Stack:** Python 3, curated JSON ledgers, Kotlin/JDK 21 generated constants, React/TypeScript,
Gradle, pnpm.

---

## Task 1: Append-only commandery ID registry

**Files:**

- Create: `data/curated/han/commandery-id-registry-v1.json`
- Create: `tools/map/tests/test_commandery_id_registry.py`
- Modify: `tools/map/build_terrain_grid.py`
- Modify: `tools/map/tests/test_han_tile_canonical_grid_contract.py`
- Modify: `tools/map/tests/test_build_tile_grid_parent_adjudications.py`
- Modify: `tools/map/tests/test_materialize_province_jurisdictions.py`
- Modify: `tools/map/tests/test_build_han_parent_reconciliation.py`

**Step 1: Red-test registry invariants**

Seed the current 172 exact `PARENT-*` IDs. Tests must prove:

- shuffling source roster order does not change IDs;
- retiring one entry preserves the gap and every later ID;
- a new commandery receives the next never-issued number;
- duplicate identity, duplicate ID, missing current identity, and retired-ID reuse fail closed;
- generated parent surfaces and ledgers reference registry IDs, not array indices.

**Step 2: Replace ordinal construction**

Add strict `load_commandery_id_registry`. Pass its mapping into world-province construction and remove
all `PARENT-{index:04d}` generation paths. Keep record array ordering a presentation detail only.

**Step 3: Verify and commit**

```bash
python3 -m unittest tools.map.tests.test_commandery_id_registry
python3 -m unittest tools.map.tests.test_han_tile_canonical_grid_contract
python3 -m unittest tools.map.tests.test_materialize_province_jurisdictions
python3 -m unittest tools.map.tests.test_build_han_parent_reconciliation
```

```text
refactor(map): stabilize commandery identities

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

## Task 2: Reviewed 歷城 binding and parent correction

**Files:**

- Modify: `data/curated/han/administrative-place-bindings-v1.json`
- Modify: `data/curated/han/jurisdiction-commandery-adjudications-v1.json`
- Modify: `data/curated/han/administrative-parent-reconciliation-v1.json`
- Modify: `tools/map/build_administrative_place_overlay.py`
- Modify: `tools/map/world_province_geometry.py`
- Modify: `tools/map/tests/test_materialize_province_jurisdictions.py`
- Modify: `tools/map/tests/test_han_admin_topology_audit.py`
- Modify: `tools/map/tests/test_build_han_parent_reconciliation.py`

**Step 1: Red-test the exact identity chain**

Assert:

```text
hhs:112:濟南國:010
  -> physical place 45022
  -> jurisdiction 45022
  -> commandery PARENT-0035 濟南國
```

The current `NO_COORDINATE_CANDIDATE`, `zhi:false`, and `PARENT-0036 平原郡` state must fail the new
test. Assert all province members and commandery membership change together; 歷城 is not a commandery
seat.

**Step 2: Add reviewed evidence rows**

Bind the HHS unit to existing physical place `45022` and apply the supported whole-jurisdiction
reparent from 平原郡 to 濟南國. Cite the existing 郡國志 source row and do not move raster cells.

**Step 3: Regenerate only governed derived ledgers and commit**

Run each generator in check/write/check order required by its existing CLI. Inspect exact diffs and
confirm 1,524 land provinces, 1,020 jurisdictions, and 172 commandery IDs remain.

```text
fix(map): bind Licheng to Jinan commandery

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

## Task 3: Append 歷城 to the reviewed Han V2 route-node registry

**Files:**

- Modify: `data/curated/han/route-node-source-claims-v1.json`
- Modify: `data/curated/han/route-node-review-policy-v1.json`
- Modify: `data/curated/han/route-node-key-registry-v1.json`
- Modify: `data/curated/han/route-node-selection-v1.json`
- Modify: `data/curated/han/route-node-migration-v1.json`
- Modify: `tools/scenario/han_route_node_selection.py`
- Modify: `tools/scenario/materialize_han_route_node_selection.py`
- Modify: `tools/scenario/validate_han_route_node_selection.py`
- Modify: `tools/scenario/tests/test_han_route_node_selection.py`
- Modify: `tools/scenario/tests/test_han_route_node_materializer.py`
- Modify: `tools/scenario/tests/test_han_route_node_validator.py`
- Modify: `tools/scenario/tests/test_han_route_node_review_fixes.py`

**Step 1: Red-test append-only expansion**

The new V2 selection is explicitly allowed to grow from 780 to 781 by this approved movement work.
Do not retire an unrelated node and do not reuse a key or numeric slot. Tests require:

- all existing route-node UUIDs and numeric IDs remain unchanged;
- 歷城 receives a new opaque UUID and numeric ID `781`;
- physical place is exactly `45022`;
- numeric IDs are unique `1..781` for `han-world-v2`;
- legacy `han-780-v1` remains exact and byte-identical;
- route-node migration declares this as a new-world appended identity, not an in-place reinterpretation
  of an old numeric city.

**Step 2: Materialize the reviewed row**

Move the existing candidate `replacement:hhs:112:濟南國:010` from pending/no-coordinate to selected,
using the reviewed binding from Task 2. Update validators to make expected roster count a versioned
artifact property, not a global hard-coded 780.

**Step 3: Verify and commit**

```bash
python3 tools/scenario/materialize_han_route_node_selection.py --check
python3 tools/scenario/validate_han_route_node_selection.py
python3 -m unittest tools.scenario.tests.test_han_route_node_selection
python3 -m unittest tools.scenario.tests.test_han_route_node_materializer
python3 -m unittest tools.scenario.tests.test_han_route_node_validator
```

```text
feat(map): append Licheng as a stable route node

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

## Task 4: Fix world generation index domains and build `han-world-v2`

**Files:**

- Modify: `tools/scenario/build_han_world.py`
- Create: `tools/scenario/tests/test_build_han_world_v2.py`
- Modify: `tools/scenario/tests/test_build_han_world_gate.py`
- Create: `data/map/han-world-v2-manifest-v1.json`
- Create: `infra/src/main/resources/map/han-world-v2.json`
- Create: `common/src/main/kotlin/opensamguk/common/constants/HanWorldV2CityConst.kt`
- Create: `common/src/main/kotlin/opensamguk/common/constants/HanWorldV2GateIndex.kt`
- Modify: `logic/src/main/kotlin/opensamguk/logic/world/CityConstRegistry.kt`
- Modify: `logic/src/test/kotlin/opensamguk/logic/world/CityConstRegistryTest.kt`

**Step 1: Red-test the current index-domain defect**

Prove `adjacency.county[].a/b` index `provinceRecords`, not physical `cities`. Add a fixture whose
city and province arrays have deliberately different orders; the correct physical edge must survive.
Assert 盧 `45098` and 歷城 `45022` map through stable province IDs and produce a reciprocal one-hop edge
with canonical shared-boundary count six.

Also add a gate that full source selection and committed runtime output have the same reviewed node
set, so the current 833-candidate/774-output drift cannot hide behind `--check-gate`.

**Step 2: Generate from reviewed selection, never sorted city ordinals**

Build mappings in this order:

```text
routeNodeKey -> physicalPlaceId -> physical city index
             -> jurisdictionId -> seat SpatialProvinceRecord.id
             -> spatial province array index
```

Translate each county adjacency endpoint through the inverse stable province map before creating
route-node connections. Do not compare province and city integer array positions.

Generate `han-world-v2` from the reviewed 781-node selection. Keep:

- `han` → current 774-node compatibility map;
- `han-780-v1` → immutable recovery map;
- `han-world-v2` → reviewed stable 781-node map.

Add a V2-specific CLI path to `build_han_world.py`, such as
`--target han-world-v2 [--check]`, with separate output constants. The existing no-target
`--check`/`--check-gate` behavior must continue checking only the compatibility `han` artifact.
The V2 path reads the selection, migration, map, and generated outputs and writes a manifest that
pins all of their SHA-256 hashes plus the exact route-node key/numeric/physical-place triples.

**Step 3: Generate Kotlin constants and registry routing**

Follow existing generated-constant conventions. `CityConstRegistry.of("han-world-v2")` returns the new
map; existing names and hashes remain unchanged.

**Step 4: Verify and commit**

```bash
python3 -m unittest tools.scenario.tests.test_build_han_world_v2
python3 -m unittest tools.scenario.tests.test_build_han_world_gate
python3 tools/scenario/build_han_world.py --check
python3 tools/scenario/build_han_world.py --target han-world-v2 --check
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test :logic:test \
  --tests 'opensamguk.logic.world.CityConstRegistryTest' --no-daemon
```

`test_build_han_world_v2.py` must independently assert compatibility `han` remains the same 774-node
hash, `han-780-v1` remains byte-identical, V2 is exactly 781 reviewed nodes, and the selection,
migration, JSON, Kotlin constants, gate index, and manifest hashes agree. A passing legacy
`--check-gate` is not V2 proof. Extend `test_build_han_world_gate.py` only to prove its existing
compatibility behavior did not silently switch to the V2 artifact.

```text
fix(map): derive Han routes across stable identity domains

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

## Task 5: Re-seed scenario references by physical identity

**Files:**

- Modify: `tools/scenario/apply_han_world.py`
- Modify: `tools/scenario/tests/test_apply_han_world.py`
- Modify generated scenario resources governed by that script

**Step 1: Red-test numeric-ID corruption**

Tests must reject treating an old numeric ID as the same place merely because the number still exists.
Construct the bijection:

```text
old runtime id -> old physicalPlaceId
physicalPlaceId -> reviewed han-world-v2 numeric id
```

Reject unknown old IDs, duplicate physical places, and removed nodes without an explicit migration.

**Step 2: Apply the reviewed migration**

Translate every nation/general/capital/city reference by physical identity for all 15 scenarios. Seed
歷城's initial owner from canonical spatial province ownership. Do not infer owner from nearby city
color. Preserve insertion order and deterministic JSON/Kotlin output.

Add an explicit V2 map input/CLI path, such as `--map han-world-v2`, so the current hard-coded
`infra/src/main/resources/map/han.json` remains the compatibility default while V2 scenario output
loads `infra/src/main/resources/map/han-world-v2.json` and verifies its manifest. Never silently make
the legacy `--check` consume a different map.

**Step 3: Verify all references and commit**

Assert every scenario city/general/nation reference resolves to the reviewed 781-node map and all
existing places retain identity.

`test_apply_han_world.py` must enumerate `ACTIVE_GENERAL_CONTRACTS` and assert all 15 active scenarios
were checked, every prior 780 physical-place identity maps to the same route-node key/numeric ID,
and the only append is `45022 -> 781` with owner taken from the exact scenario province assignment.

```bash
python3 -m unittest tools.scenario.tests.test_apply_han_world
python3 tools/scenario/apply_han_world.py --map han-world-v2 --check
```

```text
fix(scenario): migrate Han city references by place identity

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

## Task 6: One authoritative move/precheck/UI result

**Files:**

- Modify: `logic/src/main/kotlin/opensamguk/logic/world/CalcCityDistance.kt`
- Modify: `logic/src/test/kotlin/opensamguk/logic/world/CalcCityDistanceTest.kt`
- Modify: `logic/src/main/kotlin/opensamguk/logic/actions/military/CheIdong.kt`
- Modify: `logic/src/main/kotlin/opensamguk/logic/constraints/Presets.kt`
- Modify: `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/CommandPrecheckService.kt`
- Modify: `app/game-engine/src/test/kotlin/opensamguk/engine/turn/PrecheckFullCrossCallSiteTest.kt`
- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/turn/ReservedTurnHandler.kt`
- Modify: `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2CityTransportRulesTest.kt`
- Modify: `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/MapPreviewController.kt`
- Modify: `app/game-api/src/test/kotlin/opensamguk/gameapi/controller/MapPreviewControllerTest.kt`
- Modify: `web/game/components/command/SelectCityField.tsx`
- Create or modify: `web/game/__tests__/SelectCityField.test.tsx`

**Step 1: Red-test the real regression end to end**

Under active map `han-world-v2`:

- 盧縣 → 歷城 is reciprocal distance 1;
- API PRECHECK and engine FULL both Allow;
- map preview exposes 歷城 as runtime-backed and interactive;
- the selector shows one-hop 歷城 and submits stable numeric ID 781;
- 魯國 魯縣 `45180` → 歷城 remains non-adjacent (four spatial hops), preventing a fabricated edge;
- `che` distance fixtures and traversal order are unchanged.

**Step 2: Keep one server authority**

The generated `han-world-v2` path is a projection of canonical spatial adjacency. API precheck and
engine consume the same registry/path. The browser may render server-provided reachable destinations
but must not derive an alternate graph from overlay markers.

This task fixes current one-hop movement. The versioned weighted land/water path and typed edge state
replace this projection in Wave 3 of the water-topology plan without changing stable identities.

**Step 3: Verify and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :logic:test :app:game-api:test :app:game-engine:test --no-daemon
cd web/game && pnpm typecheck && pnpm test -- SelectCityField MapViewer
```

```text
fix(movement): allow the canonical Lu-Licheng route

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

## Task 7: Full audit, review, issue updates, and report

**Files:**

- Create: `reports/opensamguk/tasks/2026-09-05-stable-ordinal-licheng-movement.md` in the metarepo

Run:

```bash
python3 tools/map/build_administrative_place_overlay.py --check
python3 tools/scenario/materialize_han_route_node_selection.py --check
python3 tools/scenario/validate_han_route_node_selection.py
python3 tools/map/build_tile_grid.py --check
python3 tools/map/audit_han_admin_topology.py --check
python3 tools/map/build_han_parent_reconciliation.py --check
python3 tools/map/audit_territory_disconnections.py --check
python3 tools/scenario/build_scenario_province_ownership.py --check
python3 tools/scenario/build_han_world.py --check
python3 tools/scenario/build_han_world.py --target han-world-v2 --check
python3 tools/scenario/apply_han_world.py --map han-world-v2 --check
python3 -m unittest discover -s tools/map/tests -p 'test_*.py'
python3 -m unittest discover -s tools/scenario/tests -p 'test_*.py'
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build --no-daemon
cd web/game && pnpm typecheck && pnpm test && pnpm build
```

Confirm exact 1,524/1,020/172 land hierarchy, unchanged legacy artifact hashes, reviewed 781-node V2
set, no dangling scenario references, and PRECHECK/FULL equality. Request independent review focused
on ID reuse, array-domain confusion, scenario migration, accidental 魯–歷城 adjacency, and hidden full-build
drift.

Update #473/#474 with the stable route identity and real regression evidence, #596 with the corrected
歷城 parent/runtime state, and #598 only if land geometry changed (this plan should not change it).
Do not close broader issues. Record commits, hashes, test counts, issue comments, and remaining
water-topology dependency in the task report.
