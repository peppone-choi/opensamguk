package opensamguk.engine.turn

import opensamguk.common.world.WorldId
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AiTurnAdapterCureThresholdTest {
    private val turnTime = Instant.parse("0200-01-01T12:34:00Z")
    private val registry = CommandRegistry(GeneralActionPipeline())

    private fun general(
        injury: Int,
        specialWar: String? = null,
    ) = TurnGeneral(
        id = 1,
        name = "부상장수",
        nationId = 1,
        cityId = 7,
        troopId = 0,
        stats = GeneralStats(leadership = 70, strength = 70, intelligence = 70),
        experience = 0,
        dedication = 0,
        officerLevel = 1,
        role = GeneralRole(specialWar = specialWar),
        injury = injury,
        gold = 100_000,
        rice = 100_000,
        npcState = 2,
        turnTime = turnTime,
        meta = linkedMapOf("killturn" to 1_000, "belong" to 1),
    )

    private fun world(injury: Int): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(
                id = 1,
                currentYear = 200,
                currentMonth = 1,
                tickSeconds = 60,
                lastTurnTime = turnTime,
            ),
            generals = listOf(general(injury)),
            cities = listOf(
                City(
                    id = 7,
                    name = "낙양",
                    nationId = 1,
                    level = 5,
                    population = 100_000,
                    populationMax = 200_000,
                    agriculture = 10_000,
                    agricultureMax = 20_000,
                    commerce = 10_000,
                    commerceMax = 20_000,
                    security = 10_000,
                    securityMax = 20_000,
                    defence = 10_000,
                    defenceMax = 20_000,
                    wall = 10_000,
                    wallMax = 20_000,
                    supplyState = 1,
                    frontState = 0,
                    meta = linkedMapOf("trust" to 80, "pop" to 100_000, "pop_max" to 200_000),
                ),
            ),
            nations = listOf(
                Nation(
                    id = 1,
                    name = "한",
                    color = "#000",
                    capitalCityId = 7,
                    gold = 100_000,
                    rice = 100_000,
                    level = 7,
                ),
            ),
            worldId = WorldId(1),
        ),
    )

    private fun adapter(world: InMemoryTurnWorld) = AiTurnAdapter(
        world = world,
        registry = registry,
        hiddenSeed = "0".repeat(32),
        startYear = 184,
        turnTerm = 1,
    )

    @Test
    fun `general-pass recovery begins only above the PHP default threshold of ten`() {
        val atThreshold = adapter(world(10)).chooseGeneralTurn(1, ReservedTurn("휴식", ""))
        assertNotEquals("che_요양", atThreshold.actionCode, "injury == 10 must not enter the strict greater-than recovery branch")

        listOf(11, 30).forEach { injury ->
            val chosen = adapter(world(injury)).chooseGeneralTurn(1, ReservedTurn("휴식", ""))

            assertEquals("che_요양", chosen.actionCode, "injury $injury must enter the recovery branch")
            assertEquals("do요양", chosen.reason)
        }
    }

    @Test
    fun `live reserved-turn AI hook preserves the recovery choice and resolves it`() {
        val world = world(11)
        val adapter = adapter(world)
        val handler = ReservedTurnHandler(
            world = world,
            registry = registry,
            hiddenSeed = "0".repeat(32),
            startYear = 184,
            aiHook = adapter::chooseGeneralTurn,
        )

        val handled = handler.handle(1, ReservedTurn("휴식", ""), year = 200, month = 1, date = "12:34")

        assertEquals("che_요양", handled.definition.key)
        assertTrue(handled.autorunMode)
        assertFalse(handled.fellBack)
        assertEquals(0, world.getGeneralById(1)!!.injury)
    }

    @Test
    fun `daemon preprocess reduces injury before the AI recovery threshold`() {
        val world = world(12)
        val adapter = adapter(world)
        val handler = ReservedTurnHandler(
            world = world,
            registry = registry,
            hiddenSeed = "0".repeat(32),
            startYear = 184,
            aiHook = adapter::chooseGeneralTurn,
        )
        var preAiInjury: Int? = null
        val lifecycle = TurnDaemonLifecycle(
            world = world,
            handler = handler,
            beginGeneralTurn = {
                preAiInjury = world.getGeneralById(it)!!.injury
                adapter.beginGeneralTurn(it)
            },
            reservedActionOf = { ReservedTurn("휴식", "") },
        )

        val handled = lifecycle.runTick(turnTime.plusSeconds(1))

        assertEquals(2, preAiInjury)
        assertNotEquals("che_요양", handled.single().definition.key)
    }

    @Test
    fun `medical specialty heals self before the AI recovery threshold`() {
        val base = world(59)
        val medical = general(59, specialWar = "che_의술")
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = base.getState(),
                generals = listOf(medical),
                cities = base.listCities(),
                nations = base.listNations(),
                worldId = WorldId(1),
            ),
        )
        val adapter = adapter(world)
        val handler = ReservedTurnHandler(
            world = world,
            registry = registry,
            hiddenSeed = "0".repeat(32),
            startYear = 184,
            aiHook = adapter::chooseGeneralTurn,
        )
        var preAiInjury: Int? = null
        val lifecycle = TurnDaemonLifecycle(
            world = world,
            handler = handler,
            beginGeneralTurn = {
                preAiInjury = world.getGeneralById(it)!!.injury
                adapter.beginGeneralTurn(it)
            },
            reservedActionOf = { ReservedTurn("휴식", "") },
        )

        val handled = lifecycle.runTick(turnTime.plusSeconds(1))

        assertEquals(0, preAiInjury)
        assertNotEquals("che_요양", handled.single().definition.key)
    }

    @Test
    fun `medical preprocess heals the selected patient and emits PHP PLAIN logs in target then actor order`() {
        val hiddenSeed = "0".repeat(32)
        val healerId = (1..100).first { id ->
            RandUtil(LiteHashDrbg(serializeSeed(hiddenSeed, "preprocess", 200, 1, id))).nextBool(0.5)
        }
        val base = world(0)
        val healer = general(0, specialWar = "che_의술").copy(id = healerId, name = "명의")
        val patient = general(20).copy(id = 200, name = "ⓝ위유")
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = base.getState(),
                generals = listOf(healer, patient),
                cities = base.listCities(),
                nations = base.listNations(),
                worldId = WorldId(1),
            ),
        )
        val handler = ReservedTurnHandler(
            world = world,
            registry = registry,
            hiddenSeed = hiddenSeed,
            startYear = 184,
        )

        handler.preprocessGeneral(healerId, 200, 1)

        assertEquals(0, world.getGeneralById(200)!!.injury)
        assertTrue(handler.recorder.dirtyGeneralIds().contains(200))
        assertEquals(
            listOf(
                200 to "<C>●</><Y>명의</>가 <C>의술</>로써 치료해줍니다!",
                healerId to "<C>●</><C>의술</>을 펼쳐 도시의 장수 <Y>ⓝ위유</>를 치료합니다!",
            ),
            world.peekLogs().map { it.generalId to it.text },
        )
    }
}
