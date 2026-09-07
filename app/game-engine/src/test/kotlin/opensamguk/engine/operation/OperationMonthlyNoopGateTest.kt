package opensamguk.engine.operation

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.engine.retainer.RetainerMonthlyService
import opensamguk.engine.run.MonthlyPostUpdateHook
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.City
import opensamguk.engine.turn.DirtyState
import opensamguk.engine.turn.GeneralRole
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.Operation
import opensamguk.engine.turn.OperationUnit
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.entity.AuctionEntity
import opensamguk.infra.read.AuctionRepository
import opensamguk.logic.operation.OperationRules
import opensamguk.logic.stats.GeneralActionPipeline
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** spec v4.1 §8 적색 프로브 — 행 0 이면 훅 유무와 산출물(dirty·패치·로그·meta) 동일, 작전 1행이면 상이. 훅 순서(부곡 → 작전)도 핀. */
class OperationMonthlyNoopGateTest {

    private val t0 = Instant.parse("0200-04-01T00:00:00Z")

    private class ScriptedRng : RandUtil(LiteHashDrbg("operation-noop-gate")) {
        override fun nextRange(min: Double, max: Double): Double = 1.0
        override fun nextBool(prob: Double): Boolean = false
        override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int = minInclusive
        override fun <T> shuffle(srcArray: List<T>): List<T> = srcArray
    }

    private data class Outcome(val dirty: DirtyState, val patches: List<Pair<Int, Map<String, Any?>>>, val logs: List<LogEntryDraft>, val meta: Map<String, Any?>)

    private fun world(ops: List<Operation> = emptyList(), units: List<OperationUnit> = emptyList()) = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 4, tickSeconds = 3600, lastTurnTime = t0, meta = mapOf("startYear" to 184, "map" to "miniche", "turnterm" to 60, "tnmt_trig" to false)),
            generals = listOf(TurnGeneral(id = 70, name = "장수70", nationId = 1, cityId = 1, troopId = 0, stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0, officerLevel = 12, gold = 1000, rice = 1000, atmos = 50, turnTime = t0, role = GeneralRole())),
            cities = listOf(City(id = 1, name = "낙양", nationId = 1, level = 5, frontState = 0, supplyState = 1), City(id = 2, name = "허창", nationId = 2, level = 5, frontState = 0, supplyState = 1)),
            nations = listOf(Nation(id = 1, name = "촉", color = "#000", level = 3, gold = 10_000, rice = 10_000, tech = 100.0, meta = mapOf("gennum" to 1)), Nation(id = 2, name = "위", color = "#00f", level = 3, gold = 10_000, rice = 10_000, tech = 100.0, meta = mapOf("gennum" to 0))),
            operations = ops, operationUnits = units,
            worldId = opensamguk.common.world.WorldId(1),
        ),
    )

    private fun auctionRepo(): AuctionRepository = Proxy.newProxyInstance(AuctionRepository::class.java.classLoader, arrayOf(AuctionRepository::class.java)) { _, method, _ ->
        when (method.returnType) { java.util.List::class.java -> emptyList<AuctionEntity>(); java.lang.Boolean.TYPE -> false; else -> null }
    } as AuctionRepository

    private fun runOnce(wired: Boolean, ops: List<Operation> = emptyList(), units: List<OperationUnit> = emptyList()): Outcome {
        val world = world(ops, units); val recorder = ChangeRecorder()
        MonthlyPostUpdateHook(
            world, recorder, GeneralActionPipeline(), auctionRepository = auctionRepo(),
            retainerMonthly = RetainerMonthlyService(), operationMonthly = if (wired) OperationMonthlyService() else null,
        ).run(ScriptedRng())
        val dirty = world.consumeDirtyState()
        return Outcome(dirty.copy(logs = emptyList()), recorder.generalPatches().map { it.id to it.columns }, dirty.logs, world.getState().meta)
    }

    @Test
    fun `row-zero world is byte-identical with and without the operation hook`() {
        val off = runOnce(false); val on = runOnce(true)
        assertEquals(off.dirty, on.dirty); assertEquals(off.patches, on.patches); assertEquals(off.logs, on.logs); assertEquals(off.meta, on.meta)
        assertTrue("maxOperationId" !in on.meta && "maxOperationUnitId" !in on.meta)
    }

    @Test
    fun `one overdue operation makes the wired run differ`() {
        val op = Operation(id = 1, nationId = 1, kind = OperationRules.KIND_CAPTURE_CITY, targetCityId = 2, title = "허창 공략", declaredByGeneralId = 70,
            declaredYear = 200, declaredMonth = 2, declaredPhase = 1, deadlineYear = 200, deadlineMonth = 4, deadlinePhase = 1, status = OperationRules.STATUS_ACTIVE)
        val unit = OperationUnit(id = 1, operationId = 1, generalId = 70, role = "main", joinedCityId = 1, joinedYear = 200, joinedMonth = 2, joinedPhase = 1)
        val off = runOnce(false, listOf(op), listOf(unit)); val on = runOnce(true, listOf(op), listOf(unit))
        assertNotEquals(off.dirty, on.dirty)
        assertEquals(OperationRules.STATUS_FAILED, on.dirty.operations.single().status)
        assertTrue(on.patches.any { (id, cols) -> id == 70 && "atmos" in cols })
        assertTrue(on.logs.any { it.scope == "nation" && it.category == "history" && it.text.contains("기한") })
    }
}
