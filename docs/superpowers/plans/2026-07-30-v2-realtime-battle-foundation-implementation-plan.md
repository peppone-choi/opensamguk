# OpenSamguk V2 Realtime Battle Common Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the isolated, deterministic, durable common battle foundation required by the approved V2 realtime battle design, without implementing land, siege, naval, or the 2.5D client.

**Architecture:** Add a pure JVM `battle` module for versioned contracts, deterministic state, authority, commands, replay, and the adapter SPI. Add a resource-only `battle-schema` module and one-shot `app:v2-schema-provisioner`; no long-running service receives object-owner or migrator membership. Add a dedicated Spring Boot `app:battle-engine` whose single-world session actors are the only writers of `battle_*`. Keep campaign locks, handoffs, deferred effects, and result application in the V2 `game-engine` process, and apply every campaign mutation through `ChangeRecorder -> JdbcFlushExecutor`. `game-api` issues short-lived battle JoinTickets after authoritative campaign reads. PostgreSQL is the correctness source; Redis is wakeup-only.

**Tech Stack:** Kotlin 2.1/JDK 21, Spring Boot 3.4.1, Spring JDBC transactions, PostgreSQL 16/Flyway, Spring WebSocket, kotlinx.serialization JSON, `LiteHashDrbg`, SHA-256, JUnit 5/Testcontainers, Gradle 8.12.

**Plan status:** PENDING USER APPROVAL. Independent review must be clear before this document is offered for approval; implementation and tracker writes remain out of scope until approval.

## Global Constraints

- Source design: `docs/superpowers/specs/2026-07-30-v2-realtime-battle-session-command-replay-design.md`.
- Governing decisions: ADR-LITE-018, 021, 023, and 025 in `.ai/decisions.md`.
- Hard predecessors: `OPENSAM-149`, `OPENSAM-35`, `OPENSAM-43` through `OPENSAM-48`, and `OPENSAM-56` must prove the V2 campaign identity/revision/runtime-state substrate, separate V2 DB/process profile, route/bean gate, migration owner, and v1 production absence. Task 0 is a stop gate; do not start Task 1 until every check is green.
- One process binds exactly one `WorldId` and one database. `battle-engine`, V2 `game-engine`, and V2 `game-api` must use the same V2 world/database values but separate processes.
- One-shot `app:v2-schema-provisioner` is the sole V2 Flyway migrator. It preserves `SPRING_FLYWAY_LOCATIONS` with default `classpath:db/v2/migration`, exits after migration/grants, and disables its transient login. `battle-engine`, V2 `game-engine`, and V2 `game-api` set `spring.flyway.enabled=false`.
- `battle-engine` may `SELECT` campaign-owned handoff/ack rows, but every `INSERT`, `UPDATE`, and `DELETE` it issues must target `battle_*`.
- `game-engine` must never write `battle_*`. Campaign battle mutations must travel through `ChangeRecorder`, `DatabaseHooks`, and `JdbcFlushExecutor`.
- Database roles enforce the same boundary: a `NOLOGIN` role owns V2 schema objects; a transient `NOINHERIT` migrator login may `SET ROLE` only during the one-shot provisioner and is reset to `NOLOGIN` afterward. Battle-engine uses distinct `V2_BATTLE_DB_USER` / `V2_BATTLE_DB_PASSWORD` credentials with DML only on `battle_*` and an explicit campaign read allowlist; V2 game-engine has no battle DML grant; V2 game-api is read-only. Runtime roles are not members of the owner or migrator role and cannot `SET ROLE` or re-grant ownership privileges.
- PostgreSQL rows are durable truth. Redis messages only wake a DB scan; losing or duplicating them cannot change correctness.
- `logic/war/*`, v1 `ProcessWar`, PHP goldens, `db/migration`, v1 routes, v1 beans, and v1 catalogs remain unchanged.
- Do not widen the frozen v1 `InMemoryTurnWorld`. Battle-lockable V2 campaign entities use a separate persisted runtime-state and revision surface, losslessly rehydrated before V2 intake opens.
- No implementation step may add an `EntityManager` write, inline campaign DML in a resolver, client-authoritative position/damage, floating-point simulation state, or wall-clock input to deterministic reduction.
- All stored contracts carry an explicit schema version. Existing event/snapshot/ticket readers are retained through upcasters; stored rows are never destructively rewritten.
- Hash and idempotency comparisons use exact canonical bytes. PostgreSQL-rendered `jsonb` text is a query projection only and is never hashed.
- Every commit is one logical change and ends with:

  ```text
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

- Test verdicts require `BUILD SUCCESSFUL` and XML with `failures="0"` and `errors="0"`; Gradle wrapper exit code alone is not evidence.
- The current worktree contains unrelated user changes. Stage only the exact paths named by the active task.

---

## Scope Check

This plan owns the common foundation only:

1. battle identity, message versions, deterministic clock/RNG/state codec/hash;
2. V2 campaign/battle schema and DML ownership guards;
3. campaign locks, handoff, deferred effects, reinforcement handoff, result apply/block acknowledgments;
4. battle ticket intake, session actor, epoch lease, event/snapshot storage, recovery;
5. authority snapshot, formation seats, orders, pause, reconnect, AI proxy, deputy succession;
6. WebSocket JoinTicket/ACK/faction projection;
7. reinforcement admission close, deadline resolver, replay, fault and server-load harnesses.

The following are separate required launch specs/plans and must not be implemented here:

- land adapter movement/contact/damage/objectives;
- siege adapter walls/gates/engines/breaches;
- naval adapter ships/depth/wind/fire/boarding;
- Three.js orthographic renderer, HUD, asset loader, and browser frame gate;
- cross-adapter G6 launch acceptance and realtime/fallback release decision.

This plan defines the SPI and fixtures those consumers require. It does not make G6 or the browser-render portion of G4/G5 green.

---

## Target File Structure

### New pure module

```text
battle/
  build.gradle.kts
  src/main/kotlin/opensamguk/battle/
    identity/BattleIds.kt
    contract/BattleTicket.kt
    contract/BattleMessages.kt
    contract/BattleResults.kt
    contract/BattleSchemaVersions.kt
    codec/CanonicalBytes.kt
    codec/CanonicalBattleStateCodec.kt
    codec/BattleSnapshotReader.kt
    deterministic/BattleClock.kt
    deterministic/BattleRng.kt
    deterministic/BattleState.kt
    deterministic/BattleStateHasher.kt
    adapter/BattleRulesAdapter.kt
    adapter/BattleRulesAdapterRegistry.kt
    adapter/BattleArtifactRegistry.kt
    authority/BattleAuthority.kt
    authority/FormationSeat.kt
    command/BattleCommand.kt
    command/BattleCommandReducer.kt
    lifecycle/BattleLifecycleReducer.kt
    replay/BattleReplayReducer.kt
  src/test/kotlin/opensamguk/battle/
    contract/BattleContractWireTest.kt
    codec/CanonicalBattleStateCodecTest.kt
    deterministic/BattleDeterminismTest.kt
    authority/BattleAuthorityReducerTest.kt
    command/BattleCommandReducerTest.kt
    lifecycle/BattleLifecycleReducerTest.kt
    replay/BattleReplayReducerTest.kt
```

### New resource-only schema module

```text
battle-schema/
  build.gradle.kts
  src/main/resources/db/v2/migration/
    V20260730_01__battle_session_foundation.sql
    V20260730_02__campaign_battle_boundaries.sql
  src/main/resources/db/v2/grants/
    migrator.sql
    battle_engine.sql
    game_engine.sql
    game_api.sql
```

### New one-shot schema provisioner

```text
app/v2-schema-provisioner/
  build.gradle.kts
  src/main/kotlin/opensamguk/provisioner/V2SchemaProvisionerApplication.kt
  src/main/resources/application.yml
  src/test/kotlin/opensamguk/provisioner/V2SchemaProvisionerIT.kt

docker/v2-schema-provisioner.Dockerfile
tools/v2-db/provision-battle-db.sh
```

### New dedicated service

```text
app/battle-engine/
  build.gradle.kts
  src/main/kotlin/opensamguk/battleengine/
    BattleEngineApplication.kt
    config/BattleEngineConfiguration.kt
    config/BattleEngineProcessWorld.kt
    config/BattleDatabaseWorldBindingVerifier.kt
    persistence/BattleHandoffReader.kt
    persistence/BattleSessionRepository.kt
    persistence/BattleEventRepository.kt
    persistence/BattleSnapshotRepository.kt
    persistence/BattleResultRepository.kt
    persistence/BattleReinforcementRepository.kt
    intake/BattleHandoffIntakeCoordinator.kt
    session/BattleSessionActor.kt
    session/BattleSessionSupervisor.kt
    session/BattleRecoveryService.kt
    command/BattleCommandIngress.kt
    auth/BattleJoinTicketVerifier.kt
    projection/FactionProjectionService.kt
    websocket/BattleWebSocketConfig.kt
    websocket/BattleWebSocketHandler.kt
    lifecycle/BattleDeadlineResolver.kt
    lifecycle/BattleDisconnectCoordinator.kt
    lifecycle/BattleReinforcementCoordinator.kt
    lifecycle/BattleResultAckCoordinator.kt
  src/main/resources/application.yml
  src/test/kotlin/opensamguk/battleengine/
    architecture/BattleEngineDmlOwnershipTest.kt
    architecture/BattleEngineDependencyBoundaryTest.kt
    persistence/BattleCommitBeforeAckIT.kt
    intake/BattleHandoffIntakeCoordinatorIT.kt
    session/BattleEpochFenceIT.kt
    session/BattleRecoveryIT.kt
    websocket/BattleWebSocketIT.kt
    projection/FactionProjectionLeakTest.kt
    lifecycle/BattleDeadlineRecoveryIT.kt
    lifecycle/BattleReinforcementHandshakeIT.kt
    replay/BattleReplay100xTest.kt
    fault/BattleFaultMatrixIT.kt
    load/BattleServerLoadGateIT.kt
```

### V2 campaign and join integration

```text
app/game-engine/src/main/kotlin/opensamguk/engine/v2/battle/
  CampaignBattleCoordinator.kt
  CampaignBattleDrain.kt
  CampaignBattleResultApplier.kt
  CampaignBattleAckRelay.kt
  DeferredCampaignEffectCoordinator.kt
  CampaignReinforcementCoordinator.kt
  CampaignBattleConfiguration.kt

app/game-engine/src/main/kotlin/opensamguk/engine/v2/campaign/
  CampaignEntityRevision.kt
  CampaignBattleRuntimeState.kt
  CampaignBattleStateLoader.kt
  CampaignMutationGate.kt
  CampaignMutationRootCatalog.kt

app/game-api/src/main/kotlin/opensamguk/gameapi/v2/battle/
  BattleJoinController.kt
  BattleJoinReadRepository.kt
  BattleJoinTicketIssuer.kt
  BattleJoinConfiguration.kt
```

### Shared persistence and operations

```text
docker/battle-engine.Dockerfile
tools/battle/run-server-load-gate.sh
tools/battle/assert-server-load-report.mjs
tools/battle/assert-mandatory-testcontainers.mjs
tools/battle/smoke-handoff-rejection.sh
```

Existing shared files widened by the foundation:

```text
settings.gradle.kts
infra/build.gradle.kts
app/v2-schema-provisioner/build.gradle.kts
app/game-api/build.gradle.kts
app/game-engine/build.gradle.kts
app/battle-engine/build.gradle.kts
app/game-api/src/main/resources/application-v2-sandbox.yml
app/game-engine/src/main/resources/application-v2-sandbox.yml
app/game-engine/src/main/kotlin/opensamguk/engine/turn/ChangeRecorder.kt
app/game-engine/src/main/kotlin/opensamguk/engine/flush/DatabaseHooks.kt
app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnRunService.kt
app/game-engine/src/main/kotlin/opensamguk/engine/config/DaemonLoopConfig.kt
app/game-engine/src/main/kotlin/opensamguk/engine/flush/DaemonWriteGuard.kt
infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt
app/game-engine/src/test/kotlin/opensamguk/engine/flush/DaemonNoEntityManagerTest.kt
infra/src/test/kotlin/opensamguk/infra/persistence/InfraNoEntityManagerTest.kt
docker-compose.yml
```

---

## Task 0: Verify V2 campaign revision, runtime-state, mutation-gate, and migration-owner predecessors

**Files inspected, not modified by this task:**

- Verify: `app/game-engine/src/main/kotlin/opensamguk/engine/v2/campaign/CampaignEntityRevision.kt`
- Verify: `app/game-engine/src/main/kotlin/opensamguk/engine/v2/campaign/CampaignBattleRuntimeState.kt`
- Verify: `app/game-engine/src/main/kotlin/opensamguk/engine/v2/campaign/CampaignBattleStateLoader.kt`
- Verify: `app/game-engine/src/main/kotlin/opensamguk/engine/v2/campaign/CampaignMutationGate.kt`
- Verify: `app/game-engine/src/main/kotlin/opensamguk/engine/v2/campaign/CampaignMutationRootCatalog.kt`
- Verify: `app/game-engine/src/test/kotlin/opensamguk/engine/v2/campaign/V2CampaignRevisionCrossCallSiteTest.kt`
- Verify: `app/game-engine/src/test/kotlin/opensamguk/engine/v2/campaign/V2CampaignRestartRehydrateIT.kt`
- Verify: `app/game-engine/src/test/kotlin/opensamguk/engine/v2/campaign/V2CampaignMutationGateArchitectureTest.kt`
- Verify: `app/game-engine/src/main/resources/application-v2-sandbox.yml`
- Verify: `app/game-api/src/main/resources/application-v2-sandbox.yml`
- Verify: `docs/superpowers/reviews/` predecessor review evidence for `OPENSAM-149`, `OPENSAM-35`, `OPENSAM-43` through `OPENSAM-48`, and `OPENSAM-56`
- Create: `docs/superpowers/reviews/2026-07-30-v2-battle-foundation-predecessor-evidence.md`

**Interfaces required:** Persisted monotonic revisions for every battle-lockable V2 campaign entity; separately rehydrated V2 battle runtime state; one mutation gate covering every V2 campaign mutation root; one V2 Flyway owner.

**Stop condition:** If any file, invariant, green test, or ticket evidence below is absent, stop this plan. Create or complete the missing predecessor issue and obtain its independent review before Task 1. Do not implement the missing campaign substrate opportunistically inside this battle-foundation branch.

- [ ] **Step 1: Verify persisted revision coverage**

  `CampaignEntityRevision` must identify every V2 general, formation, city, supply pool, and other entity that may enter a battle lock. Every mutation root named by `CampaignMutationRootCatalog` increments the persisted monotonic revision exactly once in the same campaign flush as its mutation.

- [ ] **Step 2: Verify restart-safe V2 runtime state**

  `CampaignBattleStateLoader` must losslessly rebuild `CampaignBattleRuntimeState`, including entity revisions, active locks, `lockGeneration`, `lockSetRevision`, deferred-effect sequence, and pending handoff/result acknowledgments before V2 intake opens. The implementation must not add these fields to v1 `InMemoryTurnWorld`.

- [ ] **Step 3: Verify complete mutation-gate coverage**

  `CampaignMutationGate` must make every catalogued V2 producer either mutate an unlocked entity or enqueue a durable deferred effect. Run:

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test \
    --tests '*V2CampaignRevisionCrossCallSiteTest' \
    --tests '*V2CampaignMutationGateArchitectureTest' \
    --tests '*V2CampaignRestartRehydrateIT' --rerun-tasks
  ```

  Expected: `BUILD SUCCESSFUL`; mandatory XML has `tests>0`, `failures=0`, `errors=0`, and `skipped=0`.

- [ ] **Step 4: Verify the single V2 migration owner**

  OPENSAM-35 must preserve the `SPRING_FLYWAY_LOCATIONS` environment override contract and prove the V2 location remains absent from v1 production. This predecessor check establishes the route/profile/database boundary; Task 4 replaces long-running migration with the one-shot provisioner. V2 game-engine, game-api, and battle-engine runtime configurations must disable Flyway.

- [ ] **Step 5: Record the predecessor evidence**

  Write the exact predecessor ticket keys, implementation commit SHAs, independent review verdicts, executed commands, and XML paths to `docs/superpowers/reviews/2026-07-30-v2-battle-foundation-predecessor-evidence.md`. Bind the record to the current base SHA, run `git diff --check`, and commit this durable checkpoint before Task 1:

  ```bash
  git add docs/superpowers/reviews/2026-07-30-v2-battle-foundation-predecessor-evidence.md
  git commit -m $'docs(v2-battle): record foundation predecessor evidence\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>'
  ```

---

## Task 1: Create the isolated module and application skeleton

**Files:**

- Modify: `settings.gradle.kts`
- Create: `battle/build.gradle.kts`
- Create: `battle-schema/build.gradle.kts`
- Create: `app/battle-engine/build.gradle.kts`
- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/BattleEngineApplication.kt`
- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/config/BattleEngineProcessWorld.kt`
- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/config/BattleEngineConfiguration.kt`
- Create: `app/battle-engine/src/main/resources/application.yml`
- Create: `app/battle-engine/src/test/kotlin/opensamguk/battleengine/config/BattleEngineProcessWorldTest.kt`
- Create: `app/battle-engine/src/test/kotlin/opensamguk/battleengine/architecture/BattleEngineDependencyBoundaryTest.kt`

**Interfaces produced:** Gradle modules `:battle` and `:app:battle-engine`; required single-world runtime configuration.

**Interfaces consumed:** `common.WorldId`; Task 0 process/world/database and migration-owner contract.

- [ ] **Step 1: Write the failing process-world test**

  ```kotlin
  class BattleEngineProcessWorldTest {
      @Test
      fun `process binds one positive world and one nonblank instance`() {
          val process = BattleEngineProcessWorld(7, "battle-node-a")
          assertEquals(WorldId(7), process.worldId)
          assertEquals("battle-node-a", process.instanceId)
          assertFailsWith<IllegalArgumentException> { BattleEngineProcessWorld(0, "node") }
          assertFailsWith<IllegalArgumentException> { BattleEngineProcessWorld(1, " ") }
      }
  }
  ```

- [ ] **Step 2: Run the test and observe the missing module/class failure**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:battle-engine:test \
    --tests '*BattleEngineProcessWorldTest' --rerun-tasks
  ```

  Expected: Gradle cannot resolve `:app:battle-engine` or compile `BattleEngineProcessWorld`.

- [ ] **Step 3: Add the modules and minimal configuration**

  `settings.gradle.kts` must contain:

  ```kotlin
  include("common", "logic", "battle", "battle-schema", "infra")
  include("app:gateway-api", "app:game-api", "app:game-engine", "app:battle-engine")
  ```

  `BattleEngineProcessWorld` must reject a non-positive world ID and blank instance ID. `application.yml` must require, without secret defaults:

  ```yaml
  opensamguk:
    world-id: ${OPENSAMGUK_WORLD_ID}
  battle:
    instance-id: ${BATTLE_ENGINE_INSTANCE_ID}
    join-ticket:
      current-kid: ${BATTLE_JOIN_TICKET_CURRENT_KID}
      current-secret: ${BATTLE_JOIN_TICKET_CURRENT_SECRET}
      previous-kid: ${BATTLE_JOIN_TICKET_PREVIOUS_KID:}
      previous-secret: ${BATTLE_JOIN_TICKET_PREVIOUS_SECRET:}
  spring:
    datasource:
      url: ${V2_BATTLE_DATABASE_URL}
      username: ${V2_BATTLE_DB_USER}
      password: ${V2_BATTLE_DB_PASSWORD}
    flyway:
      enabled: false
  server:
    port: ${BATTLE_ENGINE_PORT:8083}
  ```

- [ ] **Step 4: Prove dependency direction**

  `battle/build.gradle.kts` depends on `:common` and kotlinx serialization only. It must not depend on `:logic`, Spring, JDBC, or an app module. `battle-schema` applies the built-in `java-library` plugin, contains resources only, and exposes no Kotlin/Java production class. `app:battle-engine` depends on `:battle` and uses Spring WebSocket/JDBC/Actuator, PostgreSQL, and Testcontainers. It must not depend on `:battle-schema`, `:infra`, Flyway, or import `opensamguk.infra.persistence`. `BattleEngineDependencyBoundaryTest` fails if either forbidden project or migration resource appears in battle-engine's runtime graph.

- [ ] **Step 5: Run focused and module build checks**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
    :battle:test \
    :app:battle-engine:test --tests '*BattleEngineProcessWorldTest' \
    --tests '*BattleEngineDependencyBoundaryTest' \
    :app:battle-engine:bootJar --rerun-tasks
  ```

  Expected: `BUILD SUCCESSFUL`; all produced XML files have zero failures/errors.

- [ ] **Step 6: Commit**

  ```bash
  git add settings.gradle.kts battle battle-schema app/battle-engine
  git commit -m $'build(v2-battle): add isolated battle modules\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>'
  ```

---

## Task 2: Freeze versioned identities, tickets, messages, results, and adapter SPI

**Files:**

- Create: `battle/src/main/kotlin/opensamguk/battle/identity/BattleIds.kt`
- Create: `battle/src/main/kotlin/opensamguk/battle/contract/BattleSchemaVersions.kt`
- Create: `battle/src/main/kotlin/opensamguk/battle/contract/BattleTicket.kt`
- Create: `battle/src/main/kotlin/opensamguk/battle/contract/BattleMessages.kt`
- Create: `battle/src/main/kotlin/opensamguk/battle/contract/BattleResults.kt`
- Create: `battle/src/main/kotlin/opensamguk/battle/adapter/BattleRulesAdapter.kt`
- Create: `battle/src/main/kotlin/opensamguk/battle/adapter/BattleRulesAdapterRegistry.kt`
- Create: `battle/src/main/kotlin/opensamguk/battle/adapter/BattleArtifactRegistry.kt`
- Create: `battle/src/main/kotlin/opensamguk/battle/codec/CanonicalBytes.kt`
- Create: `battle/src/test/kotlin/opensamguk/battle/contract/BattleContractWireTest.kt`
- Create: `battle/src/test/kotlin/opensamguk/battle/adapter/BattleArtifactRegistryTest.kt`

**Interfaces produced:** Immutable `BattleTicketV1`; command/ACK/result envelopes; `BattleRulesAdapter`; immutable adapter and content-addressed artifact registries.

**Interfaces consumed:** `WorldId`, kotlinx serialization.

- [ ] **Step 1: Write wire round-trip and unknown-version rejection tests**

  Pin these discriminator values:

  ```kotlin
  enum class BattleKind { LAND, SIEGE, NAVAL }
  enum class BattleCommandScope { FORMATION, SIDE, SESSION_META }
  enum class BattleCommandVerdict { ACCEPTED, REJECTED }
  enum class BattleControlMode { DIRECT_TACTICS, ASSISTED_DIRECT, INTENT_DRIVEN_AI }
  enum class BattleSessionState {
      READY, STARTING, RUNNING, PAUSED, RECOVERING, RESOLVING,
      RESULT_PENDING, APPLIED, CANCELLED, QUARANTINED, RESULT_BLOCKED,
  }
  ```

  Test that `BattleTicketV1` JSON includes `ticketSchemaVersion=1`, `rulesetRevision`, `catalogRevision`, `terrainRevision`, all three content hashes, RNG algorithm/serializer IDs, lock generation/set revision, entity revisions, frozen authority, formation seats, legal objectives, and selected setup.

  `BattleCommandEnvelopeV1` must include `clientCommandId`, battle/scope/target fields, `expectedAuthorityRevision`, `intentType`, and versioned intent payload. Its durable idempotency identity is `(battleId, issuerParticipantId, clientCommandId, intentHash)`.

  Every durable ticket, handoff, event, command receipt, result, and reinforcement payload is encoded once into `canonicalPayload: CanonicalBytes`; `payloadHash` is SHA-256 over those exact bytes. A `jsonb` column may store a query projection derived from the same decoded object, but PostgreSQL JSON rendering, key order, whitespace, or numeric formatting never enters a hash. Tests must permute JSON object key order and prove the canonical hash is unchanged while altered semantic content changes it.

- [ ] **Step 2: Run RED**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :battle:test \
    --tests '*BattleContractWireTest' --rerun-tasks
  ```

  Expected: missing contract classes.

- [ ] **Step 3: Implement immutable V1 contracts**

  `CanonicalBytes` defensively copies every input/output byte array and implements content equality/hash code; raw mutable arrays never appear in immutable contracts.

  The adapter boundary must be exact:

  ```kotlin
  interface BattleRulesAdapter {
      val kind: BattleKind
      val rulesetRevision: String
      fun validateTicket(ticket: BattleTicketV1): AdapterValidation
      fun initialAdapterState(ticket: BattleTicketV1): CanonicalBytes
      fun validateIntent(view: BattleValidationView, intent: BattleIntent): IntentValidation
      fun step(input: AdapterTickInput): AdapterTickOutput
      fun resolve(input: AdapterResolveInput): CampaignBattleResultV1
      fun safeDefaultSetup(ticketDraft: BattleTicketDraftV1): PrebattleSetupV1
  }
  ```

  No adapter may access a socket, clock, database, Spring context, or mutable global registry.

- [ ] **Step 4: Add stable upcaster entry points**

  ```kotlin
  interface BattleTicketReader {
      fun read(schemaVersion: Int, payload: ByteArray): BattleTicketV1
  }

  interface BattleEventPayloadReader {
      fun read(eventType: String, schemaVersion: Int, payload: ByteArray): BattleEventPayload
  }

  ```

  V1 readers reject unknown versions with a typed `UnsupportedBattleSchemaVersion`; they never silently reinterpret them.

- [ ] **Step 5: Add immutable adapter and artifact lookup**

  `BattleRulesAdapterRegistry` is constructed once from an explicit adapter list and rejects duplicate `(BattleKind, rulesetRevision)` keys. `BattleArtifactRegistry` resolves ruleset, unit catalog, and terrain artifacts by the exact key `(BattleKind, revision, contentHash)` and fails closed if a revision exists with a different hash. No adapter may discover mutable classpath or network state after session creation.

- [ ] **Step 6: Run tests**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :battle:test --rerun-tasks
  ```

  Expected: `BUILD SUCCESSFUL`; wire bytes remain stable across two repeated encodes.

- [ ] **Step 7: Commit**

  ```bash
  git add battle/src/main battle/src/test
  git commit -m $'feat(v2-battle): define versioned battle contracts\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>'
  ```

---

## Task 3: Implement the deterministic kernel, pure lifecycle/authority/replay reducers, and fixture adapter

**Files:**

- Create: `battle/src/main/kotlin/opensamguk/battle/deterministic/BattleClock.kt`
- Create: `battle/src/main/kotlin/opensamguk/battle/deterministic/BattleRng.kt`
- Create: `battle/src/main/kotlin/opensamguk/battle/deterministic/BattleState.kt`
- Create: `battle/src/main/kotlin/opensamguk/battle/codec/CanonicalBattleStateCodec.kt`
- Create: `battle/src/main/kotlin/opensamguk/battle/codec/BattleSnapshotReader.kt`
- Create: `battle/src/main/kotlin/opensamguk/battle/deterministic/BattleStateHasher.kt`
- Create: `battle/src/main/kotlin/opensamguk/battle/authority/BattleAuthority.kt`
- Create: `battle/src/main/kotlin/opensamguk/battle/authority/FormationSeat.kt`
- Create: `battle/src/main/kotlin/opensamguk/battle/command/BattleCommand.kt`
- Create: `battle/src/main/kotlin/opensamguk/battle/command/BattleCommandReducer.kt`
- Create: `battle/src/main/kotlin/opensamguk/battle/lifecycle/BattleLifecycleReducer.kt`
- Create: `battle/src/main/kotlin/opensamguk/battle/replay/BattleReplayReducer.kt`
- Modify: `battle/build.gradle.kts`
- Create: `battle/src/testFixtures/kotlin/opensamguk/battle/fixture/FoundationFixtureAdapter.kt`
- Create: `battle/src/test/kotlin/opensamguk/battle/codec/CanonicalBattleStateCodecTest.kt`
- Create: `battle/src/test/kotlin/opensamguk/battle/deterministic/BattleDeterminismTest.kt`
- Create: `battle/src/test/kotlin/opensamguk/battle/authority/BattleAuthorityReducerTest.kt`
- Create: `battle/src/test/kotlin/opensamguk/battle/command/BattleCommandReducerTest.kt`
- Create: `battle/src/test/kotlin/opensamguk/battle/lifecycle/BattleLifecycleReducerTest.kt`
- Create: `battle/src/test/kotlin/opensamguk/battle/replay/BattleReplayReducerTest.kt`

**Interfaces produced:** `BattleClockState`, restorable `BattleRngCursor`, canonical binary state codec V1, SHA-256 state hash, all pure reducers required by actor recovery, and a non-production fixture adapter.

**Interfaces consumed:** `LiteHashDrbg(seed, stateIdx, bufferIdx)`.

- [ ] **Step 1: Write RED tests for the three clock domains**

  Assert:

  - `simulationTick` advances only in `RUNNING`;
  - `sessionElapsedMs` advances during pause and recovery;
  - `campaignDeadlineAtDb` and absolute disconnect deadlines never move;
  - recovery adds DB-observed downtime before live admission;
  - expired deadlines sort by `(deadlineAtDb, eventTypePriority, participantId)`.

- [ ] **Step 2: Write RED tests for RNG restoration and state bytes**

  Create one seed and snapshot at byte offsets `0`, `1`, `63`, `64`, and `65`, plus a cursor after multiple blocks. Persist `(algorithmId, seedSerializerRevision, cursorCodecRevision, seed, generatedBlockIndex, bufferIdx)`, restore, and assert the next 1,000 draws match for every boundary. Encode the same state 100 times and assert byte identity and one SHA-256 value.

- [ ] **Step 3: Implement the cursor wrapper without modifying `LiteHashDrbg`**

  ```kotlin
  data class BattleRngCursor(
      val algorithmId: String = "lite-hash-drbg-v1",
      val seedSerializerRevision: String = "seed-serializer-v1",
      val cursorCodecRevision: String = "battle-rng-cursor-v1",
      val seed: CanonicalBytes,
      val generatedBlockIndex: Long,
      val bufferIdx: Int,
  )
  ```

  `LiteHashDrbg` increments its internal `stateIdx` while generating the current 64-byte block, so snapshot uses `generatedBlockIndex = peekStateIdx() - 1` and restore calls `LiteHashDrbg(seed, stateIdx = generatedBlockIndex, bufferIdx = bufferIdx)`. Passing `peekStateIdx()` directly is an off-by-one stream change and is forbidden by the test. Do not add a second RNG stream inside a session.

- [ ] **Step 4: Implement canonical binary codec V1**

  Use explicit big-endian field writes, fixed enum numeric tags, sorted stable IDs, UTF-8 length prefixes, and no Java object serialization. Hash the uncompressed canonical bytes. Snapshot compression uses raw DEFLATE level 6 and stores `compressionRevision="deflate-raw-jdk21-v1"` separately.

  Add `BattleSnapshotReader.read(codecRevision, payload): BattleState`. It dispatches through an immutable codec registry, upcasts retained historical revisions, and rejects unknown codec/compression revisions without mutating stored rows.

- [ ] **Step 5: Write and implement the complete G2 authority/order matrix**

  Parameterize commander, officer, AI, delegate, revoke, reassign, commander override, stale authority, all three control modes, delayed mode switch, pause request/approve/deny/exhaustion, disconnect deadlines, deputy succession, reinforcement admission, and same-tick conflict ordering. Pin:

  ```text
  one participant controls at most one formation
  one formation has at most one human controller
  commander controls one formation plus side authority
  priority: COMMANDER > OFFICER > AI_POLICY
  tie: durable event_seq
  stale revision: STALE_AUTHORITY
  late admission: BATTLE_ADMISSION_CLOSED
  ```

  Every dynamic seat, authority, order, mode, reconnect, and deputy change becomes effective only at `deliverTick`. Pause stops simulation ticks but not logical time or the campaign deadline.

- [ ] **Step 6: Implement lifecycle and replay reducers before actor code**

  `BattleLifecycleReducer` owns legal state transitions, 30-second AI and 90-second deputy absolute deadlines, admission-close state, reinforcement-cap accounting, and deterministic deadline priority. `BattleReplayReducer` consumes only ticket plus committed event sequence and uses the adapter/artifact registries from Task 2. Neither reducer imports Spring, JDBC, sockets, or wall-clock APIs.

- [ ] **Step 7: Add the test-fixture adapter**

  Apply Gradle `java-test-fixtures` to `:battle`. `FoundationFixtureAdapter` uses integer positions and deterministic objective-score changes, accepts all three battle kinds for foundation tests, and emits a fixed `CampaignBattleResultV1`. It is available only through `testFixtures(project(":battle"))` and never appears in a production registry.

- [ ] **Step 8: Run pure tests twice**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :battle:test --rerun-tasks
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :battle:test --rerun-tasks
  ```

  Expected: both runs have the same asserted fixture hash and zero XML failures/errors.

- [ ] **Step 9: Commit**

  ```bash
  git add battle/build.gradle.kts battle/src/main battle/src/test battle/src/testFixtures
  git commit -m $'feat(v2-battle): add deterministic battle reducers\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>'
  ```

---

## Task 4: Create the V2 battle schema and enforce DML ownership

**Files:**

- Create: `battle-schema/src/main/resources/db/v2/migration/V20260730_01__battle_session_foundation.sql`
- Create: `battle-schema/src/main/resources/db/v2/migration/V20260730_02__campaign_battle_boundaries.sql`
- Create: `battle-schema/src/main/resources/db/v2/grants/migrator.sql`
- Create: `battle-schema/src/main/resources/db/v2/grants/battle_engine.sql`
- Create: `battle-schema/src/main/resources/db/v2/grants/game_engine.sql`
- Create: `battle-schema/src/main/resources/db/v2/grants/game_api.sql`
- Modify: `settings.gradle.kts`
- Modify: `infra/build.gradle.kts`
- Create: `app/v2-schema-provisioner/build.gradle.kts`
- Create: `app/v2-schema-provisioner/src/main/kotlin/opensamguk/provisioner/V2SchemaProvisionerApplication.kt`
- Create: `app/v2-schema-provisioner/src/main/resources/application.yml`
- Create: `app/v2-schema-provisioner/src/test/kotlin/opensamguk/provisioner/V2SchemaProvisionerIT.kt`
- Create: `tools/v2-db/provision-battle-db.sh`
- Modify: `app/game-engine/src/main/resources/application-v2-sandbox.yml`
- Modify: `app/game-api/src/main/resources/application-v2-sandbox.yml`
- Create: `infra/src/test/kotlin/opensamguk/infra/persistence/V2BattleFoundationMigrationIT.kt`
- Create: `infra/src/test/kotlin/opensamguk/infra/persistence/V1MigrationExcludesBattleTablesIT.kt`
- Create: `infra/src/test/kotlin/opensamguk/infra/persistence/BattleDatabaseRoleLifecycleIT.kt`
- Create: `app/v2-schema-provisioner/src/test/kotlin/opensamguk/provisioner/V2BattleMigrationOwnerArchitectureTest.kt`
- Create: `app/battle-engine/src/test/kotlin/opensamguk/battleengine/architecture/BattleEngineDmlOwnershipTest.kt`
- Create: `app/game-engine/src/test/kotlin/opensamguk/engine/v2/battle/V2CampaignBattleDmlOwnershipArchitectureTest.kt`
- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/flush/DaemonWriteGuard.kt`
- Modify: `app/game-engine/src/test/kotlin/opensamguk/engine/flush/DaemonNoEntityManagerTest.kt`
- Modify: `infra/src/test/kotlin/opensamguk/infra/persistence/InfraNoEntityManagerTest.kt`

**Interfaces produced:** World-scoped tables and constraints, a single migration owner, database-enforced runtime DML ownership, and source-level write-path guards.

**Interfaces consumed:** V2-0A Flyway location.

- [ ] **Step 1: Write migration tests before SQL**

  `V2BattleFoundationMigrationIT` loads the resource-only `:battle-schema` test runtime and migrates only `classpath:db/v2/migration`, then inspects PostgreSQL metadata and asserts:

  - battle-owned tables: `battle_ticket`, `battle_session`, `battle_event`, `battle_command_receipt`, `battle_snapshot`, `battle_result_outbox`, `battle_reinforcement_ticket`, `battle_handoff_rejection`;
  - campaign-owned tables: `campaign_battle_lock`, `campaign_battle_handoff`, `campaign_deferred_effect`, `campaign_battle_result_apply`, `campaign_battle_outbox`, `campaign_reinforcement_handoff`;
  - every table has `world_id`;
  - every child reference uses a composite foreign key containing `world_id`;
  - cross-world ticket/session/event/result/reinforcement references fail;
  - ticket payload/hash are immutable through a trigger;
  - versioned payload tables store `canonical_payload bytea` and `payload_hash bytea`; any `jsonb` column is projection-only;
  - event identity is `(world_id, battle_id, event_seq)`;
  - command receipt identity is `(world_id, battle_id, issuer_participant_id, client_command_id)`;
  - result apply identity is `(world_id, battle_id, result_revision)`;
  - session state, result state, and reinforcement state use check constraints;
  - required CAS columns are non-null.

  `V1MigrationExcludesBattleTablesIT` migrates the existing default `classpath:db/migration` location and asserts every table listed above is absent.

  `V2SchemaProvisionerIT` and `V2BattleMigrationOwnerArchitectureTest` are written at the same RED checkpoint: they require the environment-overridable V2 location, one-shot exit, and absence of Flyway/schema resources from every long-running application.

- [ ] **Step 2: Run RED**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
    :infra:test --tests '*V2BattleFoundationMigrationIT' \
    :app:v2-schema-provisioner:test --tests '*V2SchemaProvisionerIT' \
    --tests '*V2BattleMigrationOwnerArchitectureTest' --rerun-tasks
  ```

  Expected: Gradle cannot resolve the provisioner module and Flyway cannot find the V2 migrations or tables.

- [ ] **Step 3: Implement both migrations**

  Use PostgreSQL `jsonb` for versioned wire payloads, `bytea` for canonical state/hash artifacts, `timestamptz` for DB deadlines, and `bigint` for tick/event/revision counters. Include indexes for:

  ```text
  battle_session(world_id, state, lease_until)
  battle_event(world_id, battle_id, event_seq)
  battle_result_outbox(world_id, status, created_at)
  campaign_battle_handoff(world_id, created_at, battle_id)
  campaign_battle_outbox(world_id, event_type, published_at, created_at)
  campaign_deferred_effect(world_id, battle_id, campaign_sequence)
  ```

  Add `:app:v2-schema-provisioner` to `settings.gradle.kts`. Only that module adds `runtimeOnly(project(":battle-schema"))` and Flyway; add `testRuntimeOnly(project(":battle-schema"))` to `infra`. Do not add the schema resource or Flyway to game-engine, game-api, or battle-engine. All three long-running V2 services set `spring.flyway.enabled=false`.

  Provisioner `application.yml` binds:

  ```yaml
  spring:
    flyway:
      url: ${V2_MIGRATOR_DATABASE_URL}
      locations: ${SPRING_FLYWAY_LOCATIONS:classpath:db/v2/migration}
      user: ${V2_MIGRATOR_DB_USER}
      password: ${V2_MIGRATOR_DB_PASSWORD}
  ```

  The provision script constructs `V2_MIGRATOR_DATABASE_URL` from the validated base URL with a percent-encoded PostgreSQL `options=-c role=<object-owner>` connection parameter; callers may not supply an arbitrary options string. `V2BattleMigrationOwnerArchitectureTest` scans runtime graphs/configuration and fails if another process can load or execute V2 migrations.

- [ ] **Step 4: Add source-level DML ownership tests**

  `BattleEngineDmlOwnershipTest` scans compiled battle-engine classes and source SQL literals. Reject every mutating statement whose parsed table does not start with `battle_`. Permit campaign `SELECT` statements only. It also rejects direct `JdbcTemplate.update`, `execute`, and `batchUpdate` calls outside the battle persistence package.

  Extend `DaemonWriteGuard.writePathPackages` to include `opensamguk.engine.v2.battle`. `DaemonNoEntityManagerTest` must continue rejecting JPA and direct Spring Data writes there. `V2CampaignBattleDmlOwnershipArchitectureTest` rejects direct campaign JDBC DML outside `ChangeRecorder -> DatabaseHooks -> JdbcFlushExecutor` and rejects all game-engine battle DML.

- [ ] **Step 5: Add the one-shot role and migration lifecycle**

  `tools/v2-db/provision-battle-db.sh` is the only supported bootstrap command. It validates every role identifier against `[a-z_][a-z0-9_]*`, requires an explicitly supplied bootstrap-admin connection, and installs an `EXIT/INT/TERM` cleanup trap before the first role mutation. The lifecycle is exact:

  1. create/alter the V2 object-owner as `NOLOGIN`;
  2. create/alter the migrator as `LOGIN NOINHERIT VALID UNTIL now()+10 minutes`, grant it membership in the owner, and keep all runtime roles `NOLOGIN`;
  3. invoke Flyway through a JDBC connection option that sets `role=<validated object-owner>` on every connection;
  4. assert `session_user=migrator`, `current_user=object-owner`, and that every V2 schema/table/sequence/function is owned by the object-owner;
  5. apply and verify grants/default privileges, schema history, runtime non-membership, and forbidden writes;
  6. set the migrator `NOLOGIN`, revoke its owner membership, then enable runtime logins as the final transaction;
  7. on any exit before step 6, the trap forces migrator and all runtime roles to `NOLOGIN` and leaves battle-engine blocked by `service_completed_successfully`.

  It never echoes or persists passwords. Rerun is idempotent, completes/repairs Flyway state as permitted by its validated schema history, and re-applies least privilege after every migration.

  `BattleDatabaseRoleLifecycleIT` executes the same lifecycle with temporary roles and proves:

  - battle-engine can mutate every required `battle_*` table and can only select the three campaign handoff/ack tables it consumes;
  - battle-engine receives `permission denied` for campaign DML and unrelated campaign reads;
  - game-engine can read pending battle result/reinforcement rows but receives `permission denied` for battle DML;
  - game-api can read join eligibility/session epoch only and receives `permission denied` for all battle DML.
  - the object owner is `NOLOGIN`; the migrator is `NOLOGIN` after provisioning;
  - every runtime role returns false for owner/migrator membership, cannot `SET ROLE`, cannot grant itself privileges, and cannot alter/drop owned objects;
  - the bootstrap-admin credential is absent from every long-running service environment.

  `V2SchemaProvisionerIT` additionally inspects owner OIDs for every schema/table/sequence/function and uses the test-only `V2_PROVISION_TEST_FAILPOINT=AFTER_MIGRATE|BEFORE_GRANT_VERIFY` (accepted only when `V2_PROVISION_TEST_MODE=true`) to inject both failures. Each failure must leave migrator/runtime roles unable to log in, prevent battle-engine startup, and permit a clean idempotent rerun to success.

- [ ] **Step 6: Prove v1 migration isolation**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
    :infra:test --tests '*V1MigrationExcludesBattleTablesIT' \
    --tests '*V2BattleFoundationMigrationIT' --tests '*BattleDatabaseRoleLifecycleIT' \
    :app:v2-schema-provisioner:test --tests '*V2SchemaProvisionerIT' \
    --tests '*V2BattleMigrationOwnerArchitectureTest' \
    :app:game-engine:test --tests '*DaemonNoEntityManagerTest' \
    --tests '*V2CampaignBattleDmlOwnershipArchitectureTest' \
    :app:battle-engine:test --tests '*BattleEngineDmlOwnershipTest' \
    --rerun-tasks
  ```

  Expected: V1 default migration tests see zero new V2 tables; V2 migration tests see the complete schema.

- [ ] **Step 7: Commit**

  ```bash
  git add battle-schema/src/main/resources/db/v2 \
    settings.gradle.kts infra/build.gradle.kts app/v2-schema-provisioner tools/v2-db \
    app/game-engine/src/main/resources/application-v2-sandbox.yml \
    app/game-api/src/main/resources/application-v2-sandbox.yml \
    app/battle-engine/src/test/kotlin/opensamguk/battleengine/architecture \
    app/game-engine/src/main/kotlin/opensamguk/engine/flush/DaemonWriteGuard.kt \
    app/game-engine/src/test/kotlin/opensamguk/engine/{flush/DaemonNoEntityManagerTest.kt,v2} \
    infra/src/test/kotlin/opensamguk/infra/persistence
  git commit -m $'feat(v2-battle): add isolated battle persistence schema\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>'
  ```

---

## Task 5: Add battle ticket, event, receipt, snapshot, result, and reinforcement stores

**Files:**

- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/persistence/BattleHandoffReader.kt`
- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/persistence/BattleSessionRepository.kt`
- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/persistence/BattleEventRepository.kt`
- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/persistence/BattleSnapshotRepository.kt`
- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/persistence/BattleResultRepository.kt`
- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/persistence/BattleReinforcementRepository.kt`
- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/persistence/BattleHandoffRejectionRepository.kt`
- Create: `app/battle-engine/src/test/kotlin/opensamguk/battleengine/persistence/BattleStoreIT.kt`
- Create: `app/battle-engine/src/test/kotlin/opensamguk/battleengine/persistence/BattleCommitBeforeAckIT.kt`

**Interfaces produced:** Battle-owned JDBC transaction boundary and epoch-fenced append APIs.

**Interfaces consumed:** V2 migrations and battle contracts.

- [ ] **Step 1: Write RED integration tests**

  Cover:

  1. committed campaign handoff creates exactly one immutable ticket and `READY` session;
  2. identical handoff hash is idempotent and a different hash is rejected;
  3. receipt/event append uses one transaction;
  4. same client ID/same intent returns the first receipt;
  5. same client ID/different intent returns `IDEMPOTENCY_CONFLICT`;
  6. stale epoch cannot append event, snapshot, or result;
  7. snapshot checksum corruption is detectable;
  8. result and `BattleResolved` event commit together.
  9. malformed or hash-invalid initial handoff writes one battle-owned durable rejection without creating a session;
  10. semantically identical JSON key order encodes to identical canonical bytes/hash;
  11. cross-world references are rejected by repository predicates and database foreign keys.

- [ ] **Step 2: Run RED**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:battle-engine:test \
    --tests '*BattleStoreIT' --tests '*BattleCommitBeforeAckIT' --rerun-tasks
  ```

- [ ] **Step 3: Implement row-lock append semantics**

  `appendAcceptedCommand` must:

  ```text
  SELECT battle_session ... FOR UPDATE
  verify world_id, session_epoch, lease_owner, lease_until using clock_timestamp()
  read/compare command receipt identity and intent_hash
  allocate event_seq = latest_event_seq + 1
  INSERT battle_event
  INSERT battle_command_receipt
  UPDATE battle_session.latest_event_seq
  COMMIT
  ```

  The method returns the durable ACK model only after the transaction commits. It never enqueues the actor itself.

  Accepted and actor-level rejected domain commands both create durable receipts so same-ID replay returns the first verdict. Only malformed envelopes, failed JoinTicket authentication, and requests rejected before a battle/participant identity is established may return a nondurable transport rejection.

- [ ] **Step 4: Implement ticket polling without Redis correctness**

  `BattleHandoffReader.findWithoutBattleTicket(limit)` performs a left anti-join from campaign handoff to `battle_ticket` and orders by `(created_at, battle_id)`. The intake transaction re-reads the immutable handoff, verifies its payload hash, and creates battle-owned rows idempotently. It does not lock or update the campaign row. The unique ticket plus matching hash is the durable consumption receipt. Redis support, if later added, may only prompt this scan.

- [ ] **Step 5: Persist malformed-handoff rejection and define snapshot failure**

  A syntactically readable handoff with unsupported version, canonical-hash mismatch, unknown artifact, or invalid immutable fields writes exactly one `battle_handoff_rejection(worldId, battleId, handoffHash, reasonCode)` in a battle-owned transaction. Task 7 consumes it to release campaign locks, preventing an unbounded lock.

  Committed events remain authoritative if snapshot compression/write/checksum update fails. Mark the session snapshot health `DEGRADED`, retry snapshot creation from the committed event anchor, and keep readiness down for takeover until R1 or R2 replay is verified. Never fabricate, advance, or discard a battle result because a snapshot write failed.

- [ ] **Step 6: Run store tests**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:battle-engine:test \
    --tests 'opensamguk.battleengine.persistence.*' --rerun-tasks
  ```

- [ ] **Step 7: Commit**

  ```bash
  git add app/battle-engine/src/main/kotlin/opensamguk/battleengine/persistence \
    app/battle-engine/src/test/kotlin/opensamguk/battleengine/persistence
  git commit -m $'feat(v2-battle): persist battle sessions and events\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>'
  ```

---

## Task 6: Add campaign locks, durable handoff, and deferred-effect flush channels

**Files:**

- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/turn/ChangeRecorder.kt`
- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/flush/DatabaseHooks.kt`
- Modify: `infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt`
- Create: `app/game-engine/src/main/kotlin/opensamguk/engine/v2/battle/CampaignBattleCoordinator.kt`
- Create: `app/game-engine/src/main/kotlin/opensamguk/engine/v2/battle/DeferredCampaignEffectCoordinator.kt`
- Create: `app/game-engine/src/test/kotlin/opensamguk/engine/v2/battle/CampaignBattleHandoffIT.kt`
- Create: `app/game-engine/src/test/kotlin/opensamguk/engine/v2/battle/DeferredCampaignEffectIT.kt`
- Create: `infra/src/test/kotlin/opensamguk/infra/persistence/CampaignBattleFlushIT.kt`

**Interfaces produced:** Campaign-owned lock/handoff/deferred-effect deltas in the one-daemon-write path.

**Interfaces consumed:** `BattleTicketDraftV1`, `BattleAuthoritySnapshotV1`, V2 campaign tables.

- [ ] **Step 1: Write RED tests for lock closure and one-flush creation**

  Given generals, formations, source/target cities, and supplies with revisions, assert one flush:

  - assigns one `battleId`;
  - records entity type/id/expected revision;
  - sets `lockGeneration=1`, `lockSetRevision=1`;
  - freezes authority/objective/artifact/RNG inputs;
  - inserts one `campaign_battle_handoff`;
  - rolls back all rows if any lock revision CAS fails.

- [ ] **Step 2: Write RED tests for deferred effects**

  Parameterize every producer in the Task 0 `CampaignMutationRootCatalog`. A campaign effect targeting a locked entity must pass through `CampaignMutationGate` and create `campaign_deferred_effect(originalSequence, precheckEvidence, payload)` instead of mutating that entity. An unrelated entity still mutates normally and increments its persisted revision in the same flush.

- [ ] **Step 3: Add recorder and flush payload rows**

  Add typed rows, not raw maps:

  ```kotlin
  data class CampaignBattleLockRow(...)
  data class CampaignBattleHandoffRow(...)
  data class DeferredCampaignEffectRow(...)
  data class CampaignReinforcementHandoffRow(...)
  data class CampaignBattleAckRow(...)
  ```

  `DatabaseHooks.flushChanges` maps recorder deltas into `FlushPayload`. `JdbcFlushExecutor.flush` writes them in a fixed documented order inside the existing transaction.

- [ ] **Step 4: Implement campaign coordinator ports**

  `CampaignBattleCoordinator.prepareHandoff` accepts a fully prechecked V2 operation snapshot; it does not inspect or call land/siege/naval rules. It validates the Task 0 persisted entity revisions and lock closure, computes the immutable handoff hash from canonical bytes, and records deltas only. Restart tests must create a handoff, terminate the daemon, rehydrate `CampaignBattleRuntimeState`, and prove the same lock/revision/mutation-gate decisions before accepting new input.

- [ ] **Step 5: Run focused tests plus v1 flush regressions**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
    :infra:test --tests '*CampaignBattleFlushIT' --tests '*JdbcFlushExecutorIT' \
    :app:game-engine:test --tests '*CampaignBattleHandoffIT' \
    --tests '*DeferredCampaignEffectIT' --tests '*FlushPayloadConvergenceTest' \
    --tests '*V2CampaignBattleDmlOwnershipArchitectureTest' \
    --rerun-tasks
  ```

- [ ] **Step 6: Commit**

  ```bash
  git add app/game-engine/src/main/kotlin/opensamguk/engine/{turn/ChangeRecorder.kt,flush/DatabaseHooks.kt,v2/battle} \
    infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt \
    app/game-engine/src/test/kotlin/opensamguk/engine/v2/battle \
    infra/src/test/kotlin/opensamguk/infra/persistence/CampaignBattleFlushIT.kt
  git commit -m $'feat(v2-battle): add campaign handoff and lock boundary\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>'
  ```

---

## Task 7: Complete exactly-once campaign result and reinforcement workflows

**Files:**

- Create: `app/game-engine/src/main/kotlin/opensamguk/engine/v2/battle/CampaignBattleDrain.kt`
- Create: `app/game-engine/src/main/kotlin/opensamguk/engine/v2/battle/CampaignBattleResultApplier.kt`
- Create: `app/game-engine/src/main/kotlin/opensamguk/engine/v2/battle/CampaignBattleAckRelay.kt`
- Create: `app/game-engine/src/main/kotlin/opensamguk/engine/v2/battle/CampaignReinforcementCoordinator.kt`
- Create: `app/game-engine/src/main/kotlin/opensamguk/engine/v2/battle/CampaignBattleConfiguration.kt`
- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnRunService.kt`
- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/config/DaemonLoopConfig.kt`
- Create: `app/game-engine/src/test/kotlin/opensamguk/engine/v2/battle/CampaignBattleResultApplyIT.kt`
- Create: `app/game-engine/src/test/kotlin/opensamguk/engine/v2/battle/CampaignBattleResultBlockedIT.kt`
- Create: `app/game-engine/src/test/kotlin/opensamguk/engine/v2/battle/CampaignBattleApplyRetryTest.kt`
- Create: `app/game-engine/src/test/kotlin/opensamguk/engine/v2/battle/CampaignBattleAckRelayTest.kt`
- Create: `app/game-engine/src/test/kotlin/opensamguk/engine/v2/battle/CampaignBattleHandoffRejectionIT.kt`
- Create: `app/game-engine/src/test/kotlin/opensamguk/engine/v2/battle/CampaignReinforcementReleaseIT.kt`

**Interfaces produced:** Optional V2 daemon-drain extension and exactly-once campaign apply/block workflow.

**Interfaces consumed:** battle result outbox read model; campaign lock rows; ChangeRecorder flush channels.

- [ ] **Step 1: Write RED tests for apply, retry, and block**

  Test the failure boundaries:

  - matching `(battleId, resultRevision, lockGeneration, finalLockSetRevision, entity revisions)` applies all deltas, marker, unlock, and `BattleResultApplied` outbox in one flush;
  - retry after commit returns the existing apply marker and makes no second delta;
  - crash before flush leaves result pending;
  - mismatch writes `BattleResultBlocked`, keeps locks, and changes no campaign entity;
  - unrelated world/entity revisions do not block;
  - the result flush never applies deferred effects itself;
  - a later serialized daemon drain re-prechecks eligible deferred effects in original campaign sequence against the committed post-unlock revisions.
  - a durable initial-handoff rejection with matching battle/hash releases the original locks and records one campaign acknowledgment in one flush;
  - retrying or replaying that rejection performs no second unlock.
  - a rejected/expired reinforcement releases only its added lock set, increments `lockSetRevision`, and records one durable campaign acknowledgment;
  - admission-close CAS prevents new handoffs and drains every existing reinforcement handoff to admitted or released before fixing `finalLockSetRevision`.

- [ ] **Step 2: Add a single-writer drain seam**

  Define:

  ```kotlin
  fun interface CampaignLifecycleDrain {
      fun drain(world: InMemoryTurnWorld, recorder: ChangeRecorder, limit: Int): Int
  }
  ```

  `TurnRunService` invokes the configured drain inside its existing serialized intake/flush cycle. V1 configuration injects a no-op; `CampaignBattleConfiguration` registers the V2 drain only under the V2-0A gate. Do not add a second `@Scheduled` campaign writer.

- [ ] **Step 3: Implement result validation and delta recording**

  `CampaignBattleResultApplier` reads battle-owned pending rows but performs no battle DML. It validates all fences before mutating memory or recorder. Applied/blocked acknowledgments are campaign-owned outbox rows; their relay is retry-only. The apply flush only unlocks and marks deferred effects eligible. `DeferredCampaignEffectCoordinator` consumes them in a subsequent serialized drain so every re-precheck observes committed post-battle entity revisions.

  The same serialized drain reads `battle_handoff_rejection`. A matching `(worldId, battleId, handoffHash)` releases only that handoff's original lock set, records a campaign-owned rejection acknowledgment, and becomes idempotent through a campaign apply marker. Unknown or mismatched hashes stay locked and produce an operator-visible blocked record.

  `CampaignReinforcementCoordinator` owns the campaign half of reinforcement rejection/release and admission close before any actor code exists. It records deltas only and uses the same persisted revision/mutation gate. Task 11 later connects the battle actor's durable messages to this already-tested campaign workflow.

- [ ] **Step 4: Add the retry-only wake relay**

  `CampaignBattleAckRelay` scans committed unpublished `campaign_battle_outbox` rows, publishes only a world/battle/result wake hint, then marks that campaign outbox row published. Failure leaves it unpublished. `battle-engine` still polls the DB acknowledgment, so a lost or duplicate wake is harmless.

- [ ] **Step 5: Run focused recovery tests**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test \
    --tests '*CampaignBattleResultApplyIT' \
    --tests '*CampaignBattleResultBlockedIT' \
    --tests '*CampaignBattleApplyRetryTest' \
    --tests '*CampaignBattleAckRelayTest' \
    --tests '*CampaignBattleHandoffRejectionIT' \
    --tests '*CampaignReinforcementReleaseIT' \
    --tests '*TurnRunServiceFlushRecoveryTest' --rerun-tasks
  ```

- [ ] **Step 6: Run architecture checks**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
    :app:game-engine:test --tests '*DaemonNoEntityManagerTest' \
    --tests '*V2CampaignBattleDmlOwnershipArchitectureTest' \
    :infra:test --tests '*InfraNoEntityManagerTest' --rerun-tasks
  ```

- [ ] **Step 7: Commit**

  ```bash
  git add app/game-engine/src/main/kotlin/opensamguk/engine/{v2/battle,run/TurnRunService.kt,config/DaemonLoopConfig.kt} \
    app/game-engine/src/test/kotlin/opensamguk/engine/v2/battle
  git commit -m $'feat(v2-battle): apply campaign battle results exactly once\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>'
  ```

---

## Task 8: Implement session actor, epoch lease, fixed tick, and recovery ladder

**Files:**

- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/session/BattleSessionActor.kt`
- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/session/BattleSessionSupervisor.kt`
- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/session/BattleRecoveryService.kt`
- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/intake/BattleHandoffIntakeCoordinator.kt`
- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/config/BattleDatabaseWorldBindingVerifier.kt`
- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/lifecycle/BattleResultAckCoordinator.kt`
- Modify: `app/battle-engine/build.gradle.kts`
- Create: `app/battle-engine/src/test/kotlin/opensamguk/battleengine/session/BattleEpochFenceIT.kt`
- Create: `app/battle-engine/src/test/kotlin/opensamguk/battleengine/session/BattleRecoveryIT.kt`
- Create: `app/battle-engine/src/test/kotlin/opensamguk/battleengine/session/BattleFixedTickTest.kt`
- Create: `app/battle-engine/src/test/kotlin/opensamguk/battleengine/lifecycle/BattleResultAckIT.kt`
- Create: `app/battle-engine/src/test/kotlin/opensamguk/battleengine/config/BattleDatabaseWorldBindingVerifierIT.kt`
- Create: `app/battle-engine/src/test/kotlin/opensamguk/battleengine/session/BattleMailboxOverloadTest.kt`
- Create: `app/battle-engine/src/test/kotlin/opensamguk/battleengine/intake/BattleHandoffIntakeCoordinatorIT.kt`

**Interfaces produced:** One serialized bounded actor mailbox per battle on a shared bounded dispatcher, 200ms fixed tick, DB-time epoch lease, R1/R2/R3 recovery, and startup world/database identity enforcement.

**Interfaces consumed:** battle stores, deterministic kernel, adapter registry.

  Add `testImplementation(testFixtures(project(":battle")))` before writing recovery tests. Register `FoundationFixtureAdapter` only in the test application context so actor/recovery work consumes an already-tested adapter registry and replay reducer; production startup still has an empty registry.

- [ ] **Step 1: Write RED lifecycle/lease tests**

  Assert legal transitions only:

  ```text
  READY -> STARTING -> RUNNING -> RESOLVING -> RESULT_PENDING
  RUNNING <-> PAUSED
  RUNNING|PAUSED -> RECOVERING -> RUNNING|RESOLVING
  READY|STARTING -> CANCELLED
  RECOVERING -> QUARANTINED
  RESULT_PENDING -> APPLIED|RESULT_BLOCKED
  ```

  Two supervisors racing for one expired lease must yield one winning epoch. Writes from the old epoch must fail after takeover.

- [ ] **Step 2: Verify startup database/world binding**

  Before opening handoff intake or WebSockets, `BattleDatabaseWorldBindingVerifier` reads the database's immutable V2 world binding and schema identity and compares them with `OPENSAMGUK_WORLD_ID` and the expected V2 schema revision. Mismatch makes readiness `DOWN` and prevents lease acquisition. Its integration test connects the process to a database bound to another world and proves no battle write occurs.

- [ ] **Step 3: Drive durable handoff intake without Redis**

  `BattleHandoffIntakeCoordinator` starts only after database/world binding is verified and readiness prerequisites are green. It repeatedly calls `BattleHandoffReader.findWithoutBattleTicket(limit=100)`, creates/rejects battle-owned intake rows transactionally, and asks `BattleSessionSupervisor` to recover/start resulting sessions. Polling uses a bounded single-flight loop with 250ms minimum delay, exponential idle backoff capped at 5 seconds, and no overlapping scans. Redis wakes reset the backoff only; they never carry authoritative content.

  `BattleHandoffIntakeCoordinatorIT` proves a handoff is discovered with Redis disabled, restart catches rows committed while the process was down, duplicate wakes remain idempotent, a full batch is drained across bounded pages, and readiness-down/world-mismatch suppresses every scan/write/actor start.

- [ ] **Step 4: Implement bounded actor mailbox and tick ownership**

  One serialized mailbox owns state for one `battleId`, but actors run on a shared bounded dispatcher; do not allocate one platform thread or executor per battle. Defaults are:

  ```text
  per-battle mailbox capacity = 4096
  WebSocket outbound queue per connection = 256
  overload reason = SERVER_BUSY_RETRY
  terminal actor retention = 5 minutes after durable terminal acknowledgment
  ```

  All commands, deadlines, lease renewals, ticks, snapshots, and resolution transitions enter the mailbox. Once capacity is reached, new transport commands receive `SERVER_BUSY_RETRY` without a false durable acceptance, while already committed events, deadlines, leases, and recovery messages use a reserved control lane. Slow WebSocket clients lose/reconnect rather than growing an unbounded send queue. The supervisor releases actor state, queues, and registry entries after retention. The actor advances exactly one 200ms simulation tick at a time and never derives missed simulation ticks from wall-clock time.

- [ ] **Step 5: Implement DB-time lease CAS**

  Lease acquisition increments `session_epoch` and commits a new DB anchor. Renewal and all writes include `(world_id, battle_id, session_epoch, lease_owner)` predicates and compare `lease_until` with `clock_timestamp()`.

- [ ] **Step 6: Implement recovery**

  - R1: latest valid snapshot plus event tail;
  - fallback: older snapshots in descending order;
  - R2: ticket plus complete committed event replay;
  - R3: only from a verified committed state, close admission and call headless adapter resolution with `DEADLINE_FALLBACK`;
  - corruption that prevents R2 produces `QUARANTINED`, never a campaign result.

  Before reopening admission, add DB downtime to logical time and consume overdue deadlines.

- [ ] **Step 7: Consume durable campaign acknowledgments**

  `BattleResultAckCoordinator` polls campaign-owned APPLIED/BLOCKED acknowledgments. Matching APPLIED transitions the battle-owned result and session to `APPLIED`; matching BLOCKED transitions both to `RESULT_BLOCKED`; stale or mismatched result revisions are recorded as rejected battle events. Only APPLIED unlocks full replay access.

- [ ] **Step 8: Run tests**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:battle-engine:test \
    --tests 'opensamguk.battleengine.session.*' \
    --tests '*BattleHandoffIntakeCoordinatorIT' \
    --tests '*BattleDatabaseWorldBindingVerifierIT' \
    --tests '*BattleMailboxOverloadTest' \
    --tests '*BattleResultAckIT' --rerun-tasks
  ```

- [ ] **Step 9: Commit**

  ```bash
  git add app/battle-engine/build.gradle.kts \
    app/battle-engine/src/main/kotlin/opensamguk/battleengine/{config/BattleDatabaseWorldBindingVerifier.kt,session,lifecycle/BattleResultAckCoordinator.kt} \
    app/battle-engine/src/main/kotlin/opensamguk/battleengine/intake \
    app/battle-engine/src/test/kotlin/opensamguk/battleengine/{config,intake,session,lifecycle/BattleResultAckIT.kt}
  git commit -m $'feat(v2-battle): add recoverable session actors\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>'
  ```

---

## Task 9: Bind command ingress to durable commit-before-ACK

**Files:**

- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/command/BattleCommandIngress.kt`
- Modify: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/session/BattleSessionActor.kt`
- Create: `app/battle-engine/src/test/kotlin/opensamguk/battleengine/command/BattleCommandIngressIT.kt`
- Extend: `app/battle-engine/src/test/kotlin/opensamguk/battleengine/persistence/BattleCommitBeforeAckIT.kt`

**Interfaces produced:** Actor-owned command submission future and durable ACK reconstruction.

**Interfaces consumed:** command reducer and event/receipt repository.

- [ ] **Step 1: Write crash-boundary RED tests**

  Inject failures:

  1. before transaction;
  2. after event insert but before commit;
  3. after commit but before actor state update;
  4. after actor update but before WebSocket send.

  The first two emit no accepted ACK and leave no committed receipt. The latter two return the original durable ACK on same-ID retry and apply the command once.

- [ ] **Step 2: Implement actor-mailbox validation**

  `BattleCommandIngress.submit` resolves the actor and posts the envelope. The actor validates JoinTicket session epoch, seat, authority revision, target visibility, payload size, state, deadline, and per-formation rate limit against its owned state.

- [ ] **Step 3: Commit receipt/event, then mutate memory**

  The actor calls repository append, waits for commit, reduces the committed event into memory, then completes `CompletionStage<BattleCommandAck>`. Accepted commands and every rejection made after `(worldId, battleId, issuerParticipantId, clientCommandId)` is authenticated are durable actor events/receipts, so same-ID retry reconstructs the first verdict. Malformed transport frames, failed JoinTicket authentication, and payloads lacking a resolvable battle/participant identity remain nondurable and cannot claim replay guarantees. If post-commit in-memory reduction fails, mark the actor recovering and rebuild from DB before accepting another command.

- [ ] **Step 4: Run focused tests**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:battle-engine:test \
    --tests '*BattleCommandIngressIT' --tests '*BattleCommitBeforeAckIT' --rerun-tasks
  ```

- [ ] **Step 5: Commit**

  ```bash
  git add app/battle-engine/src/main/kotlin/opensamguk/battleengine/{command,session/BattleSessionActor.kt} \
    app/battle-engine/src/test/kotlin/opensamguk/battleengine/{command,persistence/BattleCommitBeforeAckIT.kt}
  git commit -m $'feat(v2-battle): enforce commit before command ack\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>'
  ```

---

## Task 10: Issue scoped JoinTickets and expose faction-safe WebSocket sessions

**Files:**

- Modify: `app/game-api/build.gradle.kts`
- Modify: `app/battle-engine/build.gradle.kts`
- Create: `app/game-api/src/main/kotlin/opensamguk/gameapi/v2/battle/BattleJoinReadRepository.kt`
- Create: `app/game-api/src/main/kotlin/opensamguk/gameapi/v2/battle/BattleJoinTicketIssuer.kt`
- Create: `app/game-api/src/main/kotlin/opensamguk/gameapi/v2/battle/BattleJoinController.kt`
- Create: `app/game-api/src/main/kotlin/opensamguk/gameapi/v2/battle/BattleJoinConfiguration.kt`
- Create: `app/game-api/src/test/kotlin/opensamguk/gameapi/v2/battle/BattleJoinControllerIT.kt`
- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/auth/BattleJoinTicketVerifier.kt`
- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/projection/FactionProjectionService.kt`
- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/websocket/BattleWebSocketConfig.kt`
- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/websocket/BattleWebSocketHandler.kt`
- Create: `app/battle-engine/src/test/kotlin/opensamguk/battleengine/websocket/BattleWebSocketIT.kt`
- Create: `app/battle-engine/src/test/kotlin/opensamguk/battleengine/projection/FactionProjectionLeakTest.kt`

**Interfaces produced:** `POST /api/v2/battles/{battleId}/join`, `/battle/ws`, faction snapshot/delta protocol.

**Interfaces consumed:** shared gateway identity, campaign authority snapshot, current session epoch.

- [ ] **Step 1: Write RED security tests**

  Cover valid, expired, forged, wrong-world, wrong-battle, wrong-side, and stale-epoch tickets. Assert the battle service never receives the long-lived gateway access token.

- [ ] **Step 2: Implement versioned JoinTickets with bounded key rotation**

  Use HS256 keys separate from `JWT_SECRET`. Configuration provides one current signing key and at most one previous verification key, each identified by a nonblank `kid`; reject duplicate or unknown `kid`. Never try every key. Default TTL is 60 seconds; configuration rejects values outside 10–120 seconds. Claims are exactly:

  ```text
  ticketSchemaVersion=1, kid, worldId, battleId, sessionEpoch,
  accountId, participantId,
  generalId, sideId, initialAuthorityRevision, issuedAt, expiresAt
  ```

  Formation seat is deliberately absent and is read from live server state.

- [ ] **Step 3: Implement authoritative join lookup**

  `BattleJoinReadRepository` uses process-world-scoped JDBC reads. It verifies account ownership, frozen participant eligibility, and current battle/session state before signing. The controller is registered only under the V2 gate.

- [ ] **Step 4: Implement WebSocket handshake and reconnect**

  Client connects with JoinTicket and `lastSeenEventSeq`. The server verifies DB-time expiry and current epoch, then sends either:

  - a side-specific snapshot plus deltas after the snapshot sequence; or
  - deltas after `lastSeenEventSeq` when still retained.

  Commands are delegated to `BattleCommandIngress`.

  The WebSocket integration test rotates from key A to key B, proves unexpired A tickets validate only while A is the configured previous key, proves B signs new tickets, and rejects an unknown `kid` without probing either key.

- [ ] **Step 5: Prove zero hidden-information leakage**

  `FactionProjectionLeakTest` constructs enemy hidden entities with sentinel IDs, coordinates, and payload strings, then scans every snapshot, delta, rejection, and ACK byte for those sentinels. Full replay access remains denied until campaign-owned `BattleResultApplied` is consumed.

- [ ] **Step 6: Run security and WebSocket tests**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
    :app:game-api:test --tests '*BattleJoinControllerIT' \
    :app:battle-engine:test --tests '*BattleWebSocketIT' \
    --tests '*FactionProjectionLeakTest' --rerun-tasks
  ```

- [ ] **Step 7: Commit**

  ```bash
  git add app/game-api/build.gradle.kts app/battle-engine/build.gradle.kts \
    app/game-api/src/{main,test}/kotlin/opensamguk/gameapi/v2/battle \
    app/battle-engine/src/main/kotlin/opensamguk/battleengine/{auth,projection,websocket} \
    app/battle-engine/src/test/kotlin/opensamguk/battleengine/{projection,websocket}
  git commit -m $'feat(v2-battle): add scoped realtime battle sessions\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>'
  ```

---

## Task 11: Implement disconnect AI, deputy succession, reinforcement, admission close, and deadline resolution

**Files:**

- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/lifecycle/BattleDisconnectCoordinator.kt`
- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/lifecycle/BattleReinforcementCoordinator.kt`
- Create: `app/battle-engine/src/main/kotlin/opensamguk/battleengine/lifecycle/BattleDeadlineResolver.kt`
- Create: `app/battle-engine/src/test/kotlin/opensamguk/battleengine/lifecycle/BattleDisconnectRecoveryIT.kt`
- Create: `app/battle-engine/src/test/kotlin/opensamguk/battleengine/lifecycle/BattleReinforcementHandshakeIT.kt`
- Create: `app/battle-engine/src/test/kotlin/opensamguk/battleengine/lifecycle/BattleDeadlineRecoveryIT.kt`

**Interfaces produced:** Durable 30s/90s connection deadlines, AI takeover/reclaim, deputy succession, reinforcement two-sided handshake, deterministic deadline close.

**Interfaces consumed:** actor mailbox, Task 7 campaign reinforcement/release/admission-close workflow, campaign/battle handoffs and acks, authority/lifecycle reducers.

- [ ] **Step 1: Write RED deadline/recovery tests**

  Pin:

  - disconnect grace: 30 seconds logical time;
  - commander succession: 90 seconds logical time;
  - reconnect reclaim: next safe tick;
  - original commander never automatically reclaims side authority;
  - actor restart does not reset a deadline;
  - expired deadlines are applied before live admission after recovery.

- [ ] **Step 2: Implement durable connection transitions**

  Persist absolute `aiTakeoverDeadlineAtDb` and `deputySuccessionDeadlineAtDb` in events/snapshots. AI inherits the last active objective/policy. Reconnect emits cancellation/reclaim events and uses current authority revision.

- [ ] **Step 3: Write and implement reinforcement cap tests**

  The side cap counts initial, admitted, and scheduled formations. Pin reason codes `FORMATION_CAP_REACHED` and `BATTLE_ADMISSION_CLOSED`. Same idempotency key returns the original result.

- [ ] **Step 4: Integrate the existing campaign rejection release**

  Rejected/expired battle tickets produce battle-owned durable acknowledgments. The Task 7 V2 game-engine drain removes only that reinforcement's locks, increments `lockSetRevision`, unlocks entities, and records `CampaignReinforcementReleased` in one flush. Battle-engine terminalizes the ticket only after reading the release acknowledgment.

- [ ] **Step 5: Implement admission-close handshake**

  Enforce:

  ```text
  BattleAdmissionCloseRequested(observedLockSetRevision)
  -> campaign stops new handoffs
  -> drain every existing handoff to ADMITTED or RELEASED
  -> CampaignBattleAdmissionClosed(finalLockSetRevision)
  -> battle verifies every local ticket/revision
  -> RESOLVING
  ```

  Resolution and new lock-set expansion must be mutually exclusive through CAS.

- [ ] **Step 6: Implement 12/15-minute resolver**

  Ticket defaults are target 12 minutes and hard deadline 15 minutes. At deadline, close live admission, replay all committed events, run adapter resolution from the verified state, and persist one result revision. Pause never extends campaign deadline.

- [ ] **Step 7: Run lifecycle tests**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
    :app:battle-engine:test --tests 'opensamguk.battleengine.lifecycle.*' \
    :app:game-engine:test --tests '*CampaignReinforcementReleaseIT' --rerun-tasks
  ```

- [ ] **Step 8: Commit**

  ```bash
  git add app/battle-engine/src/{main,test}/kotlin/opensamguk/battleengine/lifecycle
  git commit -m $'feat(v2-battle): add durable battle lifecycle controls\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>'
  ```

---

## Task 12: Build deterministic replay, fault, and 32-formation server gates

**Files:**

- Create: `app/battle-engine/src/test/kotlin/opensamguk/battleengine/replay/BattleReplay100xTest.kt`
- Create: `app/battle-engine/src/test/kotlin/opensamguk/battleengine/fault/BattleFaultMatrixIT.kt`
- Create: `app/battle-engine/src/test/kotlin/opensamguk/battleengine/load/BattleServerLoadGateIT.kt`
- Modify: `app/battle-engine/build.gradle.kts`
- Create: `tools/battle/run-server-load-gate.sh`
- Create: `tools/battle/assert-server-load-report.mjs`

**Interfaces produced:** G0–G5 server-side evidence harness and deterministic fixture adapter.

**Interfaces consumed:** all common foundation components, `BattleReplayReducer`, `BattleSnapshotReader`, and the Task 3 `FoundationFixtureAdapter` test fixture.

- [ ] **Step 1: Register the existing non-production fixture adapter**

  Import `FoundationFixtureAdapter` from `testFixtures(project(":battle"))` and register it only in the test context. Assert the production adapter registry remains empty.

- [ ] **Step 2: Implement G1 replay tests**

  For the same ticket and event log:

  - run from ticket 100 times;
  - run from every retained snapshot plus tail;
  - delete each snapshot in turn and prove fallback;
  - restart actor between every event;
  - assert every checkpoint and final state hash match.

- [ ] **Step 3: Implement the G3 fault matrix**

  Parameterize:

  ```text
  duplicate/drop/reorder WebSocket delivery
  crash before/after command append
  crash before/after snapshot write
  crash before/after deputy succession
  crash before/after result outbox
  crash before/after campaign apply
  stale epoch writes
  corrupted snapshot
  corrupted committed event tail
  ```

  Expected invariants: accepted command loss 0, duplicate apply 0, partial campaign apply 0, and corrupt unreplayable tail yields `QUARANTINED`.
  Snapshot-write failures additionally prove committed events stay authoritative, readiness is degraded until a verified replay anchor exists, retry can rebuild the snapshot, and no result is fabricated.

- [ ] **Step 4: Add a dedicated load-test task**

  `battleLoadTest` is separate from default unit tests and runs `BattleServerLoadGateIT`. The test starts 32 formations and 32 WebSocket clients for 15 simulated realtime minutes with RTT 80ms, packet loss 1%, retransmission, dense command traffic, reconnects, and actor failover.

- [ ] **Step 5: Assert server performance thresholds**

  The report validator rejects unless:

  ```text
  tick p95 <= 100ms
  tick p99 <= 180ms
  consecutive two-tick deadline misses = 0
  ACK p95 <= 250ms
  accepted loss = 0
  duplicate apply = 0
  reconnect p95 <= 3000ms
  actor recovery <= 10000ms
  state hash mismatches = 0
  OOM = 0
  stabilized RSS slope <= 1% per hour in the 60-minute soak
  ```

  The 64-formation stretch may produce a report but cannot fail the launch gate.

- [ ] **Step 6: Run deterministic and fault gates**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:battle-engine:test \
    --tests '*BattleReplay100xTest' --tests '*BattleFaultMatrixIT' --rerun-tasks
  ```

- [ ] **Step 7: Run the isolated server load gate**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) tools/battle/run-server-load-gate.sh
  ```

  Expected: report validator exits zero and prints every threshold with measured value. This does not claim the separate browser frame gate.

- [ ] **Step 8: Commit**

  ```bash
  git add app/battle-engine/build.gradle.kts \
    app/battle-engine/src/test/kotlin/opensamguk/battleengine/{replay,fault,load} \
    tools/battle
  git commit -m $'test(v2-battle): add replay fault and load gates\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>'
  ```

---

## Task 13: Wire a local non-production battle-engine service and run the common-foundation gate

**Files:**

- Create: `docker/v2-schema-provisioner.Dockerfile`
- Create: `docker/battle-engine.Dockerfile`
- Modify: `docker-compose.yml`
- Create: `tools/battle/assert-mandatory-testcontainers.mjs`
- Create: `tools/battle/smoke-handoff-rejection.sh`
- Create: `app/battle-engine/src/test/kotlin/opensamguk/battleengine/BattleEngineApplicationTests.kt`
- Create: `docs/superpowers/reviews/2026-07-30-v2-battle-foundation-implementation-review.md`
- Update: `.ai/current-state.md`
- Update: `.ai/handoff.md`

**Interfaces produced:** Local battle-engine container on port 8083 and final implementation evidence.

**Interfaces consumed:** V2-0A local V2 database/profile values.

- [ ] **Step 1: Add the application context test**

  Boot the application context with a test double for the already-covered `BattleDatabaseWorldBindingVerifier`, test JoinTicket current key/kid, `OPENSAMGUK_WORLD_ID=2`, and `BATTLE_ENGINE_INSTANCE_ID=test-node`. Assert liveness is UP and the adapter registry is empty in production configuration. This test does not load `battle-schema` or invoke Flyway; Task 8 owns the real database/world binding IT and Step 7 owns the migrated-container smoke.

- [ ] **Step 2: Add the Docker image**

  `docker/v2-schema-provisioner.Dockerfile` builds the one-shot provisioner and contains `psql` for the reviewed role/grant lifecycle. `docker/battle-engine.Dockerfile` mirrors the game-engine image, builds `:app:battle-engine:bootJar`, exposes 8083, and uses actuator health. Neither image contains credentials. Do not edit `docker-compose.production.yml`, deploy workflows, or external `opensamguk-docker` in this plan.

- [ ] **Step 3: Add the local service**

  The one-shot provisioner accepts the following variables; all except `SPRING_FLYWAY_LOCATIONS` are required, while that variable preserves the OPENSAM-35 override and defaults to `classpath:db/v2/migration`:

  ```text
  V2_DB_BOOTSTRAP_URL
  V2_DB_BOOTSTRAP_USER
  V2_DB_BOOTSTRAP_PASSWORD
  V2_DB_OBJECT_OWNER
  V2_MIGRATOR_DB_USER
  V2_MIGRATOR_DB_PASSWORD
  V2_GAME_DB_USER
  V2_GAME_DB_PASSWORD
  V2_BATTLE_DATABASE_URL
  V2_BATTLE_DB_USER
  V2_BATTLE_DB_PASSWORD
  V2_GAME_API_DB_USER
  V2_GAME_API_DB_PASSWORD
  SPRING_FLYWAY_LOCATIONS
  ```

  The ignored host env supplies `V2_WORLD_ID` so v1 and v2 can coexist. Compose maps it to the established application contract:

  ```yaml
  environment:
    OPENSAMGUK_WORLD_ID: ${V2_WORLD_ID:?V2_WORLD_ID required}
  ```

  Battle-engine container receives only:

  ```text
  V2_BATTLE_DATABASE_URL
  V2_BATTLE_DB_USER
  V2_BATTLE_DB_PASSWORD
  OPENSAMGUK_WORLD_ID
  BATTLE_ENGINE_INSTANCE_ID
  BATTLE_JOIN_TICKET_CURRENT_KID
  BATTLE_JOIN_TICKET_CURRENT_SECRET
  ```

  Compose adds one-shot `v2-schema-provisioner` and long-running `battle-engine`. The provisioner runs `tools/v2-db/provision-battle-db.sh`; battle-engine uses the V2 database, not the default v1 `sammo` database, and declares `depends_on: v2-schema-provisioner: condition: service_completed_successfully` plus PostgreSQL/Redis health. Bootstrap-admin and migrator credentials exist only on the one-shot service; runtime services receive only their own credentials. No secret value is committed.

- [ ] **Step 4: Run the full common-foundation JVM gate**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
    :common:test :battle:test :infra:test \
    :app:v2-schema-provisioner:test :app:game-engine:test \
    :app:game-api:test :app:battle-engine:test \
    --rerun-tasks 2>&1 | tee /tmp/v2-battle-foundation-gradle.log
  ```

  Confirm `BUILD SUCCESSFUL` in the tail and aggregate XML failures/errors equal zero.

- [ ] **Step 5: Prove mandatory Testcontainers suites actually ran**

  `tools/battle/assert-mandatory-testcontainers.mjs` parses XML for migration isolation, database-role lifecycle, provisioner, world/database binding, Redis-free handoff intake, handoff/store, result apply/block, epoch/recovery, WebSocket, deadline/reinforcement, and fault-matrix suites. It fails when a named suite is absent or when `tests=0`, `failures>0`, `errors>0`, or `skipped>0`. The required class list explicitly includes `BattleDatabaseRoleLifecycleIT`, `V2SchemaProvisionerIT`, `BattleDatabaseWorldBindingVerifierIT`, and `BattleHandoffIntakeCoordinatorIT`.

  ```bash
  node tools/battle/assert-mandatory-testcontainers.mjs \
    infra/build/test-results/test \
    app/v2-schema-provisioner/build/test-results/test \
    app/game-engine/build/test-results/test \
    app/game-api/build/test-results/test \
    app/battle-engine/build/test-results/test
  ```

- [ ] **Step 6: Run parity, architecture, strict Agent OS, and repository gates**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
    :app:game-engine:test --tests '*DaemonNoEntityManagerTest' \
    :app:battle-engine:test --tests '*BattleEngineDmlOwnershipTest' --rerun-tasks
  tools/parity/gate.sh backend
  python3 tools/agent-system/check.py --strict --base origin/main
  git diff --check
  scripts/agent/verify-changes.sh --run
  ```

- [ ] **Step 7: Run local container smoke without production activation**

  With an ignored `.env.v2.local`, provision and migrate first, require successful one-shot exit, then start battle-engine:

  ```bash
  docker compose --env-file .env.v2.local up \
    --abort-on-container-exit --exit-code-from v2-schema-provisioner \
    v2-schema-provisioner
  docker compose --env-file .env.v2.local up -d battle-engine
  curl -fsS http://localhost:8083/actuator/health
  ```

  Expected: provisioner exits 0 after disabling and expiring the migrator login; runtime-role checks cannot `SET ROLE`; battle-engine body contains `"status":"UP"`.

  Keep the production adapter registry empty. `tools/battle/smoke-handoff-rejection.sh` uses only the local campaign runtime credential to insert a uniquely identified handoff referencing a deliberately unknown artifact, stops Redis, restarts battle-engine, and polls for one matching durable `battle_handoff_rejection`. It asserts no `battle_ticket` or `battle_session` exists for that battle. Successful ticket/session creation remains covered by `BattleHandoffIntakeCoordinatorIT` with the test-fixture adapter.

  ```bash
  tools/battle/smoke-handoff-rejection.sh --env-file .env.v2.local
  ```

  Do not push an image, edit production compose, or deploy.

- [ ] **Step 8: Obtain independent adversarial review**

  A reviewer who did not implement the change must inspect G0–G5 evidence, DML ownership, commit-before-ACK, epoch fence, recovery, hidden-information tests, and the exact commit SHA. Every `fix-required` finding is remediated and re-reviewed before handoff.

- [ ] **Step 9: Record implemented and unimplemented gates**

  The review/current-state/handoff must state:

  - common server foundation G0–G3: executed result;
  - G4 faction-security/WebSocket server slice: executed result;
  - G4 browser renderer slice: not in this plan;
  - G5 server 32-formation/socket/load slice: executed result;
  - G5 browser frame slice: not in this plan;
  - G6 land/siege/naval: not in this plan.

- [ ] **Step 10: Commit**

  ```bash
  git add docker/v2-schema-provisioner.Dockerfile docker/battle-engine.Dockerfile docker-compose.yml \
    tools/battle/assert-mandatory-testcontainers.mjs \
    tools/battle/smoke-handoff-rejection.sh \
    app/battle-engine/src/test/kotlin/opensamguk/battleengine/BattleEngineApplicationTests.kt \
    docs/superpowers/reviews/2026-07-30-v2-battle-foundation-implementation-review.md \
    .ai/current-state.md .ai/handoff.md
  git commit -m $'chore(v2-battle): verify local battle foundation\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>'
  ```

---

## Spec-to-Task Traceability

| Approved requirement | Owning task(s) | Primary evidence |
|---|---:|---|
| Campaign revision/runtime-state/mutation gate | 0, 6, 7 | predecessor cross-call-site, rehydrate, flush tests |
| One-shot migration and role lifecycle | 4, 13 | provisioner/role lifecycle IT and local smoke |
| Dedicated single-world battle-engine | 1, 8, 13 | process-world/binding test, context test, local health |
| Immutable BattleTicket and version pins | 2, 4, 5 | wire test, immutable trigger, handoff hash IT |
| Canonical payload bytes and artifact lookup | 2, 4, 5 | key-order/hash, artifact registry, schema tests |
| 200ms deterministic fixed tick | 3, 8 | deterministic and fixed-tick tests |
| battle-engine writes only `battle_*` | 4, 5 | DB role and DML ownership tests |
| Campaign write only through ChangeRecorder/JDBC | 6, 7 | flush IT, daemon architecture test |
| Lock generation/set/entity revisions | 4, 6, 7 | migration and result apply/block IT |
| Deferred locked-target effects | 6, 7 | deferred effect and apply order IT |
| Commit-before-ACK/idempotency | 5, 9 | command crash-boundary IT |
| Commander/officer/AI and one human per formation | 3 | G2 matrix |
| Delayed authority/order/mode changes | 3 | reducer tests |
| Limited tactical pause | 3 | pause matrix |
| JoinTicket and WebSocket | 10 | join, key-rotation, and WebSocket IT |
| Faction projection/fog protection | 10 | sentinel leak test |
| 30s AI, 90s deputy, reconnect | 3, 11 | pure reducer and disconnect recovery IT |
| Reinforcement cap/release/admission close | 3, 6, 7, 11 | reducer, campaign workflow, handshake/release IT |
| Epoch lease, R1/R2/R3, quarantine | 5, 8, 12 | epoch, recovery, fault matrix |
| Redis-free handoff discovery and restart catch-up | 5, 8, 13 | intake coordinator IT and local smoke |
| Exactly-once result and durable APPLIED/BLOCKED | 5, 7, 11 | apply/retry/block IT |
| Full replay only after APPLIED | 7, 10, 12 | access and replay tests |
| G0–G5 common server harness | 12, 13 | replay/fault/load/review evidence |
| Realtime fallback uses same contract | 2, 3, 8, 11 | adapter SPI, replay reducer, headless R3 path |

---

## Final Plan Self-Review Checklist

- [ ] Every section of the approved common-foundation scope maps to at least one task and test.
- [ ] Task 0 predecessor evidence proves revisions, restart rehydration, mutation-gate coverage, and one migration owner before foundation work begins.
- [ ] Land, siege, naval, renderer/HUD, asset placement, and G6 remain separate.
- [ ] No step requires editing v1 `logic/war`, v1 Flyway `db/migration`, PHP goldens, or legacy sources.
- [ ] Every new stored payload has a schema version and reader/upcaster entry point.
- [ ] Every payload hash is derived from canonical bytes, never PostgreSQL-rendered `jsonb`.
- [ ] Every mutating boundary names its owner, transaction, idempotency key, and fence.
- [ ] The one-shot provisioner preserves `SPRING_FLYWAY_LOCATIONS`, disables its migrator login, and no runtime role can regain owner membership.
- [ ] Database-role integration tests reject cross-owner DML and every composite foreign key rejects cross-world references.
- [ ] Redis-free handoff polling, restart catch-up, duplicate wake, readiness suppression, and bounded backoff are tested.
- [ ] Actor mailbox, shared dispatcher, WebSocket send queue, overload reason, and terminal cleanup are bounded.
- [ ] Every failure path says whether to retry, recover, block, or quarantine.
- [ ] Every test command names the exact module/class and expected evidence.
- [ ] Mandatory Testcontainers XML proves `tests>0` and `skipped=0`; backend parity and strict Agent OS gates pass.
- [ ] Unresolved-marker scan is empty:

  ```bash
  rg -n 'TO[D]O|TB[D]|FIXM[E]' \
    docs/superpowers/plans/2026-07-30-v2-realtime-battle-foundation-implementation-plan.md
  ```

- [ ] Path scan confirms every modified existing file exists and every created parent directory has a valid module owner.
- [ ] An independent plan reviewer reports no remaining `fix-required`.

## Follow-up Specs and Ticket Dependencies

After this plan is approved, create one Jira target/Epic for the common foundation and separate dependent Epics for:

1. land adapter spec and implementation;
2. siege adapter spec and implementation;
3. naval adapter spec and implementation;
4. 2.5D renderer/HUD and tracked asset integration;
5. G6 cross-adapter acceptance plus realtime GO/fallback decision.

All five depend on the common foundation. The three adapter Epics may proceed in parallel only after the shared SPI, ticket, command, replay, and result contracts are committed. The renderer may consume server projection contracts after Task 10. G6 starts only after all four consumers are complete.
