package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.*
import opensamguk.logic.world.*
import opensamguk.logic.war.hwiha.HwihaS3Provisional

/** Resolves saved scheme and corps-policy reactions against current deployments, sight and pinned geography. */
class HwihaMarchReactionInterpreter(
    private val topology: StrategicTopologySnapshot,
    private val metrics: LandMarchMetricSnapshot,
    private val commanderies: HanCommanderyIndex,
    private val visionRules: HwihaVisionRules.Rules = HwihaVisionRules.CANON,
) : HwihaMarchReactionPolicy {
    private data class Decision(
        val scheme: Boolean,
        val interceptors: List<Int>,
        val evaders: Map<Int, StrategicNodeRef.LandProvince>,
        val yieldingOrders: Set<String>,
    ) {
        val hazard get() = if (scheme || interceptors.isNotEmpty()) LandMarchEntry.ENCOUNTER else LandMarchEntry.CLEAR
    }

    override fun entryHazard(world: InMemoryTurnWorld, actorId: Int, node: StrategicNodeRef.LandProvince): LandMarchEntry =
        decide(world, actorId, node)?.hazard ?: LandMarchEntry.UNAVAILABLE

    override fun evadingOrderIds(world: InMemoryTurnWorld, actorId: Int, node: StrategicNodeRef.LandProvince): Set<String> =
        decide(world, actorId, node)?.yieldingOrders.orEmpty()

    override fun schemeContact(world: InMemoryTurnWorld, actorId: Int, node: StrategicNodeRef.LandProvince): Boolean =
        decide(world, actorId, node)?.scheme == true

    override fun interceptsAt(world: InMemoryTurnWorld, actorId: Int, node: StrategicNodeRef.LandProvince): Boolean =
        decide(world, actorId, node)?.interceptors?.isNotEmpty() == true

    override fun onEntered(world: InMemoryTurnWorld, recorder: ChangeRecorder, actorId: Int, node: StrategicNodeRef.LandProvince) {
        val decision = decide(world, actorId, node) ?: error("Reaction authority changed during entry")
        for ((commander, retreat) in decision.evaders.toSortedMap()) {
            check(recorder.moveGeneral(world, commander, retreat) is GeneralPositionChangeResult.Changed) {
                "Validated evasive retreat failed"
            }
        }
        for (commander in decision.interceptors.sorted()) {
            if (world.positionOf(commander) == node) continue
            check(recorder.moveGeneral(world, commander, node) is GeneralPositionChangeResult.Changed) {
                "Validated interception failed"
            }
        }
    }

    private fun decide(world: InMemoryTurnWorld, actorId: Int, node: StrategicNodeRef.LandProvince): Decision? {
        if (world.ruleProfile != RuleProfile.HWIHA || !topology.containsNode(node)) return null
        val inventory = try { HwihaMarchReactions.read(world.getState().meta) } catch (_: IllegalArgumentException) { null }
            ?: return null
        if (inventory == HwihaMarchReactions.Empty) return Decision(false, emptyList(), emptyMap(), emptySet())
        val actor = world.getGeneralById(actorId) ?: return null
        val projection = HwihaDeploymentExecutor(world, ChangeRecorder(), topology, metrics).projection() ?: return null
        val wars = world.listDiplomacy().filter { it.state == 0 }.mapTo(hashSetOf()) { it.fromNationId to it.toNationId }
        val now = world.getState().let { HwihaPhase(it.currentYear, it.currentMonth, it.currentPhase) }
        fun hostile(nation: Int) = nation > 0 && nation != actor.nationId && actor.nationId > 0 &&
            ((actor.nationId to nation) in wars || (nation to actor.nationId) in wars)
        fun corps(order: HwihaReactionOrder): HwihaDeployedCorps? = projection.deployed.singleOrNull {
            it.orderId == order.orderId && it.ownerGeneralId == order.ownerGeneralId &&
                it.commanderGeneralId == order.commanderGeneralId && it.nationId == order.nationId
        }
        val allOrders = inventory.interceptions + inventory.avoidanceOrders
        if (allOrders.any { it.since > now || corps(it) == null }) return null
        if (inventory.installedSchemes.any { scheme -> scheme.since > now || !topology.containsNode(
                StrategicNodeRef.LandProvince(scheme.provinceId)) || world.getGeneralById(scheme.ownerGeneralId)?.nationId != scheme.nationId }) return null
        val scheme = inventory.installedSchemes.any { it.provinceId == node.id && hostile(it.nationId) }
        val actorIsCorps = projection.deployed.any { it.commanderGeneralId == actorId }
        val edges = try { HwihaLandPassageState.read(world.getState().meta, topology) } catch (_: IllegalArgumentException) { null }
            ?: return null
        val nationEdges = hashMapOf<Int, StrategicEdgeStateSnapshot?>()
        fun passableFor(nationId: Int): StrategicEdgeStateSnapshot? = nationEdges.getOrPut(nationId) {
            HwihaRoadFortPassage.forNation(world, edges, nationId)
        }
        val interceptors = if (!actorIsCorps) emptyList() else inventory.interceptions.filter { hostile(it.nationId) }.mapNotNull { order ->
            val from = world.positionOf(order.commanderGeneralId) as? StrategicNodeRef.LandProvince ?: return@mapNotNull null
            if (from == node || !visible(world, order.ownerGeneralId, node, projection, now)) return@mapNotNull null
            val passage = passableFor(order.nationId) ?: return@mapNotNull null
            val path = (StrategicPathResolver.resolveLandMarch(topology, StrategicPathRequest(from, node, 1), passage, metrics)
                as? LandMarchPathResult.Resolved)?.path ?: return@mapNotNull null
            if (path.edgeIds.size > HwihaS3Provisional.INTERCEPT_RANGE_PROVINCES) return@mapNotNull null
            order.commanderGeneralId
        }.distinct().sorted()
        val evaders = linkedMapOf<Int, StrategicNodeRef.LandProvince>()
        val yielding = sortedSetOf<String>()
        for (order in inventory.avoidanceOrders.filter { hostile(it.nationId) }) {
            if (world.positionOf(order.commanderGeneralId) != node) continue
            val passage = passableFor(order.nationId) ?: continue
            val retreat = retreat(world, order, node, passage, now) ?: continue
            evaders[order.commanderGeneralId] = retreat
            yielding += order.orderId
        }
        return Decision(scheme, interceptors, evaders, yielding)
    }

    /** A live target in FULL sight only; stale INTEL and FOG never grant interception. */
    private fun visible(world: InMemoryTurnWorld, viewerId: Int, target: StrategicNodeRef.LandProvince,
        projection: DeploymentProjection, now: HwihaPhase): Boolean {
        val viewer = world.getGeneralById(viewerId) ?: return false
        val territory = world.provinceControlSnapshot()?.statesByProvinceId.orEmpty().values
            .filter { it.nationId == viewer.nationId }.mapTo(sortedSetOf()) { it.provinceId }
        val cards = world.listRetainers().filter { it.masterGeneralId == viewerId }
        val posts = HwihaMetaVisionSourceReader.scoutPosts(viewer.meta).value
            .filter { post -> cards.any { it.id == post.retainerId } }
        val watchtowers = world.listCities().filter { it.nationId == viewer.nationId }.mapNotNull { city ->
            if (!HwihaMetaVisionSourceReader.hasCompletedWatchtower(city.meta).value) return@mapNotNull null
            (world.landNodeOfCity(city.id) as? StrategicNodeRef.LandProvince)?.let { city.id to it.id }
        }
        val reports = try { HwihaScoutReports.read(viewer.meta) } catch (_: IllegalArgumentException) { null }
        val sources = VisionViewer(viewerId, viewer.nationId.coerceAtLeast(0), world.positionOf(viewerId),
            projection.deployed.filter { it.ownerGeneralId == viewerId }
                .associate { it.commanderGeneralId to world.positionOf(it.commanderGeneralId) },
            cards.mapNotNull { it.generalId }.distinct().associateWith(world::positionOf),
            territory, posts, watchtowers, reports)
        val commandery = commanderies.commanderyOf(target.id) ?: return false
        return HwihaVision.project(sources, commanderies, visionRules, now).tierOf(commandery) == VisionTier.FULL
    }

    private fun retreat(world: InMemoryTurnWorld, order: HwihaReactionOrder, from: StrategicNodeRef.LandProvince,
        edges: StrategicEdgeStateSnapshot, now: HwihaPhase): StrategicNodeRef.LandProvince? {
        val actor = world.getGeneralById(order.commanderGeneralId) ?: return null
        val march = try { HwihaCorpsMarchState.read(actor.meta, topology, metrics) } catch (_: IllegalArgumentException) { null }
        if (march?.checkpoint?.lastAdvancedAt == now) return null // this turn's movement was already spent
        fun own(province: String): Boolean = world.provinceControlSnapshot()?.statesByProvinceId?.get(province)?.nationId
            ?.let { it == order.nationId } ?: world.listCities().any { city ->
                city.nationId == order.nationId && world.landNodeOfCity(city.id) == StrategicNodeRef.LandProvince(province)
            }
        val candidates = topology.traversalEdges.filter(LandMarchMetricSnapshot::supports).mapNotNull { edge ->
            val to = when {
                edge.from == from -> edge.to as? StrategicNodeRef.LandProvince
                edge.to == from && !edge.directed -> edge.from as? StrategicNodeRef.LandProvince
                else -> null
            } ?: return@mapNotNull null
            if (!own(to.id)) return@mapNotNull null
            val path = (StrategicPathResolver.resolveLandMarch(topology, StrategicPathRequest(from, to, 1), edges, metrics)
                as? LandMarchPathResult.Resolved)?.path ?: return@mapNotNull null
            if (path.edgeIds.size != 1) return@mapNotNull null
            path.totalCostMm to to
        }
        return candidates.minWithOrNull(compareBy({ it.first }, { it.second.id }))?.second
    }
}
