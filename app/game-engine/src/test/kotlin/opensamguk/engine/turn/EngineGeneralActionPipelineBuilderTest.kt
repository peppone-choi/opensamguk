package opensamguk.engine.turn

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineGeneralActionPipelineBuilderTest {
    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    @Test
    fun `pipelineFor includes live nation officer war personality crew scenario inherit and item sources`() {
        val general = TurnGeneral(
            id = 10,
            name = "유비",
            nationId = 1,
            cityId = 5,
            troopId = 0,
            stats = GeneralStats(leadership = 70, strength = 60, intelligence = 80),
            experience = 0,
            dedication = 0,
            officerLevel = 12,
            gold = 1000,
            rice = 1000,
            crewTypeId = 1100,
            turnTime = t0,
            role = GeneralRole(
                personality = "che_명사",
                specialDomestic = "che_상재",
                specialWar = "che_징병",
                items = GeneralItems(horse = "None", weapon = "None", book = "None", item = "None"),
            ),
            meta = linkedMapOf("inheritBuff" to mapOf("warAvoidRatio" to 2, "success" to 2)),
        )
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 1,
                    tickSeconds = 3600,
                    lastTurnTime = t0,
                    meta = linkedMapOf("scenarioEffect" to "event_MoreEffect"),
                ),
                generals = listOf(general),
                cities = listOf(City(id = 5, name = "업", nationId = 1, level = 6)),
                nations = listOf(Nation(id = 1, name = "촉", color = "#0f0", level = 7, typeCode = "che_유가")),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 1,
                    tickSeconds = 3600,
                    lastTurnTime = t0,
                    meta = linkedMapOf("scenarioEffect" to "event_MoreEffect"),
                )).id),
            ),
        )

        val pipeline = EngineGeneralActionPipelineBuilder(world, startYear = 184).pipelineFor(general)

        assertEquals(101.5, pipeline.onCalcStat(PerTurnOverlay.toLogicGeneral(general), "leadership", 70.0))
        assertEquals(70.0, pipeline.onCalcDomestic(PerTurnOverlay.toLogicGeneral(general), "징병", "train", 40.0))
        assertEquals(
            254.1,
            pipeline.onCalcDomestic(PerTurnOverlay.toLogicGeneral(general), "상업", "score", 100.0),
            0.0001,
        )
        assertEquals(0.0, pipeline.onCalcDomestic(PerTurnOverlay.toLogicGeneral(general), "changeDefenceTrain", "train999", 70.0))
    }
}
