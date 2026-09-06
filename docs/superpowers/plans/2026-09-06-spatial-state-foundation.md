# Spatial State Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox syntax for tracking.

**Goal:** Add durable, world-scoped province occupancy and general strategic position channels without activating unfinished gameplay rules.

**Architecture:** Follow the existing WaterControlSnapshot pattern: immutable pure state, typed revisioned writes, ChangeRecorder, the existing fenced JDBC transaction, strict cold-boot and read codecs. Missing state is unknown, never inferred from a nearby city or silently neutral. This is the first foundation slice of the approved multi-system change, not the full gameplay rollout.

**Tech Stack:** Kotlin, Java 21, Spring JDBC, PostgreSQL/Flyway, JUnit 5/Testcontainers.

**Spec:** `docs/superpowers/specs/2026-09-06-province-front-and-county-capture-design.md`, especially §§1,4,5,8.

## Global Constraints

- `InMemoryTurnWorld` is the execution authority; mutations use `ChangeRecorder` → `JdbcFlushExecutor` only.
- Stable province IDs are strings, not array indices or runtime city IDs. Nation `0` is explicit neutral; missing rows are unknown.
- Support only explicit `han-world-v3`, pin revision/hash to `StrategicTopologySnapshot`.
- Existing world/version writer fencing and row-level CAS must protect all new writes in the same transaction.
- No production activation, automatic backfill, inferred initial positions, numerical balance changes, merge/deploy, golden edits, or dirty-worktree cleanup. The user's later 2026-09-06 request authorizes commit/push and a PR only.
- All source comments are English; player-facing strings are Korean. Preserve unrelated edits.
- Verify tests with `BUILD SUCCESSFUL` and XML counts; distinguish skipped PostgreSQL tests from executed tests.

## Scope boundary and successors

This plan installs and verifies storage channels only. It does not switch General.cityId, existing combat, map occupancy or supply consumers to new state.
Complete canonical seeding/admission (including the eight unmapped cities), county-administration persistence, capture events, movement-cost/progress state,
command/UI adapters and persistent orders are successor slices. Empty foundation snapshots must not be advertised as active complete gameplay state.
Keeping these channels unused by existing commands until admission is complete prevents an old world from silently acquiring invented territory or positions.

### Task 1: Pure revisioned spatial state

**Files:**
- Create: `logic/src/main/kotlin/opensamguk/logic/world/ProvinceControlState.kt`
- Create: `logic/src/main/kotlin/opensamguk/logic/world/GeneralPositionState.kt`
- Test: `logic/src/test/kotlin/opensamguk/logic/world/ProvinceControlStateTest.kt`
- Test: `logic/src/test/kotlin/opensamguk/logic/world/GeneralPositionStateTest.kt`

**Interfaces:** Mirror WaterControl's small typed interfaces without changing it:

```kotlin
data class ProvinceControlState(val topologyRevision: String, val topologyHash: String,
    val provinceId: String, val nationId: Int, val revision: Long)
data class ProvinceControlAssessment(val topologyRevision: String, val topologyHash: String,
    val provinceId: String, val nationId: Int)
// Snapshot: immutable knownProvinceIds and statesByProvinceId; stateFor, withState, fromTopology.
// ProvinceControlChangeResult: Changed(expectedRevision: Long?, state), Unchanged(state), Denied(code).
fun projectProvinceControl(snapshot: ProvinceControlSnapshot, expectedRevision: Long?,
    assessment: ProvinceControlAssessment): ProvinceControlChangeResult

data class GeneralPositionState(val topologyRevision: String, val topologyHash: String,
    val generalId: Int, val node: StrategicNodeRef, val revision: Long)
data class GeneralPositionAssessment(val topologyRevision: String, val topologyHash: String,
    val generalId: Int, val node: StrategicNodeRef)
// Snapshot: immutable knownLandProvinceIds, knownWaterZoneIds, statesByGeneralId; stateFor,
// withState, withoutGeneral, fromTopology. Restrict node to known LandProvince or WaterZone.
// GeneralPositionChangeResult: Changed(expectedRevision: Long?, state), Unchanged(state), Denied(code).
fun projectGeneralPosition(snapshot: GeneralPositionSnapshot, expectedRevision: Long?,
    assessment: GeneralPositionAssessment): GeneralPositionChangeResult
```

Each denial enum includes UNSUPPORTED_WORLD, TOPOLOGY_MISMATCH, UNKNOWN_PROVINCE/UNKNOWN_NODE,
STALE_REVISION, REVISION_EXHAUSTED. General existence belongs to the recorder; invalid IDs/negative owner/revision and malformed SHA-256 are rejected at construction.
Expected revision must match the current row before no-op comparison. First write is revision 1, changed writes increment by 1, no-op preserves revision.
Collection inputs and exposed collections must be defensively immutable. Reject duplicate rows and topology mismatch in snapshots. Missing rows return null.

- [x] Add behavioral tests before production code. The breaks caught are neutral-vs-missing conflation, stale revision bypass, invalid topology/node acceptance, no-op revision growth, overflow and mutable aliases.

```kotlin
val snapshot = ProvinceControlSnapshot("r1", "a".repeat(64), setOf("p1"))
assertNull(snapshot.stateFor("p1"))
val result = projectProvinceControl(snapshot, null,
    ProvinceControlAssessment("r1", "a".repeat(64), "p1", 0)) as ProvinceControlChangeResult.Changed
assertEquals(0, result.state.nationId)
assertEquals(1L, result.state.revision)
assertNull(result.expectedRevision)
```

- [x] Run `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests '*ProvinceControlStateTest' --tests '*GeneralPositionStateTest' --console=plain`; record expected missing-feature failure.
- [x] Implement the types/projections following `WaterControlState.kt`; use checked revision increment after overflow guard and immutable defensive maps/sets.
- [x] Re-run targeted tests plus `*WaterControlStateTest`, inspect XML; self-review and report. No commit without user approval.

### Task 2: Strict row codecs and fenced storage

**Files:**
- Create: `infra/src/main/resources/db/migration/V50__create_spatial_state.sql`
- Create: `infra/src/main/kotlin/opensamguk/infra/persistence/ProvinceControlRowCodec.kt`
- Create: `infra/src/main/kotlin/opensamguk/infra/persistence/GeneralPositionRowCodec.kt`
- Modify: `infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt`
- Test: corresponding `*RowCodecTest.kt`, `SpatialStateFlushTest.kt` in infra persistence tests.

**Interfaces:** Produce `ProvinceControlWriteRow(expectedRevision: Long?, state: ProvinceControlState)`,
`ProvinceControlWriteBatch`, `GeneralPositionWriteRow(expectedRevision: Long?, state: GeneralPositionState)`,
`GeneralPositionWriteBatch`, `StaleProvinceControlException`, `StaleGeneralPositionException`.
Batch wrappers are deeply immutable, reject duplicate IDs and mixed pins, and preserve stable insertion order.
`FlushPayload` gains empty-default `provinceControlWrites` and `generalPositionWrites`.
When spatial rows are present, their topology pins must agree across province, position and any water rows in the same payload; reject mixed pins before writes.

- [x] Add codec/batch tests catching null/invalid integers, invalid node kind, invalid revision/hash, duplicate keys, mutable payloads, invalid expected revision.
- [x] Add PostgreSQL tests for insert/update/coalesced revision, same-ID different-world isolation and stale-row rollback of both spatial channels plus world fence.

```sql
-- New schema only, no seed/backfill. Follow V49 world foreign-key conventions.
-- province_control: PK(world_id, province_id), topology_revision TEXT NOT NULL,
-- topology_hash TEXT NOT NULL (lowercase 64 hex), nation_id INTEGER NOT NULL CHECK(nation_id >= 0),
-- revision BIGINT NOT NULL CHECK(revision > 0).
-- general_spatial_position: PK(world_id, general_id), topology pins, node_kind TEXT
-- CHECK(node_kind IN ('LAND_PROVINCE','WATER_ZONE')), node_id TEXT NOT NULL, revision BIGINT > 0.
-- FK(world_id, general_id) REFERENCES general(world_id, id) ON DELETE CASCADE;
-- the same-world composite PK already exists in V32.
```

- [x] Run new infra tests to capture RED. Implement strict codecs and tables, no lenient defaults. Add both batches to `flush()` after core general inserts/updates and before commit.
- [x] INSERT uses conflict-do-nothing with affected count exactly 1; UPDATE uses world+key+pins+expected revision with affected count exactly 1. General deletion must remove stored position and must not be followed by a queued position write for that general.
- [x] Reject a payload that includes a position write for a general in its deletedGenerals list. Preserve deletion cascade isolation across worlds.

```sql
UPDATE province_control SET nation_id=:nationId, revision=:revision
WHERE world_id=:worldId AND province_id=:provinceId
  AND topology_revision=:topologyRevision AND topology_hash=:topologyHash
  AND revision=:expectedRevision
```

- [x] Run targeted infra tests and WaterControl codec/flush regressions, inspect XML and report executed/skipped counts. No commit.

### Task 3: Recorder, restart and read boundary

**Files:**
- Modify: `logic/src/main/kotlin/opensamguk/logic/world/GeneralPositionState.kt` (add UNKNOWN_GENERAL denial for the recorder boundary).
- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/turn/InMemoryTurnWorld.kt`
- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/turn/ChangeRecorder.kt`
- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/flush/DatabaseHooks.kt`
- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/flush/FlushRecoveryGate.kt`
- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/boot/WorldSnapshotLoader.kt`
- Modify: `logic/src/main/kotlin/opensamguk/logic/memory/HotColdCatalog.kt`
- Create: `app/game-api/src/main/kotlin/opensamguk/gameapi/read/SpatialStateReadRepository.kt`
- Test: `app/game-engine/src/test/kotlin/opensamguk/engine/turn/SpatialStateRecorderTest.kt`
- Test: `app/game-engine/src/test/kotlin/opensamguk/engine/boot/SpatialStatePersistenceIT.kt`
- Test: `app/game-api/src/test/kotlin/opensamguk/gameapi/read/SpatialStateReadRepositoryTest.kt`
- Update tests covering FlushRecoveryGate, WorldSnapshotLoader and HotColdCatalog as needed without weakening old expectations.

**Interfaces:** `WorldSnapshot` gains nullable empty-default `provinceControlSnapshot` and `generalPositionSnapshot`.
World getters and internal dirty-free setters follow WaterControl; pins/catalogs cannot change after construction.
Any present province, position and water snapshots in one WorldSnapshot must share the same topology revision/hash and consistent land/water catalogues.
Recorder produces the two Task 2 batches with `writesFor(worldId)` guards. New loader/read queries use the shared Task 2 codecs, exact world scope and deterministic key order.

- [x] Add tests catching mutation while recorder is gated, cross-world recorder reuse/flush, incorrect earliest persisted revision after multiple same-turn changes, no-op dirtiness, missing-general positions, delete-then-write and create-then-delete leaks.

```kotlin
// Same turn: DB revision 3 -> state revision 4 -> state revision 5.
// Assert payload expectedRevision == 3L and final state revision == 5L.
// A new row changed twice retains expectedRevision == null.
// A snapshot obtained before the second change stays unchanged.
```

- [x] Run recorder/recovery tests RED, implement gated methods and batches. Return GeneralPositionChangeResult.Denied(UNKNOWN_GENERAL) for nonexistent/deleted generals before changing memory. General removal prunes its position snapshot and pending write; stored rows disappear in the same existing delete transaction. Core general creation must precede its first explicit position assessment.
- [x] Bind both typed batches through DatabaseHooks, include in isDirty/clear, classify new CAS exceptions RELOAD_REQUIRED. Do not add a duplicate DirtyState channel.
- [x] Extend explicit V3 cold boot to load both snapshots with the existing pinned topology loader; legacy maps return null and execute no new table query. Missing rows remain missing (storage foundation, not complete gameplay admission). Reject row pins/unknown nodes/orphan general IDs. Register boot reads in HotColdCatalog.
- [x] Existing WaterControlPersistenceIT currently stops migrations at V49. Preserve its V48→V49 empty-seed assertions, then migrate to V50 before invoking the newly extended current boot loader. Do not add a production missing-table fallback just to accommodate an old test schema.
- [x] Add read-only repository loading both channels for a supplied world/topology, returning immutable snapshots and no inferred ownership. Bind it to GameApiProcessWorld like WaterControlReadRepository and reject a mismatching requested world before SQL. If returning a combined snapshot, read its rows in one SQL statement or one read-only REPEATABLE_READ transaction so a concurrent flush cannot mix two completed versions. Do not replace existing map/supply consumers yet.
- [x] Prove real PostgreSQL recorder→DatabaseHooks→flush→cold boot/read roundtrip, multi-world isolation, rollback, general deletion and retry-payload immutability. Run focused engine/API tests and current water regressions.
- [ ] Update `docs/design/county-ownership-and-movement.md` and task report with storage-only status and remaining activation gates. Run independent review and full affected backend suites once. Preserve the paused worktree; no merge or deployment.

## Success condition

The first slice is complete only when typed occupancy and position changes survive the real fenced flush/restart/read path with isolation and rollback evidence.
It does not claim the user's original map/movement bug is fixed. The next gameplay slice must provide complete seeding/admission and event/command consumers before any user-visible switch.

## Paused at user request — 2026-09-06

The user requested a PR and all work to wait. Implementation and independent task/final code reviews are complete for the storage-only slice. The amended focused gate passed 85/85 tests with executed PostgreSQL and no skips. The first full five-module gate found one schema-inventory failure, which was fixed and covered by the focused gate. The amended full gate was interrupted at user request (exit 130), not marked successful; its API module had completed 614/614. Common/logic previously passed unchanged code (common from cache); one existing engine replay test requires an external PHP candidate.

A draft PR records the current implementation and verified evidence. No further implementation, test run, merge, deployment, or automatic follow-up is authorized while paused. The final verification checkbox intentionally remains open until the amended full gate is completed after resumption.
