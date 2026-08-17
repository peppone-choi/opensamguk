package opensamguk.engine.v2

import opensamguk.common.wire.CityGarrisonRecruit
import opensamguk.common.wire.CommandLifecycleResult
import opensamguk.engine.run.TurnDaemonCommandDispatcher
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * OPENSAM-153 (v2 R4) — dispatcher binding test for `CityGarrisonRecruit`. Domain rules are NOT covered
 * here (owned by the domain part of OPENSAM-153) — this only proves the wiring never silently drops the
 * command when the v2 city ledger bean is absent (the FE result-poll would hang PENDING forever on a
 * null dispatch result).
 */
class V2GarrisonRecruitDispatchTest {

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun world(): InMemoryTurnWorld = InMemoryTurnWorld(
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
            worldId = opensamguk.common.world.WorldId(1),
        ),
    )

    private inline fun <reified T> noopRepo(): T = java.lang.reflect.Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            java.util.List::class.java -> emptyList<Any>()
            java.lang.Boolean.TYPE -> false
            else -> null
        }
    } as T

    @Test
    fun `v2 원장이 없으면 null이 아니라 ok=false 결과를 돌려준다 - 유실 방지`() {
        val d = TurnDaemonCommandDispatcher(
            world(), ChangeRecorder(),
            noopRepo<opensamguk.infra.read.AuctionRepository>(),
            noopRepo<opensamguk.infra.read.AuctionBidRepository>(),
            noopRepo<opensamguk.infra.read.BoardPostRepository>(),
            v2CityLedger = null,
        )
        val result = d.dispatch(CityGarrisonRecruit(requestId = "req-1", generalId = 10, cityId = 5, amount = 100))
        val lifecycle = assertIs<CommandLifecycleResult>(result)
        assertFalse(lifecycle.ok)
        assertEquals("v2 도시 원장이 없는 월드입니다.", lifecycle.reason)
    }
}
