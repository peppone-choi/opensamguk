package opensamguk.engine.intake

import opensamguk.common.wire.MySettings
import opensamguk.common.wire.ReadLatestMessageResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.common.world.WorldId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountCommandHandlerTest {
    private val now = Instant.parse("0200-01-01T00:00:00Z")

    private fun general(id: Int, latest: Int = 0) = TurnGeneral(
        id = id,
        userId = "7",
        name = "장수$id",
        nationId = 1,
        cityId = 1,
        troopId = 0,
        stats = GeneralStats(70, 70, 70),
        experience = 0,
        dedication = 0,
        officerLevel = 0,
        turnTime = now,
        meta = linkedMapOf("latestReadPrivateMsg" to latest, "myset" to 2, "defence_train" to 80),
    )

    private fun world(): InMemoryTurnWorld {
        val state = TurnWorldState(
            id = 1,
            currentYear = 200,
            currentMonth = 1,
            tickSeconds = 60,
            lastTurnTime = now,
            config = mapOf("killturn" to 6),
        )
        return InMemoryTurnWorld(
            WorldSnapshot(state = state, generals = listOf(general(10, 40), general(11)), worldId = WorldId(1)),
        )
    }

    @Test
    fun `read cursor is monotonic and settings use PHP defence normalization`() {
        val world = world()
        val handler = AccountCommandHandler(world, ChangeRecorder())

        val old = handler.handleReadLatest(
            TurnDaemonCommand.ReadLatestMessage(generalId = 10, messageType = "private", msgID = 20),
        ) as ReadLatestMessageResult
        assertEquals(40, old.latestRead)

        handler.handleSetting(
            TurnDaemonCommand.SetMySetting(
                generalId = 10,
                settings = MySettings(tnmt = 0, defenceTrain = 85, useTreatment = 200, useAutoNationTurn = 0),
            ),
        )
        val updated = world.getGeneralById(10)!!
        assertEquals(90, updated.meta["defence_train"])
        assertEquals(1, updated.meta["myset"])
        assertEquals(100, updated.meta["use_treatment"])
        assertEquals(0, updated.meta["tnmt"])
    }

    @Test
    fun `vacation applies killturn times three to every general of the owner`() {
        val world = world()
        AccountCommandHandler(world, ChangeRecorder()).handleVacation(TurnDaemonCommand.Vacation(generalId = 10))

        assertEquals(18, world.getGeneralById(10)!!.meta["killturn"])
        assertEquals(18, world.getGeneralById(11)!!.meta["killturn"])
    }
}
