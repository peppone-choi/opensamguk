package opensamguk.engine.politics

import kotlin.test.*
import opensamguk.engine.campaign.*
import opensamguk.engine.turn.*
import opensamguk.logic.input.*

/** action.rise: Approved rise policy: no renown gate, one unowned county, personal military assets survive. */
class RiseTransitionTest {
    private val fixture = CampaignWorldFixture()

    @Test fun `rise transfers the complete retinue and owned military assets without a renown requirement`() {
        val route = fixture.route()
        val actor = fixture.person(1, 0, route.startCity, userId = "42", lord = false).copy(
            gold = 321, rice = 654, crew = 987, troopId = 1, officerLevel = 3,
            meta = mapOf(LordStatus.META_KEY to false, "keep" to "personal", "makelimit" to 37,
                "officer_city" to route.startCity, "permission" to "auditor", "belong" to 9,
                CountyAssignment.META_KEY to CountyAssignment("old-office", 9, 1, route.startCity).toMetaValue()))
        val follower = fixture.person(2, 0, route.startCity, lord = false).copy(troopId = 1,
            gold = 12, rice = 34, crew = 56, officerLevel = 6)
        val grandchild = fixture.person(3, 0, route.startCity, lord = false).copy(troopId = 3)
        val outsider = fixture.person(4, 2, route.startCity, lord = false)
        val cards = listOf(Retainer(4, 1, "EXISTING", 2, "G2", "guest"),
            Retainer(5, 2, "EXISTING", 3, "G3", "guest"), Retainer(6, 1, "GENERATED", null, "card", "guest"))
        val units = listOf(fixture.unit(7, 1, 100).copy(fatigue = 17, commanderRetainerId = 4),
            fixture.unit(8, 2, 200), fixture.unit(9, 3, 300))
        val world = fixture.world(listOf(actor to route.start, follower to route.start,
            grandchild to route.start, outsider to route.start), retainers = cards, bugoks = units)
        world.createTroop(Troop(1, 0, "founder troop"))
        world.createTroop(Troop(3, 0, "descendant troop"))
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, 2, listOf(8), route.destination)
        world.consumeDirtyState()
        val beforePeople = world.listGenerals().associateBy { it.id }
        val beforeCities = world.listCities().associateBy { it.id }
        val oldNations = world.listNations()
        val oldDiplomacy = world.listDiplomacy()
        val handler = PoliticalHandler(world, recorder, DomesticContext())

        val result = assertIs<TurnOutcome.Applied>(handler.handle(PoliticalInput.RISE, 1, "{}", "rise-assets", 42))

        val nation = world.listNations().single { it.id !in oldNations.map(Nation::id) }
        assertEquals(listOf("nationId:${nation.id}", "countyId:${route.startCity}"), result.effects)
        assertEquals(1, nation.chiefGeneralId)
        assertEquals(route.startCity, nation.capitalCityId)
        assertEquals(3, nation.meta["gennum"])
        for (id in 1..3) {
            val before = beforePeople.getValue(id)
            val after = world.getGeneralById(id)!!
            assertEquals(nation.id, after.nationId)
            assertEquals(if (id == 1) 12 else 0, after.officerLevel)
            assertEquals(id == 1, LordStatus.read(after.meta))
            assertEquals(before.copy(nationId = after.nationId, officerLevel = after.officerLevel, meta = after.meta), after)
            for (key in listOf(CountyAssignment.META_KEY, DeploymentState.META_KEY, CorpsOrder.META_KEY,
                CorpsMarchState.META_KEY, DispatchState.META_KEY, QueuedCourtAction.META_KEY,
                QueuedDispatch.META_KEY)) assertFalse(key in after.meta, key)
        }
        assertEquals(37, world.getGeneralById(1)!!.meta["makelimit"])
        assertEquals("personal", world.getGeneralById(1)!!.meta["keep"])
        assertEquals(outsider, world.getGeneralById(4))
        assertEquals(cards, world.listRetainers())
        assertEquals(units, world.listBugoks())
        assertEquals(listOf(Troop(1, nation.id, "founder troop"), Troop(3, nation.id, "descendant troop")), world.listTroops())
        assertEquals(oldNations, world.listNations().filter { it.id != nation.id })
        assertEquals(oldDiplomacy, world.listDiplomacy().filter { it.fromNationId != nation.id && it.toNationId != nation.id })
        assertEquals(oldNations.size * 2, world.listDiplomacy().count { it.fromNationId == nation.id || it.toNationId == nation.id })
        assertTrue(world.listDiplomacy().filter { it.fromNationId == nation.id || it.toNationId == nation.id }.all { it.state == 2 })
        for (city in world.listCities()) assertEquals(beforeCities.getValue(city.id).let {
            if (city.id == route.startCity) it.copy(nationId = nation.id) else it }, city)
        val dirty = world.consumeDirtyState()
        assertTrue(dirty.deletedTroops.isEmpty())
        assertEquals(listOf(1, 3), dirty.troops.map { it.id })
        assertEquals(result, handler.handle(PoliticalInput.RISE, 1, "{}", "rise-assets", 42))
        assertEquals(PoliticalFailure.ALREADY_PROCESSED.name,
            assertIs<TurnOutcome.Rejected>(handler.handle(PoliticalInput.RISE, 1, "{}", "different", 42)).code)
        assertEquals(oldNations.size + 1, world.listNations().size)
        assertTrue(world.consumeDirtyState().nations.isEmpty())
    }

    @Test fun `corrupt deployment refuses rise before allocating any nation or altering assets`() {
        val route = fixture.route()
        val actor = fixture.person(1, 0, route.startCity, userId = "42", lord = false).let {
            it.copy(meta = it.meta + (DeploymentState.META_KEY to "broken")) }
        val world = fixture.world(listOf(actor to route.start), bugoks = listOf(fixture.unit(7, 1, 100)))
        val state = world.getState()
        val nations = world.listNations()
        val recorder = ChangeRecorder()
        assertEquals(PoliticalFailure.STATE_UNAVAILABLE.name,
            assertIs<TurnOutcome.Rejected>(PoliticalHandler(world, recorder, DomesticContext())
                .handle(PoliticalInput.RISE, 1, "{}", "rise-corrupt", 42)).code)
        assertEquals(actor, world.getGeneralById(1))
        assertEquals(state, world.getState())
        assertEquals(nations, world.listNations())
        assertTrue(recorder.dirtyGeneralIds().isEmpty())
        assertTrue(world.consumeDirtyState().nations.isEmpty())
    }
}
