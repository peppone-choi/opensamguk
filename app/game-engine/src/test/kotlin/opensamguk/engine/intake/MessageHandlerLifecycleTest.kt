package opensamguk.engine.intake

import opensamguk.common.wire.SendMessageResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.world.WorldId
import opensamguk.engine.run.TurnDaemonCommandDispatcher
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralRole
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageHandlerLifecycleTest {
    private val turnTime = Instant.parse("0200-01-01T00:00:00Z")

    private fun general(id: Int, name: String, meta: Map<String, Any?> = emptyMap()) = TurnGeneral(
        id = id,
        name = name,
        nationId = 1,
        cityId = 5,
        troopId = 0,
        stats = GeneralStats(80, 70, 60),
        experience = 0,
        dedication = 0,
        officerLevel = 5,
        turnTime = turnTime,
        role = GeneralRole(),
        meta = meta,
    )

    private fun world(sentAt: Instant): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(
                id = 1,
                currentYear = 200,
                currentMonth = 3,
                tickSeconds = 3600,
                lastTurnTime = turnTime,
            ),
            generals = listOf(
                general(
                    1,
                    "유비",
                    meta = mapOf(
                        "penalty" to mapOf("send_private_msg_delay" to 2),
                        "lastMsg" to sentAt.minusSeconds(3).epochSecond,
                    ),
                ),
                general(7, "관우"),
            ),
            nations = listOf(Nation(id = 1, name = "촉", color = "#00ff00", gold = 1000)),
            worldId = WorldId(1),
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

    private fun dispatcher(world: InMemoryTurnWorld, recorder: ChangeRecorder) = TurnDaemonCommandDispatcher(
        world,
        recorder,
        noopRepo<opensamguk.infra.read.AuctionRepository>(),
        noopRepo<opensamguk.infra.read.AuctionBidRepository>(),
        noopRepo<opensamguk.infra.read.BoardPostRepository>(),
    )

    @Test
    fun `decoded envelope sentAt governs private message throttle state and row time`() {
        val sentAt = turnTime.plusSeconds(60)
        val world = world(sentAt)
        val recorder = ChangeRecorder()
        val envelope = TurnDaemonCommandEnvelope(
            requestId = "req-message-time",
            sentAt = sentAt.toString(),
            command = TurnDaemonCommand.SendMessage(generalId = 1, mailbox = 7, text = "실시간 서신"),
        )

        val results = dispatcher(world, recorder).dispatchEnvelopes(listOf(envelope))

        val result = results.single().second as SendMessageResult
        assertTrue(result.ok)
        assertEquals(sentAt.epochSecond, world.getGeneralById(1)!!.meta["lastMsg"])
        assertEquals(sentAt.epochSecond, recorder.generalPatches().single { it.id == 1 }.meta["lastMsg"])
        assertEquals(
            listOf(MessageHandler.formatPhpDate(sentAt), MessageHandler.formatPhpDate(sentAt)),
            recorder.createdMessages().map { it.time },
        )
    }
}
