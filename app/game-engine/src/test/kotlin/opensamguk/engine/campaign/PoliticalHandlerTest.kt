package opensamguk.engine.campaign

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Retainer
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.Operation
import opensamguk.engine.turn.OperationUnit
import opensamguk.engine.turn.BattlePlan
import opensamguk.engine.turn.Siege
import opensamguk.engine.turn.Troop
import opensamguk.engine.siege.RoadFortSiegeService
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.logic.domestic.ActivePlacement
import opensamguk.logic.domestic.PlacementOrder
import opensamguk.logic.domestic.PlacementPost
import opensamguk.logic.domestic.PlacementState
import opensamguk.logic.domestic.PlacementTarget
import opensamguk.logic.input.*
import opensamguk.logic.vision.ScoutPosts

class PoliticalHandlerTest {
    private val fixture = CampaignWorldFixture()

    private fun catalogWithResign(state: String): InputCatalog {
        val original = checkNotNull(javaClass.classLoader.getResource("command-catalog/input-catalog.json")).readText()
        val row = Regex("""("inputId":\s*"action\.resign"[\s\S]*?"deliveryState":\s*")(PLANNED|DOMAIN_READY|HANDLER_READY|UI_READY)(")""")
        val updated = row.replace(original, "${'$'}1$state${'$'}3")
        assertTrue(row.containsMatchIn(original))
        return InputCatalog.parse(updated)
    }

    private fun deliveredCatalog() = catalogWithResign("HANDLER_READY")

    private fun roadFort(route: CampaignWorldFixture.Route): RoadFort {
        val edge = fixture.topology.traversalEdges.first { (it.from == route.start && it.to == route.first) ||
            (!it.directed && it.from == route.first && it.to == route.start) }
        return RoadFort(RoadFort.siteId(edge.id, 0, 0), edge.id, route.start.id, 0, 0,
            ownerNationId = 2, wall = 100, garrison = 0)
    }

    @Test fun `approved rise creates one nation at the current unowned county`() {
        val route = fixture.route()
        val base = fixture.person(1011, 0, route.startCity, userId = "42", lord = false)
        val policy = PersonPolicyState(50, true, "test", "1", base.id)
        val actor = base.copy(meta = base.meta + (PersonPolicyState.META_KEY to policy.toMetaValue()))
        val world = fixture.world(listOf(actor to route.start))
        val handler = PoliticalHandler(world, ChangeRecorder(), DomesticContext())
        val result = assertIs<TurnOutcome.Applied>(handler.handle(PoliticalInput.RISE,
            actor.id, "{}", "rise-1011", 42))
        assertEquals(listOf("nationId:3", "countyId:${route.startCity}"), result.effects)
        assertEquals(3, world.getGeneralById(actor.id)!!.nationId)
        assertTrue(LordStatus.read(world.getGeneralById(actor.id)!!.meta))
        assertEquals(3, world.listNations().size)
    }

    @Test fun `resignation waits for military and appointment cleanup`() {
        val route = fixture.route()
        val actor = fixture.person(1021, 1, route.startCity, userId = "42", lord = false)
        val follower = fixture.person(1022, 1, route.startCity, lord = false)
        val world = fixture.world(listOf(actor to route.start, follower to route.start),
            retainers = listOf(Retainer(31, actor.id, "EXISTING", follower.id, follower.name, "guest")))
        assertEquals(InputRejection.NOT_DELIVERED.name,
            assertIs<TurnOutcome.Rejected>(PoliticalHandler(world, ChangeRecorder(), DomesticContext(),
                catalogWithResign("PLANNED"))
                .handle(PoliticalInput.RESIGN, actor.id, "{}", "resign-1021", 42)).code)
        assertEquals(1, world.getGeneralById(actor.id)!!.nationId)
        assertEquals(1, world.getGeneralById(follower.id)!!.nationId)
        assertEquals(actor.id, world.listRetainers().single().masterGeneralId)
    }

    @Test fun `resignation frees the retinue and removes former military and office authority`() {
        val route = fixture.route()
        val phase = Phase(200, 1, 1)
        val placement = PlacementOrder("place-actor", 1023, 32, PlacementPost.SCOUT,
            PlacementTarget.Province(route.start.id), phase)
        val actor = fixture.person(1021, 1, route.startCity, userId = "42", lord = false).copy(
            officerLevel = 3, troopId = 1021,
            meta = fixture.person(1021, 1, route.startCity, lord = false).meta +
                mapOf("officer_city" to route.startCity, "permission" to "ambassador", "belong" to 8,
                    QueuedCourtAction.META_KEY to QueuedCourtAction("old-court", 42, "court.releaseCorps", "{}").toMetaValue(),
                    CountyAssignment.META_KEY to CountyAssignment("old-dispatch", 1023, 1, route.startCity).toMetaValue(),
                    DispatchState.META_KEY to DispatchState("old-dispatch", 1023, 1021, 1, route.startCity,
                        phase, phase.plus(1)).toMetaValue(),
                    PlacementState.META_KEY to PlacementState(ActivePlacement(placement, phase, phase), null).toMetaValue()))
        val follower = fixture.person(1022, 1, route.startCity, lord = false).copy(
            officerLevel = 6, troopId = actor.id,
            meta = fixture.person(1022, 1, route.startCity, lord = false).meta +
                mapOf("permission" to "auditor", "officer_city" to route.startCity))
        val outsider = fixture.person(1023, 1, route.startCity, lord = true).copy(troopId = actor.id,
            meta = fixture.person(1023, 1, route.startCity, lord = true).meta +
                (ScoutPosts.META_KEY to mapOf("version" to 1, "posts" to listOf(mapOf(
                    "retainerId" to 32, "provinceId" to route.start.id, "status" to "ACTIVE")))))
        val nation = Nation(1, "N1", "#111111", chiefGeneralId = outsider.id,
            meta = mapOf("gennum" to 3, "chief_set" to (1 shl 6)))
        val world = fixture.world(listOf(actor to route.start, follower to route.start, outsider to route.start),
            nations = listOf(nation, Nation(2, "N2", "#222222")),
            retainers = listOf(Retainer(31, actor.id, "EXISTING", follower.id, follower.name, "guest"),
                Retainer(32, outsider.id, "EXISTING", actor.id, actor.name, "guest")),
            cityChanges = { if (it.id == route.startCity) it.copy(nationId = 1, officerSet = 1 shl 3) else it })
        world.createTroop(Troop(actor.id, 1, "old corps"))
        world.createOperation(Operation(41, 1, "ATTACK", route.destinationCounty, "old order",
            declaredByGeneralId = actor.id, declaredYear = 200, declaredMonth = 1,
            declaredPhase = 1, deadlineYear = 200, deadlineMonth = 2, status = "ACTIVE"))
        world.createOperationUnit(OperationUnit(42, 41, follower.id, role = "ATTACK", joinedCityId = route.startCity,
            joinedYear = 200, joinedMonth = 1, joinedPhase = 1))
        world.createBattlePlan(BattlePlan(43, actor.id, route.destinationCounty, "ATTACK"))
        world.consumeDirtyState() // Treat the setup rows as already flushed; resignation must emit durable deletes.
        val handler = PoliticalHandler(world, ChangeRecorder(), DomesticContext(), deliveredCatalog())

        val applied = assertIs<TurnOutcome.Applied>(handler.handle(PoliticalInput.RESIGN,
            actor.id, "{}", "resign-1021", 42))
        assertEquals(listOf("nationId:0"), applied.effects)
        for (id in listOf(actor.id, follower.id)) {
            val free = world.getGeneralById(id)!!
            assertEquals(0, free.nationId)
            assertEquals(0, free.officerLevel)
            assertEquals(0, free.troopId)
            assertEquals(0, free.meta["officer_city"])
            assertEquals("normal", free.meta["permission"])
        }
        assertEquals(0, world.getGeneralById(outsider.id)!!.troopId)
        assertNull(world.getTroopById(actor.id))
        assertEquals(listOf(31), world.listRetainers().map { it.id })
        assertNull(world.getOperationUnitById(42))
        assertNull(world.getBattlePlanById(43))
        assertNull(world.getOperationById(41)!!.declaredByGeneralId)
        assertEquals(1, world.getNationById(1)!!.meta["gennum"])
        assertEquals(1 shl 6, world.getNationById(1)!!.meta["chief_set"])
        assertEquals(1 shl 3, world.getCityById(route.startCity)!!.officerSet)
        assertFalse(QueuedCourtAction.META_KEY in world.getGeneralById(actor.id)!!.meta)
        assertFalse(CountyAssignment.META_KEY in world.getGeneralById(actor.id)!!.meta)
        assertFalse(DispatchState.META_KEY in world.getGeneralById(actor.id)!!.meta)
        assertFalse(PlacementState.META_KEY in world.getGeneralById(actor.id)!!.meta)
        assertFalse(ScoutPosts.META_KEY in world.getGeneralById(outsider.id)!!.meta)
        val dirty = world.consumeDirtyState()
        assertEquals(listOf(actor.id), dirty.deletedTroops)
        assertEquals(listOf(32), dirty.deletedRetainers)
        assertEquals(listOf(42), dirty.deletedOperationUnits)
        assertEquals(listOf(43), dirty.deletedBattlePlans)
        assertEquals(listOf(41), dirty.operations.map { it.id })
        assertEquals(applied, handler.handle(PoliticalInput.RESIGN, actor.id, "{}", "resign-1021", 42))
        assertEquals(PoliticalFailure.ALREADY_PROCESSED.name,
            assertIs<TurnOutcome.Rejected>(handler.handle(PoliticalInput.RESIGN,
                actor.id, "{}", "different", 42)).code)
    }

    @Test fun `resignation recalls a deployed corps and preserves its personal unit`() {
        val route = fixture.route()
        val actor = fixture.person(1071, 1, route.startCity, userId = "42", lord = false)
        val world = fixture.world(listOf(actor to route.start), bugoks = listOf(fixture.unit(71, actor.id, 100)))
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, actor.id, listOf(71), route.destination)
        assertTrue(DeploymentState.META_KEY in world.getGeneralById(actor.id)!!.meta)
        assertTrue(CorpsOrder.META_KEY in world.getGeneralById(actor.id)!!.meta)

        assertIs<TurnOutcome.Applied>(PoliticalHandler(world, recorder, DomesticContext(), deliveredCatalog())
            .handle(PoliticalInput.RESIGN, actor.id, "{}", "resign-deployed", 42))

        val free = world.getGeneralById(actor.id)!!
        assertEquals(0, free.nationId)
        assertFalse(DeploymentState.META_KEY in free.meta)
        assertFalse(CorpsOrder.META_KEY in free.meta)
        assertFalse(CorpsMarchState.META_KEY in free.meta)
        assertEquals(actor.id, world.getBugokById(71)!!.masterGeneralId)
        assertEquals(PoliticalFailure.NOT_A_SUBJECT,
            assertIs<PoliticalAssessment.Rejected>(PoliticalRules.assess(
                PoliticalRequest(actor.id, PoliticalInput.RESIGN), DomesticContext().projection(world))).reason)
    }

    @Test fun `resignation refuses corrupt deployment before changing allegiance or retainers`() {
        val route = fixture.route()
        val actor = fixture.person(1081, 1, route.startCity, userId = "42", lord = false).copy(
            meta = fixture.person(1081, 1, route.startCity, lord = false).meta +
                (DeploymentState.META_KEY to "broken"))
        val follower = fixture.person(1082, 1, route.startCity, lord = false)
        val world = fixture.world(listOf(actor to route.start, follower to route.start),
            retainers = listOf(Retainer(81, actor.id, "EXISTING", follower.id, follower.name, "guest")))

        val denied = assertIs<TurnOutcome.Rejected>(PoliticalHandler(world, ChangeRecorder(), DomesticContext(),
            deliveredCatalog()).handle(PoliticalInput.RESIGN, actor.id, "{}", "resign-corrupt", 42))

        assertEquals(PoliticalFailure.STATE_UNAVAILABLE.name, denied.code)
        assertEquals(1, world.getGeneralById(actor.id)!!.nationId)
        assertEquals(1, world.getGeneralById(follower.id)!!.nationId)
        assertEquals(actor.id, world.listRetainers().single().masterGeneralId)
    }

    @Test fun `active siege prevents resignation until its former nation command is settled`() {
        val route = fixture.route()
        val actor = fixture.person(1091, 1, route.startCity, userId = "42", lord = false)
        val world = fixture.world(listOf(actor to route.start))
        world.putSiege(Siege(route.destinationCounty, "ACTIVE", actor.id, actor.id, "old-siege", 1, 2,
            route.first.id, 200, 1, 1, morale = 50, garrison = 100))

        val denied = assertIs<TurnOutcome.Rejected>(PoliticalHandler(world, ChangeRecorder(), DomesticContext(),
            deliveredCatalog()).handle(PoliticalInput.RESIGN, actor.id, "{}", "resign-siege", 42))

        assertEquals(PoliticalFailure.STATE_UNAVAILABLE.name, denied.code)
        assertEquals(1, world.getGeneralById(actor.id)!!.nationId)
        assertEquals("ACTIVE", world.getSiege(route.destinationCounty)!!.status)
    }

    @Test fun `active road fort siege prevents resignation without changing its corps or retinue`() {
        val route = fixture.route()
        val fort = roadFort(route)
        val base = fixture.person(1092, 1, route.startCity, userId = "42", lord = false)
        val actor = base.copy(meta = base.meta + (QueuedCourtAction.META_KEY to
            QueuedCourtAction("queued-fort", 42, "court.releaseCorps", "{}").toMetaValue()))
        val follower = fixture.person(1093, 1, route.startCity, lord = false)
        val card = Retainer(92, actor.id, "EXISTING", follower.id, follower.name, "guest")
        val world = fixture.world(listOf(actor to route.start, follower to route.start),
            bugoks = listOf(fixture.unit(93, actor.id, 1000)), retainers = listOf(card),
            extraStateMeta = mapOf(RoadFortState.META_KEY to RoadFortState.toMetaValue(listOf(fort))))
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, actor.id, listOf(93), route.first)
        val siege = RoadFortSiegeService(world, recorder, fixture.topology, fixture.metrics)
        assertNull(siege.start(actor.id, fort.id))
        val beforeActor = world.getGeneralById(actor.id)
        val beforeFort = RoadFortState.read(world.getState().meta).single()
        val beforePosition = world.positionOf(actor.id)
        world.consumeDirtyState()

        val denied = assertIs<TurnOutcome.Rejected>(PoliticalHandler(world, recorder, DomesticContext(),
            deliveredCatalog()).handle(PoliticalInput.RESIGN, actor.id, "{}", "resign-road-fort", 42))

        assertEquals(PoliticalFailure.STATE_UNAVAILABLE.name, denied.code)
        assertEquals(beforeFort, RoadFortState.read(world.getState().meta).single())
        assertEquals(beforeActor, world.getGeneralById(actor.id))
        assertEquals(beforePosition, world.positionOf(actor.id))
        assertEquals(1, world.getGeneralById(follower.id)!!.nationId)
        assertEquals(listOf(card), world.listRetainers())
        assertEquals(listOf(93), DeploymentState.read(world.getGeneralById(actor.id)!!.meta)!!.corps.single().bugokIds)
        assertEquals(RoadFortSiegeService.Failure.ALREADY_BESIEGED, siege.start(follower.id, fort.id))
    }

    @Test fun `road fort siege by a retinue commander prevents resignation before its corps is released`() {
        val route = fixture.route()
        val actor = fixture.person(1094, 1, route.startCity, userId = "42", lord = false)
        val commander = fixture.person(1095, 1, route.startCity, lord = false)
        val card = Retainer(95, actor.id, "EXISTING", commander.id, commander.name, "lieutenant", hasOwnBugok = true)
        val fort = roadFort(route)
        val world = fixture.world(listOf(actor to route.start, commander to route.start),
            bugoks = listOf(fixture.unit(96, actor.id, 1000).copy(commanderRetainerId = card.id)),
            retainers = listOf(card),
            extraStateMeta = mapOf(RoadFortState.META_KEY to RoadFortState.toMetaValue(listOf(fort))))
        val recorder = ChangeRecorder()
        assertIs<DeploymentExecution.Applied>(DeploymentExecutor(world, recorder, fixture.topology, fixture.metrics)
            .deploy("retinue-siege", DeploymentRequest(actor.id, card.id, listOf(96))))
        val siege = RoadFortSiegeService(world, recorder, fixture.topology, fixture.metrics)
        assertNull(siege.start(commander.id, fort.id))
        val beforeActor = world.getGeneralById(actor.id)
        val beforeCommander = world.getGeneralById(commander.id)
        val beforeFort = RoadFortState.read(world.getState().meta).single()
        val beforePosition = world.positionOf(commander.id)
        world.consumeDirtyState()

        val denied = assertIs<TurnOutcome.Rejected>(PoliticalHandler(world, recorder, DomesticContext(),
            deliveredCatalog()).handle(PoliticalInput.RESIGN, actor.id, "{}", "resign-shared-corps", 42))

        assertEquals(PoliticalFailure.STATE_UNAVAILABLE.name, denied.code)
        assertEquals(beforeFort, RoadFortState.read(world.getState().meta).single())
        assertEquals(beforeActor, world.getGeneralById(actor.id))
        assertEquals(beforeCommander, world.getGeneralById(commander.id))
        assertEquals(beforePosition, world.positionOf(commander.id))
        assertEquals(actor.id, world.getBugokById(96)!!.masterGeneralId)
        assertEquals(listOf(card), world.listRetainers())
        assertEquals(listOf(96), DeploymentState.read(world.getGeneralById(actor.id)!!.meta)!!.corps.single().bugokIds)
        assertEquals(RoadFortSiegeService.Failure.ALREADY_BESIEGED, siege.start(actor.id, fort.id))
    }

    @Test fun `resignation can release a deployed corps when its road fort is not besieged`() {
        val route = fixture.route()
        val actor = fixture.person(1096, 1, route.startCity, userId = "42", lord = false)
        val fort = roadFort(route)
        val world = fixture.world(listOf(actor to route.start),
            bugoks = listOf(fixture.unit(97, actor.id, 1000)),
            extraStateMeta = mapOf(RoadFortState.META_KEY to RoadFortState.toMetaValue(listOf(fort))))
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, actor.id, listOf(97), route.first)

        assertIs<TurnOutcome.Applied>(PoliticalHandler(world, recorder, DomesticContext(), deliveredCatalog())
            .handle(PoliticalInput.RESIGN, actor.id, "{}", "resign-clear-road-fort", 42))

        assertEquals(fort, RoadFortState.read(world.getState().meta).single())
        assertEquals(0, world.getGeneralById(actor.id)!!.nationId)
        assertNull(DeploymentState.read(world.getGeneralById(actor.id)!!.meta))
    }

    @Test fun `dissolution waits for the shared nation deletion path`() {
        val route = fixture.route()
        val actor = fixture.person(1031, 1, route.startCity, userId = "42", lord = true)
        val world = fixture.world(listOf(actor to route.start),
            cityChanges = { city -> if (city.id == route.startCity) city.copy(nationId = 1) else city })
        assertEquals(InputRejection.NOT_DELIVERED.name,
            assertIs<TurnOutcome.Rejected>(PoliticalHandler(world, ChangeRecorder(), DomesticContext())
                .handle(PoliticalInput.DISSOLVE, actor.id, "{}", "dissolve-1031", 42)).code)
        assertNotNull(world.getNationById(1))
        assertEquals(1, world.getCityById(route.startCity)!!.nationId)
        assertEquals(1, world.getGeneralById(actor.id)!!.nationId)
    }

    @Test fun `founding promotes an existing lord's unestablished nation once`() {
        val route = fixture.route()
        val actor = fixture.person(1041, 1, route.startCity, userId = "42", lord = true)
        val world = fixture.world(listOf(actor to route.start),
            nations = listOf(Nation(1, "N1", "#111111", capitalCityId = route.startCity), Nation(2, "N2", "#222222")))
        val handler = PoliticalHandler(world, ChangeRecorder(), DomesticContext())
        assertIs<TurnOutcome.Applied>(handler.handle(PoliticalInput.FOUND_STATE, actor.id, "{}", "found-1041", 42))
        assertEquals(1, world.getNationById(1)!!.level)
        assertEquals(PoliticalFailure.ALREADY_PROCESSED.name,
            assertIs<TurnOutcome.Rejected>(handler.handle(PoliticalInput.FOUND_STATE,
                actor.id, "{}", "found-again", 42)).code)
    }

    @Test fun `successor directly accepts before abdication transfers the lord and nation chief`() {
        val route = fixture.route()
        val ruler = fixture.person(1051, 1, route.startCity, userId = "42", lord = true)
        val successor = fixture.person(1052, 1, route.startCity, userId = "43", lord = false)
        val world = fixture.world(listOf(ruler to route.start, successor to route.start),
            nations = listOf(Nation(1, "N1", "#111111", capitalCityId = route.startCity, chiefGeneralId = ruler.id),
                Nation(2, "N2", "#222222")))
        val political = PoliticalHandler(world, ChangeRecorder(), DomesticContext())
        val args = """{"targetGeneralId":${successor.id}}"""
        assertEquals(PoliticalFailure.CONSENT_REQUIRED.name,
            assertIs<TurnOutcome.Rejected>(political.handle(PoliticalInput.ABDICATE,
                ruler.id, args, "abdicate-denied", 42)).code)
        val reply = CourtHandler(world, ChangeRecorder()).handle(TurnDaemonCommand.ImmediateInput(
            "accept-1052", successor.id, 43, PoliticalConsent.COURT_INPUT_ID,
            """{"issuerGeneralId":${ruler.id},"inputId":"action.abdicate","accepted":true}"""))
        assertTrue(reply.ok)
        assertIs<TurnOutcome.Applied>(political.handle(PoliticalInput.ABDICATE,
            ruler.id, args, "abdicate-1051", 42))
        assertFalse(LordStatus.read(world.getGeneralById(ruler.id)!!.meta))
        assertTrue(LordStatus.read(world.getGeneralById(successor.id)!!.meta))
        assertEquals(successor.id, world.getNationById(1)!!.chiefGeneralId)
        assertNull(PoliticalConsent.read(world.getGeneralById(successor.id)!!.meta))
    }

    @Test fun `oath needs an explicit acceptance and stores a symmetric bond`() {
        val route = fixture.route()
        val actor = fixture.person(1061, 1, route.startCity, userId = "42", lord = false)
        val target = fixture.person(1062, 2, route.startCity, userId = "43", lord = false)
        val world = fixture.world(listOf(actor to route.start, target to route.start))
        val political = PoliticalHandler(world, ChangeRecorder(), DomesticContext())
        val args = """{"targetGeneralId":${target.id}}"""
        val refused = CourtHandler(world, ChangeRecorder()).handle(TurnDaemonCommand.ImmediateInput(
            "refuse-1062", target.id, 43, PoliticalConsent.COURT_INPUT_ID,
            """{"issuerGeneralId":${actor.id},"inputId":"action.oath","accepted":false}"""))
        assertTrue(refused.ok)
        assertEquals(PoliticalFailure.CONSENT_DECLINED.name,
            assertIs<TurnOutcome.Rejected>(political.handle(PoliticalInput.OATH,
                actor.id, args, "oath-refused", 42)).code)
        val accepted = CourtHandler(world, ChangeRecorder()).handle(TurnDaemonCommand.ImmediateInput(
            "accept-1062", target.id, 43, PoliticalConsent.COURT_INPUT_ID,
            """{"issuerGeneralId":${actor.id},"inputId":"action.oath","accepted":true}"""))
        assertTrue(accepted.ok)
        assertIs<TurnOutcome.Applied>(political.handle(PoliticalInput.OATH,
            actor.id, args, "oath-1061", 42))
        assertEquals(setOf(target.id), OathBonds.read(world.getGeneralById(actor.id)!!.meta))
        assertEquals(setOf(actor.id), OathBonds.read(world.getGeneralById(target.id)!!.meta))
    }
}
