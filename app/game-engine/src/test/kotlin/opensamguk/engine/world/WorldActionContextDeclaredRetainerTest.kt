package opensamguk.engine.world

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.*
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.GeneralPositionSnapshot
import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.StrategicNodeRef

class WorldActionContextDeclaredRetainerTest {
    private fun world(masterLord: Any? = true, subjectNation: Int = 1): InMemoryTurnWorld {
        val hash = "b".repeat(64)
        fun general(id: Int, name: String, nationId: Int, lord: Any?) = TurnGeneral(
            id = id, name = name, nationId = nationId, cityId = 10, npcState = 2,
            troopId = 0, stats = GeneralStats(70, 70, 70), experience = 0, dedication = 100,
            officerLevel = 1, gold = 0, rice = 0, crew = 0, turnTime = Instant.EPOCH,
            meta = mapOf("lord" to lord),
        )
        return InMemoryTurnWorld(WorldSnapshot(
            worldId = WorldId(1),
            state = TurnWorldState(1, 190, 1, 3600, Instant.EPOCH,
                config = mapOf("ruleProfile" to "HWIHA", "mapName" to "han-world-v3")),
            nations = listOf(Nation(1, "세력", "#000"), Nation(2, "타세력", "#fff")),
            generals = listOf(general(1001, "ⓝ주공", 1, masterLord),
                general(1002, "ⓝ신하", subjectNation, false)),
            cities = listOf(City(10, "縣", 1, 1)),
            administrativeCountyIds = setOf(10),
            generalPositionSnapshot = GeneralPositionSnapshot("r1", hash, setOf("p10"), emptySet())
                .withState(GeneralPositionState("r1", hash, 1001, StrategicNodeRef.LandProvince("p10"), 1))
                .withState(GeneralPositionState("r1", hash, 1002, StrategicNodeRef.LandProvince("p10"), 1)),
            cityLandProvinceById = mapOf(10 to "p10"),
        ))
    }

    private fun context(world: InMemoryTurnWorld) = WorldActionContext(
        env = mutableMapOf<String, Any?>("year" to 190, "month" to 1), world = world,
        recorder = ChangeRecorder(), pipeline = GeneralActionPipeline(emptyList()),
    )

    @Test fun `future officer receives one declared card`() {
        val world = world()
        val context = context(world)
        context.stageDeclaredRetainer(1002, "ⓝ주공")
        context.stageDeclaredRetainer(1002, "ⓝ주공")

        val card = world.listRetainers().single()
        assertEquals(1001, card.masterGeneralId)
        assertEquals(1002, card.generalId)
        assertEquals("ⓝ신하", card.name)
        assertEquals("EXISTING", card.origin)
        assertEquals("MUTUAL", card.releasePolicy)
    }

    @Test fun `lost or malformed lord and changed allegiance leave appearance alive without a card`() {
        val missingLord = world()
        context(missingLord).stageDeclaredRetainer(1002, "ⓝ없는주공")
        assertTrue(missingLord.listRetainers().isEmpty())
        for (world in listOf(world(masterLord = false), world(masterLord = "bad"), world(subjectNation = 2))) {
            context(world).stageDeclaredRetainer(1002, "ⓝ주공")
            assertTrue(world.listRetainers().isEmpty())
            assertEquals("ⓝ신하", world.getGeneralById(1002)?.name)
        }
    }
}
