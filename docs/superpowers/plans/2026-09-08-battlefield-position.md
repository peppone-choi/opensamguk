# Battlefield Position Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Preserve independent battlefield presence and return origin across position transitions and database reload.

**Architecture:** A battlefield is a named sublocation of a real land province or water zone. Extend the existing authoritative general_spatial_position row with an optional atomic BattlefieldPresence value (siteId, catalogHash, returnCityId). This avoids changing the physical topology graph or duplicating mutable position in general.meta. Catalog and ingress validation belongs to the movement resolver; the persistence boundary rejects malformed presence.

**Tech Stack:** Kotlin, PostgreSQL/Flyway, JDBC.

**Spec:** ../specs/2026-09-08-independent-historical-battlefields.md

## Global Constraints

Preserve existing position constructors with null presence defaults. ChangeRecorder is the sole writer. A same-node entry/exit changes revision. Presence is not a new administrative city or ownership record.

## Task 1: Position transitions

Files: logic/.../world/GeneralPositionState.kt and GeneralPositionStateTest.kt.

- [ ] Add a failing test for entering a named battlefield in the same province, stale revision rejection, then exit clearing presence.
- [ ] Run :logic:test --tests '*GeneralPositionStateTest' and confirm the missing API failure.
- [ ] Add `data class BattlefieldPresence(val siteId: String, val catalogHash: String, val returnCityId: Int)`; validate slug, SHA-256, positive city ID.
- [ ] Add `val battlefield: BattlefieldPresence? = null` to state and assessment. Include it in equality/no-op and projected next state.
- [ ] Run focused tests and retain existing spatial transition tests.

## Task 2: Database round trip

Files: V59 migration, GeneralPositionRowCodec, JdbcFlushExecutor, WorldSnapshotLoader, SpatialStateReadRepository and spatial persistence tests.

- [ ] Add failing round-trip coverage for non-null presence and clearing presence.
- [ ] Add nullable battlefield_id, battlefield_catalog_hash, battlefield_return_city_id with all-null/all-valid CHECK.
- [ ] Decode all three fields as one value; reject partial or malformed persisted rows.
- [ ] Include columns in both INSERT and CAS UPDATE, boot SELECT, and API snapshot SELECT.
- [ ] Run persistence integration tests against PostgreSQL and boot/read regression tests.

This foundation is not a claim that gameplay movement is connected. Runtime catalog validation, command gates, arrival combat and UI are required by the parent spec.
