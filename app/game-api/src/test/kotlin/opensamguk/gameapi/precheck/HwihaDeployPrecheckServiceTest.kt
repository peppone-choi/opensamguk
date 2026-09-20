package opensamguk.gameapi.precheck

import kotlin.test.*
import org.mockito.Mockito.*
import opensamguk.gameapi.read.*
import opensamguk.gameapi.web.HwihaDeployController
import opensamguk.gameapi.reserve.*
import opensamguk.infra.seed.ResolvedHanWorldArtifacts
import opensamguk.logic.input.*
import opensamguk.logic.world.*
import java.util.Optional

class HwihaDeployPrecheckServiceTest {
    private val generals = mock(GeneralReadRepository::class.java)
    private val retainers = mock(RetainerReadRepository::class.java)
    private val resolver = mock(ActiveWorldArtifactResolver::class.java)
    private val spatial = mock(SpatialStateReadRepository::class.java)
    private val service = HwihaDeployPrecheckService(generals, retainers, resolver, spatial)
    private val pin = "a".repeat(64)
    private val a = StrategicNodeRef.LandProvince("A")
    private val b = StrategicNodeRef.LandProvince("B")
    private val topology = StrategicTopologySnapshot("qa", setOf("A", "B"), emptyList(), listOf(
        TraversalEdge("ab", a, b, TraversalMode.LAND, false, 1, 10, RiskBand.LOW, SeasonalAvailability.ALWAYS,
            sourceRefs = listOf("qa:ab"), confidence = EvidenceConfidence.REVIEWED)), emptyList(),
        mapOf(LandMarchMetricSnapshot.TILES_PATH to pin))
    private val metrics = LandMarchMetricSnapshot(topology, pin, listOf(LandMarchEdgeMetric("ab", 40, 40)))
    private val request = DeployInput(1, listOf(4), b)
    private fun setup(): Pair<GeneralReadEntity, WorldStateReadEntity> {
        val actor = GeneralReadEntity(id = 1, worldId = 1, name = "본인", nationId = 1, userId = "41", npcState = 0)
        val other = GeneralReadEntity(id = 2, worldId = 1, name = "상대", nationId = 2, userId = "42", npcState = 0)
        `when`(generals.findById(1)).thenReturn(Optional.of(actor))
        `when`(generals.findAll()).thenReturn(listOf(actor, other))
        `when`(retainers.findAll()).thenReturn(emptyList())
        `when`(retainers.allBugoks()).thenReturn(listOf(
            GeneralBugokReadEntity(worldId = 1, id = 4, masterGeneralId = 1, name = "내부대", troops = 100),
            GeneralBugokReadEntity(worldId = 1, id = 5, masterGeneralId = 2, name = "비공개 상대부대", troops = 999)))
        val bundle = mock(ResolvedHanWorldArtifacts::class.java)
        `when`(bundle.projection).thenReturn(HanStrategicRouteProjection(topology, listOf(
            HanStrategicRouteBinding(1, "r1", "p1", "A"), HanStrategicRouteBinding(2, "r2", "p2", "B"),
            HanStrategicRouteBinding(3, "r3", "p3", "B"))))
        `when`(bundle.landMarchMetrics).thenReturn(metrics)
        val world = WorldStateReadEntity(id = 1, config = mapOf("ruleProfile" to "HWIHA"), meta = mapOf(
            HwihaLandPassageState.META_KEY to HwihaLandPassageState.initialMetaValue(topology),
            HwihaMarchReactions.META_KEY to HwihaMarchReactions.Empty.toMetaValue()))
        `when`(resolver.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world, listOf(
            CityReadEntity(id = 3, worldId = 1, name = "후순위"), CityReadEntity(id = 2, worldId = 1, name = "도착지"),
            CityReadEntity(id = 1, worldId = 1, name = "출발지")), bundle))
        `when`(spatial.readSnapshot(1, topology)).thenReturn(SpatialStateReadSnapshot(
            ProvinceControlSnapshot.fromTopology(topology), GeneralPositionSnapshot.fromTopology(topology,
                listOf(1,2).map { GeneralPositionState(topology.topologyRevision, topology.contentHash, it, a, 1) })))
        return actor to world
    }

    @Test fun `owner rejection precedes roster artifacts and position reads`() {
        setup()
        assertFailsWith<DeployReadForbidden> { service.options(1, 42) }
        assertFailsWith<DeployReadForbidden> { service.assess(request, 0) }
        verifyNoInteractions(resolver, spatial)
        verify(generals, never()).findAll()
        verify(retainers, never()).allBugoks()
    }

    @Test fun `options expose only own units and stable deduplicated public destinations`() {
        setup()
        val result = service.options(1,41)
        assertTrue(result.available); assertEquals(12,result.maxReservedTurns)
        assertEquals(listOf(4),result.bugoks.map { it.id }); assertTrue(result.bugoks.single().available)
        assertEquals(listOf("A", "B"), result.destinations.map { it.provinceId })
        assertEquals(listOf("출발지", "도착지"), result.destinations.map { it.name })
        assertNull(result.order)
        assertIs<DeploymentAssessment.Eligible>(service.assess(request,41))
        assertEquals(DeploymentFailure.UNIT_UNAVAILABLE,
            assertIs<DeploymentAssessment.Rejected>(service.assess(request.copy(bugokIds=listOf(5)),41)).reason)
    }

    @Test fun `missing authorities corrupt metadata and wrong world never become available`() {
        val (actor, world) = setup()
        actor.meta = mapOf(HwihaDeploymentState.META_KEY to null)
        assertEquals("STATE_UNAVAILABLE",service.options(1,41).code)
        actor.meta = emptyMap(); world.meta = emptyMap()
        assertEquals("STATE_UNAVAILABLE",service.options(1,41).code)
        assertEquals(DeploymentFailure.STATE_UNAVAILABLE,
            assertIs<DeploymentAssessment.Rejected>(service.assess(request,41)).reason)
        setup().first.worldId = 9
        assertEquals("STATE_UNAVAILABLE",service.options(1,41).code)
    }

    @Test fun `wrong profile is rejected and deputy assigned unit is not offered as personal command`() {
        val (_, world) = setup(); world.config = mapOf("ruleProfile" to "SAMMO")
        assertEquals("WRONG_RULE_PROFILE",service.options(1,41).code)
        world.config = mapOf("ruleProfile" to null)
        assertEquals("STATE_UNAVAILABLE",service.options(1,41).code)
        setup()
        `when`(retainers.allBugoks()).thenReturn(listOf(GeneralBugokReadEntity(worldId=1,id=4,masterGeneralId=1,
            troops=100,commanderRetainerId=7)))
        val row = service.options(1,41).bugoks.single()
        assertFalse(row.available); assertEquals(HwihaDeployRules.reason(DeploymentFailure.COMMANDER_CHANGED),row.reason)
    }

    @Test fun `owned durable order is visible but blocks another deployment`() {
        val (actor, _) = setup()
        val corps = HwihaDeployedCorps("order",1,1,null,1,listOf(4),HwihaPhase(200,1,1))
        val order = HwihaCorpsOrder("order",1,1,b,topology.topologyRevision,topology.contentHash)
        actor.meta = mapOf(HwihaDeploymentState.META_KEY to HwihaDeploymentState(listOf(corps)).toMetaValue(),
            HwihaCorpsOrder.META_KEY to order.toMetaValue())
        val result = service.options(1,41)
        assertFalse(result.available); assertEquals("ALREADY_DEPLOYED",result.code)
        assertEquals("order",result.order!!.orderId); assertEquals("B",result.order!!.destinationProvinceId)
    }

    @Test fun `controller rejects anonymous and other owners without exposing options`() {
        setup(); val controller = HwihaDeployController(service)
        assertEquals(401, controller.options(null,1).statusCode.value())
        assertEquals(403, controller.options(42,1).statusCode.value())
        assertEquals(200, controller.options(41,1).statusCode.value())
    }

    @Test fun `admission validates owner slot strict args and shared reason before delivery gate`() {
        setup(); val admission = HwihaDeployAdmission(service)
        assertEquals("UNAUTHORIZED",assertFailsWith<HwihaAdmissionDenied> { admission.canonicalArguments(1,null,0,"{}") }.code)
        assertEquals("FORBIDDEN",assertFailsWith<HwihaAdmissionDenied> { admission.canonicalArguments(1,42,0,"{}") }.code)
        assertEquals("INVALID_TURN_SLOT",assertFailsWith<HwihaAdmissionDenied> { admission.canonicalArguments(1,41,12,"{}") }.code)
        val malformed = """{"bugokIds":[4],"bugokIds":[4],"destinationProvinceId":"B"}"""
        assertEquals("INVALID_INPUT",assertFailsWith<HwihaAdmissionDenied> { admission.canonicalArguments(1,41,0,malformed) }.code)
        val rejected = assertFailsWith<HwihaAdmissionDenied> { admission.canonicalArguments(1,41,0,
            HwihaDeployInput.canonicalJson(request.copy(bugokIds=listOf(5)))) }
        assertEquals(DeploymentFailure.UNIT_UNAVAILABLE.name,rejected.code)
        assertEquals(HwihaDeployRules.reason(DeploymentFailure.UNIT_UNAVAILABLE),rejected.message)
        // No fake ready catalog is injected: successful admission is tested after main integrates its handler catalog.
    }
    @Test fun `all bugoks read is scoped to configured process world`() {
        val rawCards = mock(GeneralRetainerReadRawRepository::class.java)
        val rawUnits = mock(GeneralBugokReadRawRepository::class.java)
        val repository = RetainerReadRepository(rawCards, rawUnits, opensamguk.gameapi.config.GameApiProcessWorld(7))
        val rows = listOf(GeneralBugokReadEntity(worldId=7,id=1))
        `when`(rawUnits.findByWorldIdOrderByIdAsc(7)).thenReturn(rows)
        assertEquals(rows,repository.allBugoks())
        verify(rawUnits).findByWorldIdOrderByIdAsc(7)
        verifyNoInteractions(rawCards)
    }

    @Test fun `defender options show pending encounter without revealing the attacking roster`() {
        val (defender, _) = setup()
        val attacker = generals.findAll().single { it.id == 2 }
        val phase = HwihaPhase(200,1,1)
        val attacking = HwihaDeployedCorps("private-attack-order",2,2,null,2,listOf(5),phase)
        val defending = HwihaDeployedCorps("my-defense-order",1,1,null,1,listOf(4),phase)
        val encounter = HwihaCorpsEncounter(HwihaEncounterParticipant.from(attacking),
            listOf(HwihaEncounterParticipant.from(defending)),b,a,phase,topology.topologyRevision,topology.contentHash)
        val path = assertIs<LandMarchPathResult.Resolved>(StrategicPathResolver.resolveLandMarch(topology,
            StrategicPathRequest(a,b,1),HwihaLandPassageState.read(setupWorldMeta(),topology)!!,metrics)).path
        val checkpoint = HwihaMarchCheckpoint(path,LandMarchCursor(path.pathHash,1,0),phase,LandMarchStop.ENCOUNTER)
        fun meta(corps:HwihaDeployedCorps) = mapOf(
            HwihaDeploymentState.META_KEY to HwihaDeploymentState(listOf(corps)).toMetaValue(),
            HwihaCorpsOrder.META_KEY to HwihaCorpsOrder(corps.orderId,corps.ownerGeneralId,corps.commanderGeneralId,
                b,topology.topologyRevision,topology.contentHash).toMetaValue(),
            HwihaCorpsEncounter.META_KEY to encounter.toMetaValue())
        defender.meta = meta(defending)
        attacker.meta = meta(attacking) + (HwihaCorpsMarchState.META_KEY to
            HwihaCorpsMarchState(attacking.orderId,2,2,checkpoint).toMetaValue())
        `when`(spatial.readSnapshot(1,topology)).thenReturn(SpatialStateReadSnapshot(
            ProvinceControlSnapshot.fromTopology(topology),GeneralPositionSnapshot.fromTopology(topology,
                listOf(1,2).map { GeneralPositionState(topology.topologyRevision,topology.contentHash,it,b,1) })))
        val result = service.options(1,41)
        assertFalse(result.available)
        assertEquals("ENCOUNTER",result.order?.stop)
        assertEquals("my-defense-order",result.order?.orderId)
        assertEquals(listOf(4),result.bugoks.map { it.id })
        val rendered = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result)
        assertFalse(rendered.contains("private-attack-order")); assertFalse(rendered.contains("비공개 상대부대"))
        attacker.meta = attacker.meta - HwihaCorpsEncounter.META_KEY
        assertEquals("STATE_UNAVAILABLE",service.options(1,41).code)
        assertNull(service.options(1,41).order)
    }

    private fun setupWorldMeta(): Map<String,Any> = mapOf(
        HwihaLandPassageState.META_KEY to HwihaLandPassageState.initialMetaValue(topology))

}
