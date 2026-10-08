package opensamguk.engine.campaign

import java.time.Instant
import kotlin.test.*
import opensamguk.common.wire.TurnDaemonCommand.ImmediateInput
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.*
import opensamguk.logic.domestic.*
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.Phase

class WorkReductionEngineTest {
    private val context = DomesticContext()
    private val args = """{"countyId":10,"work":"FORTIFICATION"}"""

    private fun world(): InMemoryTurnWorld {
        val stock = Resources(100_000, 100_000, 100_000, 100_000, 100_000)
        val person = TurnGeneral(id = 1, name = "Lord", nationId = 1, cityId = 10, troopId = 0,
            stats = GeneralStats(60, 60, 60, 80, 60), experience = 0, dedication = 0, officerLevel = 12,
            userId = "42", turnTime = Instant.EPOCH, meta = mapOf("lord" to true), gold = 2000, rice = 1000)
        val city = City(10, "縣10", 1, 1, population = 1000, populationMax = 10000,
            agriculture = 100, agricultureMax = 1000, commerce = 100, commerceMax = 1000,
            defence = 500, defenceMax = 2000, wall = 500, wallMax = 2000, security = 500, securityMax = 1000,
            meta = mapOf("trust" to 80.0, CountyWarehouse.META_KEY to CountyWarehouse(10, 0, stock).toMetaValue()))
        return InMemoryTurnWorld(WorldSnapshot(worldId = WorldId(1),
            state = TurnWorldState(1, 200, 1, 3600, Instant.EPOCH, currentPhase = 1,
                config = mapOf("ruleProfile" to "HWIHA", "mapName" to "han-world-v3")),
            generals = listOf(person), nations = listOf(Nation(1, "N1", "#111", capitalCityId = 10)),
            cities = listOf(city), administrativeCountyIds = setOf(10), cityLandProvinceById = mapOf(10 to "A")))
    }

    private fun submit(world: InMemoryTurnWorld, recorder: ChangeRecorder, id: String = "reduce-1",
        owner: Int = 42, input: String = "work.reduce", body: String = args) =
        CourtHandler(world, recorder, context).handle(ImmediateInput(id, 1, owner, input, body))

    private fun next(world: InMemoryTurnWorld): Phase = world.phaseNow().plus(1).also {
        world.setCurrentDate(it.year, it.month, it.phase)
    }

    private fun completedWorld(): InMemoryTurnWorld = world().also { world ->
        val city = world.getCityById(10)!!
        world.applyCityDirtyFree(city.copy(meta = city.meta + (CountyWorks.META_KEY to
            CountyWorks(null, listOf(CompletedWork(DomesticWork.FORTIFICATION, world.phaseNow()))).toMetaValue())))
    }

    @Test fun `actual work producer completes before reduction is queued and applied at the next boundary`() {
        val world = world(); val recorder = ChangeRecorder()
        assertTrue(submit(world, recorder, "build-1", input = "work.start").ok)
        assertNotNull(CountyWorks.read(world.getCityById(10)!!.meta)!!.active)
        for (i in 1..100) {
            next(world)
            DomesticBoundary(world, recorder, context).run()
            if (CountyWorks.read(world.getCityById(10)!!.meta)!!.active == null) break
        }
        val built = world.getCityById(10)!!
        assertEquals(listOf(DomesticWork.FORTIFICATION), CountyWorks.read(built.meta)!!.completed.map { it.work })
        val stock = CountyWarehouse.read(built.meta, 10)!!.stock
        val person = world.getGeneralById(1)!!
        val intake = submit(world, recorder)
        assertTrue(intake.ok); assertEquals("reservationAccepted", intake.type)
        assertEquals(built.defence, world.getCityById(10)!!.defence)
        assertEquals(built.wall, world.getCityById(10)!!.wall)
        assertNotNull(WorkReductionState.read(world.getCityById(10)!!.meta)!!.pending)
        val queued = world.getCityById(10)!!
        assertEquals("UNCHANGED", submit(world, recorder).code)
        assertEquals(queued, world.getCityById(10))
        assertTrue(DomesticBoundary(world, recorder, context).run()!!.alreadyStamped)
        assertEquals(queued, world.getCityById(10))
        next(world)
        DomesticBoundary(world, recorder, context).run()
        val reduced = world.getCityById(10)!!
        assertEquals((built.defence - 500).coerceAtLeast(0), reduced.defence)
        assertEquals((built.wall - 500).coerceAtLeast(0), reduced.wall)
        assertTrue(CountyWorks.read(reduced.meta)!!.completed.isEmpty())
        assertEquals(stock, CountyWarehouse.read(reduced.meta, 10)!!.stock)
        assertEquals(person.gold, world.getGeneralById(1)!!.gold)
        assertEquals(person.rice, world.getGeneralById(1)!!.rice)
        assertNull(WorkReductionState.read(reduced.meta)!!.pending)
        assertNull(WorkReductionState.read(reduced.meta)!!.last!!.reason)
        assertTrue(DomesticBoundary(world, ChangeRecorder(), context).run()!!.alreadyStamped)
        assertEquals(reduced, world.getCityById(10))
    }

    @Test fun `intake rejects uncompleted active foreign and road fort inputs without mutations`() {
        val unfinished = world()
        assertEquals("WORK_NOT_COMPLETED", submit(unfinished, ChangeRecorder()).code)
        val active = world(); val recorder = ChangeRecorder()
        assertTrue(submit(active, recorder, "build", input = "work.start").ok)
        val beforeActive = active.getCityById(10)
        assertEquals("WORK_IN_PROGRESS", submit(active, recorder).code)
        assertEquals(beforeActive, active.getCityById(10))
        val completed = completedWorld(); val before = completed.getCityById(10)
        assertEquals("FORBIDDEN", submit(completed, recorder, owner = 43).code)
        assertEquals("INVALID_REQUEST", submit(completed, recorder, body =
            """{"countyId":10,"work":"FORTIFICATION","edgeId":"road"}""").code)
        assertEquals(before, completed.getCityById(10))
        val person = completed.getGeneralById(1)!!
        completed.applyGeneralDirtyFree(person.copy(officerLevel = 1, meta = emptyMap()))
        assertEquals("NOT_COUNTY_AUTHORITY", submit(completed, recorder).code)
        assertEquals(before, completed.getCityById(10))
    }

    @Test fun `boundary rechecks ownership authority unfinished works and completed identity`() {
        for (change in listOf("user", "authority", "nation", "active", "completion")) {
            val world = completedWorld(); val recorder = ChangeRecorder()
            assertTrue(submit(world, recorder).ok)
            val person = world.getGeneralById(1)!!; val city = world.getCityById(10)!!
            when (change) {
                "user" -> world.applyGeneralDirtyFree(person.copy(userId = "43"))
                "authority" -> world.applyGeneralDirtyFree(person.copy(officerLevel = 1, meta = emptyMap()))
                "nation" -> world.applyCityDirtyFree(city.copy(nationId = 2))
                "active" -> world.applyCityDirtyFree(city.copy(meta = city.meta + (CountyWorks.META_KEY to
                    CountyWorks(DomesticEffects.newWork(context.design, DomesticWork.WAREHOUSE, "other", 1,
                        world.phaseNow()), CountyWorks.read(city.meta)!!.completed).toMetaValue())))
                "completion" -> world.applyCityDirtyFree(city.copy(meta = city.meta + (CountyWorks.META_KEY to
                    CountyWorks(null, listOf(CompletedWork(DomesticWork.FORTIFICATION, world.phaseNow().plus(1)))).toMetaValue())))
            }
            val before = world.getCityById(10)!!
            val stock = CountyWarehouse.read(before.meta, 10)!!.stock
            WorkReductionExecutor(world, recorder, context).applyPending(10, next(world))
            val after = world.getCityById(10)!!
            assertEquals(before.defence, after.defence, change); assertEquals(before.wall, after.wall, change)
            assertEquals(before.meta[CountyWorks.META_KEY], after.meta[CountyWorks.META_KEY], change)
            assertEquals(stock, CountyWarehouse.read(after.meta, 10)!!.stock, change)
            assertNotNull(WorkReductionState.read(after.meta)!!.last!!.reason, change)
            val settled = after
            WorkReductionExecutor(world, recorder, context).applyPending(10, world.phaseNow())
            assertEquals(settled, world.getCityById(10), change)
        }
    }

    @Test fun `zero floor preserves other completed works and old replay ids after rebuilding`() {
        val world = completedWorld(); val recorder = ChangeRecorder()
        val city = world.getCityById(10)!!
        val other = CompletedWork(DomesticWork.WAREHOUSE, world.phaseNow())
        val fort = CompletedWork(DomesticWork.FORTIFICATION, world.phaseNow())
        world.applyCityDirtyFree(city.copy(defence = 200, wall = 300, meta = city.meta +
            (CountyWorks.META_KEY to CountyWorks(null, listOf(fort, other)).toMetaValue())))
        assertTrue(submit(world, recorder).ok)
        WorkReductionExecutor(world, recorder, context).applyPending(10, world.phaseNow())
        assertEquals(200, world.getCityById(10)!!.defence)
        WorkReductionExecutor(world, recorder, context).applyPending(10, next(world))
        val reduced = world.getCityById(10)!!
        assertEquals(0, reduced.defence); assertEquals(0, reduced.wall)
        assertEquals(listOf(other), CountyWorks.read(reduced.meta)!!.completed)
        world.applyCityDirtyFree(reduced.copy(meta = reduced.meta + (CountyWorks.META_KEY to
            CountyWorks(null, listOf(other, fort.copy(completedAt = next(world)))).toMetaValue())))
        val rebuilt = world.getCityById(10)!!
        assertEquals("UNCHANGED", submit(world, recorder).code)
        assertEquals(rebuilt, world.getCityById(10))
    }
}
