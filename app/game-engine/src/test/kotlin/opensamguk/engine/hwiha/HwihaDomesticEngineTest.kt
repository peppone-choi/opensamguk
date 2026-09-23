package opensamguk.engine.hwiha

import java.time.Instant
import kotlin.test.*
import opensamguk.common.wire.CommandLifecycleResult
import opensamguk.common.wire.TurnDaemonCommand.HwihaCourtInput
import opensamguk.common.world.WorldId
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.*
import opensamguk.logic.economy.HwihaCountyWarehouse
import opensamguk.logic.economy.HwihaResources
import opensamguk.logic.input.*
import opensamguk.logic.world.*

/** 접수(즉시 봉투) → 카드 턴 효력 → 부임 행군 → 순 경계(공사·방침·치적)까지 레코더 경로로 본다. DB 없음. */
class HwihaDomesticEngineTest {
    private val pin = "a".repeat(64)
    private val a = StrategicNodeRef.LandProvince("A")
    private val b = StrategicNodeRef.LandProvince("B")
    private val topology = StrategicTopologySnapshot("qa", setOf("A", "B"), emptyList(), listOf(
        TraversalEdge("ab", a, b, TraversalMode.LAND, false, 1, 10, RiskBand.LOW, SeasonalAvailability.ALWAYS,
            sourceRefs = listOf("qa:ab"), confidence = EvidenceConfidence.REVIEWED)), emptyList(),
        mapOf(LandMarchMetricSnapshot.TILES_PATH to pin))
    private val metrics = LandMarchMetricSnapshot(topology, pin, listOf(LandMarchEdgeMetric("ab", 40, 40)))
    private val events = mutableListOf<HwihaGovernanceRenownEvent>()
    private val context = HwihaDomesticContext(
        geography = HwihaCountyGeography(listOf(HwihaCountyPlace(10, "甲郡", "갑군", "j10"), HwihaCountyPlace(11, "甲郡", "갑군", "j11"))),
        topology = topology, metrics = metrics, renown = { events += it })

    private fun general(id: Int, node: String, human: Boolean = false, lord: Boolean = false, level: Int = 0,
        stats: GeneralStats = GeneralStats(60, 60, 60, 80, 60)) = TurnGeneral(id = id, name = "G$id", nationId = 1,
        cityId = if (node == "A") 10 else 11, troopId = 0, stats = stats, experience = 0, dedication = 0, officerLevel = level,
        userId = if (human) "42" else null, npcState = if (human) 0 else 2, turnTime = Instant.EPOCH,
        meta = mapOf("hwihaLord" to lord))

    private fun county(id: Int, stock: HwihaResources = HwihaResources()) = City(id, "縣$id", 1, 1, population = 50_000,
        populationMax = 100_000, agriculture = 1000, agricultureMax = 5000, commerce = 1000, commerceMax = 5000, security = 500,
        securityMax = 1000, defence = 500, defenceMax = 1000, wall = 500, wallMax = 1000,
        meta = mapOf("trust" to 80.0, HwihaCountyWarehouse.META_KEY to HwihaCountyWarehouse(id, 0, stock).toMetaValue()))

    private fun world(stock: HwihaResources = HwihaResources()): InMemoryTurnWorld {
        val positions = listOf(1 to a, 2 to a, 3 to b).fold(GeneralPositionSnapshot("qa", topology.contentHash, setOf("A", "B"), emptySet())) { s, (id, node) ->
            s.withState(GeneralPositionState("qa", topology.contentHash, id, node, 1)) }
        return InMemoryTurnWorld(WorldSnapshot(worldId = WorldId(1),
            state = TurnWorldState(1, 200, 1, 3600, Instant.EPOCH, currentPhase = 1,
                config = mapOf("ruleProfile" to "HWIHA", "mapName" to "han-world-v3"),
                meta = mapOf(HwihaLandPassageState.META_KEY to HwihaLandPassageState.initialMetaValue(topology),
                    HwihaMarchReactions.META_KEY to HwihaMarchReactions.Empty.toMetaValue())),
            generals = listOf(general(1, "A", human = true, lord = true, level = 12), general(2, "A"), general(3, "B")),
            nations = listOf(Nation(1, "N1", "#000", capitalCityId = 10), Nation(2, "N2", "#fff", capitalCityId = 11)),
            cities = listOf(county(10, stock), county(11, stock)),
            retainers = listOf(Retainer(4, 1, "EXISTING", 2, "G2", "lieutenant"), Retainer(5, 1, "EXISTING", 3, "G3", "staff")),
            generalPositionSnapshot = positions, cityLandProvinceById = mapOf(10 to "A", 11 to "B"), administrativeCountyIds = setOf(10, 11)))
    }

    private fun submit(world: InMemoryTurnWorld, recorder: ChangeRecorder, inputId: String, body: String, owner: Int = 42) =
        HwihaCourtHandler(world, recorder, context).handle(HwihaCourtInput("req-${body.hashCode().toUInt()}", 1, owner, inputId, body))

    private fun boundary(world: InMemoryTurnWorld, recorder: ChangeRecorder, year: Int, month: Int, phase: Int): HwihaDomesticBoundary.Outcome {
        world.setCurrentDate(year, month, phase)
        return checkNotNull(HwihaDomesticBoundary(world, recorder, context).run())
    }

    @Test fun `standing inputs are accepted into the immediate channel as pending orders only`() {
        val world = world(); val recorder = ChangeRecorder()
        val result = submit(world, recorder, "placement.assign", """{"cardId":5,"post":"MAGISTRATE","countyId":10}""")
        assertEquals(CommandLifecycleResult(type = "reservationAccepted", ok = true, commandKind = "PLACEMENT",
            actionCode = "placement.assign", generalId = 1), result)
        val stored = assertNotNull(HwihaPlacementState.read(world.getGeneralById(3)!!.meta))
        assertNull(stored.active)
        assertEquals(PlacementTarget.County(10), stored.pending!!.target)
        assertEquals(world.positionOf(3), b, "intake never moves the card")
        assertEquals("FORBIDDEN", submit(world, recorder, "policy.set", """{"scope":"COUNTY","countyId":10,"policy":"COMMERCE"}""", owner = 43).code)
        assertEquals("INVALID_REQUEST", submit(world, recorder, "work.start", """{"countyId":10,"work":"망루봉화"}""").code)
        assertEquals("NOT_DELIVERED", submit(world, recorder, "stratagem.play", "{}").code)
        // A second card cannot claim the same county seat while the first order is pending.
        assertEquals(DomesticFailure.COUNTY_OCCUPIED.name,
            submit(world, recorder, "placement.assign", """{"cardId":4,"post":"MAGISTRATE","countyId":10}""").code)
        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        assertEquals(listOf(3), payload.updatedGenerals.map { it.id })
    }

    @Test fun `placement takes effect on the card's next turn and the card marches to its post`() {
        val world = world(); val recorder = ChangeRecorder()
        submit(world, recorder, "placement.assign", """{"cardId":5,"post":"MAGISTRATE","countyId":10}""")
        HwihaDomesticTurn(world, recorder, context).beforeMovement(3)
        val active = assertNotNull(HwihaPlacementState.read(world.getGeneralById(3)!!.meta)?.active)
        assertNull(active.arrivedAt)
        assertTrue(HwihaPlacementMarchTurn(world, recorder, topology, metrics).onTurn(3))
        assertEquals(a, world.positionOf(3))
        assertEquals(HwihaPhase(200, 1, 1), HwihaPlacementState.read(world.getGeneralById(3)!!.meta)!!.active!!.arrivedAt)
        assertFalse(HwihaPlacementMarch.META_KEY in world.getGeneralById(3)!!.meta)
        val state = context.projection(world)
        assertEquals(HwihaSeatedMagistrate(3, 1, true, 5), HwihaDomesticRules.seatedMagistrate(state.county(10)!!, state))
        // A second turn on the post neither re-marches nor re-logs the arrival.
        val logs = world.peekLogs().size
        assertTrue(HwihaPlacementMarchTurn(world, recorder, topology, metrics).onTurn(3))
        assertEquals(logs, world.peekLogs().size)
    }

    @Test fun `an invalidated pending placement is dropped with a reason and keeps no stale march`() {
        val world = world(); val recorder = ChangeRecorder()
        submit(world, recorder, "placement.assign", """{"cardId":5,"post":"MAGISTRATE","countyId":11}""")
        world.applyCityDirtyFree(world.getCityById(11)!!.copy(nationId = 2)) // county captured before the card's turn
        HwihaDomesticTurn(world, recorder, context).beforeMovement(3)
        assertNull(HwihaPlacementState.read(world.getGeneralById(3)!!.meta))
        assertTrue(world.peekLogs().any { it.text.contains(DomesticFailure.INVALID_COUNTY.message) })
    }

    @Test fun `phase boundary applies the seated magistrate's policy once and the empty seat default`() {
        val world = world(); val recorder = ChangeRecorder()
        submit(world, recorder, "placement.assign", """{"cardId":5,"post":"MAGISTRATE","countyId":10}""")
        HwihaDomesticTurn(world, recorder, context).beforeMovement(3)
        HwihaPlacementMarchTurn(world, recorder, topology, metrics).onTurn(3)
        submit(world, recorder, "policy.set", """{"scope":"COUNTY","countyId":10,"policy":"COMMERCE"}""")
        HwihaDomesticTurn(world, recorder, context).beforeMovement(3)
        assertEquals("COMMERCE", HwihaCountyPolicyState.read(world.getCityById(10)!!.meta)!!.slot.active!!.policy)
        val design = context.design
        boundary(world, recorder, 200, 1, 2)
        val seated = HwihaDomesticEffects.multiplier(design, HwihaDomesticDesign.Stat.POLITICS, HwihaSeatStats(60, 60, 60, 80, 60, false))
        assertEquals(1000 + (20 * seated / 1000).toInt(), world.getCityById(10)!!.commerce)
        assertEquals(1000, world.getCityById(10)!!.agriculture)
        assertEquals(1000 + 20 * design.scaling.emptySeatPermille / 1000, world.getCityById(11)!!.agriculture)
        val again = boundary(world, recorder, 200, 1, 2)
        assertTrue(again.alreadyStamped)
        assertEquals(1000 + (20 * seated / 1000).toInt(), world.getCityById(10)!!.commerce)
        assertEquals(HwihaPolicyApplication(HwihaPhase(200, 1, 2), "COMMERCE", "SEATED", "APPLIED"),
            HwihaCountyPolicyState.read(world.getCityById(10)!!.meta)!!.lastApplied)
        // The untouched county records no policy key: only its indicators moved.
        assertFalse(HwihaCountyPolicyState.META_KEY in world.getCityById(11)!!.meta)
    }

    @Test fun `commandery policy activates at the next boundary and overrides every county in it`() {
        val world = world(); val recorder = ChangeRecorder()
        val accepted = submit(world, recorder, "policy.set", """{"scope":"COMMANDERY","commanderyId":"甲郡","policy":"MILITARY_FARM"}""")
        assertTrue(accepted.ok, accepted.toString())
        boundary(world, recorder, 200, 1, 2)
        val policies = HwihaCommanderyPolicies.read(world.getNationById(1)!!.meta)!!
        assertEquals("MILITARY_FARM", policies["甲郡"]!!.slot.active!!.policy)
        // Both counties ran the commandery policy with the empty-seat multiplier (security rises, 둔전병).
        for (id in listOf(10, 11)) assertEquals(500 + 10 * context.design.scaling.emptySeatPermille / 1000, world.getCityById(id)!!.security)
    }

    @Test fun `works start at the next boundary stop on shortage and complete at the total cost`() {
        val world = world(); val recorder = ChangeRecorder()
        assertTrue(submit(world, recorder, "work.start", """{"countyId":10,"work":"FORTIFICATION"}""").ok)
        assertEquals(DomesticFailure.WORK_IN_PROGRESS.name, submit(world, recorder, "work.start", """{"countyId":10,"work":"ROAD"}""").code)
        world.setCurrentDate(200, 1, 1)
        HwihaDomesticBoundary(world, recorder, context).run()
        assertEquals(0, HwihaCountyWorks.read(world.getCityById(10)!!.meta)!!.active!!.progress, "no progress in the ordering phase")
        boundary(world, recorder, 200, 1, 2)
        val stopped = HwihaCountyWorks.read(world.getCityById(10)!!.meta)!!.active!!
        assertEquals(HwihaDomesticEffects.INSUFFICIENT_STOCK, stopped.stopReason)
        assertEquals(0, stopped.progress)
        // Stock the warehouse through the settlement boundary and let the work run to completion.
        val warehouse = HwihaCountyWarehouse.read(world.getCityById(10)!!.meta, 10)!!
        assertEquals(HwihaWarehouseSettlement.Result.APPLIED, HwihaWarehouseSettlement(world, recorder).settle(10, 1, warehouse.revision,
            HwihaResources(), HwihaResources(money = 1_000_000, timber = 100_000)))
        val spec = context.design.works.getValue(DomesticWork.FORTIFICATION)
        var phase = HwihaPhase(200, 1, 2)
        while (HwihaCountyWorks.read(world.getCityById(10)!!.meta)!!.active != null) {
            phase = phase.plus(1)
            boundary(world, recorder, phase.year, phase.month, phase.phase)
        }
        val works = HwihaCountyWorks.read(world.getCityById(10)!!.meta)!!
        assertEquals(listOf(DomesticWork.FORTIFICATION), works.completed.map { it.work })
        assertEquals(HwihaResources(1_000_000 - spec.cost.money, 0, 0, 100_000 - spec.cost.timber, 0),
            HwihaCountyWarehouse.read(world.getCityById(10)!!.meta, 10)!!.stock)
        assertEquals(minOf(1000, 500 + spec.completion.single().amount), world.getCityById(10)!!.defence)
    }

    @Test fun `corps reaction policies populate the march reaction inventory on the commander's turn`() {
        val world = world(); val recorder = ChangeRecorder()
        val owner = world.getGeneralById(1)!!
        val corps = HwihaDeploymentState(listOf(HwihaDeployedCorps("o1", 1, 2, 4, 1, listOf(7), HwihaPhase(200, 1, 1))))
        world.applyGeneralDirtyFree(owner.copy(meta = owner.meta + (HwihaDeploymentState.META_KEY to corps.toMetaValue())))
        assertTrue(submit(world, recorder, "policy.set", """{"scope":"CORPS","orderId":"o1","policy":"INTERCEPT"}""").ok)
        assertEquals(HwihaMarchReactions.Empty, HwihaMarchReactions.read(world.getState().meta), "not before the commander's turn")
        HwihaDomesticTurn(world, recorder, context).beforeMovement(2)
        val reactions = assertIs<HwihaMarchReactions.Inventory>(HwihaMarchReactions.read(world.getState().meta))
        assertEquals(listOf(HwihaReactionOrder("o1", 1, 2, 1, HwihaPhase(200, 1, 1))), reactions.interceptions)
        assertTrue(recorder.kvDirty().keys.any { it.key == HwihaMarchReactions.META_KEY })
        // Until an encounter consumer resolves reaction orders, march entry stays undecidable (fail closed).
        assertEquals(LandMarchEntry.UNAVAILABLE, HwihaMilitaryPresenceProvider(world, topology, metrics).entryAt(3, a))
        // The corps commander card cannot be re-placed while deployed.
        assertEquals(DomesticFailure.CARD_DEPLOYED.name,
            submit(world, recorder, "placement.assign", """{"cardId":4,"post":"SCOUT","provinceId":"B"}""").code)
    }

    @Test fun `monthly merit fires for a placed magistrate's rising county`() {
        val world = world(); val recorder = ChangeRecorder()
        submit(world, recorder, "placement.assign", """{"cardId":5,"post":"MAGISTRATE","countyId":10}""")
        HwihaDomesticTurn(world, recorder, context).beforeMovement(3)
        HwihaPlacementMarchTurn(world, recorder, topology, metrics).onTurn(3)
        boundary(world, recorder, 200, 2, 1) // first month on the seat: snapshot only
        assertTrue(events.isEmpty())
        assertEquals("0200-02", HwihaCountyMonthly.read(world.getCityById(10)!!.meta)!!.stamp)
        boundary(world, recorder, 200, 2, 2); boundary(world, recorder, 200, 2, 3)
        boundary(world, recorder, 200, 3, 1)
        val event = events.single()
        assertEquals(1, event.ownerGeneralId); assertEquals(3, event.cardGeneralId); assertEquals(5, event.retainerId)
        assertEquals(10, event.countyId); assertEquals("0200-03", event.monthStamp)
        assertTrue("agriculture" in event.risen)
        // The empty county never produces merit events or monthly snapshots.
        assertFalse(HwihaCountyMonthly.META_KEY in world.getCityById(11)!!.meta)
    }
}
