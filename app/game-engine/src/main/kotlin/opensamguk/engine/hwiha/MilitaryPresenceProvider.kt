package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.*
import opensamguk.logic.world.*

/** Fresh snapshot reader. It never infers deployment from resident generals or legacy crew. */
class HwihaMilitaryPresenceProvider(private val world: InMemoryTurnWorld,
    private val topology: StrategicTopologySnapshot, private val metrics: LandMarchMetricSnapshot) {
    private fun projection() = HwihaDeploymentExecutor(world, ChangeRecorder(), topology, metrics).projection()
    private fun wars() = world.listDiplomacy().filter { it.state == 0 }.mapTo(linkedSetOf()) {
        it.fromNationId to it.toNationId
    }
    fun assess(actorId: Int): MilitaryPresenceAssessment = projection()?.let {
        MilitaryPresence.assess(actorId, it, wars())
    } ?: MilitaryPresenceAssessment.Unavailable

    /** Production entry reader: missing or unsupported reaction inventories are not clear terrain. */
    fun entryAt(actorId: Int, node: StrategicNodeRef.LandProvince): LandMarchEntry {
        if (world.ruleProfile != RuleProfile.HWIHA) return LandMarchEntry.UNAVAILABLE
        val reactions = try { MarchReactions.read(world.getState().meta) }
            catch (_: IllegalArgumentException) { return LandMarchEntry.UNAVAILABLE }
        if (reactions != MarchReactions.Empty) return LandMarchEntry.UNAVAILABLE
        return entryAt(actorId, node, LandMarchEntry.CLEAR)
    }

    /** Production march entry: reaction records are judged by [reactions] instead of stalling on any record. */
    fun entryAt(actorId: Int, node: StrategicNodeRef.LandProvince, reactions: HwihaMarchReactionPolicy): LandMarchEntry {
        if (world.ruleProfile != RuleProfile.HWIHA) return LandMarchEntry.UNAVAILABLE
        val hazard = reactions.entryHazard(world, actorId, node)
        if (hazard == LandMarchEntry.UNAVAILABLE) return LandMarchEntry.UNAVAILABLE
        return entryAt(actorId, node, hazard, reactions.evadingOrderIds(world, actorId, node))
    }

    fun directEntryAt(actorId: Int, node: StrategicNodeRef.LandProvince, reactions: HwihaMarchReactionPolicy): LandMarchEntry {
        if (world.ruleProfile != RuleProfile.HWIHA) return LandMarchEntry.UNAVAILABLE
        val hazard = reactions.directEntryHazard(world, actorId, node)
        if (hazard == LandMarchEntry.UNAVAILABLE) return hazard
        return entryAt(actorId, node, hazard, reactions.evadingOrderIds(world, actorId, node))
    }

    /** Other encounter authorities (installed schemes, interception, avoidance) are mandatory. */
    fun entryAt(actorId: Int, node: StrategicNodeRef.LandProvince, otherHazards: LandMarchEntry,
        yieldingOrderIds: Set<String> = emptySet()): LandMarchEntry {
        if (!topology.containsNode(node)) return LandMarchEntry.UNAVAILABLE
        return when (val result = assess(actorId)) {
            MilitaryPresenceAssessment.Unavailable -> LandMarchEntry.UNAVAILABLE
            is MilitaryPresenceAssessment.Ready -> if (result.hostileCorps.any { corps ->
                world.positionOf(corps.commanderGeneralId) == node && corps.orderId !in yieldingOrderIds
            }) LandMarchEntry.ENCOUNTER else otherHazards
        }
    }

    fun withMilitarySupply(network: SpatialSupplyNetwork): SpatialSupplyNetwork {
        check(world.ruleProfile == RuleProfile.HWIHA)
        val strategic = network.strategicSupply ?: throw MilitarySupplyUnavailableException("Military supply requires strategic topology")
        if (strategic.topology.topologyRevision != topology.topologyRevision || strategic.topology.contentHash != topology.contentHash)
            throw MilitarySupplyUnavailableException("Military supply topology is stale")
        val state = projection() ?: throw MilitarySupplyUnavailableException("Deployment authority is unavailable")
        val wars = wars()
        val blocks = world.listNations().map { it.id }.filter { it > 0 }.sorted().associateWith { nation ->
            when (val result = MilitaryPresence.assessNation(nation, state, wars)) {
                MilitaryPresenceAssessment.Unavailable -> throw MilitarySupplyUnavailableException("Active corps authority is unavailable")
                is MilitaryPresenceAssessment.Ready -> result.blockedProvinceIds
            }
        }
        return network.copy(strategicSupply = strategic.withMilitaryBlocks(blocks))
    }
}
