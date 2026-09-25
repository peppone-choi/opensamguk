package opensamguk.gameapi.read

import opensamguk.logic.vision.VisionSourceReader
import opensamguk.logic.vision.MetaVisionSourceReader

import opensamguk.logic.vision.ScoutFailure
import opensamguk.logic.vision.ScoutAssessment
import opensamguk.logic.vision.ScoutRules
import opensamguk.logic.vision.ScoutReports

import opensamguk.logic.vision.VisionView
import opensamguk.logic.vision.VisionViewer
import opensamguk.logic.vision.Vision
import opensamguk.logic.vision.CorpsVisibility

import opensamguk.logic.vision.VisionRules

import opensamguk.gameapi.dto.*
import opensamguk.infra.seed.ResolvedHanWorldArtifacts
import opensamguk.logic.input.*
import opensamguk.logic.retainer.RetainerRules
import opensamguk.logic.world.HanCommanderyIndex
import opensamguk.logic.world.StrategicNodeRef
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

class HwihaVisionForbidden : RuntimeException()

/**
 * Server-side vision projection (#785 · #343 · #465). **Read only** — no recorder, no writes.
 *
 * Every response is computed for exactly one viewer (`?generalId=` owned by the principal, else 403). Data the
 * viewer may not see is filtered here, before serialization: a corps in a FOG commandery is never loaded into a
 * DTO, so it cannot appear in the bytes. The same logic functions ([Vision], [CorpsVisibility],
 * [ScoutRules]) are what the engine and reservation admission use.
 *
 * Corruption anywhere in the authoritative state yields `UNAVAILABLE` (nothing), never a partial map.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class HwihaVisionReader(
    private val generals: GeneralReadRepository,
    private val worlds: WorldStateReadRepository,
    private val nations: NationReadRepository,
    private val retainers: RetainerReadRepository,
    private val artifacts: ActiveWorldArtifactResolver,
    private val spatial: SpatialStateReadRepository,
    private val sources: VisionSourceReader,
    private val rules: VisionRules.Rules,
) {
    @Autowired
    constructor(generals: GeneralReadRepository, worlds: WorldStateReadRepository, nations: NationReadRepository,
        retainers: RetainerReadRepository, artifacts: ActiveWorldArtifactResolver, spatial: SpatialStateReadRepository) :
        this(generals, worlds, nations, retainers, artifacts, spatial, MetaVisionSourceReader, VisionRules.CANON)

    fun visibility(generalId: Int, userId: Long): HwihaVisibilityResponse {
        val frame = when (val built = frame(generalId, userId)) {
            is Built.Failed -> return HwihaVisibilityResponse(built.status)
            is Built.Ready -> built.frame
        }
        val view = frame.view
        return HwihaVisibilityResponse(
            status = "READY",
            stamp = stamp(view.now),
            commanderies = frame.index.commanderies.map { commandery ->
                val entry = requireNotNull(view.entry(commandery.no))
                HwihaVisibilityCommanderyDto(commandery.no, commandery.id, commandery.name, entry.tier.name,
                    entry.seenAt?.let(::stamp), entry.ageTurns)
            },
            sources = view.sources.map { HwihaVisionSourceDto(it.kind.name, it.commanderyNo, it.radius, it.provinceId, it.refId) },
            invalidSourceRecords = frame.invalidSources,
        )
    }

    fun corps(generalId: Int, userId: Long): HwihaCorpsResponse {
        val frame = when (val built = frame(generalId, userId)) {
            is Built.Failed -> return HwihaCorpsResponse(built.status)
            is Built.Ready -> built.frame
        }
        val sightings = CorpsVisibility.project(frame.viewer, frame.view, frame.index, frame.projection, rules)
        val people = frame.people.associateBy { it.id }
        val nationColors = nations.findAll().associate { it.id to it.color.takeIf(String::isNotBlank) }
        val bundle = frame.bundle
        val rows = sightings.map { seen ->
            val path = if (seen.own) ownPath(people[seen.commanderGeneralId], bundle) else null
            HwihaCorpsDto(
                corpsId = seen.orderId ?: seen.corpsKey,
                ownerGeneralId = seen.ownerGeneralId,
                ownerName = people[seen.ownerGeneralId]?.name,
                commanderGeneralId = seen.commanderGeneralId,
                commanderName = people[seen.commanderGeneralId]?.name,
                nationId = seen.nationId,
                nationColor = nationColors[seen.nationId]?.takeIf { seen.nationId != 0 },
                provinceId = seen.provinceId,
                commanderyNo = seen.commanderyNo,
                visibility = seen.visibility.name,
                own = seen.own,
                troops = seen.troops,
                troopsBand = seen.troopsBand?.let { code -> rules.bandByCode(code)?.let { HwihaTroopBandDto(it.code, it.label) } },
                marchPath = path?.first,
                destinationProvinceId = path?.second,
                lastSeenStamp = seen.seenAt?.let(::stamp),
                ageTurns = seen.ageTurns,
            )
        }
        return HwihaCorpsResponse("READY", stamp(frame.view.now), rows)
    }

    fun scoutOptions(generalId: Int, userId: Long): HwihaScoutOptionsResponse {
        val frame = when (val built = frame(generalId, userId)) {
            is Built.Failed -> return blocked(built.status, if (built.status == "WRONG_RULE_PROFILE")
                ScoutFailure.WRONG_RULE_PROFILE else ScoutFailure.STATE_UNAVAILABLE)
            is Built.Ready -> built.frame
        }
        val index = frame.index
        val cost = rules.scoutCost.let { HwihaResourceCostDto(it.money, it.grain, it.iron, it.timber, it.horses) }
        val node = frame.viewer.actorNode as? StrategicNodeRef.LandProvince
        val origin = index.commanderyOf(node)
            ?: return blocked("READY", ScoutFailure.POSITION_UNAVAILABLE).copy(cost = cost)
        val options = index.neighbours(origin).map { no ->
            val commandery = index.commanderies[no]
            val entry = requireNotNull(frame.view.entry(no))
            val check = ScoutRules.assess(RuleProfile.HWIHA, node, commandery.id, index)
            val failure = (check as? ScoutAssessment.Rejected)?.reason
            HwihaScoutOptionDto(no, commandery.id, commandery.name, entry.tier.name, failure == null,
                failure?.name, failure?.let(ScoutRules::reason), entry.seenAt?.let(::stamp), entry.ageTurns)
        }
        val any = options.any { it.available }
        return HwihaScoutOptionsResponse(
            status = "READY", available = any,
            code = if (any) null else ScoutFailure.NOT_ADJACENT.name,
            reason = if (any) null else "맞닿은 郡國이 없어 첩보할 수 없습니다.",
            origin = HwihaScoutOriginDto(requireNotNull(node).id, origin, index.commanderies[origin].id, index.commanderies[origin].name),
            cost = cost, options = options,
        )
    }

    /** Reservation precheck: the same rule the personal turn re-runs ([ScoutRules.assess]). */
    fun assessScout(generalId: Int, userId: Long, commanderyId: String): ScoutAssessment {
        val frame = when (val built = frame(generalId, userId)) {
            is Built.Failed -> return ScoutAssessment.Rejected(
                if (built.status == "WRONG_RULE_PROFILE") ScoutFailure.WRONG_RULE_PROFILE else ScoutFailure.STATE_UNAVAILABLE)
            is Built.Ready -> built.frame
        }
        return ScoutRules.assess(RuleProfile.HWIHA, frame.viewer.actorNode, commanderyId, frame.index)
    }

    // ── 공용 ───────────────────────────────────────────────────────────────

    private class Frame(
        val bundle: ResolvedHanWorldArtifacts,
        val index: HanCommanderyIndex,
        val people: List<GeneralReadEntity>,
        val projection: DeploymentProjection,
        val viewer: VisionViewer,
        val view: VisionView,
        val invalidSources: Int,
    )

    private sealed interface Built {
        data class Ready(val frame: Frame) : Built
        data class Failed(val status: String) : Built
    }

    private fun frame(generalId: Int, userId: Long): Built {
        val actor = generals.findById(generalId).orElse(null) ?: throw HwihaVisionForbidden()
        if (userId <= 0 || userId > Int.MAX_VALUE || actor.userId?.toLongOrNull() != userId) throw HwihaVisionForbidden()
        val world = worlds.findProcessWorld() ?: return Built.Failed("UNAVAILABLE")
        if (actor.worldId != world.id) return Built.Failed("UNAVAILABLE")
        if (world.config["ruleProfile"] != "HWIHA") return Built.Failed("WRONG_RULE_PROFILE")
        // resolve() runs in its own transactional proxy: let its failures propagate instead of swallowing them into
        // a rollback-only outer transaction (see HwihaCampReader.county).
        val selected = artifacts.resolve() ?: return Built.Failed("UNAVAILABLE")
        val bundle = selected.artifacts ?: return Built.Failed("UNAVAILABLE")
        return try {
            require(world.currentMonth in 1..12 && world.currentPhase in 1..3 && world.currentYear >= 0)
            val now = HwihaPhase(world.currentYear, world.currentMonth, world.currentPhase)
            val index = bundle.commanderyIndex
            val topology = bundle.projection.topology
            val people = generals.findAll(); val cards = retainers.findAll(); val units = retainers.allBugoks()
            require(people.all { it.worldId == world.id } && cards.all { it.worldId == world.id } && units.all { it.worldId == world.id })
            val snapshot = spatial.readSnapshot(world.id, topology)
            val positions = snapshot.generalPositionSnapshot
            val projection = requireNotNull(HwihaDeploymentProjection.build(RuleProfile.HWIHA,
                people.map { DeploymentPersonSource(it.id, it.nationId,
                    it.npcState == 2 && (it.userId.isNullOrBlank() || it.userId?.toLongOrNull()?.let { id -> id <= 0 } == true), it.meta) },
                units.map { DeploymentUnit(it.id, it.masterGeneralId, it.troops, it.commanderRetainerId) },
                cards.map { DeploymentRetainer(it.id, it.masterGeneralId, it.generalId, it.relation == RetainerRules.RELATION_LIEUTENANT) },
                positions, topology, bundle.landMarchMetrics)) { "Deployment authority is unavailable" }
            var invalid = 0
            val ownCards = cards.filter { it.masterGeneralId == actor.id }
            val posts = sources.scoutPosts(actor.meta).let { read ->
                invalid += read.invalid
                read.value.filter { post ->
                    (post.retainerId in ownCards.map { it.id } && index.commanderyOf(post.provinceId) != null).also { if (!it) invalid++ }
                }
            }
            val watchtowers = if (actor.nationId <= 0) emptyList() else selected.cities.filter { it.nationId == actor.nationId }
                .sortedBy { it.id }.mapNotNull { city ->
                    val read = sources.hasCompletedWatchtower(city.meta)
                    invalid += read.invalid
                    if (!read.value) return@mapNotNull null
                    val province = bundle.projection.bindingsByCityId[city.id]?.landProvinceId
                    if (province == null) { invalid++; null } else city.id to province
                }
            val reports = try { ScoutReports.read(actor.meta) } catch (_: IllegalArgumentException) { invalid++; null }
            val viewer = VisionViewer(
                actorId = actor.id,
                nationId = actor.nationId.coerceAtLeast(0),
                actorNode = positions.stateFor(actor.id)?.node,
                ownCorpsNodes = projection.deployed.filter { it.ownerGeneralId == actor.id }
                    .associate { it.commanderGeneralId to positions.stateFor(it.commanderGeneralId)?.node },
                retinueNodes = ownCards.mapNotNull { it.generalId }.distinct().sorted()
                    .associateWith { positions.stateFor(it)?.node },
                territoryProvinceIds = if (actor.nationId <= 0) emptySet() else snapshot.provinceControlSnapshot.statesByProvinceId
                    .values.filter { it.nationId == actor.nationId }.map { it.provinceId }.toSortedSet(),
                scoutPosts = posts,
                watchtowers = watchtowers,
                reports = reports,
            )
            Built.Ready(Frame(bundle, index, people, projection, viewer, Vision.project(viewer, index, rules, now), invalid))
        } catch (_: IllegalArgumentException) { Built.Failed("UNAVAILABLE") }
          catch (_: IllegalStateException) { Built.Failed("UNAVAILABLE") }
          catch (_: java.io.IOException) { Built.Failed("UNAVAILABLE") }
    }

    /** Remaining land path and destination of the viewer's own corps (current province first). */
    private fun ownPath(commander: GeneralReadEntity?, bundle: ResolvedHanWorldArtifacts): Pair<List<String>?, String?>? {
        commander ?: return null
        val topology = bundle.projection.topology
        return try {
            val order = HwihaCorpsOrder.read(commander.meta, topology)
            val march = HwihaCorpsMarchState.read(commander.meta, topology, bundle.landMarchMetrics)
            val path = march?.checkpoint?.let { checkpoint ->
                checkpoint.path.nodeKeys.drop(checkpoint.cursor.edgeIndex).map { it.removePrefix("land:") }
            }
            path to order?.destination?.id
        } catch (_: IllegalArgumentException) { null }
    }

    private fun blocked(status: String, failure: ScoutFailure) =
        HwihaScoutOptionsResponse(status = status, available = false, code = failure.name, reason = ScoutRules.reason(failure))

    private fun stamp(phase: HwihaPhase) = HwihaStampDto(phase.year, phase.month, phase.phase)
}
