package opensamguk.engine.v2

import opensamguk.common.wire.CityGarrisonRecruit
import opensamguk.common.wire.CityTransport
import opensamguk.common.wire.CommandLifecycleResult
import opensamguk.engine.run.TurnDaemonCommandDispatcher
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.v2.command.V2CommandAvailability
import opensamguk.logic.v2.command.V2CommandRegistry
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

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

    private class CountingClock(private val current: Instant) : Clock() {
        var reads: Int = 0
            private set

        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current.also { reads += 1 }
    }

    @Test
    fun `direct dispatch reads the execution clock once`() {
        val clock = CountingClock(t0)
        val d = TurnDaemonCommandDispatcher(
            world(), ChangeRecorder(),
            noopRepo<opensamguk.infra.read.AuctionRepository>(),
            noopRepo<opensamguk.infra.read.AuctionBidRepository>(),
            noopRepo<opensamguk.infra.read.BoardPostRepository>(),
            v2CityLedger = null,
            clock = clock,
        )

        d.dispatch(CityGarrisonRecruit(generalId = 10, cityId = 5, amount = 100))

        assertEquals(1, clock.reads)
    }

    @Test
    fun `legacy wire without expiresAt remains executable and returns a terminal result`() {
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

    @Test
    fun `expired v2 command returns a terminal rejection before handler execution`() {
        val d = TurnDaemonCommandDispatcher(
            world(), ChangeRecorder(),
            noopRepo<opensamguk.infra.read.AuctionRepository>(),
            noopRepo<opensamguk.infra.read.AuctionBidRepository>(),
            noopRepo<opensamguk.infra.read.BoardPostRepository>(),
            v2CityLedger = null,
            clock = Clock.fixed(Instant.parse("0200-01-01T02:00:00Z"), ZoneOffset.UTC),
        )
        val envelope = opensamguk.common.wire.TurnDaemonCommandEnvelope(
            requestId = "expired-1",
            sentAt = "0200-01-01T00:00:00Z",
            command = CityGarrisonRecruit(
                requestId = "expired-1",
                generalId = 10,
                cityId = 5,
                amount = 100,
                expiresAt = "0200-01-01T01:00:00Z",
            ),
        )

        val lifecycle = assertIs<CommandLifecycleResult>(d.dispatchEnvelopes(listOf(envelope)).single().second)

        assertFalse(lifecycle.ok)
        assertEquals("COMMAND_EXPIRED", lifecycle.code)
        assertEquals("명령이 만료되었습니다.", lifecycle.reason)
        assertEquals("city.garrison.recruit", lifecycle.canonicalCommandId)
        assertNotNull(lifecycle.replayEvent)
    }

    @Test
    fun `api precheck and daemon execution deny with the same reason`() {
        val precheck = assertIs<V2CommandAvailability.Blocked>(
            V2CommandRegistry.precheck("city.garrison.recruit", mapOf("cityId" to 5, "amount" to 99)),
        )
        val d = TurnDaemonCommandDispatcher(
            world(), ChangeRecorder(),
            noopRepo<opensamguk.infra.read.AuctionRepository>(),
            noopRepo<opensamguk.infra.read.AuctionBidRepository>(),
            noopRepo<opensamguk.infra.read.BoardPostRepository>(),
            v2CityLedger = null,
        )

        val terminal = assertIs<CommandLifecycleResult>(
            d.dispatch(CityGarrisonRecruit(generalId = 10, cityId = 5, amount = 99)),
        )

        assertEquals(precheck.code, terminal.code)
        assertEquals(precheck.reason, terminal.reason)
    }

    @Test
    fun `transport wire without route revision is not rejected as malformed`() {
        val d = TurnDaemonCommandDispatcher(
            world(), ChangeRecorder(),
            noopRepo<opensamguk.infra.read.AuctionRepository>(),
            noopRepo<opensamguk.infra.read.AuctionBidRepository>(),
            noopRepo<opensamguk.infra.read.BoardPostRepository>(),
            v2CityLedger = null,
        )

        val result = assertIs<CommandLifecycleResult>(
            d.dispatch(CityTransport(generalId = 10, fromCityId = 5, toCityId = 6, gold = 1)),
        )

        assertEquals(null, result.code)
        assertEquals("v2 도시 원장이 없는 월드입니다.", result.reason)
    }
}
