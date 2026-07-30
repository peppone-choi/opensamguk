package opensamguk.logic.event

import opensamguk.logic.betting.BettingInfo
import opensamguk.logic.betting.BettingItem
import opensamguk.logic.betting.GeneralForBetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenNationBettingActionTest {

    @Test
    fun `open matches PHP candidate ordering master row trigger bonus and notifications`() {
        val world = BettingWorld()

        OpenNationBettingAction(nationCnt = 1, bonusPoint = 2_000).run(world)

        val info = world.saved.getValue(1)
        assertEquals("천통국 예상", info.name)
        assertEquals(true, info.reqInheritancePoint)
        assertEquals(2_402, info.openYearMonth)
        assertEquals(2_426, info.closeYearMonth)
        assertEquals(listOf(22, 11, 33), info.candidates.values.map { it.aux?.get("nation") })
        assertEquals(listOf(1 to 1), world.scheduled)
        assertEquals(listOf(1 to 2_000), world.bonuses)
        assertEquals(
            listOf(
                "<B><b>【내기】</b></>천하통일 후보를 점치는 <C>내기</>가 진행중입니다! 호사가의 참여를 기다립니다!",
            ),
            world.history,
        )
        assertEquals(listOf("천통국"), world.notifications)
    }

    @Test
    fun `scheduled finish closes the open betting and records the surviving nation`() {
        val world = BettingWorld()
        OpenNationBettingAction(nationCnt = 1).run(world)
        world.alive = listOf(22)

        FinishNationBettingAction(1).run(world)

        val info = world.saved.getValue(1)
        assertTrue(info.finished)
        assertEquals(listOf(0), info.winner)
        assertEquals(
            "<B><b>【내기】</b></> 200년 3월에 열렸던 천통국 예상 내기의 결과가 나왔습니다!",
            world.history.last(),
        )
    }

    @Test
    fun `invalid constructor arguments keep the PHP failure boundary`() {
        assertFalse(runCatching { OpenNationBettingAction(nationCnt = 0) }.isSuccess)
        assertFalse(runCatching { OpenNationBettingAction(bonusPoint = -1) }.isSuccess)
    }

    private class BettingWorld :
        OpenNationBettingContext,
        FinishNationBettingContext {

        override val env: Map<String, Any?> = emptyMap()
        val saved = linkedMapOf<Int, BettingInfo>()
        val scheduled = mutableListOf<Pair<Int, Int>>()
        val bonuses = mutableListOf<Pair<Int, Int>>()
        val history = mutableListOf<String>()
        val notifications = mutableListOf<String>()
        var alive = listOf(11, 22, 33)

        override fun year(): Int = 200
        override fun month(): Int = 3

        override fun nationBettingCandidates(): List<NationBettingCandidate> =
            listOf(
                candidate(11, "촉", 500),
                candidate(22, "위", 900),
                candidate(33, "오", 300),
            )

        override fun nextBettingId(): Int = 1

        override fun saveBettingInfo(info: BettingInfo) {
            saved[info.id] = info
        }

        override fun scheduleNationBettingFinish(bettingId: Int, nationCnt: Int) {
            scheduled += bettingId to nationCnt
        }

        override fun placeNationBettingBonus(bettingId: Int, amount: Int) {
            bonuses += bettingId to amount
        }

        override fun pushGlobalHistoryLog(msg: String, type: Int) {
            history += msg
        }

        override fun notifyNationBettingOpened(name: String) {
            notifications += name
        }

        override fun loadBettingInfo(bettingId: Int): BettingInfo? = saved[bettingId]

        override fun aliveNationIds(): List<Int> = alive

        override fun loadBettingItems(bettingId: Int): List<BettingItem> = emptyList()

        override fun generalsById(ids: List<Int>): Map<Int, GeneralForBetting> = emptyMap()

        override fun addGeneralGold(generalId: Int, amount: Int) = Unit

        override fun increaseRankData(generalId: Int, type: String, amount: Double) = Unit

        override fun getRankVar(generalId: Int, type: String, default: Int): Int = default

        override fun increaseInheritancePointRaw(userId: Int, amount: Double): Double = amount

        override fun pushUserLogs(userId: Int, lines: List<String>, type: String) = Unit

        override fun pushGeneralActionLog(generalId: Int, msg: String) = Unit

        private fun candidate(id: Int, name: String, power: Int) =
            NationBettingCandidate(
                nationId = id,
                name = name,
                power = power,
                generalCount = id,
                cityCount = 1,
                aux = linkedMapOf("nation" to id),
            )
    }
}
