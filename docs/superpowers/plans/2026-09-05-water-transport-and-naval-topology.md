# Water Transport and Naval Strategic Topology Implementation Plan

> **Compatibility amendment:** Execute new Han runtime integration against
> `han-world-v3` (781), not persisted `han-world-v2` (774). Wave 1's land node type
> must use exact string spatial IDs when the Wave 3 loader is added. Wave 2's
> reviewed geometry is rebound to Track C's exact base hash only after confirming
> terrain/owner grids and the land ID set are unchanged. No new port/crossing is
> authorized by this hash refresh.

> **For Codex:** Execute each wave with `superpowers:test-driven-development`. Stop at explicit
> dependency gates rather than placing temporary naval logic in game-engine. Run independent review
> and `superpowers:verification-before-completion` at every wave boundary.

**Goal:** Add stable, evidence-backed river/lake/coastal strategic zones and typed traversal edges;
make Han V2 movement, transport, supply, map preview, and replay consume one topology revision; then
feed that topology into the approved NAVAL battle adapter after the battle foundation exists.

**Architecture:** Preserve the exact 1,524 land province IDs and place a separately versioned
`WaterZone`/`TraversalEdge` graph beside them. A pure logic module resolves paths. A protected Python
pipeline creates a hash-pinned artifact from reviewed rows. Infra loads the same artifact for API and
engine. Runtime water control is campaign state; battle-engine consumes immutable tickets and never
writes campaign tables.

**Tech Stack:** Python 3, Kotlin/JDK 21, kotlinx serialization/Jackson, Spring Boot, PostgreSQL/Flyway,
React/TypeScript/Canvas, Gradle, pnpm.

---

## Dependency gates

1. **Stable route identity:** the Track C plan must land before a missing runtime city such as
   歷城 can be introduced without renumbering downstream IDs.
2. **Supply safety:** destructive supply must use the approved dual-evidence gate before land/water
   edges are removed or blocked.
3. **Battle foundation:**
   `docs/superpowers/plans/2026-07-30-v2-realtime-battle-foundation-implementation-plan.md` must land
   before Wave 6. `app:battle-engine` does not exist today.
4. **Evidence:** unreviewed raster rivers/lakes may generate candidates but never canonical shortcuts,
   ports, or crossings.

Waves 1–2 are independent of stable runtime city ordinals and may start immediately. Wave 3 follows
Track C. Wave 4 follows supply safety. Wave 6 follows the battle foundation.

## Wave 1: Pure strategic topology and deterministic path resolver

**Files:**

- Create: `logic/src/main/kotlin/opensamguk/logic/world/StrategicTopology.kt`
- Create: `logic/src/main/kotlin/opensamguk/logic/world/StrategicPathResolver.kt`
- Create: `logic/src/test/kotlin/opensamguk/logic/world/StrategicTopologyTest.kt`
- Create: `logic/src/test/kotlin/opensamguk/logic/world/StrategicPathResolverTest.kt`

**Step 1: Red-test the domain invariants**

Test exact enums and records from the approved spec:

- `WaterZoneKind`, `TraversalMode`, `SeasonalAvailability`, `StrategicNodeRef`;
- separate land/water namespaces;
- no duplicate node/edge IDs, self edges, dangling refs, or duplicate canonical edge keys;
- only reviewed directed river edges may be asymmetric;
- deterministic ordering and revision/hash input;
- `supplyAllowed` is explicit and defaults false for water edges.

**Step 2: Red-test path behavior**

Use synthetic graphs to prove:

- dry `LAND` adjacency works;
- `RIVER_BARRIER` removes implicit dry crossing;
- only active `FORD|BRIDGE|FERRY` crosses that barrier;
- embark/water/disembark requires compatible ordered edges and capacity;
- upstream/downstream costs differ deterministically;
- seasonal closure and blockade yield typed denial codes;
- no lake or open-sea shortcut is inferred;
- equal-cost paths use stable edge-ID tie-breaking;
- returned path includes ordered nodes/edges, mode, total cost, capacity, revision, and hash.

Run and capture RED:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test \
  --tests 'opensamguk.logic.world.StrategicTopologyTest' \
  --tests 'opensamguk.logic.world.StrategicPathResolverTest' \
  --no-daemon
```

**Step 3: Implement the pure model and resolver**

Keep the resolver free of Spring, DB, Jackson, and runtime world objects. Dynamic inputs are an
immutable edge-state snapshot keyed by edge ID. Use integer costs/capacities and stable priority
queue ordering; no wall-clock or unordered-map iteration may affect results.

**Step 4: Commit Wave 1**

```text
feat(map): add typed strategic topology resolver

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

## Wave 2: Reviewed water artifact and protected generator

**Files:**

- Create: `data/curated/han/water-topology-adjudications-v1.json`
- Create: `data/map/han-water-topology-v1.json`
- Create: `data/map/han-strategic-topology-manifest-v1.json`
- Create: `tools/map/build_han_water_topology.py`
- Create: `tools/map/audit_han_water_topology.py`
- Create: `tools/map/tests/test_build_han_water_topology.py`
- Create: `tools/map/tests/test_audit_han_water_topology.py`
- Modify: `tools/map/han_tiles_contract.py`
- Modify: `tools/map/tests/test_han_tiles_contract.py`

**Step 1: Red-test a hash-pinned derived-overlay contract**

The existing land contract says `han-tiles.json` is the sole coordinate-bearing artifact. Extend it
narrowly: the water overlay may carry cell/component geometry only when it pins the exact
`han-tiles.json` SHA-256, projection metadata, dimensions, and terrain legend. It may not redefine a
land owner, province, jurisdiction, commandery, or place coordinate.

Tests must reject wrong base hash, dimensions, projection, land ID set, unreviewed geometry, and any
attempt to write land ownership. Preserve protected-orchestrator attestation rules.

**Step 2: Red-test generator/audit behavior**

Test stable IDs under shuffled input, byte-identical regeneration, exact schema, evidence/source
requirements, endpoint existence, mode-specific fields, directed-flow pairing, zero unapproved
crossings, zero per-water-tile nodes, and zero deep-sea shortcuts.

**Step 3: Materialize a reviewed pilot, not an automatic national network**

Use existing source-ledger rows to create the smallest representative artifact that can verify all
three semantics:

- one reviewed river barrier/crossing pair;
- one reviewed `LAKE_BASIN` around 彭蠡/鄱陽 evidence already present in the disconnection dossier;
- one reviewed coastal separation/route candidate around 合浦–朱崖.

Every row must cite exact existing repository/source IDs. If the repository does not contain enough
evidence for a river crossing, keep that production row absent and the audit red-blocked for river
route activation; do not infer a ford from raster contact. Synthetic fixtures still test the river
contract.

**Step 4: Implement deterministic builder and audit**

The builder reads `han-tiles.json` plus reviewed adjudications and emits stable zones/edges and a
manifest of hashes/counts. It must not revive the dormant automatic road/water A* exporter. The audit
supports `--check`, verifies committed bytes, and prints deterministic zone/mode/evidence counts.

**Step 5: Verify and commit Wave 2**

```bash
python3 -m unittest tools.map.tests.test_han_tiles_contract
python3 -m unittest tools.map.tests.test_build_han_water_topology
python3 -m unittest tools.map.tests.test_audit_han_water_topology
python3 tools/map/build_han_water_topology.py --check
python3 tools/map/audit_han_water_topology.py --check
```

```text
feat(map): materialize reviewed Han water topology

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

## Wave 3: Shared loader and unified Han V2 land/water route resolution

**Files:**

- Create: `infra/src/main/kotlin/opensamguk/infra/seed/HanStrategicTopologyJson.kt`
- Create: `infra/src/test/kotlin/opensamguk/infra/seed/HanStrategicTopologyJsonTest.kt`
- Modify: `common/src/main/kotlin/opensamguk/common/wire/CityTransportCommand.kt`
- Modify: `common/src/test/kotlin/opensamguk/common/wire/v2/V2CityTransportWireTest.kt`
- Modify: `logic/src/main/kotlin/opensamguk/logic/v2/command/V2CommandDecisions.kt`
- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/v2/V2CityTransportHandler.kt`
- Modify: `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2CityTransportRulesTest.kt`
- Modify: `app/game-api/src/main/kotlin/opensamguk/gameapi/v2/V2CommandPrecheckService.kt`
- Modify: `app/game-api/src/test/kotlin/opensamguk/gameapi/v2/V2CommandPrecheckServiceTest.kt`
- Modify: `web/game/components/v2/CityTransportForm.tsx`
- Modify: the existing CityTransport form test under `web/game/__tests__`

**Step 1: Wait for the stable-ordinal/歷城 gate**

Require physical place ↔ stable runtime route-node identity. Do not index the resolver solely by current
numeric array position. The required regression is 盧縣 `45098` ↔ 歷城縣 `45022`, six shared edges,
five dry, resolving as a one-edge land path.

**Step 2: Red-test one authoritative route result**

Replace `hopDistance` in the V2 decision context with a `ResolvedStrategicPath?`. Test typed denial
reasons, stale revision/hash, capacity, and deterministic path acceptance. Keep current resource,
escort, authority, and atomic ledger mutations unchanged.

Extend `CityTransport` with optional versioned `routePathHash` while retaining `routeRevision`.
Unknown/legacy payloads must fail closed in Han V2 and remain compatible in explicitly legacy modes.

**Step 3: Load the same artifact in API and engine**

`HanStrategicTopologyJson` parses/validates the artifact into logic records and verifies the manifest
hash. API precheck returns the exact server path, revision, and hash. The reserved command carries
them; engine re-resolves against the same revision and rejects drift before mutating ledgers.

Remove Han V2 authority from `CalcCityDistance`/`CityConst.path`; retain those classes for legacy
maps. UI must submit the server preview result instead of browser BFS.

**Step 4: Verify and commit Wave 3**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test --no-daemon
cd web/game && pnpm typecheck && pnpm test -- CityTransport
```

```text
feat(transport): resolve Han routes from strategic topology

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

## Wave 4: Water-aware supply and runtime control

**Files:**

- Create: `logic/src/main/kotlin/opensamguk/logic/world/WaterControlState.kt`
- Create: `logic/src/test/kotlin/opensamguk/logic/world/WaterControlStateTest.kt`
- Create: `infra/src/main/resources/db/migration/V*_create_water_zone_control.sql`
- Modify: `infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt`
- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/turn/ChangeRecorder.kt`
- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/turn/InMemoryTurnWorld.kt`
- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/world/HanSpatialSupplyProvider.kt`
- Modify: `app/game-engine/src/test/kotlin/opensamguk/engine/world/HanSpatialSupplyProviderTest.kt`
- Add matching persistence/flush/world tests beside existing tests

**Step 1: Wait for supply-safety gate**

Require the dual-evidence evaluator and canonical disagreement audit. No new barrier or blockade may
neutralize a city solely through spatial topology.

**Step 2: Red-test runtime control and supply edges**

Test `OPEN|CONTESTED|BLOCKED`, deterministic controlling/contesting nation projection, optimistic
revision, ChangeRecorder delta, JDBC batch ownership, and snapshot restore. Water zones have dynamic
control only; scenario nation ownership must remain absent.

Supply tests prove only `supplyAllowed` and currently open/controlled typed edges extend reachability.
Blockade/season closure removes that extension but passes through the dual-evidence safety verdict.

**Step 3: Implement campaign-owned state**

Game-engine is the only writer. Extend existing ChangeRecorder/JDBC batch paths; no inline JPA write.
Persist topology revision with control state and fail closed on an unknown zone/revision.

**Step 4: Verify and commit Wave 4**

Run focused logic/engine/infra tests, Testcontainers integration when Docker is available, and
architecture scans proving the ONE daemon-write rule.

```text
feat(supply): route supply through controlled waterways

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

## Wave 5: API and strategic map layers

**Files:**

- Create: `app/game-api/src/main/kotlin/opensamguk/gameapi/dto/StrategicTopologyDto.kt`
- Create: `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/MapStrategicTopologyController.kt`
- Create: `app/game-api/src/test/kotlin/opensamguk/gameapi/controller/MapStrategicTopologyControllerTest.kt`
- Modify: `web/game/lib/api/types.ts`
- Modify: `web/game/lib/api/client.ts`
- Modify: `web/shared/src/HanMapCanvas.tsx`
- Modify: `web/game/components/game/MapViewer.tsx`
- Modify: `web/game/__tests__/HanMapCanvas.interaction.test.tsx`
- Modify: `web/game/__tests__/MapViewer.props.test.tsx`

**Step 1: Red-test revision-consistent read models**

The endpoint returns topology revision/hash, zones, typed edges, barrier/crossing/port metadata, and
dynamic control. Reject artifact/control revision mismatch. Do not add water zones to
`provinceOccupancy`.

Canvas tests cover independent layers for river barrier, ford/bridge/ferry, navigable reach, lake,
coast, port, blockade, and selected server-resolved route. Political land color remains unchanged.

**Step 2: Implement API and presentation**

Use a dedicated topology endpoint so the live ownership preview is not duplicated. `MapViewer`
fetches both and renders only matching revisions. Route preview displays mode, turns/cost, capacity,
season/blockade, and landing transitions from the server path; it never runs a separate BFS.

**Step 3: Verify visible behavior and commit**

Run focused controller/Vitest tests, web typecheck/build, and local screenshots when a seeded world is
available. Verify water control is visually distinct from administrative ownership.

```text
feat(map): display water routes and control layers

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

## Wave 6: NAVAL adapter and amphibious handoff

**Dependency:** Complete the approved battle foundation first. Do not create a temporary battle loop
inside `app:game-engine`.

**Files after that foundation exists:**

- Create: `battle/src/main/kotlin/opensamguk/battle/naval/NavalBattleTopology.kt`
- Create: `battle/src/main/kotlin/opensamguk/battle/naval/NavalBattleState.kt`
- Create: `battle/src/main/kotlin/opensamguk/battle/naval/NavalBattleReducer.kt`
- Create: `battle/src/main/kotlin/opensamguk/battle/naval/NavalObjective.kt`
- Create matching deterministic tests under `battle/src/test/kotlin/opensamguk/battle/naval/`
- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/naval/NavalBattleAdapter.kt`
- Create: `app/battle-engine/src/test/kotlin/opensamguk/battleengine/naval/NavalBattleAdapterIT.kt`
- Modify the battle ticket/handoff contracts produced by the foundation
- Modify game-engine campaign result application for exactly-once landing handoff

**Step 1: Red-test deterministic river/lake/coastal fixtures**

Pin topology revision, engagement water zone, depth/flow/wind terrain artifact, fleets, transports,
objectives, and RNG seed. Prove state-hash replay, directed retreat, convoy/blockade outcomes, invalid
shore rejection, and exactly-once landing result.

**Step 2: Implement the NAVAL adapter only through the common SPI**

Keep LAND/SIEGE/NAVAL as the only battle kinds. Battle-engine writes only `battle_*`; game-engine
applies campaign water control, transport loss, port effects, and landing handoff through
ChangeRecorder. A successful landing creates/reinforces a land/siege handoff rather than mutating a
county from battle-engine.

**Step 3: Run battle gates and commit**

Run the full battle-module/app tests, persistence ownership tests, replay hash tests, recovery tests,
and 32-formation performance gate from the battle foundation.

```text
feat(battle): add deterministic naval engagements

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

## Final verification, issues, and reports

At every landed wave:

1. Run `git diff --check`, focused tests, affected module suites, then the full repository gate.
2. Confirm Gradle `BUILD SUCCESSFUL` and XML results under JDK 21.
3. Run all water, land-topology, supply-disagreement, scenario, and `build_han_world --check-gate`
   audits.
4. Request independent review and resolve every fix-required finding.
5. Update only the issues evidenced by that wave:
   - #473 topology/crossings/revision;
   - #474 multi-turn movement/transport;
   - #475 preview/replay;
   - #463 reviewed river geometry/evidence;
   - #492 coastal provenance;
   - #349 NAVAL adapter only after Wave 6;
   - #598 only for actual land-geometry repair.
6. Do not close downstream issues from foundation-only work.
7. Write one metarepo task report per completed wave under `reports/opensamguk/tasks/`, with commits,
   tests, issue mutations, artifact hashes, and remaining dependency gates.
