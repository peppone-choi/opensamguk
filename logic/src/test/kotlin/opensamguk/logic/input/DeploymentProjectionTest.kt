package opensamguk.logic.input

import kotlin.test.*
import opensamguk.logic.world.*

class HwihaDeploymentProjectionTest {
    private val pin = "a".repeat(64)
    private val a = StrategicNodeRef.LandProvince("A")
    private val b = StrategicNodeRef.LandProvince("B")
    private val topology = StrategicTopologySnapshot("qa", setOf("A", "B"), emptyList(), listOf(
        TraversalEdge("ab", a, b, TraversalMode.LAND, false, 1, 10, RiskBand.LOW, SeasonalAvailability.ALWAYS,
            sourceRefs = listOf("qa:ab"), confidence = EvidenceConfidence.REVIEWED)), emptyList(),
        mapOf(LandMarchMetricSnapshot.TILES_PATH to pin))
    private val metrics = LandMarchMetricSnapshot(topology, pin, listOf(LandMarchEdgeMetric("ab", 40, 40)))
    private val path = assertIs<LandMarchPathResult.Resolved>(StrategicPathResolver.resolveLandMarch(topology,
        StrategicPathRequest(a, b, 1), StrategicEdgeStateSnapshot(topology.topologyRevision, topology.contentHash, emptyMap()), metrics)).path
    private val phase = HwihaPhase(200, 1, 2)
    private val corps = HwihaDeployedCorps("order", 1, 2, 3, 1, listOf(4, 5), HwihaPhase(200, 1, 1))
    private val checkpoint = HwihaMarchCheckpoint(path, LandMarchCursor(path.pathHash, 0, 30), phase, LandMarchStop.BUDGET_EXHAUSTED)
    private val state = HwihaCorpsMarchState("order", 1, 2, checkpoint)
    private fun positions(node: StrategicNodeRef = a) = GeneralPositionSnapshot.fromTopology(topology,
        listOf(1, 2).map { GeneralPositionState(topology.topologyRevision, topology.contentHash, it, node, 1) })
    private val owner = DeploymentPersonSource(1, 1, false,
        mapOf(HwihaDeploymentState.META_KEY to HwihaDeploymentState(listOf(corps)).toMetaValue()))
    private val deputy = DeploymentPersonSource(2, 1, true, emptyMap())
    private val units = listOf(DeploymentUnit(4, 1, 10, 3), DeploymentUnit(5, 1, 20, 3))
    private val cards = listOf(DeploymentRetainer(3, 1, 2, true))
    private fun build(people: List<DeploymentPersonSource> = listOf(deputy, owner),
        position: GeneralPositionSnapshot? = positions()) =
        HwihaDeploymentProjection.build(RuleProfile.HWIHA, people, units, cards, position, topology, metrics)

    @Test fun `projection preserves sorted people cards units and live assessment`() {
        val result = assertNotNull(build())
        assertEquals(listOf(1, 2), result.people.map { it.id })
        assertEquals(units, result.units); assertEquals(cards, result.retainers)
        assertEquals(listOf(corps), result.deployed)
        assertTrue(result.people.single { it.id == 2 }.isUnownedNpc)
        assertIs<DeploymentAssessment.Eligible>(HwihaDeploymentRules.assessActive(corps, result))
    }

    @Test fun `missing pins malformed metadata and foreign ownership are unavailable`() {
        assertNull(build(position = null))
        val foreign = StrategicTopologySnapshot("other", setOf("A", "B"), emptyList(), emptyList(), emptyList(), mapOf(LandMarchMetricSnapshot.TILES_PATH to pin))
        assertNull(build(position = GeneralPositionSnapshot.fromTopology(foreign)))
        for (raw in listOf(null, emptyMap<String, Any>(), owner.meta[HwihaDeploymentState.META_KEY])) {
            assertNull(build(listOf(owner.copy(id = 9, meta = mapOf(HwihaDeploymentState.META_KEY to raw)), deputy)))
        }
    }

    @Test fun `global duplicate deployment identifiers commanders and unit cards are unavailable`() {
        val alternatives = listOf(
            corps.copy(ownerGeneralId = 9, commanderGeneralId = 8, commanderRetainerId = 7, bugokIds = listOf(6)),
            corps.copy(orderId = "other", ownerGeneralId = 9, bugokIds = listOf(6)),
            corps.copy(orderId = "other", ownerGeneralId = 9, commanderGeneralId = 8, commanderRetainerId = 7))
        for (other in alternatives) {
            val extra = DeploymentPersonSource(9, 1, false,
                mapOf(HwihaDeploymentState.META_KEY to HwihaDeploymentState(listOf(other)).toMetaValue()))
            assertNull(build(listOf(owner, deputy, extra)))
        }
    }

    @Test fun `corps progress requires exact binding position and exclusive movement owner`() {
        val meta = mapOf(HwihaCorpsMarchState.META_KEY to state.toMetaValue())
        assertNotNull(build(listOf(owner, deputy.copy(meta = meta))))
        assertNull(build(listOf(owner, deputy.copy(meta = meta)), positions(b)))
        assertNull(build(listOf(owner.copy(meta = emptyMap()), deputy.copy(meta = meta))))
        assertNull(build(listOf(owner, deputy.copy(meta = mapOf(HwihaCorpsMarchState.META_KEY to
            state.copy(deploymentOrderId = "other").toMetaValue())))))
        val march = HwihaMarchState(HwihaCountyAssignment("assignment", 1, 2, 3), path,
            checkpoint.cursor, phase, checkpoint.stop)
        assertNull(build(listOf(owner, deputy.copy(meta = meta + (HwihaMarchState.META_KEY to march.toMetaValue())))))
    }

    @Test fun `both durable encounter channels remain battle pending`() {
        val cursor = LandMarchCursor(path.pathHash, 1, 0)
        val encounter = state.copy(checkpoint = checkpoint.copy(cursor = cursor, stop = LandMarchStop.ENCOUNTER))
        val assignment = HwihaMarchState(HwihaCountyAssignment("assignment", 1, 2, 3), path, cursor, phase, LandMarchStop.ENCOUNTER)
        for (meta in listOf(mapOf(HwihaCorpsMarchState.META_KEY to encounter.toMetaValue()),
            mapOf(HwihaMarchState.META_KEY to assignment.toMetaValue()))) {
            assertTrue(assertNotNull(build(listOf(owner, deputy.copy(meta = meta)), positions(b)))
                .people.single { it.id == 2 }.inBattle)
        }
    }
    @Test fun `destination requires its deployed commander and cannot override an existing path`() {
        val order=HwihaCorpsOrder("order",1,2,b,topology.topologyRevision,topology.contentHash)
        val meta=mapOf(HwihaCorpsOrder.META_KEY to order.toMetaValue())
        assertNotNull(build(listOf(owner,deputy.copy(meta=meta))))
        assertNull(build(listOf(owner.copy(meta=emptyMap()),deputy.copy(meta=meta))))
        assertNull(build(listOf(owner.copy(meta=owner.meta+meta),deputy)))
        assertNull(build(listOf(owner,deputy.copy(meta=mapOf(HwihaCorpsOrder.META_KEY to order.copy(orderId="wrong").toMetaValue())))))
        assertNull(build(listOf(owner,deputy.copy(meta=mapOf(HwihaCorpsOrder.META_KEY to order.copy(destination=a).toMetaValue(),
            HwihaCorpsMarchState.META_KEY to state.toMetaValue())))))
    }

}
