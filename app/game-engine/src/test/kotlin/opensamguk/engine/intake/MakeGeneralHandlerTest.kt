package opensamguk.engine.intake

import opensamguk.common.wire.MakeGeneralOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.City
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MakeGeneralHandlerTest {

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun state() = TurnWorldState(
        id = 1,
        currentYear = 200,
        currentMonth = 1,
        tickSeconds = 3600,
        lastTurnTime = t0,
        meta = mapOf("hiddenSeed" to "join-test-seed"),
    )

    private fun command(userId: Int = 7) = TurnDaemonCommand.MakeGeneral(
        userId = userId,
        name = "테스트",
        leadership = 55,
        strength = 55,
        intel = 55,
        character = "Random",
    )

    @Test
    fun `make general falls back to occupied level five-six cities when no neutral birth city exists`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = state(),
                cities = listOf(
                    City(id = 10, name = "낙양", nationId = 1, level = 5),
                    City(id = 11, name = "허창", nationId = 2, level = 6),
                    City(id = 12, name = "관문", nationId = 0, level = 4),
                ),
            ),
        )

        val result = MakeGeneralHandler(world, ChangeRecorder()).handle(command())

        assertIs<MakeGeneralOk>(result)
        val created = world.getGeneralById(result.generalId)
        assertNotNull(created)
        assertTrue(
            created.cityId in setOf(10, 11),
            "PHP Join.php:278-283 falls back from neutral level 5-6 cities to all level 5-6 cities.",
        )
    }

    @Test
    fun `created general carries drawn affinity into the flush payload`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = state(),
                cities = listOf(City(id = 10, name = "낙양", nationId = 0, level = 5)),
            ),
        )

        val result = assertIs<MakeGeneralOk>(MakeGeneralHandler(world, ChangeRecorder()).handle(command(userId = 8)))
        val created = world.getGeneralById(result.generalId)
        assertNotNull(created)
        val affinity = created.meta["affinity"] as? Number
        assertNotNull(affinity, "PHP Join.php:392/413 stores the RNG-drawn affinity in general.affinity.")
        assertTrue(affinity.toInt() in 1..150)

        val payload = DatabaseHooks.toFlushPayload(world.getState(), world.consumeDirtyState())

        assertTrue(payload.createdGenerals.isNotEmpty())
        val columns = payload.createdGenerals.single().columns
        assertTrue(columns["affinity"] is Int)
        assertTrue((columns["affinity"] as Int) in 1..150)
    }
}
