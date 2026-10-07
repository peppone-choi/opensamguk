package opensamguk.engine.invariance

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.City
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.world.SupplyCutReason
import opensamguk.logic.world.supplyReasonSnapshot

class WorldStateBaselineSupplyAssessmentTest {
    private val city = City(1, "C1", 1, 1, population = 1000, supplyState = 0,
        meta = mapOf("trust" to 20.0))

    private fun world(city: City = this.city) = InMemoryTurnWorld(WorldSnapshot(
        worldId = WorldId(1),
        state = TurnWorldState(1, 200, 1, 3600, Instant.EPOCH),
        cities = listOf(city),
    ))

    @Test
    fun `city explanation and cleared explanation preserve the gameplay fingerprint`() {
        val baseline = WorldStateBaseline.sha256(world())
        for (reason in SupplyCutReason.entries) {
            val snapshot = supplyReasonSnapshot(reason, 1, 1, 1, 200, 1, 1,
                "han-world-v3", "a".repeat(64))
            assertEquals(baseline, WorldStateBaseline.sha256(world(
                city.copy(meta = city.meta + ("supplyAssessment" to snapshot)))), reason.name)
        }
        assertEquals(baseline, WorldStateBaseline.sha256(world(
            city.copy(meta = city.meta + ("supplyAssessment" to null)))))
    }

    @Test
    fun `actual supply and ownership still change the gameplay fingerprint`() {
        val baseline = WorldStateBaseline.sha256(world())
        assertNotEquals(baseline, WorldStateBaseline.sha256(world(city.copy(supplyState = 1))))
        assertNotEquals(baseline, WorldStateBaseline.sha256(world(city.copy(nationId = 2))))
    }

    @Test
    fun `gameplay city metadata is still part of the fingerprint`() {
        assertNotEquals(WorldStateBaseline.sha256(world()), WorldStateBaseline.sha256(world(
            city.copy(meta = city.meta + ("trust" to 19.0)))))
    }

    @Test
    fun `the same key outside city metadata is not globally excluded`() {
        val world = world()
        val baseline = WorldStateBaseline.sha256(world)
        world.setGameEnvValue("supplyAssessment", mapOf("code" to "NO_SOURCE"))
        assertNotEquals(baseline, WorldStateBaseline.sha256(world))
    }
}
