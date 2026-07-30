package opensamguk.engine.intake

import opensamguk.common.wire.AcceptRaiseInvaderMessageFail
import opensamguk.common.wire.AcceptRaiseInvaderMessageOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.world.RaiseInvaderSpec
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaiseInvaderMessageHandlerTest {
    private val now = Instant.parse("0200-01-01T00:00:00Z")

    @Test
    fun `valid receiver after unification dispatches the four message arguments`() {
        var received: RaiseInvaderSpec? = null
        val fixture = fixture(isunited = 2, receiverId = 7) {
            received = it
            4
        }

        val result = fixture.handler.handle(
            TurnDaemonCommand.AcceptRaiseInvaderMessage(messageId = 31, generalId = 7),
        )

        assertEquals(AcceptRaiseInvaderMessageOk(messageId = 31, invaderNationCount = 4), result)
        assertEquals(RaiseInvaderSpec(-2.0, -1.2, 15_000.0, -1.0), received)
    }

    @Test
    fun `wrong receiver is rejected before the invader action`() {
        var received: RaiseInvaderSpec? = null
        val fixture = fixture(isunited = 2, receiverId = 7) {
            received = it
            4
        }

        val result = fixture.handler.handle(
            TurnDaemonCommand.AcceptRaiseInvaderMessage(messageId = 31, generalId = 8),
        )

        assertEquals(
            AcceptRaiseInvaderMessageFail(messageId = 31, reason = RaiseInvaderMessageHandler.INVALID_RECEIVER),
            result,
        )
        assertNull(received)
        assertEquals(1, fixture.recorder.createdMessages().size)
        assertTrue(fixture.recorder.createdMessages().single().bodyJson.contains("올바른 수신자가 아닙니다. 이민족 등장 불가."))
        assertEquals(1, fixture.world.getGeneralById(7)?.meta?.get("newmsg"))
    }

    @Test
    fun `receiver cannot raise invaders before unification`() {
        val result = fixture(isunited = 1, receiverId = 7) { error("must not dispatch") }.handler
            .handle(TurnDaemonCommand.AcceptRaiseInvaderMessage(messageId = 31, generalId = 7))

        assertEquals(
            AcceptRaiseInvaderMessageFail(messageId = 31, reason = RaiseInvaderMessageHandler.NOT_UNITED),
            result,
        )
    }

    private fun fixture(
        isunited: Int,
        receiverId: Int,
        raise: (RaiseInvaderSpec) -> Int,
    ): Fixture {
        val state = TurnWorldState(
            id = 1,
            currentYear = 200,
            currentMonth = 1,
            tickSeconds = 3600,
            lastTurnTime = now,
            meta = mapOf("isunited" to isunited),
        )
        val receiver = TurnGeneral(
            id = receiverId,
            name = "유비",
            nationId = 1,
            cityId = 1,
            troopId = 0,
            stats = GeneralStats(80, 80, 80),
            experience = 0,
            dedication = 0,
            officerLevel = 12,
            turnTime = now,
        )
        val world = InMemoryTurnWorld(
            WorldSnapshot(state = state, generals = listOf(receiver), worldId = WorldId(1)),
        )
        val message = MessageSnapshot(
            id = 31,
            mailbox = 7,
            hasAction = true,
            type = "private",
            srcGeneralId = 0,
            srcNationId = 0,
            destGeneralId = 7,
            destNationId = 1,
            time = now,
            validUntil = Instant.parse("9999-12-31T00:00:00Z"),
            text = "이벤트 게임으로 이민족[어려움]을 소환",
            srcArray = emptyMap(),
            destArray = emptyMap(),
            option = linkedMapOf(
                "action" to "raiseInvader",
                "args" to listOf(-2, -1.2, 15_000, -1),
                "used" to false,
            ),
        )
        val recorder = ChangeRecorder()
        val handler = RaiseInvaderMessageHandler(
            world = world,
            recorder = recorder,
            messageReader = { id -> message.takeIf { it.id == id } },
            raiseInvader = raise,
            nowProvider = { now },
        )
        return Fixture(handler, recorder, world)
    }

    private data class Fixture(
        val handler: RaiseInvaderMessageHandler,
        val recorder: ChangeRecorder,
        val world: InMemoryTurnWorld,
    )
}
