package opensamguk.engine.intake

import opensamguk.common.wire.MakeGeneralFail
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.wire.WireJson
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
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class W07DenyStubDispatchTest {

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")
    private val removedStubReason = "아직 구현되지 않은 명령입니다 (엔진 핸들러 W1 대기)"

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
            worldId = opensamguk.common.world.WorldId((TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0)).id),
        ),
    )

    /** publisher가 encode하는 그대로 → consumer가 decode하는 그대로 (RedisCommandStream과 동일 코덱). */
    private fun decodeAsConsumer(command: TurnDaemonCommand): TurnDaemonCommand {
        val payload = WireJson.encodeToString(
            TurnDaemonCommandEnvelope.serializer(),
            TurnDaemonCommandEnvelope(requestId = "req-1", sentAt = "0200-01-01T00:00:00Z", command = command),
        )
        return WireJson.decodeFromString(TurnDaemonCommandEnvelope.serializer(), payload).command
    }

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

    private fun dispatcher(world: InMemoryTurnWorld, recorder: ChangeRecorder) = TurnDaemonCommandDispatcher(
        world, recorder,
        noopRepo<opensamguk.infra.read.AuctionRepository>(),
        noopRepo<opensamguk.infra.read.AuctionBidRepository>(),
        noopRepo<opensamguk.infra.read.BoardPostRepository>(),
    )

    @Test
    fun `MakeGeneral 유산-전콘 옵션은 unsupported 스텁이 아니라 실제 핸들러 결과를 반환한다`() {
        val w = world()
        val d = dispatcher(w, ChangeRecorder())
        val before = w.listGenerals().size

        val variants = listOf(
            TurnDaemonCommand.MakeGeneral(userId = 3, name = "조운", leadership = 55, strength = 55, intel = 55, inheritSpecial = "che_귀병"),
            TurnDaemonCommand.MakeGeneral(userId = 3, name = "조운", leadership = 55, strength = 55, intel = 55, inheritTurntimeZone = 30),
            TurnDaemonCommand.MakeGeneral(userId = 3, name = "조운", leadership = 55, strength = 55, intel = 55, inheritCity = 15),
            TurnDaemonCommand.MakeGeneral(userId = 3, name = "조운", leadership = 55, strength = 55, intel = 55, inheritBonusStat = listOf(3, 2, 1)),
            TurnDaemonCommand.MakeGeneral(userId = 3, name = "조운", leadership = 55, strength = 55, intel = 55, picture = "1.jpg", imgsvr = 1),
        )
        for (v in variants) {
            val fail = assertIs<MakeGeneralFail>(d.dispatch(decodeAsConsumer(v)))
            assertNotEquals(removedStubReason, fail.reason)
        }
        // deny는 world를 변이하지 않는다 — 장수 수 불변.
        assertEquals(before, w.listGenerals().size)
    }

    @Test
    fun `옵션 없는 MakeGeneral은 기존 핸들러 경로로 흐른다 - 스텁 사유가 아니다`() {
        val w = world() // 도시 없는 월드 → 핸들러 자체 게이트('공백지가 없습니다.')가 응답한다.
        val d = dispatcher(w, ChangeRecorder())

        val plain = decodeAsConsumer(
            TurnDaemonCommand.MakeGeneral(userId = 3, name = "조운", leadership = 55, strength = 55, intel = 55),
        )
        val fail = assertIs<MakeGeneralFail>(d.dispatch(plain))
        // 핸들러에 도달했다는 증거: deny 사유가 W0-7 스텁 마커가 아니라 핸들러의 도시-풀 게이트.
        assertNotEquals(removedStubReason, fail.reason)
        assertEquals("공백지가 없습니다.", fail.reason)
    }
}
