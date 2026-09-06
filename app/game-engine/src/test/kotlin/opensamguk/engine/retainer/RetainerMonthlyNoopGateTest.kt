package opensamguk.engine.retainer

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.engine.run.MonthlyPostUpdateHook
import opensamguk.engine.turn.Bugok
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.City
import opensamguk.engine.turn.DirtyState
import opensamguk.engine.turn.GeneralRole
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.Retainer
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.entity.AuctionEntity
import opensamguk.infra.read.AuctionRepository
import opensamguk.logic.retainer.RetainerRules
import opensamguk.logic.stats.GeneralActionPipeline
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * spec v3 §8 **적색 프로브**: 같은 fixture 로 world+recorder 를 두 벌 새로 만들어 [MonthlyPostUpdateHook] 을
 * 각각 한 번 돌린다 — 한 벌은 `retainerMonthly = null`, 한 벌은 배선. 행 0 이면 `consumeDirtyState()`·recorder
 * 패치·로그·world_state meta 가 deep-equal 이어야 하고(골든 불변의 증거), 부곡 1행이 있으면 **달라져야** 한다
 * (게이트가 경로를 실제로 보는 증거). Q15/Q16 이 RNG·벽시계를 읽으므로 ScriptedRng + 빈 경매 repo 로 고정한다.
 */
class RetainerMonthlyNoopGateTest {

    private val t0 = Instant.parse("0200-04-01T00:00:00Z")

    private class ScriptedRng : RandUtil(LiteHashDrbg("retainer-noop-gate")) {
        override fun nextRange(min: Double, max: Double): Double = 1.0
        override fun nextBool(prob: Double): Boolean = false
        override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int = minInclusive
        override fun <T> shuffle(srcArray: List<T>): List<T> = srcArray
    }

    private data class Outcome(
        val dirty: DirtyState,
        val patches: List<Pair<Int, Map<String, Any?>>>,
        val logs: List<LogEntryDraft>,
        val meta: Map<String, Any?>,
    )

    private fun stateOf() = TurnWorldState(
        id = 1, currentYear = 200, currentMonth = 4, tickSeconds = 3600, lastTurnTime = t0,
        meta = mapOf("startYear" to 184, "map" to "miniche", "turnterm" to 60, "tnmt_trig" to false),
    )

    private fun general(id: Int, gold: Int = 1_000, rice: Int = 1_000) = TurnGeneral(
        id = id, name = "장수$id", nationId = 1, cityId = 1, troopId = 0,
        stats = GeneralStats(leadership = 80, strength = 70, intelligence = 60, politics = 50, charm = 50),
        experience = 0, dedication = 0, officerLevel = 12, gold = gold, rice = rice, crew = 500, crewTypeId = 1100,
        turnTime = t0, role = GeneralRole(),
    )

    private fun world(retainers: List<Retainer> = emptyList(), bugoks: List<Bugok> = emptyList()) = InMemoryTurnWorld(
        WorldSnapshot(
            state = stateOf(),
            generals = listOf(general(70)),
            cities = listOf(City(id = 1, name = "낙양", nationId = 1, level = 5, frontState = 0, supplyState = 1)),
            nations = listOf(Nation(id = 1, name = "촉", color = "#000", level = 3, gold = 10_000, rice = 10_000, tech = 100.0, meta = mapOf("gennum" to 1))),
            retainers = retainers,
            bugoks = bugoks,
            worldId = opensamguk.common.world.WorldId(1),
        ),
    )

    private fun auctionRepo(): AuctionRepository = Proxy.newProxyInstance(
        AuctionRepository::class.java.classLoader, arrayOf(AuctionRepository::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            java.util.List::class.java -> emptyList<AuctionEntity>()
            java.lang.Boolean.TYPE -> false
            else -> null
        }
    } as AuctionRepository

    private fun runOnce(wired: Boolean, retainers: List<Retainer> = emptyList(), bugoks: List<Bugok> = emptyList()): Outcome {
        val world = world(retainers, bugoks)
        val recorder = ChangeRecorder()
        MonthlyPostUpdateHook(
            world, recorder, GeneralActionPipeline(), auctionRepository = auctionRepo(),
            retainerMonthly = if (wired) RetainerMonthlyService() else null,
        ).run(ScriptedRng())
        val dirty = world.consumeDirtyState()
        return Outcome(
            dirty = dirty.copy(logs = emptyList()),
            patches = recorder.generalPatches().map { it.id to it.columns },
            logs = dirty.logs,
            meta = world.getState().meta,
        )
    }

    @Test
    fun `row-zero world is byte-identical with and without the retainer hook`() {
        val off = runOnce(wired = false)
        val on = runOnce(wired = true)
        assertEquals(off.dirty, on.dirty)
        assertEquals(off.patches, on.patches)
        assertEquals(off.logs, on.logs)
        assertEquals(off.meta, on.meta)
        assertTrue("maxRetainerId" !in on.meta && "maxBugokId" !in on.meta, "행 0 세계의 meta 에 고수위 키가 생기면 안 된다")
    }

    @Test
    fun `one bugok row makes the wired run differ — the gate sees the path`() {
        val bugok = Bugok(id = 1, masterGeneralId = 70, name = "부곡 1", troops = 300, crewTypeId = 1100, training = 50, morale = 50, fatigue = 10, provisions = 0)
        val off = runOnce(wired = false, bugoks = listOf(bugok))
        val on = runOnce(wired = true, bugoks = listOf(bugok))
        assertNotEquals(off.dirty, on.dirty)
        assertEquals(1, on.dirty.bugoks.size)
        val settled = on.dirty.bugoks.single()
        assertEquals(50 - RetainerRules.MORALE_LOSS_UNPAID, settled.morale)
        assertEquals(10 - RetainerRules.FATIGUE_REST, settled.fatigue)
        assertTrue(on.patches.any { (id, cols) -> id == 70 && "gold" in cols })
        assertTrue(off.patches.none { (id, cols) -> id == 70 && "gold" in cols })
    }

    @Test
    fun `loyalty zero retainer leaves with a general action log`() {
        val r = Retainer(id = 1, masterGeneralId = 70, origin = RetainerRules.ORIGIN_RECRUITED, name = "홍길동", relation = "guest", loyalty = 0)
        val on = runOnce(wired = true, retainers = listOf(r))
        assertEquals(listOf(1), on.dirty.deletedRetainers)
        val log = on.logs.single { it.scope == "general" && it.category == "action" && it.generalId == 70 }
        assertEquals("<Y>홍길동</>이 떠났습니다.", log.text)
    }
}
