# Active World Map Version Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resume the stalled `spep` turn daemon by pinning its persisted 780-city identity space to an immutable compatibility map, while keeping new worlds on the 774-city map and preventing invalid NPC movement candidates from aborting a tick.

**Architecture:** Restore the exact pre-merge Han artifacts as `han-780-v1`, register them through the existing `CityConstRegistry`/`ActiveWorldMap` route, and use a fail-closed Flyway migration to pin only exact `1..780` worlds. Add an AI corruption guard and a boot validator, then expose the same compatibility key through game-api and its packaged terrain assets.

**Tech Stack:** Kotlin 2.1, JDK 21, Spring Boot, Flyway Java migrations, PostgreSQL 16/Testcontainers, JUnit/Kotlin test, Gradle 8.12, Docker multi-stage images, GitHub Actions production promotion.

**Spec:** `docs/superpowers/specs/2026-08-29-active-world-map-version-recovery-design.md`

## Global Constraints

- Work only in `/Users/apple/Desktop/개인프로젝트/opensamguk-meta/worktrees/opensamguk/spep-turn-map-version-recovery`.
- Follow strict red-green TDD for every production behavior change.
- Existing exact 780-city Han worlds use `han-780-v1`; fresh Han worlds remain `han` with 774 cities.
- Restore compatibility artifacts only from commit `ad75119548050e5954d813753da41aa6a30be3b3`.
- Never remap or delete live gameplay entity ids in this task.
- Preserve valid-world RNG behavior and path insertion order.
- Permit `general.city_id=0` and null nation capitals as existing off-map domain states; reject only positive unresolved references.
- Keep `ng_games.map` unchanged because it is loaded as `map_theme`, not the active CityConst selector.
- Use migration version `V45`; the unimplemented durable-server-lifecycle plan must be rebased to the next available Flyway version before its implementation.
- Treat Testcontainers skips as missing evidence, not a pass; production migration requires Docker-backed V45 tests.
- Every logical code task ends in one commit with `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Do not push, open/merge a PR, or dispatch production workflows without the separate approval required by `docs/admin/operations-and-recovery.md`.

---

### Task 1: Restore and register the immutable 780-city compatibility map

**Files:**
- Create: `common/src/main/kotlin/opensamguk/common/constants/Han780V1CityConst.kt`
- Create: `common/src/main/kotlin/opensamguk/common/constants/Han780V1GateIndex.kt`
- Create: `infra/src/main/resources/map/han-780-v1.json`
- Create: `data/map/han-780-v1-tiles.json`
- Create: `data/map/han-780-v1-manifest.json`
- Modify: `logic/src/main/kotlin/opensamguk/logic/world/CityConstRegistry.kt`
- Modify: `logic/src/main/kotlin/opensamguk/logic/constraints/Presets.kt`
- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/turn/ReservedTurnHandler.kt`
- Modify: `logic/src/test/kotlin/opensamguk/logic/world/CityConstRegistryTest.kt`
- Modify: `logic/src/test/kotlin/opensamguk/logic/actions/founding/HanFoundAssaultTest.kt`
- Modify: `app/game-engine/src/test/kotlin/opensamguk/engine/turn/PrecheckFullCrossCallSiteTest.kt`
- Create: `infra/src/test/kotlin/opensamguk/infra/seed/Han780V1CompatibilityResourceTest.kt`

**Interfaces:**
- Produces: `HAN_780_V1_MAP_NAME: String = "han-780-v1"`.
- Produces: `isHanMapName(mapName: Any?): Boolean`, true for `han` and `han-780-v1` only.
- Produces: `foundingDefenseAfterCapture(mapName: Any?, currentDefense: Int, postDefense: Int): Int` for the handler's Han-family defense rule.
- Produces: `CityConstRegistry.of("han-780-v1")` with 780 ids, historical adjacency, gate keys, and thresholds `listOf(0, 1, 5, 13, 20, 28, 41, 53, 71, 91)`.
- Produces: classpath `map/han-780-v1.json` and external `data/map/han-780-v1-tiles.json`.

- [ ] **Step 1: Add RED registry tests for the compatibility identity space**

Add these behavioral tests to `CityConstRegistryTest` before creating any compatibility production file:

```kotlin
@Test
fun `legacy Han compatibility variant preserves the 780-city identity space`() {
    val legacy = CityConstRegistry.of("han-780-v1")
    assertEquals((1..780).toList(), legacy.all().keys.toList())
    assertEquals(listOf(0, 1, 5, 13, 20, 28, 41, 53, 71, 91), legacy.nationLevelCityThresholds)
}

@Test
fun `legacy Han compatibility variant preserves historical numeric adjacency`() {
    val graph = buildString {
        for ((id, city) in CityConstRegistry.of("han-780-v1").all()) {
            append(id).append(':').append(city.path.keys.joinToString(",")).append('\n')
        }
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(graph.toByteArray())
        .joinToString("") { "%02x".format(it) }
    assertEquals("a6d9370725010714960508bee046420ea671dddd8339f9e3b8796dddd2606014", digest)
}

@Test
fun `Han family recognition includes current and compatibility keys only`() {
    assertTrue(isHanMapName("han"))
    assertTrue(isHanMapName("han-780-v1"))
    assertFalse(isHanMapName("che"))
    assertFalse(isHanMapName(null))
}
```

The production mutation these tests catch is removal/reindexing of a compatibility city or adjacency edge.

- [ ] **Step 2: Run the registry tests and verify RED**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :logic:test --tests 'opensamguk.logic.world.CityConstRegistryTest' --rerun-tasks
```

Expected: FAIL because `CityConstRegistry.of("han-780-v1")` reports no variant.

- [ ] **Step 3: Mechanically restore the four historical artifacts**

Restore the generated artifacts from the exact approved source commit. These are bulk mechanical restores, not hand-authored rewrites:

```bash
git show ad75119548050e5954d813753da41aa6a30be3b3:common/src/main/kotlin/opensamguk/common/constants/HanCityConst.kt \
  > common/src/main/kotlin/opensamguk/common/constants/Han780V1CityConst.kt
perl -0pi -e 's/object HanCityConst/object Han780V1CityConst/' \
  common/src/main/kotlin/opensamguk/common/constants/Han780V1CityConst.kt

git show ad75119548050e5954d813753da41aa6a30be3b3:common/src/main/kotlin/opensamguk/common/constants/HanGateIndex.kt \
  > common/src/main/kotlin/opensamguk/common/constants/Han780V1GateIndex.kt
perl -0pi -e 's/object HanGateIndex/object Han780V1GateIndex/' \
  common/src/main/kotlin/opensamguk/common/constants/Han780V1GateIndex.kt

git show ad75119548050e5954d813753da41aa6a30be3b3:infra/src/main/resources/map/han.json \
  > infra/src/main/resources/map/han-780-v1.json
git show ad75119548050e5954d813753da41aa6a30be3b3:data/map/han-tiles.json \
  > data/map/han-780-v1-tiles.json
```

Verify the exact transformed/resource hashes:

```bash
shasum -a 256 \
  common/src/main/kotlin/opensamguk/common/constants/Han780V1CityConst.kt \
  common/src/main/kotlin/opensamguk/common/constants/Han780V1GateIndex.kt \
  infra/src/main/resources/map/han-780-v1.json \
  data/map/han-780-v1-tiles.json
```

Expected, in order:

```text
3df1952c65406ddde4f22e4462867b897b8707ad41db645c4e78fb3212a19199
7c381e5d7a18948f703c79a92f7edf1b11f0782942783428e3b087da930dc732
a61cbd8aa6fd0dd2f7f794df6d0ebdc026c0b6c351568c60efb8d115f54b3670
1979c193de6774af7c3cf5a9ddfd1c81bf94ead5b8c5b46dafd06bed03c6888d
```

- [ ] **Step 4: Add the immutable manifest**

Create `data/map/han-780-v1-manifest.json` with this exact content:

```json
{
  "mapName": "han-780-v1",
  "sourceCommit": "ad75119548050e5954d813753da41aa6a30be3b3",
  "cityCount": 780,
  "sourceHashes": {
    "HanCityConst.kt": "12388359f9a18a0e165621807d2fcdd8a114443081d38218162f3d0f583bde8c",
    "HanGateIndex.kt": "c73b711f730d5434fd182c49fe51a6548d9abe6122d27c27fd334978fba7ab4b",
    "han.json": "a61cbd8aa6fd0dd2f7f794df6d0ebdc026c0b6c351568c60efb8d115f54b3670",
    "han-tiles.json": "1979c193de6774af7c3cf5a9ddfd1c81bf94ead5b8c5b46dafd06bed03c6888d"
  },
  "committedHashes": {
    "Han780V1CityConst.kt": "3df1952c65406ddde4f22e4462867b897b8707ad41db645c4e78fb3212a19199",
    "Han780V1GateIndex.kt": "7c381e5d7a18948f703c79a92f7edf1b11f0782942783428e3b087da930dc732",
    "han-780-v1.json": "a61cbd8aa6fd0dd2f7f794df6d0ebdc026c0b6c351568c60efb8d115f54b3670",
    "han-780-v1-tiles.json": "1979c193de6774af7c3cf5a9ddfd1c81bf94ead5b8c5b46dafd06bed03c6888d"
  }
}
```

- [ ] **Step 5: Refactor the Han variant into a parameterized immutable implementation**

In `CityConstRegistry.kt`, add the imports and constant:

```kotlin
import opensamguk.common.constants.Han780V1CityConst
import opensamguk.common.constants.Han780V1GateIndex

const val HAN_780_V1_MAP_NAME: String = "han-780-v1"

fun isHanMapName(mapName: Any?): Boolean =
    mapName == HAN_MAP_NAME || mapName == HAN_780_V1_MAP_NAME

fun foundingDefenseAfterCapture(mapName: Any?, currentDefense: Int, postDefense: Int): Int =
    if (isHanMapName(mapName)) postDefense else currentDefense
```

Replace the singleton-only Han implementation with an internal class whose constructor consumes the identity-specific inputs while retaining the current generation logic:

```kotlin
internal class HanCityConstVariant(
    override val mapName: String,
    rawRows: List<RawCity>,
    private val gateKeysFor: (Int) -> Set<String>,
    override val nationLevelCityThresholds: List<Int>,
) : CityConstVariant {
    private val generated = generateHanCities(rawRows)

    override fun all(): Map<Int, CityInitialDetail> = generated.constID
    override fun byId(id: Int): CityInitialDetail? = generated.constID[id]
    override fun byName(name: String): CityInitialDetail? = generated.constName[name]
    override fun byRegion(region: Int): CityInitialDetail? = generated.constRegion[region]
    override fun gateKeys(cityId: Int): Set<String> = gateKeysFor(cityId)
    // Preserve the existing region/level/build/rank implementation verbatim.
}
```

Register two separately constructed variants:

```kotlin
private val currentHan = HanCityConstVariant(
    mapName = "han",
    rawRows = HanCityConst.initCity,
    gateKeysFor = HanGateIndex::keys,
    nationLevelCityThresholds = listOf(0, 1, 5, 12, 20, 27, 40, 52, 70, 90),
)
private val legacyHan = HanCityConstVariant(
    mapName = HAN_780_V1_MAP_NAME,
    rawRows = Han780V1CityConst.initCity,
    gateKeysFor = Han780V1GateIndex::keys,
    nationLevelCityThresholds = listOf(0, 1, 5, 13, 20, 28, 41, 53, 71, 91),
)
```

Map `"han" to currentHan` and `HAN_780_V1_MAP_NAME to legacyHan`. Do not modify che/miniche registration.

Replace the exact `== HAN_MAP_NAME` / `!= HAN_MAP_NAME` product gates in `foundAssaultCrewCost` and `Presets.kt` with `isHanMapName(...)`. Replace the `ReservedTurnHandler` defense ternary with `foundingDefenseAfterCapture(activeMapName(), current.defense, postCity.defense)`. Add `han-780-v1` cases to `HanFoundAssaultTest` for founding cost and defense selection, and to the Han-specific precheck cross-call-site cases for constraint dependencies and neutral-city assault behavior. Do not broaden the check to arbitrary `han-*` strings.

- [ ] **Step 6: Add RED resource-integrity tests, then make them GREEN with the restored resources**

Create `Han780V1CompatibilityResourceTest.kt`. Load `map/han-780-v1.json` through the real `MapJson` and hash its classpath bytes:

```kotlin
@Test
fun `compatibility map resource is the immutable 780-city artifact`() {
    val data = MapJson.loadFromClasspath("han-780-v1")
    assertEquals((1..780).toList(), data.cities.map { it.id })
    val bytes = requireNotNull(javaClass.classLoader.getResourceAsStream("map/han-780-v1.json")).readBytes()
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    assertEquals("a61cbd8aa6fd0dd2f7f794df6d0ebdc026c0b6c351568c60efb8d115f54b3670", hash)
}
```

Run once before Step 3 to observe the missing resource failure if the test is written first; after Steps 3–5 run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :logic:test --tests 'opensamguk.logic.world.CityConstRegistryTest' \
  --tests 'opensamguk.logic.actions.founding.HanFoundAssaultTest' \
  :infra:test --tests 'opensamguk.infra.seed.Han780V1CompatibilityResourceTest' \
  :app:game-engine:test --tests 'opensamguk.engine.turn.PrecheckFullCrossCallSiteTest' \
  --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`; inspect both XML files for zero failures/errors/skips.

- [ ] **Step 7: Commit Task 1**

```bash
git add common/src/main/kotlin/opensamguk/common/constants/Han780V1CityConst.kt \
  common/src/main/kotlin/opensamguk/common/constants/Han780V1GateIndex.kt \
  infra/src/main/resources/map/han-780-v1.json \
  data/map/han-780-v1-tiles.json data/map/han-780-v1-manifest.json \
  logic/src/main/kotlin/opensamguk/logic/world/CityConstRegistry.kt \
  logic/src/main/kotlin/opensamguk/logic/constraints/Presets.kt \
  app/game-engine/src/main/kotlin/opensamguk/engine/turn/ReservedTurnHandler.kt \
  logic/src/test/kotlin/opensamguk/logic/world/CityConstRegistryTest.kt \
  logic/src/test/kotlin/opensamguk/logic/actions/founding/HanFoundAssaultTest.kt \
  app/game-engine/src/test/kotlin/opensamguk/engine/turn/PrecheckFullCrossCallSiteTest.kt \
  infra/src/test/kotlin/opensamguk/infra/seed/Han780V1CompatibilityResourceTest.kt
git commit -m "feat(map): restore immutable Han 780 compatibility variant" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Pin exact legacy worlds with a fail-closed Flyway migration

**Files:**
- Create: `infra/src/main/kotlin/db/migration/V45__pin_legacy_han_world_map.kt`
- Create: `infra/src/test/kotlin/opensamguk/infra/persistence/V45LegacyHanWorldMapMigrationTest.kt`

**Interfaces:**
- Consumes: map key literal `han-780-v1` from Task 1.
- Produces: transactional, idempotent V45 migration of exact `1..780` Han worlds.
- Produces no changes to `city`, `general`, `nation`, `troop`, turn, ledger, or `ng_games` rows.

- [ ] **Step 1: Write a Docker-backed RED migration test**

The test must migrate a clean PostgreSQL database to version 44, seed three worlds, and target version 45:

```kotlin
@Test
fun `V45 pins only exact 780 Han worlds and preserves unrelated JSON`() {
    seedWorld(worldId = 1, mapName = "han", cityIds = 1..780,
        configExtra = "\"keep\":{\"nested\":7}", metaExtra = "\"operator\":\"spep\"")
    seedWorld(worldId = 2, mapName = "han", cityIds = 1..774)
    seedWorld(worldId = 3, mapName = "che", cityIds = 1..94)

    migrateTo("45")

    assertEquals("han-780-v1", activeMapName(1))
    assertEquals("han", activeMapName(2))
    assertEquals("che", activeMapName(3))
    assertEquals(7, config(1)["keep"].asObject()["nested"])
    assertEquals("spep", meta(1)["operator"])
    assertEquals(780, cityCount(1))
    assertEquals(0, changedGameplayIdCount())
}
```

Add a `gameplayIdentities(worldId)` helper that returns ordered id lists for `city`, `general`, `nation`, `troop`, `general_turn`, and `nation_turn`, plus `ngGamesMap(worldId)` and `worldJson(worldId)` helpers. Then add:

```kotlin
@Test
fun `V45 is isolated per world and leaves gameplay identities and ng_games map unchanged`() {
    seedWorld(1, "han", 1..780)
    seedWorld(2, "han", 1..774)
    val identitiesBefore = gameplayIdentities(1)
    val ngMapBefore = ngGamesMap(1)
    migrateTo("45")
    assertEquals("han-780-v1", activeMapName(1))
    assertEquals("han", activeMapName(2))
    assertEquals(identitiesBefore, gameplayIdentities(1))
    assertEquals(ngMapBefore, ngGamesMap(1))
}

@Test
fun `V45 fails closed for 779 Han cities`() {
    seedWorld(1, "han", 1..779)
    assertFailsWith<FlywayException> { migrateTo("45") }
    assertEquals("han", activeMapName(1))
    assertEquals(0, successfulMigrationCount("45"))
}

@Test
fun `V45 fails closed for non-contiguous 780-shaped Han ids`() {
    seedWorld(1, "han", (1..779).toList() + 781)
    assertFailsWith<FlywayException> { migrateTo("45") }
    assertEquals("han", activeMapName(1))
    assertEquals(0, successfulMigrationCount("45"))
}

@Test
fun `V45 records one migration and a second migrate call is a no-op`() {
    seedWorld(1, "han", 1..780)
    migrateTo("45")
    val pinned = worldJson(1)
    migrateTo("45")
    assertEquals(pinned, worldJson(1))
    assertEquals(1, successfulMigrationCount("45"))
}
```

Each fixture must use literal expected map values and query row identities after migration; do not assert only that the migration method was called.

- [ ] **Step 2: Run V45 tests and verify RED**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :infra:test --tests 'opensamguk.infra.persistence.V45LegacyHanWorldMapMigrationTest' \
  --rerun-tasks
```

Expected: FAIL because migration version 45 is absent and the 780 world remains `han`. If Docker is unavailable or the test is skipped, stop; this task has no valid RED evidence.

- [ ] **Step 3: Implement the minimal Java migration**

Create `V45__pin_legacy_han_world_map` extending `BaseJavaMigration`. Load worlds in id order with their JSON and exact city shape:

```kotlin
data class WorldShape(
    val id: Int,
    val config: MutableMap<String, Any?>,
    val meta: MutableMap<String, Any?>,
    val cityCount: Int,
    val minCityId: Int?,
    val maxCityId: Int?,
)
```

Resolve the map using the same precedence as `ActiveWorldMap.requireName`: config `mapName`, nested config `map.mapName`, string config `map`, then the equivalent meta fields. For map `han`:

```kotlin
when {
    shape.cityCount == 774 && shape.minCityId == 1 && shape.maxCityId == 774 -> Unit
    shape.cityCount == 780 && shape.minCityId == 1 && shape.maxCityId == 780 -> pin(shape)
    else -> throw FlywayException(
        "V45 cannot classify Han worldId=${shape.id}: " +
            "cityCount=${shape.cityCount} min=${shape.minCityId} max=${shape.maxCityId}",
    )
}
```

Because `(world_id,id)` is unique, count/min/max proves the contiguous range. `pin(shape)` must:

```kotlin
shape.config["mapName"] = "han-780-v1"
shape.config["map"] = rewriteMapField(shape.config["map"], "han-780-v1")
shape.meta["mapName"] = "han-780-v1"
shape.meta["map"] = rewriteMapField(shape.meta["map"], "han-780-v1")
```

`rewriteMapField` returns a copied insertion-ordered map with `mapName` changed when the input is a map; otherwise it returns the key string. Persist only `world_state.config/meta` with `MetaJson.encode(...)` bound as `?::jsonb`. Do not issue UPDATE/DELETE statements for any gameplay table or `ng_games`.

- [ ] **Step 4: Run focused migration tests and verify GREEN**

Run the Step 2 command. Inspect `TEST-opensamguk.infra.persistence.V45LegacyHanWorldMapMigrationTest.xml` and require zero failures, errors, and skips.

- [ ] **Step 5: Run the full infra migration chain**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :infra:test --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`; aggregate `infra/build/test-results/test/*.xml` and require zero failures/errors. Record any environment skip separately.

- [ ] **Step 6: Commit Task 2**

```bash
git add infra/src/main/kotlin/db/migration/V45__pin_legacy_han_world_map.kt \
  infra/src/test/kotlin/opensamguk/infra/persistence/V45LegacyHanWorldMapMigrationTest.kt
git commit -m "fix(migration): pin legacy Han world map identities" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Prevent invalid NPC nation-choice movement from aborting a tick

**Files:**
- Modify: `logic/src/main/kotlin/opensamguk/logic/ai/families/GenFoundFamily.kt:520-530`
- Modify: `logic/src/test/kotlin/opensamguk/logic/ai/families/GenFoundBodiesTest.kt:414-493`

**Interfaces:**
- Consumes: `GeneralAiContext.cityConst` and `selfCityId`.
- Produces: no command and no `choice` draw when the current city is unresolved or has zero ordered paths.
- Preserves: valid-path `nextBool(0.3)`, `nextBool(0.2)`, and one `choice` draw sequence.

- [ ] **Step 1: Write the missing-city RED regression test**

Add a helper that runs 401 literal seeds through the real body and records whether any invocation throws or emits a choice draw. Then add:

```kotlin
@Test
fun `do국가선택 missing current city never aborts the decision loop`() {
    var exercisedMoveBranch = false
    for (seed in 0..400) {
        val rng = RecordingRng("nc-missing-city-$seed")
        val ctx = ctxOf(
            rng,
            instance(nationId = 0),
            selfCityId = 999_999,
            cityConst = CityConstRegistry.of("han"),
            selfNpcType = 6,
            nationCount = 5,
            notFullNationCount = 5,
        )
        val result = GenFoundFamily.do국가선택(ctx)(null)
        if (rng.draws.take(2) == listOf(
                RecordingRng.Draw("nextBool", 0.3),
                RecordingRng.Draw("nextBool", 0.2),
            )) {
            exercisedMoveBranch = true
            assertNull(result)
            assertTrue(rng.draws.none { it.kind == "choice" })
        }
    }
    assertTrue(exercisedMoveBranch)
}
```

Add a second case with a test `CityConstVariant` whose `byId(selfCityId)` returns a real `CityInitialDetail` copy with `path=linkedMapOf()`. It must assert the same null/no-choice behavior.

- [ ] **Step 2: Run the body tests and verify RED**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :logic:test --tests 'opensamguk.logic.ai.families.GenFoundBodiesTest' --rerun-tasks
```

Expected: FAIL with `IllegalArgumentException: Empty items` on a seed that enters the move branch.

- [ ] **Step 3: Add the minimal corruption guard**

Change only the move branch:

```kotlin
if (nationChoiceMoveGate(rng)) {
    val paths = ctx.cityConst.byId(ctx.selfCityId)?.path?.keys?.toList().orEmpty()
    if (paths.isEmpty()) return null
    val destCityID = pickNationChoiceMove(paths, rng)
    val args = linkedMapOf<String, Any?>("destCityID" to destCityID)
    if (!ctx.candidateAllowed(MOVE_ACTION, args)) return null
    return ChosenCommand(MOVE_ACTION, args)
}
```

Do not change `RandUtil.choice`, catch generic exceptions, or move the guard before the 0.2 gate.

- [ ] **Step 4: Verify GREEN and valid-path draw preservation**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :logic:test --tests 'opensamguk.logic.ai.families.GenFoundBodiesTest' \
  --tests 'opensamguk.logic.ai.families.GenFoundFamilyTest' --rerun-tasks
```

Expected: both suites pass. Confirm the existing `nation-choice move target is a single choice` test still executes a choice for non-empty paths.

- [ ] **Step 5: Commit Task 3**

```bash
git add logic/src/main/kotlin/opensamguk/logic/ai/families/GenFoundFamily.kt \
  logic/src/test/kotlin/opensamguk/logic/ai/families/GenFoundBodiesTest.kt
git commit -m "fix(ai): contain invalid nation-choice movement" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Validate map identity at boot and expose compatibility assets through game-api

**Files:**
- Create: `app/game-engine/src/main/kotlin/opensamguk/engine/boot/ActiveWorldMapValidator.kt`
- Create: `app/game-engine/src/test/kotlin/opensamguk/engine/boot/ActiveWorldMapValidatorTest.kt`
- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/boot/WorldSnapshotLoader.kt:55-140`
- Modify: `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/TerrainMapController.kt:38-66`
- Modify: `app/game-api/src/test/kotlin/opensamguk/gameapi/controller/TerrainMapControllerTest.kt`
- Modify: `app/game-api/src/test/kotlin/opensamguk/gameapi/controller/GetConstControllerTest.kt`
- Modify: `app/game-api/src/test/kotlin/opensamguk/gameapi/controller/MapPreviewControllerTest.kt`
- Modify: `app/game-api/src/test/kotlin/opensamguk/gameapi/controller/AdminReadControllerTest.kt`
- Create: `app/game-api/src/test/kotlin/opensamguk/gameapi/v2/V2CommandPrecheckServiceTest.kt`
- Modify: `docker/game-api.Dockerfile`

**Interfaces:**
- Produces: `ActiveWorldMapValidator.validate(snapshot: WorldSnapshot): Unit`.
- Produces: fail-before-daemon behavior for map/city/reference mismatches.
- Produces: `/api/map/terrain?mapCode=han-780-v1` and compatibility province assets in the game-api image.

- [ ] **Step 1: Write RED validator unit tests**

Add a test helper with this exact contract:

```kotlin
private fun snapshot(
    mapName: String,
    cityIds: Iterable<Int>,
    generalCityIds: List<Int> = emptyList(),
    capitalCityIds: List<Int?> = emptyList(),
): WorldSnapshot
```

It builds `TurnWorldState(id=1, config=mapOf("mapName" to mapName), ...)`, one minimal `City` per id, one minimal `TurnGeneral` per `generalCityIds` entry, and one minimal `Nation` per `capitalCityIds` entry. Add these literal cases:

```kotlin
@Test
fun `exact compatibility city ids and positive references validate`() {
    ActiveWorldMapValidator.validate(snapshot("han-780-v1", 1..780, listOf(775), listOf(780)))
}

@Test
fun `774 city snapshot cannot claim the compatibility map`() {
    val failure = assertFailsWith<IllegalStateException> {
        ActiveWorldMapValidator.validate(snapshot("han-780-v1", 1..774))
    }
    assertEquals(
        "worldId=1 mapName=han-780-v1 persisted city ids do not match variant",
        failure.message,
    )
}

@Test
fun `positive unresolved general city fails but city zero is allowed`() {
    ActiveWorldMapValidator.validate(snapshot("han", 1..774, listOf(0)))
    val failure = assertFailsWith<IllegalStateException> {
        ActiveWorldMapValidator.validate(snapshot("han", 1..774, listOf(775)))
    }
    assertEquals("worldId=1 generalId=1 has unresolved cityId=775", failure.message)
}

@Test
fun `positive unresolved nation capital fails but null capital is allowed`() {
    ActiveWorldMapValidator.validate(snapshot("han", 1..774, capitalCityIds = listOf(null)))
    val failure = assertFailsWith<IllegalStateException> {
        ActiveWorldMapValidator.validate(snapshot("han", 1..774, capitalCityIds = listOf(775)))
    }
    assertEquals("worldId=1 nationId=1 has unresolved capitalCityId=775", failure.message)
}

@Test
fun `current 774 Han snapshot validates against current Han variant`() {
    ActiveWorldMapValidator.validate(snapshot("han", 1..774, listOf(774), listOf(1)))
}
```

Assert bounded messages such as:

```text
worldId=1 mapName=han-780-v1 persisted city ids do not match variant
worldId=1 generalId=1023 has unresolved cityId=781
```

- [ ] **Step 2: Run validator tests and verify RED**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :app:game-engine:test --tests 'opensamguk.engine.boot.ActiveWorldMapValidatorTest' --rerun-tasks
```

Expected: compile failure because `ActiveWorldMapValidator` does not exist.

- [ ] **Step 3: Implement and wire the boot validator**

Implement:

```kotlin
object ActiveWorldMapValidator {
    fun validate(snapshot: WorldSnapshot) {
        val variant = ActiveWorldMap.requireVariant(snapshot.state.config, snapshot.state.meta)
        val persistedIds = snapshot.cities.mapTo(linkedSetOf()) { it.id }
        check(persistedIds == variant.all().keys) {
            "worldId=${snapshot.worldId.value} mapName=${variant.mapName} persisted city ids do not match variant"
        }
        snapshot.generals.firstOrNull { it.cityId > 0 && it.cityId !in persistedIds }?.let {
            error("worldId=${snapshot.worldId.value} generalId=${it.id} has unresolved cityId=${it.cityId}")
        }
        snapshot.nations.firstOrNull { it.capitalCityId != null && it.capitalCityId !in persistedIds }?.let {
            error("worldId=${snapshot.worldId.value} nationId=${it.id} has unresolved capitalCityId=${it.capitalCityId}")
        }
    }
}
```

In `WorldSnapshotLoader.buildSnapshot`, construct the snapshot in a local variable, call `ActiveWorldMapValidator.validate(snapshot)`, then return it. Do not run validation inside the tick loop.

- [ ] **Step 4: Verify validator GREEN and run boot-focused tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :app:game-engine:test \
  --tests 'opensamguk.engine.boot.ActiveWorldMapValidatorTest' \
  --tests 'opensamguk.engine.boot.ScenarioBootIT' \
  --tests 'opensamguk.engine.boot.CheScenarioBootIT' \
  --rerun-tasks
```

Require unit tests green. If Docker is available, require both IT bodies green; if unavailable, record the skips and rely on the full Docker-backed gate before rollout.

- [ ] **Step 5: Write RED game-api compatibility tests**

In `GetConstControllerTest`, provide a `WorldStateReadEntity` with `config=mapOf("mapName" to "han-780-v1")` and assert:

```kotlin
.andExpect(jsonPath("$.mapName").value("han-780-v1"))
.andExpect(jsonPath("$.cityConst.length()").value(780))
.andExpect(jsonPath("$.cityConst[779].id").value(780))
```

In `TerrainMapControllerTest`, create sibling `han-780-v1-tiles.json` and assert a request with that exact `mapCode` returns its bytes. Run both tests now; expect the constants test to become green from Task 1 but the terrain test to fail 404 because `MAP_CODE` rejects hyphens.

In `MapPreviewControllerTest`, use a world with `mapName=han-780-v1` and assert `mapCode`, 780 coordinates, and id 780 are returned. In `AdminReadControllerTest`, assert the active map field is exactly `han-780-v1`. In `V2CommandPrecheckServiceTest`, use the historical literal adjacent pair `fromCityId=1` and `toCityId=2`, run the real distance/precheck path with `mapName=han-780-v1`, and assert the same allow/deny contract used by the current `han` case. This proves the compatibility key reaches precheck consumers rather than only DTO presentation.

- [ ] **Step 6: Permit safe versioned map keys and package both terrain sets**

Change the controller regex to:

```kotlin
val MAP_CODE = Regex("[a-z0-9]+(?:[a-z0-9_-]*[a-z0-9])?")
```

Keep the existing traversal-rejection test. In `docker/game-api.Dockerfile`, generate and copy both province maps:

```dockerfile
RUN python3 tools/map/build_province_map.py \
    --input data/map/han-tiles.json \
    --output-dir build/generated-map \
    --map-code han \
 && python3 tools/map/build_province_map.py \
    --input data/map/han-780-v1-tiles.json \
    --output-dir build/generated-map \
    --map-code han-780-v1

COPY data/map/han-tiles.json /app/data/map/han-tiles.json
COPY data/map/han-780-v1-tiles.json /app/data/map/han-780-v1-tiles.json
COPY --from=build /src/build/generated-map/ /app/data/map/
```

- [ ] **Step 7: Verify API tests and Docker image contents**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :app:game-api:test \
  --tests 'opensamguk.gameapi.controller.GetConstControllerTest' \
  --tests 'opensamguk.gameapi.controller.TerrainMapControllerTest' \
  --rerun-tasks

docker build -f docker/game-api.Dockerfile --build-arg IMAGE_TAG=map-recovery-test \
  -t opensamguk-game-api:map-recovery-test .
docker run --rm --entrypoint sh opensamguk-game-api:map-recovery-test -c \
  'test -s /app/data/map/han-780-v1-tiles.json && test -s /app/data/map/han-780-v1-provinces.png'
```

Expected: focused tests pass and the container file assertions exit 0.

- [ ] **Step 8: Commit Task 4**

```bash
git add app/game-engine/src/main/kotlin/opensamguk/engine/boot/ActiveWorldMapValidator.kt \
  app/game-engine/src/test/kotlin/opensamguk/engine/boot/ActiveWorldMapValidatorTest.kt \
  app/game-engine/src/main/kotlin/opensamguk/engine/boot/WorldSnapshotLoader.kt \
  app/game-api/src/main/kotlin/opensamguk/gameapi/controller/TerrainMapController.kt \
  app/game-api/src/test/kotlin/opensamguk/gameapi/controller/TerrainMapControllerTest.kt \
  app/game-api/src/test/kotlin/opensamguk/gameapi/controller/GetConstControllerTest.kt \
  app/game-api/src/test/kotlin/opensamguk/gameapi/controller/MapPreviewControllerTest.kt \
  app/game-api/src/test/kotlin/opensamguk/gameapi/controller/AdminReadControllerTest.kt \
  app/game-api/src/test/kotlin/opensamguk/gameapi/v2/V2CommandPrecheckServiceTest.kt \
  docker/game-api.Dockerfile
git commit -m "fix(runtime): validate and serve versioned world maps" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Document, verify, review, and recover `spep`

**Files:**
- Modify: `docs/admin/operations-and-recovery.md`
- Modify: `docs/design/architecture-boundary.md`
- Create: `docs/superpowers/reviews/2026-08-29-active-world-map-version-recovery-review.md`
- Create outside Git repo after completion: `/Users/apple/Desktop/개인프로젝트/opensamguk-meta/reports/opensamguk/tasks/2026-08-29-spep-turn-map-version-recovery.md`

**Interfaces:**
- Consumes: Tasks 1–4 and their commits.
- Produces: current verification evidence, adversarial review, rollout/rollback record, and required metarepo task report.

- [ ] **Step 1: Update operator and architecture documentation**

Add an "active-world map identity" section stating:

```text
Once a world is seeded, mapName identifies an immutable numeric city-id space.
Changing generated city count/order requires a new versioned map key.
Existing worlds are never silently moved to the new key and never repaired by ordinal id guessing.
```

In the recovery runbook, document `han-780-v1`, V45's exact `1..780` admission, backup prerequisite, fail-closed ambiguous shapes, and the four post-deploy signals: health UP, successfulTicks increasing, consecutiveFailures zero, public time advancing.

- [ ] **Step 2: Run fresh full verification**

Run:

```bash
git diff --check
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test \
  --rerun-tasks
```

Confirm `BUILD SUCCESSFUL` in output and aggregate every touched module's XML:

```bash
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
roots = [Path(p) for p in [
    'common/build/test-results/test', 'logic/build/test-results/test',
    'infra/build/test-results/test', 'app/game-engine/build/test-results/test',
    'app/game-api/build/test-results/test',
]]
totals = dict(tests=0, failures=0, errors=0, skipped=0)
for root in roots:
    for path in root.glob('*.xml'):
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.attrib.get(key, 0))
print(totals)
assert totals['failures'] == totals['errors'] == 0
PY
```

Require V45 migration tests to report `skipped=0`. Run the game-api Docker build/file assertion from Task 4 again at the final SHA.

- [ ] **Step 3: Run mutation checks for the two critical regressions**

Temporarily remove `HAN_780_V1_MAP_NAME to legacyHan`; run `CityConstRegistryTest` and observe failure, then restore.

Temporarily remove `if (paths.isEmpty()) return null`; run the missing-city `GenFoundBodiesTest` and observe `Empty items`, then restore.

Rerun both focused suites green. Record exact commands and results in the review file.

- [ ] **Step 4: Perform independent adversarial review without changing code**

Use the repository's required independent reviewer/provider path. Review the complete diff from `5cc0e202` for:

- historical artifact provenance and hashes;
- V45 false-positive/false-negative classification;
- JSON field preservation and `ng_games.map` non-mutation;
- city-zero/null-capital validity;
- RNG draw preservation;
- API path safety with hyphens;
- Docker artifact presence;
- rollout/rollback safety.

Write all findings and fixes to `docs/superpowers/reviews/2026-08-29-active-world-map-version-recovery-review.md`. Do not mark cleared while any `fix-required` item remains.

- [ ] **Step 5: Commit documentation and review evidence**

```bash
git add docs/admin/operations-and-recovery.md \
  docs/design/architecture-boundary.md \
  docs/superpowers/reviews/2026-08-29-active-world-map-version-recovery-review.md
git commit -m "docs(ops): define active world map recovery" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 6: Stop for separate push/PR/merge/deploy approval**

Present the exact branch, commit list, verification counts, review verdict, and production mutation summary. Obtain explicit approval for each externally mutating step required by `docs/admin/operations-and-recovery.md`.

- [ ] **Step 7: Capture pre-deploy `spep` evidence and backup proof**

Using the approved read-only predeploy workflow/runbook, record without exposing secrets:

- current image digests;
- `world_id`, `scenario_code`, active map key;
- city count/min/max/distinct count;
- last public game time;
- daemon `successfulTicks`, `failedTicks`, `consecutiveFailures`, and last error;
- database backup artifact identity and restore-runbook readiness.

If backup/restore evidence is absent, stop before deployment as required by the admin runbook.

- [ ] **Step 8: Promote the merged immutable SHA to `spep` including game-engine**

After merge, resolve and record the immutable merge SHA:

```bash
git fetch origin main
MERGED_SHA=$(git rev-parse origin/main)
git merge-base --is-ancestor HEAD "$MERGED_SHA"
```

Dispatch `Promote Game Server` with:

```text
server=spep
tag=$MERGED_SHA
include_engine=true
```

Do not use reset/delete. V45 applies during normal startup and changes only the active map key for the exact 780 world.

- [ ] **Step 9: Verify production recovery with two observations**

After deployment, capture two bounded status snapshots far enough apart to show progress. Require:

```text
engine health = UP
successfulTicks(second) > successfulTicks(first)
consecutiveFailures = 0
lastTickError no longer repeats Empty items
public game time(second) > public game time(first)
active map key = han-780-v1
city ids remain exact 1..780
general 1023 remains attached to its original persisted city identity
```

If validation fails, stop rollout and use the approved image/database rollback path; do not hand-edit ids or weaken V45/validator conditions.

- [ ] **Step 10: Write the required metarepo report**

Create `/Users/apple/Desktop/개인프로젝트/opensamguk-meta/reports/opensamguk/tasks/2026-08-29-spep-turn-map-version-recovery.md`. Use these exact headings and fill each body only with actually observed values; leave no speculative markers:

```markdown
# spep turn map version recovery

## Result
## Change commits
## Verification
## Production mutation
## Documentation impact
## Remaining risk
```

Record the deployed SHA and workflow run URLs/ids, but no secrets or copied transcripts.

- [ ] **Step 11: Final worktree disposition**

If the project worktree is clean after the report and integration is complete, remove it with the metarepo's supported cleanup flow and prune. If dirty, leave it in place and report why; never auto-remove a dirty worktree.
