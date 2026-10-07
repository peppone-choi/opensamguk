package opensamguk.engine.campaign

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import opensamguk.logic.input.Phase
import opensamguk.logic.vision.ScoutReport
import opensamguk.logic.vision.ScoutReports
import opensamguk.logic.vision.ScoutedCity
import opensamguk.logic.vision.VisionTier
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Retainer
import opensamguk.logic.input.DeploymentRequest
import opensamguk.logic.input.MusterRules
import opensamguk.logic.input.PeopleInput
import opensamguk.logic.input.CaptiveState
import opensamguk.logic.input.TalentDiscovery

class NpcObservationTest {
    private val fixture = CampaignWorldFixture()
    private val route = fixture.route()
    private val index = fixture.bundle.commanderyIndex
    private val factory = NpcObservationFactory(fixture.topology, fixture.metrics, index,
        domesticContext = DomesticContext())
    private val hiddenCounty = fixture.bundle.projection.administrativeCountyIds.sorted().first { id ->
        val province = fixture.bundle.projection.bindingsByCityId[id]?.landProvinceId
        province != null && index.commanderyOf(province) != index.commanderyOf(route.start.id)
    }
    private val hiddenProvince = fixture.bundle.projection.bindingsByCityId.getValue(hiddenCounty).landProvinceId!!
    private val hiddenCommandery = index.commanderyOf(hiddenProvince)!!

    @Test
    fun `local search exposes availability without undiscovered identities or private meta`() {
        val actor = fixture.person(1, 1, route.startCity)
        val free = fixture.person(3, 0, route.startCity, lord = false).let {
            it.copy(meta = it.meta + ("privateOrder" to "must-not-cross"))
        }
        val world = fixture.world(listOf(actor to route.start, free to route.start), wars = emptyList(),
            cityChanges = { if (it.id == route.startCity) it.copy(nationId = 1) else it })
        val observation = assertNotNull(factory.build(world, actor.id))

        assertEquals(NpcPeopleActions(emptyList(), emptyList(), true), observation.peopleActions)
        assertEquals(listOf(actor.id), assertNotNull(observation.domestic).people.map { it.id })
        assertEquals(listOf(actor.id), observation.ownDeployment.people.map { it.id })
        assertEquals(emptySet(), TalentDiscovery.read(observation.actor.meta))

        world.applyGeneralDirtyFree(actor.copy(meta = TalentDiscovery.add(actor.meta, free.id)))
        val discovered = assertNotNull(factory.build(world, actor.id))
        assertEquals(NpcPeopleActions(emptyList(), listOf(free.id), false), discovered.peopleActions)
        assertEquals(listOf(actor.id), assertNotNull(discovered.domestic).people.map { it.id })
    }

    @Test
    fun `discovery does not expose or recruit a person outside the current location`() {
        val actor = fixture.person(1, 1, route.startCity).let {
            it.copy(meta = TalentDiscovery.add(it.meta, 3))
        }
        val remote = fixture.person(3, 0, hiddenCounty, lord = false)
        fun observe(charm: Int) = assertNotNull(factory.build(fixture.world(
            listOf(actor to route.start, remote.copy(stats = remote.stats.copy(charm = charm)) to
                opensamguk.logic.world.StrategicNodeRef.LandProvince(hiddenProvince)),
            wars = emptyList(),
            cityChanges = { if (it.id == route.startCity) it.copy(nationId = 1) else it }), actor.id))
        val first = observe(10)
        val changed = observe(100)

        assertEquals(VisionTier.FOG, first.vision.tierOf(hiddenCommandery))
        assertEquals(NpcPeopleActions(emptyList(), emptyList(), false), first.peopleActions)
        assertEquals(first.peopleActions, changed.peopleActions)
        assertEquals(first.domestic, changed.domestic)
    }

    @Test
    fun `local person held by another master is not a search or recruit candidate`() {
        val actor = fixture.person(1, 1, route.startCity).let {
            it.copy(meta = TalentDiscovery.add(it.meta, 3))
        }
        val held = fixture.person(3, 0, route.startCity, lord = false)
        val master = fixture.person(4, 0, hiddenCounty, lord = false)
        val card = Retainer(5, master.id, "EXISTING", held.id, held.name, "guest")
        val world = fixture.world(listOf(actor to route.start, held to route.start, master to
            opensamguk.logic.world.StrategicNodeRef.LandProvince(hiddenProvince)),
            retainers = listOf(card), wars = emptyList(),
            cityChanges = { if (it.id == route.startCity) it.copy(nationId = 1) else it })

        assertEquals(NpcPeopleActions(emptyList(), emptyList(), false),
            assertNotNull(factory.build(world, actor.id)).peopleActions)
    }

    @Test
    fun `eligible captive persuasion preempts a discovered recruit`() {
        val actor = fixture.person(1, 1, route.startCity).let {
            it.copy(meta = TalentDiscovery.add(it.meta, 3))
        }
        val recruit = fixture.person(3, 0, route.startCity, lord = false)
        val captive = fixture.person(4, 2, route.startCity, lord = false).let {
            it.copy(meta = it.meta + (CaptiveState.META_KEY to CaptiveState(actor.id, route.start.id,
                Phase(200, 1, 1), "battle-4").toMetaValue()))
        }
        val world = fixture.world(listOf(actor to route.start, recruit to route.start, captive to route.start),
            wars = emptyList(),
            cityChanges = { if (it.id == route.startCity) it.copy(nationId = 1) else it })
        val observation = assertNotNull(factory.build(world, actor.id))
        val selector = NpcPeopleSelector(DomesticContext())
        val chosen = selector.select(observation, actor.id, CampaignWorldFixture.NO_INPUT)

        assertEquals(listOf(captive.id), assertNotNull(observation.peopleActions).captiveIds)
        assertEquals(listOf(recruit.id), observation.peopleActions.recruitIds)
        assertEquals(PeopleInput.PERSUADE_CAPTIVE, chosen.actionCode)
        assertEquals(selector.select(world, actor.id, CampaignWorldFixture.NO_INPUT), chosen)
    }

    private fun observed(enemyDefence: Int, enemyTroops: Int, reports: ScoutReports? = null) =
        fixture.world(
            generals = listOf(
                fixture.person(1, 1, route.startCity, lord = true).let { actor ->
                    actor.copy(meta = if (reports == null) actor.meta else
                        actor.meta + (ScoutReports.META_KEY to reports.toMetaValue()))
                } to route.start,
                fixture.person(2, 2, hiddenCounty) to opensamguk.logic.world.StrategicNodeRef.LandProvince(hiddenProvince),
            ),
            bugoks = listOf(fixture.unit(7, 1, 1_000), fixture.unit(8, 2, enemyTroops)),
            cityChanges = { city -> if (city.id == hiddenCounty) city.copy(nationId = 2, defence = enemyDefence)
                else city },
        )

    @Test
    fun `FOG county and foreign troops never enter NPC observation`() {
        val first = assertNotNull(factory.build(observed(100, 500), 1))
        val changed = assertNotNull(factory.build(observed(9_000, 50_000), 1))

        assertEquals(VisionTier.FOG, first.vision.tierOf(hiddenCommandery))
        assertEquals(VisionTier.FOG, changed.vision.tierOf(hiddenCommandery))
        assertTrue(first.foreignCounties.none { it.cityId == hiddenCounty })
        assertTrue(changed.foreignCounties.none { it.cityId == hiddenCounty })
        assertTrue(first.corps.none { it.ownerGeneralId == 2 })
        assertEquals(first.ownUnits, changed.ownUnits)
        assertEquals(first.ownDeployment, changed.ownDeployment)
        assertEquals(first.musterMeta, changed.musterMeta)
        assertEquals(first.foreignCounties, changed.foreignCounties)
        val domestic = assertNotNull(first.domestic)
        assertEquals(listOf(1), domestic.people.map { it.id })
        assertTrue(domestic.counties.none { it.id == hiddenCounty })
        assertEquals(listOf(1), domestic.nations.map { it.id })
    }

    @Test
    fun `INTEL county uses only the old scout notebook`() {
        val seen = ScoutReports(index.tilesContentHash, listOf(ScoutReport(
            index.commanderies[hiddenCommandery].id,
            Phase(200, 1, 1),
            listOf(ScoutedCity(hiddenCounty, 2, true)),
            emptyList(),
        )))
        val observation = assertNotNull(factory.build(observed(9_000, 50_000, seen), 1))
        assertEquals(VisionTier.INTEL, observation.vision.tierOf(hiddenCommandery))
        assertEquals(listOf(NpcCountySighting(hiddenCounty, 2, hiddenProvince, VisionTier.INTEL,
            Phase(200, 1, 1), true)), observation.foreignCounties.filter { it.cityId == hiddenCounty })
        assertTrue(ScoutReports.META_KEY !in observation.actor.meta)
        assertTrue(assertNotNull(observation.domestic).people.all { ScoutReports.META_KEY !in it.meta })
    }

    @Test
    fun `future and other-map scout reports do not grant NPC sight`() {
        val row = ScoutReport(index.commanderies[hiddenCommandery].id, Phase(200, 1, 2),
            listOf(ScoutedCity(hiddenCounty, 2, true)), emptyList())
        val future = assertNotNull(factory.build(observed(100, 500,
            ScoutReports(index.tilesContentHash, listOf(row))), 1))
        assertEquals(VisionTier.FOG, future.vision.tierOf(hiddenCommandery))
        assertTrue(future.foreignCounties.none { it.cityId == hiddenCounty })
        assertTrue(future.vision.reports?.reports.orEmpty().isEmpty())
        assertTrue(ScoutReports.META_KEY !in future.actor.meta)

        val otherHash = if (index.tilesContentHash == "0".repeat(64)) "f".repeat(64) else "0".repeat(64)
        val otherMap = assertNotNull(factory.build(observed(100, 500,
            ScoutReports(otherHash, listOf(row.copy(seenAt = Phase(200, 1, 1))))), 1))
        assertEquals(VisionTier.FOG, otherMap.vision.tierOf(hiddenCommandery))
        assertTrue(otherMap.foreignCounties.none { it.cityId == hiddenCounty })
        assertTrue(otherMap.vision.reports == null)
    }

    @Test
    fun `damaged scout notebook is excluded from every selector-facing field`() {
        val world = observed(100, 500)
        val actor = assertNotNull(world.getGeneralById(1))
        world.applyGeneralDirtyFree(actor.copy(meta = actor.meta + (ScoutReports.META_KEY to mapOf("bad" to true))))
        val observation = assertNotNull(factory.build(world, 1))
        assertEquals(VisionTier.FOG, observation.vision.tierOf(hiddenCommandery))
        assertEquals(null, observation.vision.reports)
        assertTrue(ScoutReports.META_KEY !in observation.actor.meta)
        assertTrue(assertNotNull(observation.domestic).people.all { ScoutReports.META_KEY !in it.meta })
    }

    @Test
    fun `only active unowned NPC gets an observation`() {
        val world = observed(100, 500)
        assertEquals(null, factory.build(world, 999_999))
        val actor = assertNotNull(world.getGeneralById(1))
        world.applyGeneralDirtyFree(actor.copy(userId = "42"))
        assertEquals(null, factory.build(world, 1))
        world.applyGeneralDirtyFree(actor.copy(npcState = 1))
        assertEquals(null, factory.build(world, 1))
        world.applyGeneralDirtyFree(actor)
        assertNotNull(factory.build(world, 1))
    }

    @Test
    fun `own county and personal selectors agree with their authoritative precheck`() {
        val world = observed(9_000, 50_000)
        val observation = assertNotNull(factory.build(world, 1))
        val row = ReservedTurn("휴식", "{}", rowExists = false)
        val context = DomesticContext()
        assertEquals(NpcCityMilitarySelector(context).select(world, 1, row),
            NpcCityMilitarySelector(context).select(observation, 1, row))
        assertEquals(NpcFieldSelector(context).select(world, 1, row),
            NpcFieldSelector(context).select(observation, 1, row))
        assertEquals(NpcPersonalSelector(context).select(world, 1, row),
            NpcPersonalSelector(context).select(observation, 1, row))
        assertEquals(NpcPeopleSelector(context).select(world, 1, row),
            NpcPeopleSelector(context).select(observation, 1, row))
    }

    @Test
    fun `muster assessment keeps owned corps and public passage without enemy authority`() {
        val owner = fixture.person(1, 1, route.startCity)
        val lieutenant = fixture.person(3, 1, route.startCity, lord = false)
        val enemy = fixture.person(2, 2, hiddenCounty)
        val card = Retainer(4, 1, "EXISTING", 3, "G3", "lieutenant", hasOwnBugok = true)
        val world = fixture.world(
            generals = listOf(owner to route.start, lieutenant to route.start,
                enemy to opensamguk.logic.world.StrategicNodeRef.LandProvince(hiddenProvince)),
            bugoks = listOf(fixture.unit(7, 1, 1_000).copy(commanderRetainerId = 4), fixture.unit(8, 2, 50_000)),
            retainers = listOf(card),
        )
        val recorder = ChangeRecorder()
        val deployed = DeploymentExecutor(world, recorder, fixture.topology, fixture.metrics)
            .deploy("own-muster", DeploymentRequest(1, 4, listOf(7)))
        assertTrue(deployed is DeploymentExecution.Applied)
        recorder.moveGeneral(world, 3, route.destination)

        val observation = assertNotNull(factory.build(world, 1))
        assertEquals(setOf(1, 3), observation.ownDeployment.people.map { it.id }.toSet())
        assertEquals(listOf(7), observation.ownDeployment.units.map { it.id })
        assertEquals(listOf("own-muster"), observation.ownDeployment.deployed.map { it.orderId })
        val meta = assertNotNull(observation.musterMeta)
        assertEquals(setOf("landPassage", "marchReactions"), meta.keys)
        val authoritative = MusterRules.assess(1, assertNotNull(
            DeploymentExecutor(world, ChangeRecorder(), fixture.topology, fixture.metrics).projection()),
            fixture.topology, fixture.metrics, world.getState().meta)
        val owned = MusterRules.assess(1, observation.ownDeployment, fixture.topology, fixture.metrics, meta)
        assertEquals(authoritative, owned)
    }
}
