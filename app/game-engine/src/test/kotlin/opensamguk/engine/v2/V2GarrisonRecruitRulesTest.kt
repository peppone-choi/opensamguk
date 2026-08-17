package opensamguk.engine.v2

import opensamguk.common.wire.CityGarrisonRecruit
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import org.mockito.Mockito
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class V2GarrisonRecruitRulesTest {

    // ── recruitDecision — 순수 함수 ──────────────────────────────────────────────────────────

    @Test
    fun `100명 미만은 deny`() {
        val d = recruitDecision(amount = 99, leadership = 100, cityPopulation = 1_000_000, cityTrust = 80.0, ledgerGold = 1_000_000)
        assertEquals(V2RecruitDecision.Denied("최소 100명부터 보충할 수 있습니다."), d)
    }

    @Test
    fun `정확히 100명은 통과`() {
        val d = recruitDecision(amount = 100, leadership = 100, cityPopulation = 1_000_000, cityTrust = 80.0, ledgerGold = 1_000_000)
        assertIs<V2RecruitDecision.Applied>(d)
    }

    @Test
    fun `통솔 100배 정확히 경계는 통과`() {
        val d = recruitDecision(amount = 5000, leadership = 50, cityPopulation = 1_000_000, cityTrust = 80.0, ledgerGold = 1_000_000)
        assertIs<V2RecruitDecision.Applied>(d)
    }

    @Test
    fun `통솔 100배 초과는 deny`() {
        val d = recruitDecision(amount = 5001, leadership = 50, cityPopulation = 1_000_000, cityTrust = 80.0, ledgerGold = 1_000_000)
        assertEquals(V2RecruitDecision.Denied("통솔로 보충할 수 있는 한도를 넘었습니다."), d)
    }

    @Test
    fun `인구가 딱 최소치로 남으면 통과`() {
        // population - amount == minAvailableRecruitPop(30000)
        val d = recruitDecision(amount = 100, leadership = 100, cityPopulation = 30_100, cityTrust = 80.0, ledgerGold = 1_000_000)
        assertIs<V2RecruitDecision.Applied>(d)
    }

    @Test
    fun `인구가 최소치보다 1 부족하면 deny`() {
        val d = recruitDecision(amount = 100, leadership = 100, cityPopulation = 30_099, cityTrust = 80.0, ledgerGold = 1_000_000)
        assertEquals(V2RecruitDecision.Denied("주민이 부족합니다."), d)
    }

    @Test
    fun `금이 부족하면 deny`() {
        // amount=100 → goldCost = round(100*0.09) = 9
        val d = recruitDecision(amount = 100, leadership = 100, cityPopulation = 1_000_000, cityTrust = 80.0, ledgerGold = 8)
        assertEquals(V2RecruitDecision.Denied("도시의 금이 부족합니다."), d)
    }

    @Test
    fun `금이 정확히 비용과 같으면 통과`() {
        val d = recruitDecision(amount = 100, leadership = 100, cityPopulation = 1_000_000, cityTrust = 80.0, ledgerGold = 9)
        assertIs<V2RecruitDecision.Applied>(d)
        assertEquals(9L, (d as V2RecruitDecision.Applied).goldCost)
    }

    @Test
    fun `비용 반올림은 PhpRound half-away-from-zero`() {
        // 105 * 0.09 = 9.45 -> phpRound = 9
        val d = recruitDecision(amount = 105, leadership = 100, cityPopulation = 1_000_000, cityTrust = 80.0, ledgerGold = 1_000_000)
        assertIs<V2RecruitDecision.Applied>(d)
        assertEquals(9L, (d as V2RecruitDecision.Applied).goldCost)
    }

    @Test
    fun `인구·치안 차감값`() {
        val d = recruitDecision(amount = 1000, leadership = 100, cityPopulation = 100_000, cityTrust = 80.0, ledgerGold = 1_000_000)
        assertIs<V2RecruitDecision.Applied>(d)
        d as V2RecruitDecision.Applied
        assertEquals(99_000, d.popAfter)
        // trust = 80 - (1000/100000)*100 = 80 - 1 = 79
        assertEquals(79.0, d.trustAfter, 1e-9)
    }

    @Test
    fun `치안이 0 아래로 내려가지 않는다`() {
        val d = recruitDecision(amount = 90_000, leadership = 1000, cityPopulation = 200_000, cityTrust = 1.0, ledgerGold = 1_000_000)
        assertIs<V2RecruitDecision.Applied>(d)
        assertEquals(0.0, (d as V2RecruitDecision.Applied).trustAfter)
    }

    @Test
    fun `결정성 - 같은 입력 같은 출력`() {
        val a = recruitDecision(amount = 500, leadership = 100, cityPopulation = 500_000, cityTrust = 60.0, ledgerGold = 1_000)
        val b = recruitDecision(amount = 500, leadership = 100, cityPopulation = 500_000, cityTrust = 60.0, ledgerGold = 1_000)
        assertEquals(a, b)
    }

    // ── handle — 소속 검사 deny 3종 ──────────────────────────────────────────────────────────

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private lateinit var lastWorld: InMemoryTurnWorld
    private lateinit var lastRecorder: ChangeRecorder
    private lateinit var lastLedger: V2CityLedgerStore

    private fun handler(cities: List<opensamguk.engine.turn.City>): V2GarrisonRecruitHandler {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0),
                generals = listOf(
                    TurnGeneral(
                        id = 10, name = "유비", nationId = 1, cityId = 5, troopId = 0,
                        stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0,
                        officerLevel = 12, gold = 1000, turnTime = t0,
                    ),
                ),
                nations = listOf(Nation(id = 1, name = "촉", color = "#0f0", gold = 1000)),
                cities = cities,
                worldId = opensamguk.common.world.WorldId(1),
            ),
        )
        val ledger = V2CityLedgerStore(Mockito.mock(NamedParameterJdbcTemplate::class.java))
        val recorder = ChangeRecorder()
        lastWorld = world
        lastRecorder = recorder
        lastLedger = ledger
        return V2GarrisonRecruitHandler(world, recorder, ledger)
    }

    private fun city(id: Int, nationId: Int) =
        opensamguk.engine.turn.City(id = id, name = "성$id", nationId = nationId, level = 1)

    @Test
    fun `장수의 현재 도시가 아니면 deny`() {
        val cities = listOf(city(5, 1), city(6, 1))
        val result = handler(cities).handle(CityGarrisonRecruit(generalId = 10, cityId = 6, amount = 100))
        assertFalse(result.ok)
        assertTrue((result as opensamguk.common.wire.CommandLifecycleResult).reason!!.contains("다른 도시"))
    }

    @Test
    fun `무소속 도시면 deny`() {
        val cities = listOf(city(5, 0))
        val result = handler(cities).handle(CityGarrisonRecruit(generalId = 10, cityId = 5, amount = 100))
        assertFalse(result.ok)
        assertTrue((result as opensamguk.common.wire.CommandLifecycleResult).reason!!.contains("자국 도시가 아닙니다"))
    }

    /**
     * 적용 경로 — 원장 델타 + 도시 델타가 **둘 다** recorder 에 실리고, 월드는 dirty 를 스스로 세우지
     * 않는다(`applyCityDirtyFree`). ChangeRecorder 가 단일 dirty 원천이라는 불변식이 여기서 깨지면
     * flush 가 조용히 갈린다(설계 Risk #4).
     */
    @Test
    fun `적용되면 원장·도시 델타가 함께 기록되고 월드 dirty 는 recorder 만 세운다`() {
        val cities = listOf(
            opensamguk.engine.turn.City(
                id = 5, name = "성5", nationId = 1, level = 1,
                population = 100_000, meta = mapOf("trust" to 80.0),
            ),
        )
        val h = handler(cities)
        lastLedger.adjust(lastWorld.worldId, ChangeRecorder(), cityId = 5, goldDelta = 10_000)

        val result = h.handle(CityGarrisonRecruit(generalId = 10, cityId = 5, amount = 1000))
        assertTrue(result.ok, (result as? opensamguk.common.wire.CommandLifecycleResult)?.reason ?: "")

        // 원장: 금 10000 - round(1000*0.09)=90 → 9910, 도시병사 0 + 1000.
        val entry = lastLedger.entry(lastWorld.worldId, 5)
        assertEquals(9_910L, entry.gold)
        assertEquals(1000, entry.garrison)
        val upsert = lastRecorder.cityLedgerV2Upserts().single()
        assertEquals(9_910L, upsert.columns["gold"])
        assertEquals(1000, upsert.columns["garrison"])

        // 도시: 인구 99000, 치안 79 — 컬럼과 meta 가 같은 값이어야 한다.
        val patch = lastRecorder.cityPatches().single { it.id == 5 }
        assertEquals(99_000, patch.columns["population"])
        assertEquals(79.0, patch.columns["trust"])
        assertEquals(79.0, patch.meta["trust"])
        val city = lastWorld.getCityById(5)!!
        assertEquals(99_000, city.population)
        assertEquals(79.0, city.meta["trust"])
        assertEquals(emptyList(), lastWorld.consumeDirtyState().cities)
    }

    @Test
    fun `장수를 찾을 수 없으면 deny`() {
        val cities = listOf(city(5, 1))
        val result = handler(cities).handle(CityGarrisonRecruit(generalId = 999, cityId = 5, amount = 100))
        assertFalse(result.ok)
        assertTrue((result as opensamguk.common.wire.CommandLifecycleResult).reason!!.contains("장수를 찾을 수 없습니다"))
    }
}
