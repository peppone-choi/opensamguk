# 0B-j — v2 world/profile/catalog/wire/Flyway query, read, and write impact inventory

## Reading rule

Each row is an observed source seam in the working tree, named by file and
symbol. “Future impact” identifies a deliberate binding or invalidation point
for a later v2 consumer; it is **not** a claim that the consumer, query, table,
or write has been implemented.

## Observed seams

| Area | Observed file and symbol | Current behavior | Future v2 impact / invalidation boundary |
|---|---|---|---|
| Process world identity | `app/game-engine/src/main/kotlin/opensamguk/engine/config/WorldIdConfig.kt` — `engineProcessWorld`; `EngineProcessWorld.worldId` | The engine obtains one `WorldId` from `OPENSAMGUK_WORLD_ID`. | A v2 engine bean must consume this configured identity inside the gate; it must not mint a second world identity. |
| Process world identity | `app/game-api/src/main/kotlin/opensamguk/gameapi/config/GameApiProcessWorldIdConfiguration.kt` — `gameApiProcessWorld`; `GameApiProcessWorld.worldId` | The API obtains its process world from `opensamguk.world-id`. | A future v2 API read/intake surface must bind this identity explicitly and retain world-scoped reads. |
| Profile/feature gate | `infra/src/main/kotlin/opensamguk/infra/v2/V2SandboxGate.kt` — `PROPERTY`, `PROFILE`; engine `V2SandboxConfiguration.v2SandboxMarker`, `v2ContentCatalog`, `v2CityCatalogAdapter`; game-api `V2SandboxConfiguration.v2SandboxMarker` | `v2.enabled=true` **and** the `v2-sandbox` profile are both required. With both, the engine registers the marker/catalog/adapter; game-api registers only its marker. Either condition alone leaves the v2 beans absent. | New v2 profile/catalog/read/intake beans belong inside this AND gate. No existing v1 bean is an invalidation target. |
| Read-only city catalog | `infra/src/main/kotlin/opensamguk/infra/v2/V2ContentCatalog.kt` — `load`; `V2ContentMetadata`; `infra/src/main/resources/content/v2/cities_1010.json` | Only typed `ACTIVE` metadata loads. The metadata points at the existing tracked payload and validates status, source, SHA, and counts. | Future caching or a catalog-query endpoint must invalidate a `V2CityCatalogSnapshot` only after a newly validated metadata/source pair; 0B creates no cache or endpoint. |
| Typed snapshot adapter | `infra/src/main/kotlin/opensamguk/infra/v2/V2CityCatalogAdapter.kt` — `load`, `V2CityCatalogSnapshot.diff` | It follows `metadata.source` through `ClassPathResource`, validates the approved SHA/94/24 values and unique city ids, and compares typed snapshots in memory. | This is the only identified 0B city read adapter. It is not a `ScenarioImporter` call and creates no world rows. |
| v1 seed admission/write boundary | `infra/src/main/kotlin/opensamguk/infra/seed/ScenarioSeedCoordinator.kt` — `importFresh`, `ensureSeeded` | The v1 seed coordinator locks and writes canonical `world_state` admission. | Do not route the v2 catalog through this coordinator in 0B; OPENSAM-44 only decomposes persistence ownership, while actual v2 seed/persistence begins with OPENSAM-150 and later product owners. |
| v1 cold snapshot read boundary | `app/game-engine/src/main/kotlin/opensamguk/engine/boot/WorldSnapshotLoader.kt` — `buildSnapshot` | It calls v1 seed bootstrap and JDBC-loads the daemon’s v1 world snapshot. | A v2 catalog load must not be treated as an in-memory v1 world rehydrate or invalidate this snapshot in 0B. |
| v1 city/world read queries | `app/game-api/src/main/kotlin/opensamguk/gameapi/read/CityReadRepository.kt` — `findAll`, `findById`, `findByNationIdOrderByIdAsc`; `WorldStateReadRepository.kt` — `findProcessWorld` | These JPA reads are scoped to `GameApiProcessWorld`. | A future v2 city/profile read model needs its own explicit query contract; it must not silently reinterpret v1 `city`/`world_state` rows as v2 catalog state. |
| Daemon write boundary | `app/game-engine/src/main/kotlin/opensamguk/engine/turn/ChangeRecorder.kt` — `ChangeRecorder`; `infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt` — `flush` | Daemon mutations are recorded then JDBC-flushed with a matching `worldId`. | No 0B catalog or wire object may write through this path. A future v2 leaf must declare its recorder/flush contract before it becomes a consumer. |
| Flyway location and schema-query boundary | `app/game-engine/src/main/resources/application.yml` and `app/game-api/src/main/resources/application.yml` — `spring.flyway.locations`; `infra/src/main/resources/db/migration_v2/README.md`; `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2ProductionContextBeanGateIT.kt` — the booted `V2ProductionShapeBeanGateIT` / `V2BothConditionsBeanGateIT` contexts; `V2FlywayIsolationAssertions.assertV1DefaultRuntime` / `assertV2SandboxRuntime`; `app/game-engine/src/test/resources/db/migration_v2/V900__v2_sandbox_probe.sql` | The real v1 Spring context resolves only `classpath:db/migration` and observes no V900 history/table. The real `v2-sandbox` context receives literal `V2_ENABLED` and `SPRING_FLYWAY_LOCATIONS`, resolves both sibling locations, applies the test-only V900 probe, and introspects every applied v2-created table for world-scoped constraints. Production `migration_v2` still has no SQL. | A future v2 Flyway leaf must select an explicit sibling location, stay world-scoped, and use a new forward migration. It must not make v1 discover a v2 path. There is no real v2 migration/leaf in this inventory. |
| Versioned v2 result/event shapes | `common/src/main/kotlin/opensamguk/common/wire/v2/V2WireEnvelope.kt` — `V2CommandResultEnvelope`, `V2TurnEventEnvelope`, encode/decode functions | The two schemas have independent explicit version constants and carry `worldId` plus `committedWorldVersion`; they are pure round-trip contracts. | When a v2 producer/consumer is added, `committedWorldVersion` is the candidate read-invalidation fence. 0B does not bind these types to a Redis publisher, controller, SSE relay, or UI. |
| Existing v1 result/realtime path (non-mutation anchor) | `app/game-engine/src/main/kotlin/opensamguk/engine/redis/RealtimePublisher.kt` — `publishCommandResultPayload`, `publishTurnCompleted`; `common/src/main/kotlin/opensamguk/common/wire/StreamKeys.kt` — `commandResultKey`, `gameEventChannel`; `app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt` — `commandResult`; `app/game-api/src/main/kotlin/opensamguk/gameapi/sse/RealtimeSubscriber.kt` — `realtimeListenerContainer`; `web/game/lib/api.ts` — `pollCommandResult` | v1 publishes and consumes its existing result/SSE payloads through world-scoped Redis names and REST/SSE clients. | v2 wire versioning must use a separately chosen producer/consumer route/channel or an explicitly versioned multiplexing rule. 0B-h/i must not alter this v1 serialization path. |

## Explicit non-goals and unimplemented seams

- No `v2_city_ledger`, profile table, v2 catalog database table, Flyway SQL leaf,
  JPA entity, repository, cache, command handler, Redis producer, REST controller,
  SSE subscription, or frontend invalidation has been completed by 0B-j.
- The test-resource `V900__v2_sandbox_probe.sql` is a Flyway isolation probe, not
  a production v2 table or OPENSAM-150 leaf.
- OPENSAM-44 owns persistence contract reconciliation and just-in-time ownership decomposition, not
  a product schema. OPENSAM-150 owns the first product v2 persistence leaf and production migration
  `V901`; later product tickets own later leaves. OPENSAM-104/105 own RTK builder inputs. G0, 1,180
  content, and `CountyParticipationFixture` remain post-open work.
- No v1 `TurnDaemonCommandResult`, `RealtimeEvent`, `CityReadRepository`,
  `WorldStateReadRepository`, or production Flyway location is changed by this
  inventory.
- Production deploy/cutover and any data migration are outside the approved
  documentation lane.

## Verification status

This is a source-inventory artifact. The docs lane inspected the named symbols
and the approved city-source hash contract; it did not run the runtime/Flyway
tests or claim their completion. The OPENSAM-43 plan remains the authoritative
place for 0B-b through 0B-k runtime acceptance commands.
