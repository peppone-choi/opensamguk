package opensamguk.engine.turn

import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.nation.NationActionResolverRegistry
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ProductionPipelineIntegrationTest {
    private val t0 = Instant.parse("0200-01-01T00:00:00Z")
    private val hiddenSeed = "00000000000000000000000000000000"

    private fun world(general: TurnGeneral, nation: Nation = Nation(id = 1, name = "촉", color = "#0f0", level = 7)) =
        InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 1, tickSeconds = 3600, lastTurnTime = t0),
                generals = listOf(general),
                cities = listOf(
                    City(
                        id = 5,
                        name = "업",
                        nationId = 1,
                        level = 6,
                        population = 200_000,
                        populationMax = 200_000,
                        supplyState = 1,
                        meta = linkedMapOf("trust" to 80),
                    ),
                ),
                nations = listOf(nation),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(id = 1, currentYear = 200, currentMonth = 1, tickSeconds = 3600, lastTurnTime = t0)).id),
            ),
        )

    @AfterTest
    fun clearRegisteredResolvers() {
        NationActionResolverRegistry.clear()
    }

    @Test
    fun `reserved human command resolves with per-general production pipeline`() {
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
            gold = 1_000_000,
            rice = 1_000_000,
            crew = 0,
            crewTypeId = 1100,
            turnTime = t0,
            role = GeneralRole(specialWar = "che_징병"),
            meta = linkedMapOf("killturn" to 80),
        )
        val world = world(general)
        val handler = ReservedTurnHandler(
            world = world,
            registry = CommandRegistry(GeneralActionPipeline()),
            hiddenSeed = hiddenSeed,
            startYear = 184,
            pipelineBuilder = EngineGeneralActionPipelineBuilder(world, 184),
        )

        handler.handle(
            10,
            ReservedTurn("che_징병", """{"crewType":1100,"amount":99999}"""),
            200,
            1,
            "12:00",
        )

        assertEquals(10_100, world.getGeneralById(10)!!.crew)
    }

    @Test
    fun `reserved command hydrates and persists general last_turn`() {
        val previous = linkedMapOf<String, Any?>(
            "command" to "전투태세",
            "arg" to linkedMapOf("mode" to "old"),
            "term" to 2,
            "seq" to 7,
        )
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
            gold = 1_000,
            rice = 1_000,
            turnTime = t0,
            meta = linkedMapOf("last_turn" to previous, "explevel" to 0),
        )
        val city = City(
            id = 5,
            name = "업",
            nationId = 1,
            level = 6,
            security = 10_000,
            securityMax = 10_000,
            supplyState = 1,
            trade = 100,
            meta = linkedMapOf("trust" to 80),
        )
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 1, tickSeconds = 3600, lastTurnTime = t0),
                generals = listOf(general),
                cities = listOf(city),
                nations = listOf(Nation(id = 1, name = "촉", color = "#0f0", level = 7)),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(id = 1, currentYear = 200, currentMonth = 1, tickSeconds = 3600, lastTurnTime = t0)).id),
            ),
        )
        assertEquals(LastTurn("전투태세", linkedMapOf("mode" to "old"), term = 2, seq = 7), PerTurnOverlay.toLogicGeneral(general).lastTurn)

        val handler = ReservedTurnHandler(
            world = world,
            registry = CommandRegistry(GeneralActionPipeline()),
            hiddenSeed = hiddenSeed,
            startYear = 184,
        )

        val outcome = handler.handle(
            10,
            ReservedTurn("che_장비매매", """{"itemType":"item","itemCode":"che_치료_환약"}"""),
            200,
            1,
            "12:00",
        )

        assertEquals(false, outcome.fellBack, "장비매매 should pass full constraints: ${outcome.denyReason}")
        @Suppress("UNCHECKED_CAST")
        val stored = world.getGeneralById(10)!!.meta["last_turn"] as Map<String, Any?>
        assertEquals("장비매매", stored["command"])
        assertEquals(linkedMapOf("itemType" to "item", "itemCode" to "che_치료_환약"), stored["arg"])
    }

    @Test
    fun `nation command bridge resolves with per-general production pipeline`() {
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
            turnTime = t0,
            role = GeneralRole(personality = "che_왕좌"),
        )
        val world = world(general, Nation(id = 1, name = "촉", color = "#0f0", gold = 200_000, rice = 200_000))
        val processor = ProcessNationCommand(
            world = world,
            recorder = ChangeRecorder(),
            hiddenSeed = hiddenSeed,
            registry = CommandRegistry(GeneralActionPipeline()),
            startYear = 184,
            pipelineBuilder = EngineGeneralActionPipelineBuilder(world, 184),
        )

        processor.process(
            generalId = 10,
            officerLevel = 12,
            nationCommand = ChosenCommand("event_상병연구", emptyMap()),
            lastTurn = LastTurn(command = "상병 연구", arg = emptyMap(), term = 23, seq = 0),
            year = 200,
            month = 1,
            date = "12:00",
        )

        assertEquals(132, world.getGeneralById(10)!!.experience)
    }

    @Test
    fun `registered nation resolver receives per-general production pipeline`() {
        NationActionResolverRegistry.register("test_registered_exp") { ctx ->
            val general = ctx.general ?: return@register
            val pipeline = ctx.pipeline ?: GeneralActionPipeline()
            ctx.general = addExperience(general, 10.0, pipeline).general
        }
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
            turnTime = t0,
            role = GeneralRole(personality = "che_왕좌"),
        )
        val world = world(general)
        val processor = ProcessNationCommand(
            world = world,
            recorder = ChangeRecorder(),
            hiddenSeed = hiddenSeed,
            registry = CommandRegistry(GeneralActionPipeline()),
            startYear = 184,
            pipelineBuilder = EngineGeneralActionPipelineBuilder(world, 184),
        )

        processor.process(
            generalId = 10,
            officerLevel = 12,
            nationCommand = ChosenCommand("test_registered_exp", emptyMap()),
            lastTurn = LastTurn(),
            year = 200,
            month = 1,
            date = "12:00",
        )

        assertEquals(11, world.getGeneralById(10)!!.experience)
    }
}
