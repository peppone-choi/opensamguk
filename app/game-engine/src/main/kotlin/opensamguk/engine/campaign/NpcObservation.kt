package opensamguk.engine.campaign

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.City
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.TurnGeneral
import opensamguk.logic.domestic.DomesticProjection
import opensamguk.logic.input.DeploymentProjection
import opensamguk.logic.input.LandPassageState
import opensamguk.logic.input.MarchReactions
import opensamguk.logic.input.Phase
import opensamguk.logic.input.PeopleAssessment
import opensamguk.logic.input.PeopleInput
import opensamguk.logic.input.PeopleRequest
import opensamguk.logic.input.PeopleRules
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.input.TalentDiscovery
import opensamguk.logic.vision.CorpsSighting
import opensamguk.logic.vision.CorpsVisibility
import opensamguk.logic.vision.MetaVisionSourceReader
import opensamguk.logic.vision.ScoutReports
import opensamguk.logic.vision.Vision
import opensamguk.logic.vision.VisionRules
import opensamguk.logic.vision.VisionTier
import opensamguk.logic.vision.VisionView
import opensamguk.logic.vision.VisionViewer
import opensamguk.logic.world.CommanderyIndex
import opensamguk.logic.world.LandMarchMetricSnapshot
import opensamguk.logic.world.StrategicNodeRef
import opensamguk.logic.world.StrategicTopologySnapshot

/** Only facts the observing NPC owns or may see. No authoritative world object crosses this boundary. */
internal data class NpcObservation(
    val actor: TurnGeneral,
    val now: Phase,
    val node: StrategicNodeRef?,
    val vision: VisionView,
    val corps: List<CorpsSighting>,
    val ownUnits: List<NpcOwnUnit>,
    val ownDeployment: DeploymentProjection,
    val musterMeta: Map<String, Any?>?,
    val ownCities: Map<Int, City>,
    val domestic: DomesticProjection?,
    val peopleActions: NpcPeopleActions?,
    val heldByAnotherGeneral: Boolean,
    val ownCountyIds: Set<Int>,
    val foreignCounties: List<NpcCountySighting>,
) {
    fun tierOf(provinceId: String, index: CommanderyIndex): VisionTier =
        index.commanderyOf(provinceId)?.let(vision::tierOf) ?: VisionTier.FOG
}

internal data class NpcOwnUnit(val id: Int, val troops: Int, val provisions: Int, val commanderRetainerId: Int?)

/** Local action availability, without undiscovered identities or another person's private state. */
internal data class NpcPeopleActions(
    val captiveIds: List<Int>,
    val recruitIds: List<Int>,
    val canSearch: Boolean,
)

/** Current FULL allegiance or a dated INTEL notebook entry. Never carries live foreign garrison strength. */
internal data class NpcCountySighting(
    val cityId: Int,
    val nationId: Int,
    val provinceId: String,
    val tier: VisionTier,
    val seenAt: Phase?,
    val warehouse: Boolean?,
) {
    init {
        require(tier != VisionTier.FOG)
        require((tier == VisionTier.INTEL) == (seenAt != null))
        require((tier == VisionTier.INTEL) == (warehouse != null))
    }
}

/** Authority stays here; selectors receive the result, never [InMemoryTurnWorld]. */
internal class NpcObservationFactory(
    private val topology: StrategicTopologySnapshot,
    private val metrics: LandMarchMetricSnapshot,
    private val index: CommanderyIndex,
    private val rules: VisionRules.Rules = VisionRules.CANON,
    private val domesticContext: DomesticContext? = null,
) {
    fun build(world: InMemoryTurnWorld, actorId: Int): NpcObservation? {
        if (world.ruleProfile != RuleProfile.HWIHA) return null
        val actor = world.getGeneralById(actorId) ?: return null
        if (!NpcDeploySelector.isUnowned(actor.userId) || actor.npcState < 2) return null
        val state = world.getState()
        val now = runCatching { Phase(state.currentYear, state.currentMonth, state.currentPhase) }.getOrNull()
            ?: return null
        val projection = runCatching {
            DeploymentExecutor(world, ChangeRecorder(), topology, metrics).projection()
        }.getOrNull() ?: return null
        val ownCards = world.listRetainers().filter { it.masterGeneralId == actorId }
        val ownCardIds = ownCards.mapTo(hashSetOf()) { it.id }
        val ownedPersonIds = ownCards.mapNotNull { it.generalId }.toSet() + actorId
        val ownDeployment = projection.copy(
            people = projection.people.filter { it.id in ownedPersonIds },
            units = projection.units.filter { it.ownerId == actorId },
            retainers = projection.retainers.filter { it.ownerId == actorId },
            deployed = projection.deployed.filter { it.ownerGeneralId == actorId },
        )
        // MusterRules needs the public passage authority and only the presence of reaction
        // metadata. Do not hand the selector the world meta, which also holds secret orders.
        val passage = runCatching { LandPassageState.read(state.meta, topology) }.getOrNull()
        val reactionsReady = MarchReactions.presence(state.meta).let {
            it != MarchReactions.Presence.MISSING && it != MarchReactions.Presence.MALFORMED
        }
        val musterMeta = if (passage != null && reactionsReady) mapOf(
            LandPassageState.META_KEY to state.meta[LandPassageState.META_KEY],
            MarchReactions.META_KEY to MarchReactions.Empty.toMetaValue(),
        ) else null
        val posts = MetaVisionSourceReader.scoutPosts(actor.meta).value.filter {
            it.retainerId in ownCardIds && index.commanderyOf(it.provinceId) != null
        }
        val watchtowers = if (actor.nationId <= 0) emptyList() else world.listCities()
            .filter { it.nationId == actor.nationId && MetaVisionSourceReader.hasCompletedWatchtower(it.meta).value }
            .mapNotNull { city ->
                (world.landNodeOfCity(city.id) as? StrategicNodeRef.LandProvince)?.let { city.id to it.id }
            }
        val reports = runCatching { ScoutReports.read(actor.meta) }.getOrNull()
        val viewer = VisionViewer(
            actorId = actorId,
            nationId = actor.nationId.coerceAtLeast(0),
            actorNode = world.positionOf(actorId),
            ownCorpsNodes = projection.deployed.filter { it.ownerGeneralId == actorId }
                .associate { it.commanderGeneralId to world.positionOf(it.commanderGeneralId) },
            retinueNodes = ownCards.mapNotNull { it.generalId }.distinct().sorted()
                .associateWith(world::positionOf),
            territoryProvinceIds = if (actor.nationId <= 0) emptySet() else
                world.provinceControlSnapshot()?.statesByProvinceId.orEmpty().values
                    .filter { it.nationId == actor.nationId }.mapTo(sortedSetOf()) { it.provinceId },
            scoutPosts = posts,
            watchtowers = watchtowers,
            reports = reports,
        )
        val projected = Vision.project(viewer, index, rules, now)
        val corps = CorpsVisibility.project(viewer, projected, index, projection, rules)
        // The authority read can contain future or foreign-map notebook rows. Do not carry them
        // into a selector, even through the actor's own meta or the view's raw reports field.
        val usableReports = projected.reports?.let { notebook ->
            notebook.copy(reports = notebook.reports.filter { report ->
                report.seenAt <= now && index.byId(report.commanderyId)?.no?.let {
                    projected.tierOf(it) == VisionTier.INTEL
                } == true
            })
        }
        val view = VisionView(projected.now, projected.entries, projected.sources, usableReports)
        val ownCountyIds = world.listCities().filter { actor.nationId > 0 && it.nationId == actor.nationId &&
            it.id in world.administrativeCountyIds }.mapTo(sortedSetOf()) { it.id }
        val ownCities = world.listCities().filter { it.id in ownCountyIds }
            .associate { it.id to it.copy(meta = it.meta.toMap()) }
        val fullDomestic = domesticContext?.projection(world)
        val peopleActions = fullDomestic?.let { projectPeopleActions(it, actorId, view) }
        val domestic = fullDomestic?.let { full ->
            full.copy(
                people = full.people.filter { it.id in ownedPersonIds }
                    .map { it.copy(meta = it.meta - ScoutReports.META_KEY) },
                cards = full.cards.filter { it.masterId == actorId },
                counties = full.counties.filter { it.id in ownCountyIds }.map { it.copy(meta = it.meta.toMap()) },
                nations = full.nations.filter { it.id == actor.nationId }.map { it.copy(meta = it.meta.toMap()) },
                homeCountyByGeneral = full.homeCountyByGeneral.filterKeys { it in ownedPersonIds },
                provinceIdsByCounty = full.provinceIdsByCounty.filterKeys { it in ownCountyIds },
                bugoks = full.bugoks.filter { it.masterGeneralId == actorId },
                countyAdjacency = full.countyAdjacency.filterKeys { it in ownCountyIds },
                diplomacy = full.diplomacy.filter { it.fromNationId == actor.nationId },
                activeSiegeCountyIds = full.activeSiegeCountyIds.intersect(ownCountyIds),
            )
        }
        val foreignFull = world.listCities().asSequence().filter { it.id in world.administrativeCountyIds &&
            it.nationId != actor.nationId }.mapNotNull { city ->
            val province = (world.landNodeOfCity(city.id) as? StrategicNodeRef.LandProvince)?.id
                ?: return@mapNotNull null
            val commandery = index.commanderyOf(province) ?: return@mapNotNull null
            if (view.tierOf(commandery) != VisionTier.FULL) return@mapNotNull null
            NpcCountySighting(city.id, city.nationId, province, VisionTier.FULL, null, null)
        }.toList()
        val foreignIntel = view.reports?.reports.orEmpty().flatMap { report ->
            val commandery = index.byId(report.commanderyId)?.no ?: return@flatMap emptyList()
            if (view.tierOf(commandery) != VisionTier.INTEL) return@flatMap emptyList()
            report.cities.mapNotNull { seen ->
                val province = (world.landNodeOfCity(seen.cityId) as? StrategicNodeRef.LandProvince)?.id
                    ?: return@mapNotNull null
                if (index.commanderyOf(province) != commandery || seen.nationId == actor.nationId)
                    return@mapNotNull null
                NpcCountySighting(seen.cityId, seen.nationId, province, VisionTier.INTEL,
                    report.seenAt, seen.warehouse)
            }
        }
        return NpcObservation(
            actor = actor.copy(meta = actor.meta - ScoutReports.META_KEY),
            now = now,
            node = world.positionOf(actorId),
            vision = view,
            corps = corps,
            ownUnits = world.bugoksOf(actorId).map { NpcOwnUnit(it.id, it.troops, it.provisions, it.commanderRetainerId) },
            ownDeployment = ownDeployment,
            musterMeta = musterMeta,
            ownCities = ownCities,
            domestic = domestic,
            peopleActions = peopleActions,
            heldByAnotherGeneral = world.listRetainers().any { it.generalId == actorId },
            ownCountyIds = ownCountyIds,
            foreignCounties = (foreignFull + foreignIntel).sortedWith(compareBy({ it.cityId }, { it.tier.ordinal })),
        )
    }

    private fun projectPeopleActions(state: DomesticProjection, actorId: Int, view: VisionView): NpcPeopleActions? {
        val actor = state.person(actorId) ?: return null
        val node = actor.node ?: return null
        val commandery = index.commanderyOf(node) ?: return null
        if (view.tierOf(commandery) != VisionTier.FULL) return null
        val known = try { TalentDiscovery.read(actor.meta) }
            catch (_: IllegalArgumentException) { return null }
        fun eligible(inputId: String, targetId: Int?) =
            PeopleRules.assess(PeopleRequest(actorId, inputId, targetId), state) is PeopleAssessment.Eligible
        val local = state.peopleAt(node)
        return NpcPeopleActions(
            captiveIds = local.filter {
                (it.meta["captive"] as? Map<*, *>)?.get("captorGeneralId") == actorId &&
                    eligible(PeopleInput.PERSUADE_CAPTIVE, it.id)
            }.map { it.id },
            recruitIds = local.filter { it.id in known && eligible(PeopleInput.EMPLOY, it.id) }.map { it.id },
            canSearch = eligible(PeopleInput.SEARCH, null),
        )
    }
}
