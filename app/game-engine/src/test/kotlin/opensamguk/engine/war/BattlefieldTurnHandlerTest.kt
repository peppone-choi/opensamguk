package opensamguk.engine.war

import opensamguk.common.world.WorldId
import opensamguk.engine.turn.*
import opensamguk.logic.world.*
import opensamguk.logic.ai.ChosenCommand
import java.time.Instant
import kotlin.test.*

class BattlefieldTurnHandlerTest {
    private val hash = "a".repeat(64)
    private val catalogHash = "b".repeat(64)
    private val node = StrategicNodeRef.LandProvince("45776")
    private fun general(id: Int, nation: Int = 1, crew: Int = 10000) = TurnGeneral(
        id = id, name = "G$id", nationId = nation, cityId = 405, troopId = 0, stats = GeneralStats(80, 80, 80),
        experience = 0, dedication = 0, officerLevel = 0,
        turnTime = Instant.EPOCH, crew = crew, rice = 10000, train = 100, atmos = 100, crewTypeId = 1100,
    )
    private fun world(defender: Boolean = false): InMemoryTurnWorld {
        val positions = if (defender) listOf(GeneralPositionState("r1", hash, 2, node, 1,
            BattlefieldPresence("changban", catalogHash, 405))) else emptyList()
        return InMemoryTurnWorld(WorldSnapshot(
            TurnWorldState(1, 200, 1, 60, Instant.EPOCH, config = mapOf("mapName" to "han-world-v3")),
            worldId = WorldId(1), generals = if (defender) listOf(general(1), general(2, 2, 1)) else listOf(general(1)),
            cities = listOf(City(405, "당양", 1, 5)),
            diplomacy = listOf(TurnDiplomacy(1, 2, 0, 0)),
            generalPositionSnapshot = GeneralPositionSnapshot("r1", hash, setOf("45776", "other"), emptySet(), positions),
        ))
    }
    private fun handler(world: InMemoryTurnWorld, recorder: ChangeRecorder) = BattlefieldTurnHandler(
        world, recorder,
        BattlefieldCatalog(catalogHash, listOf(BattlefieldCatalogEntry("changban", "장판", node, 405, BattlefieldRole.FIELD))),
        mapOf(405 to node), "test-seed",
    )
    private fun args(site: String = "changban", revision: String = "") = mapOf(
        "siteId" to site, "catalogHash" to catalogHash, "expectedRevision" to revision,
    )

    @Test fun `empty entry and exit persist presence and physical city membership`() {
        val world = world(); val recorder = ChangeRecorder(); val handler = handler(world, recorder)
        assertTrue(handler.execute(1, args(), 200, 1).allowed)
        assertFalse(world.isGeneralPhysicallyInCity(1, 405))
        assertEquals("changban", world.generalPositionSnapshot()!!.stateFor(1)!!.battlefield!!.siteId)
        assertTrue(handler.execute(1, args("", "1"), 200, 1).allowed)
        assertTrue(world.isGeneralPhysicallyInCity(1, 405))
        assertEquals(2L, recorder.generalPositionWrites().single().state.revision)
    }

    @Test fun `stale entry leaves all state unchanged`() {
        val world = world(); val recorder = ChangeRecorder()
        assertFalse(handler(world, recorder).execute(1, args(revision = "99"), 200, 1).allowed)
        assertFalse(recorder.isDirty)
        assertNull(world.generalPositionSnapshot()!!.stateFor(1))
    }

    @Test fun `hostile site arrival resolves real casualties and defeated defender returns`() {
        val world = world(true); val recorder = ChangeRecorder()
        val result = handler(world, recorder).execute(1, args(), 200, 1)
        assertTrue(result.allowed)
        assertNotNull(result.combat)
        assertTrue(world.getGeneralById(2)!!.crew < 1)
        assertNull(world.generalPositionSnapshot()!!.stateFor(2)!!.battlefield)
        assertNotNull(world.generalPositionSnapshot()!!.stateFor(1)!!.battlefield)
        assertEquals(1, world.getCityById(405)!!.nationId)
    }
    @Test fun `field actor ordinary city action is denied without mutation`() {
        val world = world(); val recorder = ChangeRecorder()
        handler(world, recorder).execute(1, args(), 200, 1)
        recorder.clear()
        val before = world.getGeneralById(1)
        val reserved = ReservedTurnHandler(world, opensamguk.logic.actions.CommandRegistry(opensamguk.logic.stats.GeneralActionPipeline()), "seed", 200, recorder = recorder)
        val result = reserved.handle(1, "che_훈련", 200, 1, "00:00")
        assertTrue(result.fellBack)
        assertNotNull(result.denyReason)
        assertEquals(before, world.getGeneralById(1))
        assertFalse(recorder.isDirty)
    }

    @Test fun `city defenders exclude deployed generals and logic view hides their city occupancy`() {
        val world = world(true)
        val context = BattleCommandContextBuilder.build(world, 1, 405, "seed", 200, 1)
        assertTrue(context.defenderGeneralsByCity.values.flatten().none { it.id == 2 })
        assertEquals(0, PerTurnOverlay(world).getLogicGeneral(2)!!.cityId)
        assertEquals(405, world.getGeneralById(2)!!.cityId, "return origin is retained, never persisted as physical city")
    }

    @Test fun `exit then city movement and return keeps position valid for reentry`() {
        val world = world(); val recorder = ChangeRecorder(); val handler = handler(world, recorder)
        handler.execute(1, args(), 200, 1)
        handler.execute(1, args("", "1"), 200, 1)
        val away = StrategicNodeRef.LandProvince("other")
        val anchors = { mapOf(405 to node, 406 to away) }
        applyPositionAwareGeneral(world, recorder, world.getGeneralById(1)!!.copy(cityId = 406), anchors)
        applyPositionAwareGeneral(world, recorder, world.getGeneralById(1)!!.copy(cityId = 405), anchors)
        val revision = world.generalPositionSnapshot()!!.stateFor(1)!!.revision.toString()
        assertTrue(handler.execute(1, args(revision = revision), 200, 1).allowed)
    }

    @Test fun `invalid defender return rejects encounter before casualties or positions change`() {
        val world = world(true); val recorder = ChangeRecorder()
        val previous = world.generalPositionSnapshot()!!.stateFor(2)!!
        recorder.applyGeneralPositionAssessment(world, previous.revision, GeneralPositionAssessment("r1", hash, 2, node,
            BattlefieldPresence("changban", catalogHash, 999)))
        recorder.clear()
        val before = world.listGenerals()
        assertFalse(handler(world, recorder).execute(1, args(), 200, 1).allowed)
        assertEquals(before, world.listGenerals())
        assertFalse(recorder.isDirty)
        assertNull(world.generalPositionSnapshot()!!.stateFor(1))
    }

    @Test fun `entry requires troops even at an empty site`() {
        val world = world(); val recorder = ChangeRecorder()
        world.applyGeneralDirtyFree(world.getGeneralById(1)!!.copy(crew = 0))
        assertFalse(handler(world, recorder).execute(1, args(), 200, 1).allowed)
        assertFalse(recorder.isDirty)
    }

    @Test fun `simultaneous troop exhaustion returns both sides`() {
        val world = world(true); val recorder = ChangeRecorder()
        world.applyGeneralDirtyFree(world.getGeneralById(1)!!.copy(crew = 1))
        val result = handler(world, recorder).execute(1, args(), 200, 1)
        assertTrue(result.allowed)
        assertEquals(0, world.getGeneralById(1)!!.crew)
        assertEquals(0, world.getGeneralById(2)!!.crew)
        assertNull(world.generalPositionSnapshot()!!.stateFor(1)?.battlefield)
        assertNull(world.generalPositionSnapshot()!!.stateFor(2)?.battlefield)
    }

    @Test fun `queued nation command cannot use deployed actors return city`() {
        val world = world(); val recorder = ChangeRecorder()
        handler(world, recorder).execute(1, args(), 200, 1)
        recorder.clear()
        val city = world.getCityById(405)
        val last = opensamguk.logic.domain.LastTurn()
        val processor = ProcessNationCommand(world, recorder, "seed",
            opensamguk.logic.actions.CommandRegistry(opensamguk.logic.stats.GeneralActionPipeline()), 200)
        assertEquals(last, processor.process(1, 12, ChosenCommand("che_초토화", emptyMap()), last, 200, 1, "00:00"))
        assertEquals(city, world.getCityById(405))
        assertFalse(recorder.isDirty)
        assertTrue(world.peekLogs().last().text.contains("전장에서 귀환한 뒤"))
        assertIs<ProcessNationCommand.InstantResult.Denied>(processor.processInstant(1, ChosenCommand("che_초토화", emptyMap())))
    }

    @Test fun `external city arrival clears obsolete position and forced release revokes deployment`() {
        val world = world(); val recorder = ChangeRecorder(); val handler = handler(world, recorder)
        handler.execute(1, args(), 200, 1)
        handler.execute(1, args("", "1"), 200, 1)
        applyPositionAwareGeneral(world, recorder, world.getGeneralById(1)!!.copy(cityId = 704), { emptyMap() })
        assertEquals(704, world.getGeneralById(1)!!.cityId)
        assertNull(world.generalPositionSnapshot()!!.stateFor(1))
        // New insert then revocation cancels the unflushed position instead of producing a bogus DELETE.
        assertTrue(recorder.generalPositionWrites().isEmpty())
        applyPositionAwareGeneral(world, recorder, world.getGeneralById(1)!!.copy(cityId = 405))
        handler.execute(1, args(), 200, 1)
        applyPositionAwareGeneral(world, recorder, world.getGeneralById(1)!!.copy(nationId = 0))
        assertNull(world.generalPositionSnapshot()!!.stateFor(1))
        assertEquals(0, world.getGeneralById(1)!!.nationId)
    }

    @Test fun `reserved battlefield command enters through real turn dispatch`() {
        val world = world(); val recorder = ChangeRecorder()
        val reserved = ReservedTurnHandler(world,
            opensamguk.logic.actions.CommandRegistry(opensamguk.logic.stats.GeneralActionPipeline()), "seed", 200,
            recorder = recorder,
            battlefieldCatalog = { BattlefieldCatalog(catalogHash, listOf(BattlefieldCatalogEntry("changban", "장판", node, 405, BattlefieldRole.FIELD))) },
            battlefieldCityAnchors = { mapOf(405 to node) })
        val result = reserved.handle(1, opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn(actionCode = "che_전장이동",
            argJson = """{"siteId":"changban","catalogHash":"$catalogHash","expectedRevision":""}"""), 200, 1, "00:00")
        assertFalse(result.fellBack)
        assertNull(result.denyReason)
        assertEquals("changban", world.generalPositionSnapshot()!!.stateFor(1)!!.battlefield!!.siteId)
        assertEquals(1, recorder.generalPositionWrites().size)
    }

    @Test fun `committed deletion and reload cannot reuse an old deployment revision`() {
        val world = world(); val recorder = ChangeRecorder()
        handler(world, recorder).execute(1, args(), 200, 1)
        recorder.clear() // prior deployment has committed
        applyPositionAwareGeneral(world, recorder, world.getGeneralById(1)!!.copy(nationId = 0))
        val savedGeneral = world.getGeneralById(1)!!
        assertEquals(1L, (savedGeneral.meta["spatialPositionRevisionFloor"] as Number).toLong())
        assertTrue(recorder.generalPositionWrites().single().delete)
        val payload = opensamguk.engine.flush.DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        assertEquals(1L, (recorder.generalPatches().single().meta["spatialPositionRevisionFloor"] as Number).toLong())
        assertTrue(payload.generalPositionWrites.single().delete)
        assertEquals(1L, (payload.updatedGenerals.single().meta["spatialPositionRevisionFloor"] as Number).toLong())
        // Reload only persisted general metadata and the now absent spatial row.
        val reloaded = world()
        reloaded.applyGeneralDirtyFree(savedGeneral)
        val nextRecorder = ChangeRecorder()
        assertTrue(handler(reloaded, nextRecorder).execute(1, args(), 200, 1).allowed)
        assertEquals(2L, reloaded.generalPositionSnapshot()!!.stateFor(1)!!.revision)
        assertFalse(handler(reloaded, nextRecorder).execute(1, args("", "1"), 200, 1).allowed)
        assertNotNull(reloaded.generalPositionSnapshot()!!.stateFor(1)!!.battlefield)
    }

}
