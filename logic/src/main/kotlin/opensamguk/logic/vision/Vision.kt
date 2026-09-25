package opensamguk.logic.vision

import opensamguk.logic.input.*


import opensamguk.logic.world.HanCommanderyIndex
import opensamguk.logic.world.StrategicNodeRef

enum class VisionTier { FULL, INTEL, FOG }

/** One reason a commandery is FULL. [refId] is the city/retainer/general behind it (own data only). */
data class VisionSource(val kind: VisionSourceKind, val commanderyNo: Int, val radius: Int,
    val provinceId: String?, val refId: Int?) {
    init { require(commanderyNo >= 0 && radius >= 0) }
}

data class VisionEntry(val no: Int, val tier: VisionTier, val seenAt: HwihaPhase?, val ageTurns: Int?) {
    init {
        require((tier == VisionTier.INTEL) == (seenAt != null) && (seenAt == null) == (ageTurns == null)) {
            "Only INTEL carries a sighting stamp"
        }
        require(ageTurns == null || ageTurns >= 0)
    }
}

class VisionView(val now: HwihaPhase, val entries: List<VisionEntry>, val sources: List<VisionSource>,
    val reports: ScoutReports?) {
    init { require(entries.withIndex().all { (index, it) -> it.no == index }) }
    fun tierOf(no: Int): VisionTier = entries.getOrNull(no)?.tier ?: VisionTier.FOG
    fun entry(no: Int): VisionEntry? = entries.getOrNull(no)
}

/**
 * Everything the viewer legitimately owns. The caller builds this from authoritative state; nothing here is a
 * guess from co-location or legacy fields. Positions are commander/card positions from `general_spatial_position`.
 */
data class VisionViewer(
    val actorId: Int,
    val nationId: Int,
    val actorNode: StrategicNodeRef?,
    /** Commander positions of corps the actor owns, keyed by commander general id. */
    val ownCorpsNodes: Map<Int, StrategicNodeRef?>,
    /** Positions of people on the actor's own retainer cards, keyed by general id. */
    val retinueNodes: Map<Int, StrategicNodeRef?>,
    /** Land provinces controlled by the actor's nation (empty when unaffiliated). */
    val territoryProvinceIds: Set<String>,
    val scoutPosts: List<ScoutPost>,
    /** (cityId, provinceId) of own-nation county seats with a completed watchtower/beacon. */
    val watchtowers: List<Pair<Int, String>>,
    val reports: ScoutReports?,
) {
    init {
        require(actorId > 0 && nationId >= 0)
        require(nationId > 0 || territoryProvinceIds.isEmpty()) { "An unaffiliated viewer has no territory" }
    }
}

object Vision {
    /** Deterministic source list: kind order, then commandery, then province, then reference id. */
    fun sources(viewer: VisionViewer, index: HanCommanderyIndex, rules: VisionRules.Rules): List<VisionSource> {
        val out = mutableListOf<VisionSource>()
        fun add(kind: VisionSourceKind, node: StrategicNodeRef?, ref: Int?) {
            val province = (node as? StrategicNodeRef.LandProvince)?.id ?: return
            val no = index.commanderyOf(province) ?: return
            out += VisionSource(kind, no, rules.radius(kind), province, ref)
        }
        add(VisionSourceKind.SELF, viewer.actorNode, viewer.actorId)
        viewer.ownCorpsNodes.forEach { (commander, node) -> add(VisionSourceKind.OWN_CORPS, node, commander) }
        viewer.retinueNodes.forEach { (person, node) -> add(VisionSourceKind.RETINUE, node, person) }
        viewer.territoryProvinceIds.forEach { add(VisionSourceKind.TERRITORY, StrategicNodeRef.LandProvince(it), null) }
        viewer.scoutPosts.forEach { add(VisionSourceKind.SCOUT_POST, StrategicNodeRef.LandProvince(it.provinceId), it.retainerId) }
        viewer.watchtowers.forEach { (city, province) ->
            add(VisionSourceKind.WATCHTOWER_BEACON, StrategicNodeRef.LandProvince(province), city)
        }
        // Territory is per province; collapse to one row per commandery so the list stays small and stable.
        return out.distinctBy { if (it.kind == VisionSourceKind.TERRITORY) "T:${it.commanderyNo}" else "${it.kind}:${it.provinceId}:${it.refId}" }
            .map { if (it.kind == VisionSourceKind.TERRITORY) it.copy(provinceId = null) else it }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.commanderyNo }, { it.provinceId ?: "" }, { it.refId ?: 0 }))
    }

    /**
     * FULL = within a source's radius. INTEL = not FULL but scouted (snapshot stamp and age). FOG = neither.
     * Reports bound to other tiles are ignored — they cannot name a commandery of this map.
     */
    fun project(viewer: VisionViewer, index: HanCommanderyIndex, rules: VisionRules.Rules, now: HwihaPhase): VisionView {
        val sources = sources(viewer, index, rules)
        val full = sortedSetOf<Int>()
        sources.forEach { full += index.within(it.commanderyNo, it.radius) }
        val reports = viewer.reports?.takeIf { it.tilesContentHash == index.tilesContentHash }
        val seen = reports?.reports.orEmpty().mapNotNull { report -> index.byId(report.commanderyId)?.let { it.no to report } }.toMap()
        val entries = index.commanderies.map { commandery ->
            val report = seen[commandery.no]
            when {
                commandery.no in full -> VisionEntry(commandery.no, VisionTier.FULL, null, null)
                report != null && report.seenAt <= now ->
                    VisionEntry(commandery.no, VisionTier.INTEL, report.seenAt, ageTurns(report.seenAt, now))
                else -> VisionEntry(commandery.no, VisionTier.FOG, null, null)
            }
        }
        return VisionView(now, entries, sources, reports)
    }

    /** Whole 旬 between two stamps (36 per year). */
    fun ageTurns(seenAt: HwihaPhase, now: HwihaPhase): Int {
        val diff = ordinal(now) - ordinal(seenAt)
        require(diff >= 0) { "A sighting cannot be in the future" }
        return Math.toIntExact(diff)
    }

    private fun ordinal(phase: HwihaPhase): Long = phase.year.toLong() * 36 + (phase.month - 1) * 3 + phase.phase - 1
}

/** A corps as a given viewer may see it. Fields that the viewer is not entitled to are null, never zeroed. */
data class CorpsSighting(
    val corpsKey: String,
    /** Raw order id — only for the viewer's own corps. */
    val orderId: String?,
    val ownerGeneralId: Int,
    val commanderGeneralId: Int,
    val nationId: Int,
    val provinceId: String,
    val commanderyNo: Int,
    val visibility: VisionTier,
    val own: Boolean,
    /** Exact troops — own corps only. */
    val troops: Int?,
    /** Band code — other corps only. */
    val troopsBand: String?,
    /** INTEL only: when the scouting snapshot was taken. */
    val seenAt: HwihaPhase?,
    val ageTurns: Int?,
) {
    init {
        require(visibility != VisionTier.FOG) { "A FOG corps is never a sighting" }
        require(own == (orderId != null) && own == (troops != null) && own != (troopsBand != null))
        require((visibility == VisionTier.INTEL) == (seenAt != null) && (seenAt == null) == (ageTurns == null))
        require(!own || visibility == VisionTier.FULL) { "Own corps are always live" }
    }
}

/**
 * Server-side corps projection (#343/#785/#465). Live state is emitted only for the viewer's own corps and
 * corps standing in a FULL commandery. In an INTEL commandery the viewer sees only its scouting snapshot —
 * the rule-allowed last sighting — and nothing live. FOG commanderies emit nothing at all.
 */
object CorpsVisibility {
    fun project(viewer: VisionViewer, view: VisionView, index: HanCommanderyIndex, projection: DeploymentProjection,
        rules: VisionRules.Rules): List<CorpsSighting> {
        val nodes = projection.people.associate { it.id to it.node }
        val live = projection.deployed.mapNotNull { corps ->
            val province = (nodes[corps.commanderGeneralId] as? StrategicNodeRef.LandProvince)?.id ?: return@mapNotNull null
            val no = index.commanderyOf(province) ?: return@mapNotNull null
            val own = corps.ownerGeneralId == viewer.actorId
            if (!own) {
                if (view.tierOf(no) != VisionTier.FULL) return@mapNotNull null
                // A stale or broken relationship is not a standing army on the map.
                if (HwihaDeploymentRules.assessActive(corps, projection) !is DeploymentAssessment.Eligible) return@mapNotNull null
            }
            val troops = CorpsTroops.of(corps, projection)
            CorpsSighting(ScoutCapture.corpsKey(corps.orderId), corps.orderId.takeIf { own }, corps.ownerGeneralId,
                corps.commanderGeneralId, corps.nationId, province, no, VisionTier.FULL, own,
                troops.takeIf { own }, if (own) null else rules.band(troops).code, null, null)
        }
        val liveKeys = live.map { it.corpsKey }.toSet()
        val intel = view.reports?.reports.orEmpty().flatMap { report ->
            val no = index.byId(report.commanderyId)?.no ?: return@flatMap emptyList()
            val entry = view.entry(no) ?: return@flatMap emptyList()
            if (entry.tier != VisionTier.INTEL) return@flatMap emptyList()
            report.corps.filter { it.ownerGeneralId != viewer.actorId && it.corpsKey !in liveKeys }.mapNotNull { seen ->
                // Only a sighting whose province still belongs to that commandery on this map.
                if (index.commanderyOf(seen.provinceId) != no || rules.bandByCode(seen.troopsBand) == null) return@mapNotNull null
                CorpsSighting(seen.corpsKey, null, seen.ownerGeneralId, seen.commanderGeneralId, seen.nationId,
                    seen.provinceId, no, VisionTier.INTEL, false, null, seen.troopsBand, entry.seenAt, entry.ageTurns)
            }
        }
        return (live + intel).sortedWith(compareBy({ !it.own }, { it.visibility.ordinal }, { it.commanderyNo }, { it.corpsKey }))
    }
}
