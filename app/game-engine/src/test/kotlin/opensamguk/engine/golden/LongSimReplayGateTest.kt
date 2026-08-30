package opensamguk.engine.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import opensamguk.common.constants.EffectiveGameConst
import opensamguk.common.constants.GameConst
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.engine.run.MonthlyPreUpdateHook
import opensamguk.engine.run.MonthlyPostUpdateHook
import opensamguk.engine.turn.AiTurnAdapter
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.EngineGeneralActionPipelineBuilder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LegacyDiplomacyIdentityOracle
import opensamguk.engine.turn.LifecycleEnv
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.ProcessNationCommand
import opensamguk.engine.turn.RankColumn
import opensamguk.engine.turn.RankDelta
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.engine.turn.TurnDiplomacy
import opensamguk.engine.world.WorldEventContextFactory
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.infra.persistence.MetaJson
import opensamguk.infra.seed.ScenarioJson
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.develop.SightseeingCandidate
import opensamguk.logic.actions.develop.SightseeingExternalOutcome
import opensamguk.logic.actions.develop.SightseeingExternalSelector
import opensamguk.logic.actions.personnel.RandomImgwanPermutationReplay
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.ai.families.RatesPromoFamily
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.event.EventDispatcher
import opensamguk.logic.event.EventStore
import opensamguk.logic.event.WorldActions
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.StatCalc
import opensamguk.logic.util.phpRound
import opensamguk.logic.tick.CheckStatistic
import opensamguk.logic.tick.GameDate
import opensamguk.logic.tick.MonthScopedRng
import opensamguk.logic.tick.MonthlyClock
import opensamguk.logic.tick.MonthlyPipeline
import opensamguk.logic.tick.PostUpdateMonthly
import opensamguk.logic.tick.PreUpdateMonthly
import opensamguk.logic.tick.ServerClock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.Instant
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import kotlin.math.sqrt

internal fun longSimReplayRecorder(world: InMemoryTurnWorld): ChangeRecorder =
    ChangeRecorder(kvWriteObserver = world::applyKvDirtyFree)

internal fun longSimSnapshotValueMatches(expectedValue: JsonElement, actualValue: Any?): Boolean {
    if (expectedValue is JsonNull) return actualValue == null
    val primitive = expectedValue.jsonPrimitive
    if (primitive.isString) return primitive.content == actualValue?.toString()
    val actualNumber = actualValue as? Number ?: return false
    return primitive.double == actualNumber.toDouble()
}

internal fun longSimReplayPreUpdate(
    world: InMemoryTurnWorld,
    recorder: ChangeRecorder,
): PreUpdateMonthly = MonthlyPreUpdateHook(
    world = world,
    recorder = recorder,
    profileName = "longsim",
)

internal fun captureLongSimReplayState(
    world: InMemoryTurnWorld,
    expectedState: JsonObject,
): JsonObject = LongSimStateCapture.captureState(world, expectedState)

internal fun advanceLongSimReplayMonthBoundary(world: InMemoryTurnWorld, nextTurn: Instant) {
    world.setLastTurnTime(nextTurn)
}

internal fun assignLongSimReplayDiplomacyIdentity(world: InMemoryTurnWorld) {
    var nextNo = world.listDiplomacy()
        .mapNotNull { (it.meta["no"] as? Number)?.toInt() }
        .maxOrNull()
        ?.plus(1)
        ?: 1
    for (entry in world.listDiplomacy()) {
        if (entry.meta["no"] != null) continue
        world.createDiplomacy(entry.copy(meta = entry.meta + ("no" to nextNo++)))
    }
}

internal fun syncLongSimReplayNationEnv(world: InMemoryTurnWorld) {
    for (nation in world.listNations()) {
        val currentEnv = nation.meta["nation_env"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val nextEnv = LinkedHashMap<String, Any?>()
        currentEnv.forEach { (key, value) -> nextEnv[key.toString()] = value }
        var changed = false
        for ((key, value) in nation.meta) {
            if (!key.startsWith("turn_last_") || nextEnv[key] == value) continue
            nextEnv[key] = value
            changed = true
        }
        if (changed) {
            world.applyNationDirtyFree(nation.copy(meta = nation.meta + ("nation_env" to nextEnv)))
        }
    }
}

internal class LongSimDiplomacyIdentityOracle(
    rawEntries: List<JsonObject>,
    private val phase: () -> Int,
) : LegacyDiplomacyIdentityOracle {
    private data class CapturedRow(val no: Int, val src: Int, val dest: Int)

    private data class Transition(
        val seq: Int,
        val type: String,
        val phase: Int,
        val nationId: Int,
        val rows: MutableList<CapturedRow>,
    )

    private val transitions = ArrayDeque(
        rawEntries.mapIndexed { index, raw ->
            val seq = raw["seq"]!!.jsonPrimitive.int
            check(seq == index) { "diplomacy identity seq expected=$index actual=$seq" }
            val type = raw["type"]!!.jsonPrimitive.content
            check(type == "create" || type == "delete") {
                "diplomacy identity seq=$seq unsupported type=$type"
            }
            check(raw["source"]!!.jsonPrimitive.content.isNotBlank()) {
                "diplomacy identity seq=$seq has blank source"
            }
            val provenance = raw["provenance"]!!.jsonObject
            check(provenance.isNotEmpty() && !provenance.toString().contains("UNKNOWN")) {
                "diplomacy identity seq=$seq has unverified provenance=$provenance"
            }
            val nationId = raw["nation"]!!.jsonObject["id"]!!.jsonPrimitive.int
            val rowKey = if (type == "create") "created" else "deleted"
            val rows = raw[rowKey]!!.jsonArray.map { element ->
                val row = element.jsonObject
                CapturedRow(
                    no = row["no"]!!.jsonPrimitive.int,
                    src = row["src"]!!.jsonPrimitive.int,
                    dest = row["dest"]!!.jsonPrimitive.int,
                )
            }
            check(rows.isNotEmpty()) { "diplomacy identity seq=$seq has no $rowKey rows" }
            check(rows.map { it.no }.toSet().size == rows.size) {
                "diplomacy identity seq=$seq repeats a MariaDB no"
            }
            if (type == "create") {
                check(raw["nationQueryOrder"]!!.jsonArray.isNotEmpty()) {
                    "diplomacy identity seq=$seq has empty nation query order"
                }
            }
            Transition(
                seq = seq,
                type = type,
                phase = raw["phase"]!!.jsonPrimitive.int,
                nationId = nationId,
                rows = rows.toMutableList(),
            )
        },
    )
    private var active = false

    fun activate() {
        active = true
    }

    override fun assignCreatedNo(entry: TurnDiplomacy, proposedNo: Int?): Int? {
        if (!active) return null
        val transition = current("create")
        val rowIndex = transition.rows.indexOfFirst {
            it.src == entry.fromNationId && it.dest == entry.toNationId
        }
        check(rowIndex >= 0) {
            "diplomacy identity seq=${transition.seq} missing create " +
                "${entry.fromNationId}->${entry.toNationId}; proposedNo=$proposedNo remaining=${transition.rows}"
        }
        val row = transition.rows.removeAt(rowIndex)
        advanceIfComplete(transition)
        return row.no
    }

    override fun observeDeleted(entry: TurnDiplomacy, nationId: Int) {
        if (!active) return
        val transition = current("delete")
        check(nationId == transition.nationId) {
            "diplomacy identity seq=${transition.seq} delete nation expected=${transition.nationId} actual=$nationId"
        }
        val no = (entry.meta["no"] as? Number)?.toInt()
        val rowIndex = transition.rows.indexOfFirst {
            it.no == no && it.src == entry.fromNationId && it.dest == entry.toNationId
        }
        check(rowIndex >= 0) {
            "diplomacy identity seq=${transition.seq} missing delete " +
                "no=$no ${entry.fromNationId}->${entry.toNationId}; remaining=${transition.rows}"
        }
        transition.rows.removeAt(rowIndex)
        advanceIfComplete(transition)
    }

    fun assertComplete() {
        check(transitions.isEmpty()) {
            val next = transitions.first()
            "diplomacy identity stream not exhausted; next seq=${next.seq} type=${next.type} rows=${next.rows.size}"
        }
    }

    private fun current(expectedType: String): Transition {
        val transition = transitions.firstOrNull()
            ?: error("diplomacy identity stream exhausted before $expectedType")
        check(transition.type == expectedType) {
            "diplomacy identity seq=${transition.seq} expected type=${transition.type} actual=$expectedType"
        }
        check(transition.phase == phase()) {
            "diplomacy identity seq=${transition.seq} phase expected=${transition.phase} actual=${phase()}"
        }
        return transition
    }

    private fun advanceIfComplete(transition: Transition) {
        if (transition.rows.isEmpty()) {
            check(transitions.removeFirst() === transition)
        }
    }
}

internal fun validateLongSimReplayScope(monthsMax: Int, manifest: JsonObject) {
    check(monthsMax > 0) { "candidate monthsMax must be positive" }
    check(manifest["maxTurns"]!!.jsonPrimitive.int == monthsMax)
    check(manifest["totalMonths"]!!.jsonPrimitive.int == monthsMax)
    check(manifest["reachedMaxTurns"]!!.jsonPrimitive.content.toBooleanStrict())
    val points = manifest["points"]!!.jsonArray
    check(points.isNotEmpty())
    check(points.last().jsonObject["gameMonths"]!!.jsonPrimitive.int == monthsMax)
    check(manifest["phaseDrains"]!!.jsonArray.size == monthsMax * 3)
}

internal fun longSimReplayEventStore(
    scenarioCode: Int,
    startYear: Int,
    extendedGeneral: Boolean,
): EventStore {
    val resource = "scenario/scenario_$scenarioCode.json"
    val scenarioJson = LongSimReplayGateTest::class.java.classLoader.getResourceAsStream(resource)
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: error("missing long-sim scenario resource: $resource")
    val scenario = ScenarioJson.loadScenario(scenarioJson)
    val store = EventStore.withDefaults(scenario.ignoreDefaultEvents)
    fun element(value: Any?) = Json.parseToJsonElement(MetaJson.encode(value))

    for (event in scenario.events) {
        store.insertRaw(
            event.target,
            event.priority,
            element(event.condition),
            element(event.actions),
        )
    }

    val deferredByBirth = LinkedHashMap<Int, MutableList<List<Any?>>>()
    for (general in scenario.seedGenerals(extendedGeneral)) {
        val birth = general.bornYear ?: 180
        val death = general.deadYear ?: 300
        if (death <= startYear || birth + GameConst.adultAge.toInt() <= startYear) continue
        val actionName = if (general.npcType == 6) "RegNeutralNPC" else "RegNPC"
        deferredByBirth.getOrPut(birth) { mutableListOf() }
            .add(listOf(actionName) + general.rawTuple)
    }
    for ((birth, actions) in deferredByBirth) {
        store.insertRaw(
            "Month",
            1000,
            element(listOf("Date", ">=", birth + GameConst.adultAge.toInt(), "1")),
            element(actions + listOf(listOf("DeleteEvent"))),
        )
    }
    return store
}

/**
 * Phase 3 — 12-month long-simulation replay gate.
 *
 * Loads the PHP golden baseline + capture points, materializes [InMemoryTurnWorld], drives the same
 * months with production wiring minus flush/Redis/JPA, and asserts state/draw parity at each capture
 * point.
 *
 * **`` `12 month structural replay matches PHP golden` `` is CI-skipped by design, not by accident (#521).**
 * Two independent reasons, both permanent:
 *   1. It needs an external `LONGSIM_SCHEMA4_CANDIDATE_DIR` — a fresh PHP capture produced by
 *      `tools/php-golden/` — which requires `legacy/devsam-core` (git-ignored repo-wide, Koei-IP
 *      hold per CLAUDE.md). A GitHub-hosted CI checkout never has `legacy/`, so this candidate can
 *      never be produced there, on any schedule. `tools/php-golden/run_longsim.sh` states the same
 *      constraint for its sibling capture ("ONE-SHOT, MANUAL HOST STEP — NEVER CI").
 *   2. Every `assertEquals` in that test compares the Kotlin replay against data loaded from that
 *      PHP-captured `candidateDir` (see `validateCandidateCohort`/`loadResource` below) — i.e. its
 *      entire purpose is Kotlin-vs-PHP byte/structural parity. ADR-LITE-042 (2026-08-20) retired PHP
 *      as product oracle; product authority is now the approved ADR/spec + current implementation.
 *      So even where this candidate exists locally, this specific test is not a live product gate —
 *      it is optional historical-parity evidence, in the same class as the frozen `tools/php-golden/`
 *      workflows CLAUDE.md already scopes to "explicitly requested frozen-regression maintenance."
 * Registered as a ticket-required quarantine entry in `tools/agent-system/skipped_it_quarantine.json`
 * so the skip stays visible in CI output rather than silent. Not deleted — that call needs separate
 * product-owner approval (does the repo still want a permanently-local PHP-parity check kept around).
 */
class LongSimReplayGateTest {

    private data class PowerGeneralInput(
        val gold: Int,
        val rice: Int,
        val leadership: Int,
        val strength: Int,
        val intel: Int,
        val npc: Int,
        val dex: Int,
        val experience: Int,
        val dedication: Int,
        val killcrew: Int,
        val deathcrew: Int,
    )

    private data class PowerCityInput(val pop: Int, val stat: Int, val max: Int)

    private data class PowerBreakdown(
        val nationGoldRice: Int,
        val generalGoldRice: Int,
        val resources: Int,
        val tech: Int,
        val cityPop: Int,
        val cityStat: Int,
        val cityMax: Int,
        val internal: Int,
        val ability: Double,
        val dex: Int,
        val expDed: Int,
        val raw: Int,
        val draw: Double,
    )

    private fun powerBreakdown(
        nationGoldRice: Int,
        tech: Int,
        level: Int,
        generals: List<PowerGeneralInput>,
        cities: List<PowerCityInput>,
        draw: Double,
    ): PowerBreakdown {
        val generalGoldRice = generals.sumOf { it.gold + it.rice }
        val resources = phpRound((nationGoldRice + generalGoldRice) / 100.0)
        val cityPop = cities.sumOf { it.pop }
        val cityStat = cities.sumOf { it.stat }
        val cityMax = cities.sumOf { it.max }
        val internal = if (level == 0 || cityMax == 0) {
            0
        } else {
            phpRound(cityPop.toDouble() * cityStat / cityMax / 100.0)
        }
        val ability = generals.sumOf {
            (it.killcrew + 1000.0) / (it.deathcrew + 1000.0) *
                (if (it.npc < 2) 1.2 else 1.0) *
                (if (it.leadership >= 40) it.leadership else 0) * 2 +
                (sqrt(it.intel.toDouble() * it.strength) * 2 + it.leadership / 2.0) / 2
        }
        val dex = phpRound(generals.sumOf { it.dex } / 1000.0)
        val expDed = phpRound(generals.sumOf { it.experience + it.dedication } / 100.0)
        val raw = phpRound((resources + tech + internal + ability + dex + expDed) / 10.0)
        return PowerBreakdown(
            nationGoldRice, generalGoldRice, resources, tech,
            cityPop, cityStat, cityMax, internal, ability, dex, expDed, raw, draw,
        )
    }

    private fun capturedPowerBreakdown(
        state: JsonObject,
        nationId: Int,
        draw: Double,
    ): PowerBreakdown {
        val nation = state["nation"]!!.jsonArray.map { it.jsonObject }
            .single { it["nation"]!!.jsonPrimitive.int == nationId }
        val generals = state["general"]!!.jsonArray.map { it.jsonObject }
            .filter { it["nation"]!!.jsonPrimitive.int == nationId }
            .map {
                PowerGeneralInput(
                    gold = it["gold"]!!.jsonPrimitive.int,
                    rice = it["rice"]!!.jsonPrimitive.int,
                    leadership = it["leadership"]!!.jsonPrimitive.int,
                    strength = it["strength"]!!.jsonPrimitive.int,
                    intel = it["intel"]!!.jsonPrimitive.int,
                    npc = it["npc"]!!.jsonPrimitive.int,
                    dex = listOf("dex1", "dex2", "dex3", "dex4", "dex5")
                        .sumOf { key -> it[key]!!.jsonPrimitive.int },
                    experience = it["experience"]!!.jsonPrimitive.int,
                    dedication = it["dedication"]!!.jsonPrimitive.int,
                    killcrew = 0,
                    deathcrew = 0,
                )
            }
        val cities = state["city"]!!.jsonArray.map { it.jsonObject }
            .filter {
                it["nation"]!!.jsonPrimitive.int == nationId &&
                    it["supply"]!!.jsonPrimitive.int == 1
            }
            .map {
                PowerCityInput(
                    pop = it["pop"]!!.jsonPrimitive.int,
                    stat = listOf("pop", "agri", "comm", "secu", "wall", "def")
                        .sumOf { key -> it[key]!!.jsonPrimitive.int },
                    max = listOf("pop_max", "agri_max", "comm_max", "secu_max", "wall_max", "def_max")
                        .sumOf { key -> it[key]!!.jsonPrimitive.int },
                )
            }
        return powerBreakdown(
            nationGoldRice = nation["gold"]!!.jsonPrimitive.int + nation["rice"]!!.jsonPrimitive.int,
            tech = nation["tech"]!!.jsonPrimitive.int,
            level = nation["level"]!!.jsonPrimitive.int,
            generals = generals,
            cities = cities,
            draw = draw,
        )
    }

    private fun livePowerBreakdown(
        world: InMemoryTurnWorld,
        recorder: ChangeRecorder,
        nationId: Int,
        draw: Double,
    ): PowerBreakdown {
        val nation = checkNotNull(world.getNationById(nationId))
        fun rankValue(generalId: Int, meta: Map<String, Any?>, column: RankColumn): Int {
            val base = (meta[column.column] as? Number)?.toInt() ?: 0
            return when (val delta = recorder.rankDeltas(generalId)[column]) {
                is RankDelta.Increment -> base + delta.value
                is RankDelta.Set -> delta.value
                null -> base
            }
        }
        val generals = world.listGenerals().filter { it.nationId == nationId }.map {
            PowerGeneralInput(
                gold = it.gold,
                rice = it.rice,
                leadership = it.stats.leadership,
                strength = it.stats.strength,
                intel = it.stats.intelligence,
                npc = it.npcState,
                dex = listOf("dex1", "dex2", "dex3", "dex4", "dex5")
                    .sumOf { key -> (it.meta[key] as? Number)?.toInt() ?: 0 },
                experience = it.experience,
                dedication = it.dedication,
                killcrew = rankValue(it.id, it.meta, RankColumn.KILLCREW_PERSON),
                deathcrew = rankValue(it.id, it.meta, RankColumn.DEATHCREW_PERSON),
            )
        }
        val cities = world.listCities()
            .filter { it.nationId == nationId && it.supplyState == 1 }
            .map {
                PowerCityInput(
                    pop = it.population,
                    stat = it.population + it.agriculture + it.commerce + it.security + it.wall + it.defence,
                    max = it.populationMax + it.agricultureMax + it.commerceMax +
                        it.securityMax + it.wallMax + it.defenceMax,
                )
            }
        return powerBreakdown(
            nationGoldRice = nation.gold + nation.rice,
            tech = nation.tech.toInt(),
            level = nation.level,
            generals = generals,
            cities = cities,
            draw = draw,
        )
    }

    private fun powerRowDiagnostic(
        expectedState: JsonObject,
        world: InMemoryTurnWorld,
        handledCommands: List<JsonObject>,
    ): String {
        val liveCities = world.listCities().associateBy { it.id }
        val cityColumns = listOf("pop", "agri", "comm", "secu", "wall", "def")
        val firstCity = expectedState["city"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["nation"]!!.jsonPrimitive.int == 1 && it["supply"]!!.jsonPrimitive.int == 1 }
            .sortedBy { it["city"]!!.jsonPrimitive.int }
            .firstNotNullOfOrNull { expected ->
                val cityId = expected["city"]!!.jsonPrimitive.int
                val actual = liveCities[cityId] ?: return@firstNotNullOfOrNull "city=$cityId|missing"
                cityColumns.firstNotNullOfOrNull { column ->
                    val expectedValue = expected[column]!!.jsonPrimitive.int
                    val actualValue = when (column) {
                        "pop" -> actual.population
                        "agri" -> actual.agriculture
                        "comm" -> actual.commerce
                        "secu" -> actual.security
                        "wall" -> actual.wall
                        "def" -> actual.defence
                        else -> error(column)
                    }
                    if (expectedValue == actualValue) null
                    else "city=$cityId|column=$column|expected=$expectedValue|actual=$actualValue"
                }
            } ?: "city=NONE"

        val liveGenerals = world.listGenerals().associateBy { it.id }
        val firstGeneral = expectedState["general"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["nation"]!!.jsonPrimitive.int == 1 }
            .sortedBy { it["no"]!!.jsonPrimitive.int }
            .firstNotNullOfOrNull { expected ->
                val generalId = expected["no"]!!.jsonPrimitive.int
                val actual = liveGenerals[generalId] ?: return@firstNotNullOfOrNull "general=$generalId|missing"
                listOf(
                    "experience" to actual.experience,
                    "dedication" to actual.dedication,
                ).firstNotNullOfOrNull { (column, actualValue) ->
                    val expectedValue = expected[column]!!.jsonPrimitive.int
                    if (expectedValue == actualValue) null
                    else "general=$generalId|column=$column|expected=$expectedValue|actual=$actualValue"
                }
            } ?: "general=NONE"

        val cityId = Regex("""city=(\d+)""").find(firstCity)?.groupValues?.get(1)?.toIntOrNull()
        val generalId = Regex("""general=(\d+)""").find(firstGeneral)?.groupValues?.get(1)?.toIntOrNull()
        val cityActors = if (cityId == null) emptyList() else {
            world.listGenerals().filter { it.cityId == cityId }.map { it.id }.sorted()
        }
        val actorIds = (cityActors + listOfNotNull(generalId)).toSet()
        val sequences = actorIds.sorted().associateWith { actorId ->
            handledCommands.filter { it["actorGeneralId"]!!.jsonPrimitive.int == actorId }
                .joinToString(",") {
                    "${it["ordinal"]!!.jsonPrimitive.int}:${it["commandCode"]!!.jsonPrimitive.content}"
                }
        }
        return "LONGSIM_POWER_ROW_DIAGNOSTIC|$firstCity|$firstGeneral|" +
            "cityActors=$cityActors|handledSequences=$sequences"
    }

    private data class WeightedDecision(
        val cursorBeforeState: Long,
        val cursorBeforeBuffer: Int,
        val cursorAfterState: Long,
        val cursorAfterBuffer: Int,
        val drawValue: Double,
        val candidates: List<Pair<String, Double>>,
        val picked: String,
    )

    private data class SightseeingReplayEntry(
        val ordinal: Int,
        val actorGeneralId: Int,
        val year: Int,
        val month: Int,
        val date: String,
        val type: Int,
        val text: String,
        val woundedDraw: Int?,
        val heavyWoundedDraw: Int?,
        val postActionInjury: Int,
    )

    private class DomesticDecisionRecordingRng(
        private val drbg: LiteHashDrbg,
        private val emit: (WeightedDecision) -> Unit,
    ) : RandUtil(drbg) {
        private var inWeightedPair = false
        private var weightedDraw: Double? = null

        override fun nextFloat1(): Double {
            val value = super.nextFloat1()
            if (inWeightedPair) weightedDraw = value
            return value
        }

        override fun <T> choiceUsingWeightPair(items: List<Pair<T, Double>>): T {
            val beforeState = drbg.peekStateIdx()
            val beforeBuffer = drbg.peekBufferIdx()
            weightedDraw = null
            inWeightedPair = true
            val picked = try {
                super.choiceUsingWeightPair(items)
            } finally {
                inWeightedPair = false
            }
            emit(
                WeightedDecision(
                    cursorBeforeState = beforeState,
                    cursorBeforeBuffer = beforeBuffer,
                    cursorAfterState = drbg.peekStateIdx(),
                    cursorAfterBuffer = drbg.peekBufferIdx(),
                    drawValue = checkNotNull(weightedDraw),
                    candidates = items.map { it.first.toString() to it.second },
                    picked = picked.toString(),
                ),
            )
            return picked
        }
    }

    private companion object {
        const val BASELINE = "golden/longsim/capture-00-baseline.json"
        const val MANIFEST = "golden/longsim/manifest_longsim.json"
        const val COHORT = "cohort.json"
        const val SIGHTSEEING_OBSERVER = "gyeonmun-sightseeing-observer.jsonl"
        const val HANDLED_COMMAND_SIDECAR = "handled-command-sidecar.jsonl"
        const val DIPLOMACY_IDENTITY_SIDECAR = "diplomacy-identity-sidecar.jsonl"
    }

    private val candidateDir: Path? =
        (
            System.getProperty("LONGSIM_SCHEMA4_CANDIDATE_DIR")
                ?: System.getenv("LONGSIM_SCHEMA4_CANDIDATE_DIR")
                ?: System.getenv("LONGSIM_CANDIDATE_DIR")
                ?: System.getProperty("LONGSIM_CANDIDATE_DIR")
            )
            .takeIf { !it.isNullOrBlank() }
            ?.let(Path::of)

    private fun loadResource(name: String): String {
        val candidate = candidateDir?.resolve(name.substringAfterLast('/'))
        if (candidate != null) return Files.readString(candidate)
        return javaClass.classLoader.getResourceAsStream(name)!!.readBytes().toString(Charsets.UTF_8)
    }

    private fun loadJson(name: String): JsonObject =
        Json.parseToJsonElement(loadResource(name)).jsonObject

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }

    private fun validateCandidateCohort(dir: Path): JsonObject {
        val resolved = dir.toAbsolutePath().normalize()
        val cohortPath = resolved.resolve(COHORT)
        check(Files.isRegularFile(cohortPath)) { "missing candidate cohort metadata: $cohortPath" }
        val cohort = Json.parseToJsonElement(Files.readString(cohortPath)).jsonObject
        val cohortSchema = cohort["schema"]!!.jsonPrimitive.content
        check(
            cohortSchema in setOf(
                "v1-ai-composite-cohort-v1",
                "v1-ai-composite-cohort-v2",
                "v1-ai-composite-cohort-v3",
            ),
        )
        check(cohort["cadence"]!!.jsonPrimitive.content == "composite-v1-36")
        check(cohort["freshDbRuns"]!!.jsonPrimitive.int == 1)
        val monthsMax = cohort["monthsMax"]!!.jsonPrimitive.int

        val manifestPath = resolved.resolve("manifest_longsim.json")
        val observerPath = resolved.resolve(SIGHTSEEING_OBSERVER)
        val sumsPath = resolved.resolve("SHA256SUMS")
        val finalStatePath = resolved.resolve(cohort["finalStateFile"]!!.jsonPrimitive.content)
        val requiredPaths = mutableListOf(manifestPath, observerPath, sumsPath, finalStatePath)
        val sidecarPath = resolved.resolve(HANDLED_COMMAND_SIDECAR)
        val diplomacySidecarPath = resolved.resolve(DIPLOMACY_IDENTITY_SIDECAR)
        if (cohortSchema != "v1-ai-composite-cohort-v1") requiredPaths.add(sidecarPath)
        if (cohortSchema == "v1-ai-composite-cohort-v3") requiredPaths.add(diplomacySidecarPath)
        for (path in requiredPaths) {
            check(Files.isRegularFile(path)) { "missing candidate cohort artifact: $path" }
        }
        check(sha256(manifestPath) == cohort["manifestSha256"]!!.jsonPrimitive.content)
        check(sha256(observerPath) == cohort["sightseeingObserverSha256"]!!.jsonPrimitive.content)
        check(sha256(finalStatePath) == cohort["finalStateSha256"]!!.jsonPrimitive.content)
        check(sha256(sumsPath) == cohort["cohortSha256"]!!.jsonPrimitive.content)
        if (cohortSchema != "v1-ai-composite-cohort-v1") {
            check(sha256(sidecarPath) == cohort["handledCommandSidecarSha256"]!!.jsonPrimitive.content)
        }
        if (cohortSchema == "v1-ai-composite-cohort-v3") {
            check(
                sha256(diplomacySidecarPath) ==
                    cohort["diplomacyIdentitySidecarSha256"]!!.jsonPrimitive.content,
            )
        }

        val manifest = Json.parseToJsonElement(Files.readString(manifestPath)).jsonObject
        check(manifest["schemaVersion"]!!.jsonPrimitive.int in setOf(2, 3, 4))
        validateLongSimReplayScope(monthsMax, manifest)
        check(manifest["handledCommands"]!!.jsonArray.size == cohort["handledCommands"]!!.jsonPrimitive.int)
        check(
            manifest["randomImgwanPermutations"]!!.jsonArray.size ==
                cohort["randomImgwanPermutations"]!!.jsonPrimitive.int,
        )
        check(manifest["domesticDecisions"]!!.jsonArray.single().jsonObject["actorGeneralId"]!!.jsonPrimitive.int == 36)
        val sightseeingEntries = Files.readAllLines(observerPath).count { it.isNotBlank() }
        check(sightseeingEntries == cohort["sightseeingEntries"]!!.jsonPrimitive.int)
        if (cohortSchema != "v1-ai-composite-cohort-v1") {
            val sidecarEntries = Files.readAllLines(sidecarPath).count { it.isNotBlank() }
            check(sidecarEntries == cohort["handledCommandSidecarEntries"]!!.jsonPrimitive.int)
            check(sidecarEntries == manifest["handledCommandSidecar"]!!.jsonObject["count"]!!.jsonPrimitive.int)
        }
        if (cohortSchema == "v1-ai-composite-cohort-v3") {
            val descriptor = manifest["diplomacyIdentitySidecar"]!!.jsonObject
            val sidecarEntries = Files.readAllLines(diplomacySidecarPath).count { it.isNotBlank() }
            check(descriptor["file"]!!.jsonPrimitive.content == DIPLOMACY_IDENTITY_SIDECAR)
            check(sidecarEntries == cohort["diplomacyIdentitySidecarEntries"]!!.jsonPrimitive.int)
            check(sidecarEntries == descriptor["count"]!!.jsonPrimitive.int)
            check(sha256(diplomacySidecarPath) == descriptor["sha256"]!!.jsonPrimitive.content)
        }
        println("LONGSIM_COHORT_ID=${cohort["cohortId"]!!.jsonPrimitive.content}")
        println("LONGSIM_COHORT_SHA256=${cohort["cohortSha256"]!!.jsonPrimitive.content}")
        return cohort
    }

    private fun loadHandledCommandSidecar(cohort: JsonObject): ArrayDeque<JsonObject>? {
        if (cohort["schema"]!!.jsonPrimitive.content == "v1-ai-composite-cohort-v1") return null
        val path = checkNotNull(candidateDir)
            .toAbsolutePath()
            .normalize()
            .resolve(HANDLED_COMMAND_SIDECAR)
        return ArrayDeque(
            Files.readAllLines(path)
                .filter { it.isNotBlank() }
                .map { Json.parseToJsonElement(it).jsonObject },
        )
    }

    private fun loadDiplomacyIdentityOracle(
        cohort: JsonObject,
        phase: () -> Int,
    ): LongSimDiplomacyIdentityOracle? {
        if (cohort["schema"]!!.jsonPrimitive.content != "v1-ai-composite-cohort-v3") return null
        val path = checkNotNull(candidateDir)
            .toAbsolutePath()
            .normalize()
            .resolve(DIPLOMACY_IDENTITY_SIDECAR)
        return LongSimDiplomacyIdentityOracle(
            rawEntries = Files.readAllLines(path)
                .filter { it.isNotBlank() }
                .map { Json.parseToJsonElement(it).jsonObject },
            phase = phase,
        )
    }

    private fun assertCitySnapshot(
        expected: JsonObject?,
        world: InMemoryTurnWorld,
        actorGeneralId: Int,
        label: String,
    ) {
        val general = world.getGeneralById(actorGeneralId)
        val city = general?.let { world.getCityById(it.cityId) }
        if (expected == null) {
            check(city == null) { "$label expected no city but was ${city?.id}" }
            return
        }
        checkNotNull(city) { "$label expected city ${expected["city"]} but actor has none" }
        val actual = linkedMapOf<String, Any?>(
            "city" to city.id,
            "name" to city.name,
            "level" to city.level,
            "nation" to city.nationId,
            "supply" to city.supplyState,
            "front" to city.frontState,
            "pop" to city.population,
            "pop_max" to city.populationMax,
            "agri" to city.agriculture,
            "agri_max" to city.agricultureMax,
            "comm" to city.commerce,
            "comm_max" to city.commerceMax,
            "secu" to city.security,
            "secu_max" to city.securityMax,
            "trust" to city.meta["trust"],
            "trade" to city.trade,
            "dead" to city.dead,
            "def" to city.defence,
            "def_max" to city.defenceMax,
            "wall" to city.wall,
            "wall_max" to city.wallMax,
            "officer_set" to city.officerSet,
            "state" to city.state,
            "region" to city.region,
            "term" to city.term,
            "conflict" to city.conflict,
        )
        for ((key, expectedValue) in expected) {
            val actualValue = actual[key]
            check(longSimSnapshotValueMatches(expectedValue, actualValue)) {
                "$label city=${city.id} key=$key expected=$expectedValue actual=$actualValue"
            }
        }
    }

    private fun loadSightseeingReplay(cohort: JsonObject): ArrayDeque<SightseeingReplayEntry> {
        val observerPath = checkNotNull(candidateDir)
            .toAbsolutePath()
            .normalize()
            .resolve(SIGHTSEEING_OBSERVER)
        check(Files.isRegularFile(observerPath)) { "missing sightseeing observer: $observerPath" }
        val actualSha256 = sha256(observerPath)
        val expectedSha256 = cohort["sightseeingObserverSha256"]!!.jsonPrimitive.content
        check(actualSha256 == expectedSha256) { "unexpected sightseeing observer SHA-256: $actualSha256" }
        return ArrayDeque(
            Files.readAllLines(observerPath)
                .filter { it.isNotBlank() }
                .mapIndexed { ordinal, line ->
                    val raw = Json.parseToJsonElement(line).jsonObject
                    val log = raw["log"]!!.jsonPrimitive.content
                    val date = Regex("<1>([0-9]{2}:[0-9]{2})</>").find(log)?.groupValues?.get(1)
                        ?: error("sightseeing observer record $ordinal has no log date")
                    SightseeingReplayEntry(
                        ordinal = ordinal,
                        actorGeneralId = raw["actorGeneralId"]!!.jsonPrimitive.int,
                        year = raw["year"]!!.jsonPrimitive.int,
                        month = raw["month"]!!.jsonPrimitive.int,
                        date = date,
                        type = raw["type"]!!.jsonPrimitive.int,
                        text = raw["text"]!!.jsonPrimitive.content,
                        woundedDraw = raw["woundedDraw"]?.jsonPrimitive?.intOrNull,
                        heavyWoundedDraw = raw["heavyWoundedDraw"]?.jsonPrimitive?.intOrNull,
                        postActionInjury = raw["postAction"]!!.jsonObject["injury"]!!.jsonPrimitive.int,
                    )
                },
        )
    }

    @Test
    fun `PHP long-sim fixtures expose the replay oracle fields`() {
        val manifest = loadJson(MANIFEST)
        val baseline = LongSimWorldMaterializer.parseBaseline(loadJson(BASELINE))

        assertEquals(2, baseline.state["nation"]!!.jsonArray.size, "pristine scenario 1010 starts with two nations")

        for (point in manifest["points"]!!.jsonArray.map { it.jsonObject }) {
            val capture = loadJson("golden/longsim/${point["file"]!!.jsonPrimitive.content}")

            assertEquals(point["gameMonths"]!!.jsonPrimitive.int, capture["gameMonths"]!!.jsonPrimitive.int)
            assertEquals(point["year"]!!.jsonPrimitive.int, capture["year"]!!.jsonPrimitive.int)
            assertEquals(point["month"]!!.jsonPrimitive.int, capture["month"]!!.jsonPrimitive.int)
            assertEquals(
                point["rngDraws"]?.jsonPrimitive?.intOrNull,
                capture["monthlyRngDraws"]?.jsonPrimitive?.intOrNull,
                "manifest rngDraws mirrors capture monthlyRngDraws",
            )
            assertNotNull(capture["monthlySeedString"]?.jsonPrimitive?.contentOrNull)
            assertNotNull(capture["state"]?.jsonObject)
        }
    }

    @Test
    fun `long-sim materializer preserves the captured production policy surface`() {
        val baseline = LongSimWorldMaterializer.parseBaseline(loadJson(BASELINE))
        val state = baseline.state
        val gameEnv = state["game_env"]!!.jsonObject
        val policyEnv = JsonObject(
            gameEnv + (
                "npc_nation_policy" to buildJsonObject {
                    put("priority", Json.parseToJsonElement("""["천도"]"""))
                    put("values", buildJsonObject { put("cureThreshold", 40) })
                }
            ),
        )
        val world = LongSimWorldMaterializer.materializeWorld(
            baseline.copy(state = JsonObject(state + ("game_env" to policyEnv))),
        )

        @Suppress("UNCHECKED_CAST")
        val policy = world.getState().meta["npc_nation_policy"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val values = policy["values"] as Map<String, Any?>
        assertEquals(listOf("천도"), policy["priority"])
        assertEquals(40, values["cureThreshold"])
    }

    @Test
    fun `long-sim event store includes the scenario adult cohort`() {
        val store = longSimReplayEventStore(scenarioCode = 1010, startYear = 181, extendedGeneral = false)
        val adultNames = store.rowsFor(opensamguk.logic.event.EventTarget.MONTH)
            .flatMap { it.actions }
            .filter { it.name == "RegNPC" || it.name == "RegNeutralNPC" }
            .map { it.args[1].jsonPrimitive.content }

        assertEquals(1, adultNames.count { it == "유변" })
        assertEquals(1, adultNames.count { it == "유벽" })
    }

    @Test
    fun `long-sim snapshot comparator preserves JSON null identity`() {
        assertEquals(true, longSimSnapshotValueMatches(JsonNull, null))
        assertEquals(false, longSimSnapshotValueMatches(JsonNull, 0))
        assertEquals(true, longSimSnapshotValueMatches(Json.parseToJsonElement("18"), 18))
        assertEquals(true, longSimSnapshotValueMatches(Json.parseToJsonElement("\"trade\""), "trade"))
    }

    @Test
    fun `captured diplomacy identity assigns MariaDB numbers without changing domain order`() {
        var phase = 2
        val entries = listOf(
            Json.parseToJsonElement(
                """
                {
                  "seq":0,"type":"create","source":"che_거병","phase":2,
                  "phaseBoundary":"0181-01-01 02:00:00.000000",
                  "handledCommandOrdinal":10,"actorGeneralId":20,
                  "provenance":{"derivation":"instrumented INSERT"},
                  "nation":{"id":9},"nationQueryOrder":[8,7],
                  "created":[
                    {"no":65,"src":8,"dest":9},{"no":66,"src":9,"dest":8},
                    {"no":67,"src":7,"dest":9},{"no":68,"src":9,"dest":7}
                  ]
                }
                """.trimIndent(),
            ).jsonObject,
            Json.parseToJsonElement(
                """
                {
                  "seq":1,"type":"delete","source":"deleteNation","phase":2,
                  "phaseBoundary":"0181-01-01 02:00:00.000000",
                  "handledCommandOrdinal":11,"actorGeneralId":21,
                  "provenance":{"derivation":"caller frame","function":"run"},
                  "nation":{"id":8},
                  "deleted":[
                    {"no":65,"src":8,"dest":9},{"no":66,"src":9,"dest":8}
                  ]
                }
                """.trimIndent(),
            ).jsonObject,
        )
        val oracle = LongSimDiplomacyIdentityOracle(entries) { phase }
        oracle.activate()

        assertEquals(67, oracle.assignCreatedNo(TurnDiplomacy(7, 9, 0, 0), 65))
        assertEquals(68, oracle.assignCreatedNo(TurnDiplomacy(9, 7, 0, 0), 66))
        assertEquals(65, oracle.assignCreatedNo(TurnDiplomacy(8, 9, 0, 0), 67))
        assertEquals(66, oracle.assignCreatedNo(TurnDiplomacy(9, 8, 0, 0), 68))
        oracle.observeDeleted(TurnDiplomacy(9, 8, 0, 0, meta = mapOf("no" to 66)), 8)
        oracle.observeDeleted(TurnDiplomacy(8, 9, 0, 0, meta = mapOf("no" to 65)), 8)
        oracle.assertComplete()

        phase = 3
    }

    @Test
    fun `12 month structural replay matches PHP golden`() {
        // Permanently local-only, not a CI gap to close — see class doc above and #521.
        assumeTrue(
            candidateDir != null,
            "schema4 36-turn PHP candidate is required; set -DLONGSIM_SCHEMA4_CANDIDATE_DIR " +
                "(never available in CI — this needs legacy/devsam-core, which is git-ignored; " +
                "see class doc / #521)",
        )
        val cohort = checkNotNull(candidateDir).let { dir ->
            val resolved = dir.toAbsolutePath().normalize()
            val manifestPath = resolved.resolve("manifest_longsim.json")
            println("LONGSIM_CANDIDATE_DIR=$resolved")
            println("LONGSIM_CANDIDATE_MANIFEST_SHA256=${sha256(manifestPath)}")
            validateCandidateCohort(resolved)
        }
        val manifest = loadJson(MANIFEST)
        val baseline = LongSimWorldMaterializer.parseBaseline(loadJson(BASELINE))
        lateinit var world: InMemoryTurnWorld
        val diplomacyIdentityOracle = cohort?.let {
            loadDiplomacyIdentityOracle(it) { world.getState().currentPhase }
        }
        world = LongSimWorldMaterializer.materializeWorld(baseline, diplomacyIdentityOracle)
        val killturnOracle = manifest["schemaVersion"]?.jsonPrimitive?.intOrNull
            ?.takeIf { it in 2..4 }
            ?.let { LongSimKillturnOracle.fromManifest(manifest) }
        killturnOracle?.observeWorld(world)
        assignLongSimReplayDiplomacyIdentity(world)
        diplomacyIdentityOracle?.activate()
        val hiddenSeed = baseline.hiddenSeed
        val startYear = baseline.startYear
        val turnterm = world.getState().tickSeconds / 60
        val startTime = LongSimWorldMaterializer.parseTurnTime(
            baseline.state["game_env"]!!.jsonObject["starttime"]?.jsonPrimitive?.contentOrNull,
        )
        val scenario = baseline.state["game_env"]!!.jsonObject["scenario"]?.jsonPrimitive?.intOrNull ?: 1010
        val expectedHandledCommands = manifest["handledCommands"]!!.jsonArray.map { it.jsonObject }
        val sightseeingStream = cohort?.let { loadSightseeingReplay(it) }
        val handledCommandSidecar = cohort?.let { loadHandledCommandSidecar(it) }
        val powerDiagnostic = System.getenv("LONGSIM_POWER_DIAGNOSTIC") == "1"
        val rowDiagnosticEnabled = System.getenv("LONGSIM_POWER_ROW_DIAGNOSTIC") == "1"
        val expectedFirstPointState = loadJson(
            "golden/longsim/${manifest["points"]!!.jsonArray.first().jsonObject["file"]!!.jsonPrimitive.content}",
        )["state"]!!.jsonObject

        val pipeline = GeneralActionPipeline()
        val registry = CommandRegistry(pipeline)
        val pipelineBuilder = EngineGeneralActionPipelineBuilder(world, startYear)
        val diagnosticManifestPath =
            System.getenv("LONGSIM_DOMESTIC_DIAGNOSTIC_MANIFEST")?.takeIf { it.isNotBlank() }?.let(Path::of)
        val expectedDomesticDecision = diagnosticManifestPath?.let {
            Json.parseToJsonElement(Files.readString(it)).jsonObject["domesticDecisions"]!!
                .jsonArray.single().jsonObject
        } ?: manifest["domesticDecisions"]!!.jsonArray.single().jsonObject
        val domesticDiagnosticActor = expectedDomesticDecision["actorGeneralId"]!!.jsonPrimitive.int
        val domesticDiagnosticPhase = expectedDomesticDecision["phase"]!!.jsonPrimitive.int
        val expectedDomesticCandidates = expectedDomesticDecision["candidates"]!!.jsonArray.map { raw ->
            val pair = raw.jsonArray
            pair[0].jsonPrimitive.content to pair[1].jsonPrimitive.double
        }
        var domesticDecisionCount = 0
        data class PendingCommandDiagnostic(
            val phase: Int,
            val boundary: Instant,
            val dueTimestamp: Instant,
            val actorGeneralId: Int,
            val sidecar: JsonObject? = null,
            val logOffset: Int = 0,
            var commandCode: String = "UNKNOWN",
            var nationCommandCode: String? = null,
            var handledResult: ReservedTurnHandler.HandledTurn? = null,
        )
        val diagnosticTimeFormat =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ServerClock.SERVER_ZONE)
        var diagnosticBoundary = world.getState().lastTurnTime
        var pendingCommandDiagnostic: PendingCommandDiagnostic? = null
        var pendingSightseeingPostAction: Pair<Int, Int>? = null
        var commandDiagnosticOrdinal = 0
        var commandDivergencePrinted = false
        fun flushPendingCommandDiagnostic() {
            val pending = pendingCommandDiagnostic ?: return
            if (pending.handledResult == null) pending.commandCode = "BLOCKED"
            val next = world.getGeneralById(pending.actorGeneralId)?.turnTime
            val expected = expectedHandledCommands.getOrNull(commandDiagnosticOrdinal)
            val actualBoundary = diagnosticTimeFormat.format(pending.boundary)
            val actualDue = diagnosticTimeFormat.format(pending.dueTimestamp)
            val actualNext = next?.let(diagnosticTimeFormat::format) ?: "KILLED"
            pending.sidecar?.let { expectedSidecar ->
                check(expectedSidecar["commandCode"]!!.jsonPrimitive.content == pending.commandCode) {
                    "handled sidecar ordinal=$commandDiagnosticOrdinal command expected=" +
                        "${expectedSidecar["commandCode"]!!.jsonPrimitive.content} actual=${pending.commandCode}"
                }
                check(
                    expectedSidecar["nationCommandCode"]?.jsonPrimitive?.contentOrNull ==
                        pending.nationCommandCode,
                ) {
                    "handled sidecar ordinal=$commandDiagnosticOrdinal nation command expected=" +
                        "${expectedSidecar["nationCommandCode"]} actual=${pending.nationCommandCode}"
                }
                assertCitySnapshot(
                    expectedSidecar["cityAfter"]?.takeUnless { it.toString() == "null" }?.jsonObject,
                    world,
                    pending.actorGeneralId,
                    "handled sidecar ordinal=$commandDiagnosticOrdinal after",
                )
                val result = pending.handledResult
                val actualSuccess = result != null && !result.fellBack
                val actualStatus = when {
                    result == null -> "blocked"
                    result.fellBack -> "condition-failed"
                    else -> "success"
                }
                val expectedSuccess = expectedSidecar["success"]!!.jsonPrimitive.content.toBooleanStrict()
                val expectedStatus = expectedSidecar["status"]!!.jsonPrimitive.content
                val expectedLogs = expectedSidecar["actionLogs"]!!.jsonArray.map { raw ->
                    val log = raw.jsonObject
                    log["logType"]!!.jsonPrimitive.content to log["text"]!!.jsonPrimitive.content
                }
                val actualLogs = world.peekLogs()
                    .drop(pending.logOffset)
                    .filter { it.generalId == pending.actorGeneralId && it.scope == "general" }
                    .map { it.category to it.text }
                check(expectedSuccess == actualSuccess) {
                    "handled sidecar ordinal=$commandDiagnosticOrdinal success " +
                        "expected=$expectedSuccess actual=$actualSuccess"
                }
                check(expectedStatus == actualStatus) {
                    "handled sidecar ordinal=$commandDiagnosticOrdinal status " +
                        "expected=$expectedStatus actual=$actualStatus"
                }
                check(expectedLogs == actualLogs) {
                    "handled sidecar ordinal=$commandDiagnosticOrdinal logs " +
                        "expected=$expectedLogs actual=$actualLogs"
                }
            }
            pendingSightseeingPostAction?.takeIf { it.first == pending.actorGeneralId }?.let { expected ->
                val actualInjury = world.getGeneralById(pending.actorGeneralId)?.injury
                assertEquals(expected.second, actualInjury, "sightseeing post-action injury")
                if (pending.actorGeneralId == 153) {
                    println(
                        "KOTLIN_SIGHTSEEING_POST_ACTION" +
                            "|actor=${pending.actorGeneralId}" +
                            "|injury=$actualInjury",
                    )
                }
                pendingSightseeingPostAction = null
            }
            if (!commandDivergencePrinted && expected != null &&
                (
                    expected["phase"]!!.jsonPrimitive.int != pending.phase ||
                        expected["phaseBoundary"]!!.jsonPrimitive.content != actualBoundary ||
                        expected["dueTimestamp"]!!.jsonPrimitive.content != actualDue ||
                        expected["actorGeneralId"]!!.jsonPrimitive.int != pending.actorGeneralId ||
                        expected["commandCode"]!!.jsonPrimitive.content != pending.commandCode ||
                        expected["nationCommandCode"]?.jsonPrimitive?.contentOrNull != pending.nationCommandCode ||
                        expected["nextScheduledTimestamp"]!!.jsonPrimitive.content != actualNext
                    )
            ) {
                val current = world.getGeneralById(pending.actorGeneralId)
                println(
                    "COMMAND_SCHEDULE_DIVERGENCE" +
                        "|ordinal=$commandDiagnosticOrdinal" +
                        "|actor=${pending.actorGeneralId}" +
                        "|due=$actualDue" +
                        "|phpCode=${expected["commandCode"]!!.jsonPrimitive.content}" +
                        "|kotlinCode=${pending.commandCode}" +
                        "|phpNationCode=${expected["nationCommandCode"]?.jsonPrimitive?.contentOrNull}" +
                        "|kotlinNationCode=${pending.nationCommandCode}" +
                        "|injury=${current?.injury}",
                )
                commandDivergencePrinted = true
            }
            println(
                listOf(
                    "KOTLIN_HANDLED_COMMAND",
                    pending.phase,
                    actualBoundary,
                    actualDue,
                    pending.actorGeneralId,
                    pending.commandCode,
                    pending.nationCommandCode ?: "NONE",
                    actualNext,
                ).joinToString("|"),
            )
            commandDiagnosticOrdinal++
            pendingCommandDiagnostic = null
        }
        val traceDomesticActor =
            System.getenv("LONGSIM_TRACE_DOMESTIC_ACTOR")?.toIntOrNull()
        val ai = AiTurnAdapter(
            world = world,
            registry = registry,
            hiddenSeed = hiddenSeed,
            startYear = startYear,
            turnTerm = turnterm,
            pipeline = pipeline,
            pipelineBuilder = pipelineBuilder,
            reservedCommandNameOf = { "che_휴식" },
            rngFactory = { hidden, year, month, generalId ->
                val seed = serializeSeed(hidden, "GeneralAI", year, month, generalId)
                val state = world.getState()
                if (
                    generalId == domesticDiagnosticActor &&
                    state.currentPhase == domesticDiagnosticPhase &&
                    domesticDecisionCount == 0
                ) {
                    val drbg = LiteHashDrbg(seed)
                    DomesticDecisionRecordingRng(drbg) { decision ->
                        val general = checkNotNull(world.getGeneralById(generalId))
                        val city = checkNotNull(world.getCityById(general.cityId))
                        val strength = StatCalc(
                            PerTurnOverlay.toLogicGeneral(general),
                            pipelineBuilder.pipelineFor(general),
                        ).getStatValue("strength")
                        val intel = StatCalc(
                            PerTurnOverlay.toLogicGeneral(general),
                            pipelineBuilder.pipelineFor(general),
                        ).getStatValue("intel")
                        val rates = RatesPromoFamily.calcCityDevelRate(
                            RatesPromoFamily.CityDevelInput(
                                trust = (city.meta["trust"] as? Number)?.toDouble() ?: 0.0,
                                pop = city.population.toDouble(),
                                popMax = maxOf(1.0, city.populationMax.toDouble()),
                                agri = city.agriculture.toDouble(),
                                agriMax = maxOf(1.0, city.agricultureMax.toDouble()),
                                comm = city.commerce.toDouble(),
                                commMax = maxOf(1.0, city.commerceMax.toDouble()),
                                secu = city.security.toDouble(),
                                secuMax = maxOf(1.0, city.securityMax.toDouble()),
                                def = city.defence.toDouble(),
                                defMax = maxOf(1.0, city.defenceMax.toDouble()),
                                wall = city.wall.toDouble(),
                                wallMax = maxOf(1.0, city.wallMax.toDouble()),
                            ),
                            RandUtil(LiteHashDrbg("longsim-domestic-diagnostic-zero-draw")),
                        )
                        assertEquals(
                            expectedDomesticDecision["seedString"]!!.jsonPrimitive.content,
                            seed,
                            "actor$generalId GeneralAI seed",
                        )
                        assertEquals(
                            expectedDomesticDecision["stats"]!!.jsonObject["strength"]!!.jsonPrimitive.double,
                            strength,
                            "actor$generalId effective strength",
                        )
                        assertEquals(
                            expectedDomesticDecision["stats"]!!.jsonObject["intel"]!!.jsonPrimitive.double,
                            intel,
                            "actor$generalId effective intel",
                        )
                        for (key in listOf("def", "wall", "secu")) {
                            assertEquals(
                                expectedDomesticDecision["rates"]!!.jsonObject[key]!!.jsonPrimitive.double,
                                rates.getValue(key).score,
                                "actor$generalId $key rate",
                            )
                        }
                        assertEquals(
                            expectedDomesticCandidates,
                            decision.candidates,
                            "actor$generalId domestic candidates " +
                                "nation=${world.getNationById(general.nationId)} " +
                                "targets=${decision.candidates.mapNotNull { (candidate, _) ->
                                    val args = candidate.removePrefix("{").removeSuffix("}")
                                        .split(", ")
                                        .mapNotNull { part ->
                                            part.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
                                        }
                                        .toMap()
                                    args["destGeneralID"]?.toIntOrNull()?.let(world::getGeneralById)
                                }}",
                        )
                        val expectedBefore = expectedDomesticDecision["cursorBefore"]!!.jsonObject
                        val expectedAfter = expectedDomesticDecision["cursorAfter"]!!.jsonObject
                        val nation = world.getNationById(general.nationId)
                        val nationRice = nation?.rice
                        val chiefLevels = world.listGenerals()
                            .filter { it.nationId == general.nationId && it.officerLevel > 4 }
                            .sortedBy { it.id }
                            .joinToString(",") { "${it.id}:${it.officerLevel}" }
                        assertEquals(expectedBefore["stateIdx"]!!.jsonPrimitive.int.toLong(), decision.cursorBeforeState)
                        assertEquals(
                            expectedBefore["bufferIdx"]!!.jsonPrimitive.int,
                            decision.cursorBeforeBuffer,
                            "actor$generalId pre-pick cursor nationLevel=${nation?.level} " +
                                "nationRice=$nationRice baserice=${GameConst.baserice} " +
                                "nationCommand=${pendingCommandDiagnostic?.nationCommandCode} chiefs=$chiefLevels",
                        )
                        assertEquals(expectedAfter["stateIdx"]!!.jsonPrimitive.int.toLong(), decision.cursorAfterState)
                        assertEquals(expectedAfter["bufferIdx"]!!.jsonPrimitive.int, decision.cursorAfterBuffer)
                        assertEquals(
                            expectedDomesticDecision["drawValue"]!!.jsonPrimitive.double,
                            decision.drawValue,
                            "actor$generalId terminal weighted draw",
                        )
                        assertEquals(
                            expectedDomesticDecision["pickedCommandCode"]!!.jsonPrimitive.content,
                            decision.picked,
                            "actor$generalId domestic pick",
                        )
                        println(
                            listOf(
                                "KOTLIN_DOMESTIC_DECISION",
                                state.currentPhase,
                                generalId,
                                year,
                                month,
                                seed,
                                strength,
                                rates.getValue("def").score,
                                rates.getValue("wall").score,
                                rates.getValue("secu").score,
                                "${decision.cursorBeforeState}:${decision.cursorBeforeBuffer}",
                                "${decision.cursorAfterState}:${decision.cursorAfterBuffer}",
                                decision.drawValue,
                                decision.candidates.sumOf { it.second.coerceAtLeast(0.0) },
                                decision.candidates.joinToString(",") { "${it.first}=${it.second}" },
                                decision.picked,
                            ).joinToString("|"),
                        )
                        domesticDecisionCount++
                    }
                } else if (generalId == traceDomesticActor) {
                    val drbg = LiteHashDrbg(seed)
                    DomesticDecisionRecordingRng(drbg) { decision ->
                        val general = checkNotNull(world.getGeneralById(generalId))
                        val city = checkNotNull(world.getCityById(general.cityId))
                        println(
                            listOf(
                                "KOTLIN_DOMESTIC_TRACE",
                                state.currentPhase,
                                generalId,
                                year,
                                month,
                                seed,
                                "nation=${general.nationId}",
                                "city=${general.cityId}",
                                "injury=${general.injury}",
                                "pop=${city.population}/${city.populationMax}",
                                "agri=${city.agriculture}/${city.agricultureMax}",
                                "comm=${city.commerce}/${city.commerceMax}",
                                "secu=${city.security}/${city.securityMax}",
                                "def=${city.defence}/${city.defenceMax}",
                                "wall=${city.wall}/${city.wallMax}",
                                "${decision.cursorBeforeState}:${decision.cursorBeforeBuffer}",
                                "${decision.cursorAfterState}:${decision.cursorAfterBuffer}",
                                decision.drawValue,
                                decision.candidates.joinToString(",") { "${it.first}=${it.second}" },
                                decision.picked,
                            ).joinToString("|"),
                        )
                    }
                } else {
                    RandUtil(LiteHashDrbg(seed))
                }
            },
        )
        val recorder = longSimReplayRecorder(world)
        val expectedPhaseDrains = manifest["phaseDrains"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val livePhaseActorIds = ArrayList<Int>()
        data class EligibilityRow(
            val nationId: Int,
            val scout: Int,
            val storedGennum: Int,
            val actualGennum: Int,
            val hasLord: Boolean,
        )
        data class PermutationEntry(
            val ordinal: Int,
            val actorGeneralId: Int,
            val year: Int,
            val month: Int,
            val orderedNationIds: List<Int>,
            val eligibilityRows: List<EligibilityRow>,
        )
        val permutationStream = ArrayDeque(
            manifest["randomImgwanPermutations"]!!.jsonArray.map { raw ->
                val entry = raw.jsonObject
                PermutationEntry(
                    ordinal = entry["ordinal"]!!.jsonPrimitive.int,
                    actorGeneralId = entry["actorGeneralId"]!!.jsonPrimitive.int,
                    year = entry["year"]!!.jsonPrimitive.int,
                    month = entry["month"]!!.jsonPrimitive.int,
                    orderedNationIds = entry["orderedNationIds"]!!.jsonArray.map { it.jsonPrimitive.int },
                    eligibilityRows = entry["eligibilityRows"]!!.jsonArray.map { rawRow ->
                        val row = rawRow.jsonObject
                        EligibilityRow(
                            nationId = row["nationId"]!!.jsonPrimitive.int,
                            scout = row["scout"]!!.jsonPrimitive.int,
                            storedGennum = row["storedGennum"]!!.jsonPrimitive.int,
                            actualGennum = row["actualGennum"]!!.jsonPrimitive.int,
                            hasLord = row["hasLord"]!!.jsonPrimitive.content.toBooleanStrict(),
                        )
                    },
                )
            },
        )
        var expectedPermutationOrdinal = 0
        val permutationReplay = RandomImgwanPermutationReplay { actorGeneralId, year, month, candidateNationIds ->
            val entry = permutationStream.removeFirstOrNull()
                ?: error("random-imgwan permutation stream exhausted early")
            assertEquals(expectedPermutationOrdinal++, entry.ordinal, "random-imgwan permutation ordinal")
            assertEquals(actorGeneralId, entry.actorGeneralId, "random-imgwan actor")
            assertEquals(year, entry.year, "random-imgwan year")
            assertEquals(month, entry.month, "random-imgwan month")
            val liveCandidateIds = candidateNationIds.sorted()
            val phpCandidateIds = entry.orderedNationIds.sorted()
            if (liveCandidateIds != phpCandidateIds) {
                val liveRows = world.listNations().sortedBy { it.id }.map { nation ->
                    val actualGennum = world.listGenerals().count { it.nationId == nation.id }
                    val storedGennum =
                        (nation.meta["gennum"] as? Number)?.toInt() ?: actualGennum
                    val scout = (nation.meta["scout"] as? Number)?.toInt() ?: 0
                    val hasLord = world.listGenerals()
                        .any { it.nationId == nation.id && it.officerLevel == 12 }
                    EligibilityRow(nation.id, scout, storedGennum, actualGennum, hasLord)
                }
                println(
                    "RANDOM_IMGWAN_ELIGIBILITY_DIVERGENCE" +
                        "|ordinal=${entry.ordinal}" +
                        "|actor=$actorGeneralId" +
                        "|phpCandidates=$phpCandidateIds" +
                        "|kotlinCandidates=$liveCandidateIds" +
                        "|phpRows=${entry.eligibilityRows}" +
                        "|kotlinRows=$liveRows",
                )
            }
            assertEquals(
                phpCandidateIds,
                liveCandidateIds,
                "random-imgwan live candidate multiset ordinal=${entry.ordinal} actor=$actorGeneralId",
            )
            entry.orderedNationIds
        }
        val expectedSightseeingCandidates =
            listOf(1 to 1.0, 2 to 1.0, 18 to 2.0, 34 to 2.0, 66 to 2.0) +
                listOf(257, 513, 1025, 2049, 4097, 4098, 8193, 12290, 290, 546, 322, 578)
                    .map { it to 1.0 }
        var expectedSightseeingOrdinal = 0
        val sightseeingExternalSelector = sightseeingStream?.let { stream ->
            SightseeingExternalSelector { actorGeneralId, year, month, date, candidates ->
                val entry = stream.removeFirstOrNull()
                    ?: error("sightseeing observer stream exhausted early")
                assertEquals(expectedSightseeingOrdinal++, entry.ordinal, "sightseeing observer ordinal")
                assertEquals(entry.actorGeneralId, actorGeneralId, "sightseeing actor")
                assertEquals(entry.year, year, "sightseeing year")
                assertEquals(entry.month, month, "sightseeing month")
                assertEquals(entry.date, date, "sightseeing date")
                assertEquals(
                    expectedSightseeingCandidates,
                    candidates.map { it.type to it.weight },
                    "sightseeing live candidate table",
                )
                val candidate: SightseeingCandidate = candidates.singleOrNull { it.type == entry.type }
                    ?: error("sightseeing observer type ${entry.type} is absent from live candidates")
                assertEquals(true, entry.text in candidate.texts, "sightseeing text for type ${entry.type}")
                assertEquals((entry.type and 0x1000) != 0, entry.woundedDraw != null, "sightseeing wounded follow-up")
                assertEquals(
                    (entry.type and 0x2000) != 0,
                    entry.heavyWoundedDraw != null,
                    "sightseeing heavy-wounded follow-up",
                )
                pendingSightseeingPostAction = entry.actorGeneralId to entry.postActionInjury
                if (entry.actorGeneralId == 153) {
                    println(
                        "KOTLIN_SIGHTSEEING_CONSUMED" +
                            "|ordinal=${entry.ordinal}" +
                            "|actor=${entry.actorGeneralId}" +
                            "|type=${entry.type}" +
                            "|woundedDraw=${entry.woundedDraw}" +
                            "|heavyWoundedDraw=${entry.heavyWoundedDraw}" +
                            "|expectedPostInjury=${entry.postActionInjury}",
                    )
                }
                SightseeingExternalOutcome(
                    type = entry.type,
                    text = entry.text,
                    woundedDraw = entry.woundedDraw,
                    heavyWoundedDraw = entry.heavyWoundedDraw,
                )
            }
        }
        val handler = ReservedTurnHandler(
            world = world,
            registry = registry,
            hiddenSeed = hiddenSeed,
            startYear = startYear,
            scenario = scenario,
            turnTerm = turnterm,
            recorder = recorder,
            pipelineBuilder = pipelineBuilder,
            aiHook = { generalId, reserved ->
                ai.chooseGeneralTurn(generalId, reserved).also { chosen ->
                    pendingCommandDiagnostic?.takeIf { it.actorGeneralId == generalId }?.commandCode =
                        chosen.actionCode
                }
            },
            randomImgwanPermutationReplay = permutationReplay,
            sightseeingExternalSelector = sightseeingExternalSelector,
        )
        val nationProcessor = ProcessNationCommand(
            world = world,
            recorder = recorder,
            hiddenSeed = hiddenSeed,
            registry = registry,
            startYear = startYear,
            turnTerm = turnterm,
            pipelineBuilder = pipelineBuilder,
        )
        val lifecycle = TurnDaemonLifecycle(
            world = world,
            handler = handler,
            nationProcessor = nationProcessor,
            reservedNationActionOf = { _, _ -> ReservedTurn("휴식", "") },
            chooseNationTurn = { generalId, reserved ->
                val g = world.getGeneralById(generalId)!!
                val raw = world.getNationById(g.nationId)?.meta?.get("turn_last_${g.officerLevel}")
                @Suppress("UNCHECKED_CAST")
                ai.chooseNationTurn(generalId, reserved, LastTurn.fromRaw(raw as? Map<String, Any?>)).also {
                    pendingCommandDiagnostic?.takeIf { pending -> pending.actorGeneralId == generalId }
                        ?.nationCommandCode = it.actionCode
                    ai.drainNationPassDeltas(recorder)
                }
            },
            beginGeneralTurn = { generalId ->
                val dueGeneral = world.getGeneralById(generalId)!!
                if (generalId == 153) {
                    val develCost = (world.getState().currentYear - startYear + 10) * 2
                    val cureThreshold = ai.assemblePolicies(
                        dueGeneral,
                        ai.nationTechOf(dueGeneral.nationId),
                        develCost,
                    ).nation.cureThreshold
                    println(
                        "KOTLIN_ACTOR153_PRE_AI" +
                            "|phase=${world.getState().currentPhase}" +
                            "|injury=${world.getGeneralById(generalId)!!.injury}" +
                            "|cureThreshold=$cureThreshold",
                    )
                }
                ai.beginGeneralTurn(generalId)
            },
            observeGeneralTurnStart = { generalId ->
                flushPendingCommandDiagnostic()
                val dueGeneral = world.getGeneralById(generalId)!!
                val expectedSidecar = handledCommandSidecar?.removeFirstOrNull()
                if (expectedSidecar != null) {
                    check(expectedSidecar["ordinal"]!!.jsonPrimitive.int == commandDiagnosticOrdinal)
                    check(expectedSidecar["phase"]!!.jsonPrimitive.int == world.getState().currentPhase)
                    check(expectedSidecar["actorGeneralId"]!!.jsonPrimitive.int == generalId)
                    assertCitySnapshot(
                        expectedSidecar["cityBefore"]?.takeUnless { it.toString() == "null" }?.jsonObject,
                        world,
                        generalId,
                        "handled sidecar ordinal=$commandDiagnosticOrdinal before",
                    )
                }
                pendingCommandDiagnostic = PendingCommandDiagnostic(
                    phase = world.getState().currentPhase,
                    boundary = diagnosticBoundary,
                    dueTimestamp = dueGeneral.turnTime,
                    actorGeneralId = generalId,
                    sidecar = expectedSidecar,
                    logOffset = world.peekLogs().size,
                )
                livePhaseActorIds.add(generalId)
            },
            observeHandledTurn = { result ->
                pendingCommandDiagnostic
                    ?.takeIf { it.actorGeneralId == result.generalId }
                    ?.handledResult = result
            },
            lifecycleEnvOf = { state, date ->
                val turnTerm = state.tickSeconds / 60
                LifecycleEnv(
                    baselineKillturn = EffectiveGameConst.killturn(turnTerm, npcmode = 0),
                    year = state.currentYear,
                    month = state.currentMonth,
                    turnTerm = turnTerm,
                    isunited = state.meta["isunited"] as? Int ?: 0,
                    turnTimeHm = date,
                )
            },
            pullGeneralTurnOf = { ai.drainGeneralPassDeltas(recorder) },
            reservedActionOf = { _ -> ReservedTurn("휴식", "") },
        )

        val monthlyRecorders = LinkedHashMap<Pair<Int, Int>, AiDrawRecorder>()
        val productionPostUpdate = MonthlyPostUpdateHook(world, recorder, pipeline)
        val monthlyPipeline = MonthlyPipeline(
            monthlyRngFactory = { year, month ->
                val rec = AiDrawRecorder(LiteHashDrbg(MonthScopedRng.monthlySeed(hiddenSeed, year, month)))
                monthlyRecorders[year to month] = rec
                rec
            },
            clock = MonthlyClock { nextTurn, _ ->
                ServerClock.turnDate(nextTurn, startYear, startTime, turnterm).also { date ->
                    world.setCurrentDate(date.year, date.month, date.phase)
                }
            },
            preUpdateMonthly = longSimReplayPreUpdate(world, recorder),
            checkStatistic = CheckStatistic { },
            postUpdateMonthly = PostUpdateMonthly { monthlyRng ->
                if (rowDiagnosticEnabled) {
                    throw AssertionError(
                        powerRowDiagnostic(expectedFirstPointState, world, expectedHandledCommands),
                    )
                }
                if (powerDiagnostic) {
                    val draw = MonthScopedRng.forMonth(hiddenSeed, startYear, 1).nextRange(0.95, 1.05)
                    val expected = capturedPowerBreakdown(expectedFirstPointState, nationId = 1, draw = draw)
                    val actual = livePowerBreakdown(world, recorder, nationId = 1, draw = draw)
                    println("LONGSIM_POWER_EXPECTED=$expected")
                    println("LONGSIM_POWER_ACTUAL=$actual")
                    assertEquals(expected, actual, "month-1 nation 1 Q3 power components")
                }
                productionPostUpdate.run(monthlyRng)
            },
        )

        val extendedGeneral =
            baseline.state["game_env"]!!.jsonObject["extended_general"]?.jsonPrimitive?.intOrNull
                ?.let { it != 0 } ?: false
        val eventStore = longSimReplayEventStore(scenario, startYear, extendedGeneral)
        val eventDispatcher = EventDispatcher(eventStore, WorldActions.register(EventActionFactory()))
        val worldContextFactory = WorldEventContextFactory.create(
            world = world,
            recorder = recorder,
            pipeline = pipeline,
            hiddenSeed = hiddenSeed,
            startYear = startYear,
            eventStore = eventStore,
        )

        fun applyRecorderKvToWorld() {
            for ((kvKey, value) in recorder.kvDirty()) {
                if (kvKey.table != "nation_env") continue
                val nationId = kvKey.namespace.toIntOrNull() ?: continue
                val nation = world.getNationById(nationId) ?: continue
                world.applyNationDirtyFree(nation.copy(meta = nation.meta + (kvKey.key to value)))
            }
        }

        var generalDrainCohort = lifecycle.snapshotGeneralDrainCohort()
        val driver = TurnDaemonLifecycle.MonthBoundaryDriver(
            drain = { upto ->
                diagnosticBoundary = upto
                lifecycle.runTick(upto, generalDrainCohort).also { flushPendingCommandDiagnostic() }
            },
            runMonth = { nextTurn ->
                advanceLongSimReplayMonthBoundary(world, nextTurn)
                val state = world.getState()
                monthlyPipeline.runMonth(
                    nextTurn = nextTurn,
                    startYear = startYear,
                    startTime = startTime,
                    turnTerm = turnterm,
                    oldYear = state.currentYear,
                    oldMonth = state.currentMonth,
                    oldPhase = state.currentPhase,
                    dispatcher = { target, env ->
                        val supplier = {
                            mutableMapOf<String, Any?>(
                                "year" to env.year,
                                "month" to env.month,
                                "phase" to env.phase,
                                "currentEventID" to env.currentEventID,
                            )
                        }
                        eventDispatcher.run(target, contextFactory = worldContextFactory, envSupplier = supplier)
                    },
                )
                applyRecorderKvToWorld()
                recorder.clear()
            },
            runMonthWhen = { nextTurn ->
                ServerClock.turnDate(nextTurn, startYear, startTime, turnterm).phase == 1
            },
            advanceNonMonthlyBoundary = { nextTurn ->
                val date = ServerClock.turnDate(nextTurn, startYear, startTime, turnterm)
                world.setCurrentDate(date.year, date.month, date.phase)
            },
        )

        var replayedTurns = 0
        var replayTurnTime = ServerClock.cutTurn(world.getState().lastTurnTime, turnterm)
        for (point in manifest["points"]!!.jsonArray.map { it.jsonObject }) {
            val gameMonths = point["gameMonths"]!!.jsonPrimitive.int
            val targetTurns = gameMonths * 3
            while (replayedTurns < targetTurns) {
                val oldDate = world.getState()
                val targetNow = ServerClock.addTurn(replayTurnTime, turnterm)
                val isUnited = oldDate.meta["isunited"] as? Int ?: 0
                expectedPhaseDrains.getOrNull(replayedTurns)?.let { expectedPhase ->
                    val liveDueActorIds = world.listGenerals()
                        .filter { it.turnTime.isBefore(targetNow) }
                        .sortedWith(compareBy({ it.turnTime }, { it.id }))
                        .map { it.id }
                    assertEquals(
                        expectedPhase["actorGeneralIds"]!!.jsonArray.map { it.jsonPrimitive.int },
                        liveDueActorIds,
                        "pre-drain due actor order at turn=${replayedTurns + 1}",
                    )
                }
                livePhaseActorIds.clear()
                generalDrainCohort = lifecycle.snapshotGeneralDrainCohort()
                driver.run(replayTurnTime, targetNow, turnterm, isUnited)
                killturnOracle?.observeWorld(world)
                assignLongSimReplayDiplomacyIdentity(world)
                syncLongSimReplayNationEnv(world)
                expectedPhaseDrains.getOrNull(replayedTurns)?.let { expectedPhase ->
                    assertEquals(
                        expectedPhase["year"]!!.jsonPrimitive.int,
                        oldDate.currentYear,
                        "phase drain year at turn=${replayedTurns + 1}",
                    )
                    assertEquals(
                        expectedPhase["month"]!!.jsonPrimitive.int,
                        oldDate.currentMonth,
                        "phase drain month at turn=${replayedTurns + 1}",
                    )
                    assertEquals(
                        expectedPhase["phase"]!!.jsonPrimitive.int,
                        oldDate.currentPhase,
                        "phase drain phase at turn=${replayedTurns + 1}",
                    )
                    assertEquals(
                        expectedPhase["actorGeneralIds"]!!.jsonArray.map { it.jsonPrimitive.int },
                        livePhaseActorIds,
                        "phase drain actor order at turn=${replayedTurns + 1}",
                    )
                }
                world.setLastTurnTime(targetNow)
                replayTurnTime = targetNow
                replayedTurns++
            }
            val newDate = GameDate(
                world.getState().currentYear,
                world.getState().currentMonth,
                world.getState().currentPhase,
            )

            // Assert the monthly seed + draw count for the LAST crossed month.
            val oldYear = if (newDate.month == 1) newDate.year - 1 else newDate.year
            val oldMonth = if (newDate.month == 1) 12 else newDate.month - 1
            val expectedCapture = loadJson("golden/longsim/${point["file"]!!.jsonPrimitive.content}")
            val rec = monthlyRecorders[oldYear to oldMonth]
            val expectedSeed = MonthScopedRng.monthlySeed(hiddenSeed, oldYear, oldMonth)
            assertEquals(
                expectedCapture["monthlySeedString"]?.jsonPrimitive?.contentOrNull,
                expectedSeed,
                "monthly seed mismatch at gameMonths=$gameMonths",
            )

            val expectedState = expectedCapture["state"]!!.jsonObject
            val liveState = captureLongSimReplayState(world, expectedState)
            val mismatch = LongSimStateCapture.compareStates(expectedState, liveState)
            if (mismatch != null) {
                val mismatchNationId = expectedState["nation"]!!.jsonArray.first().jsonObject["nation"]!!.jsonPrimitive.int
                fun activeGeneralIds(state: JsonObject): Set<Int> = state["general"]!!.jsonArray
                    .map { it.jsonObject }
                    .filter {
                        it["nation"]!!.jsonPrimitive.int == mismatchNationId &&
                            it["npc"]!!.jsonPrimitive.int != 5
                    }
                    .mapTo(linkedSetOf()) { it["no"]!!.jsonPrimitive.int }
                val expectedActive = activeGeneralIds(expectedState)
                val actualActive = activeGeneralIds(liveState)
                val nations = world.listNations().sortedBy { it.id }
                    .joinToString { "${it.id}:${it.name}:L${it.level}" }
                val foundingLogs = world.peekLogs()
                    .map { it.text }
                    .filter { "거병" in it || "건국" in it }
                    .joinToString(" | ")
                throw AssertionError(
                    "State divergence at gameMonths=$gameMonths: $mismatch; " +
                        "activeGeneralExpectedOnly=${expectedActive - actualActive}; " +
                        "activeGeneralActualOnly=${actualActive - expectedActive}; " +
                        "nations=[$nations]; foundingLogs=[$foundingLogs]",
                )
            }

            val expectedDraws = expectedCapture["monthlyRngDraws"]?.jsonPrimitive?.intOrNull
            if (rec != null && expectedDraws != null) {
                assertEquals(
                    expectedDraws.toLong(),
                    rec.stateIdx(),
                    "monthly rng draws at gameMonths=$gameMonths",
                )
            }
            killturnOracle?.assertComplete()
        }
        assertEquals(0, permutationStream.size, "random-imgwan permutation stream must be fully exhausted")
        sightseeingStream?.let {
            assertEquals(0, it.size, "sightseeing observer stream must be fully exhausted")
        }
        handledCommandSidecar?.let {
            assertEquals(0, it.size, "handled-command sidecar stream must be fully exhausted")
        }
        diplomacyIdentityOracle?.assertComplete()
    }
}
