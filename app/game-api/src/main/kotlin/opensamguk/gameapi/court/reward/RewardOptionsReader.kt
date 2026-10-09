package opensamguk.gameapi.court.reward

import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.owner.GeneralOwnershipReadSource
import opensamguk.gameapi.read.*
import opensamguk.logic.input.QueuedReward
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.input.WorldRuleProfile
import opensamguk.logic.world.WorldFormat
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

internal data class RewardPerson(val id: Int, val name: String, val cityId: Int)
internal data class RewardCard(val id: Int, val recipientId: Int, val loyalty: Int)
internal data class RewardCity(val id: Int, val nationId: Int, val supplied: Boolean, val meta: Map<String, Any?>)
internal data class RewardReadSnapshot(val generalId: Int, val status: String, val reason: String? = null,
    val date: RewardSnapshotDto? = null, val payerNationId: Int = 0, val payerNationPresent: Boolean = false,
    val capitalCityId: Int? = null, val queued: RewardQueuedDto = RewardQueuedDto("UNAVAILABLE", null, null),
    val cards: List<RewardCard> = emptyList(), val people: Map<Int, RewardPerson> = emptyMap(),
    val cities: List<RewardCity> = emptyList(), val countyIds: Set<Int> = emptySet())

internal class RewardStorageUnavailable(cause: DataAccessException) : RuntimeException(cause)

/** One persisted snapshot, including possession checks. No game-state writes or external side effects. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class RewardOptionsReader(
    private val ownership: GeneralOwnershipReadSource, private val generals: GeneralReadRepository,
    private val worlds: WorldStateReadRawRepository, private val retainers: RetainerReadRepository,
    private val cities: CityReadRepository, private val nations: NationReadRepository,
    private val artifacts: ActiveWorldArtifactResolver, processWorld: GameApiProcessWorld,
) {
    private val worldId = processWorld.worldId.value

    internal fun read(generalId: Int, userId: Long): RewardReadSnapshot {
        if (generalId <= 0 || userId <= 0 || userId > Int.MAX_VALUE) throw CampForbidden()
        val body = storage { ownership.findPlayableByUserId(userId.toString()) } ?: throw CampForbidden()
        if (body.id != generalId || body.worldId != worldId || body.userId != userId.toString() || body.npcState >= 2)
            throw CampForbidden()
        val actor = storage { ownedCampaignGeneral(generals, generalId, userId) }
        if (actor.worldId != worldId || actor.npcState != body.npcState) throw CampForbidden()
        // Read this exact process row without the wrapper's exception-only format gate, so both statuses remain distinct.
        val world = storage { worlds.findById(worldId).orElse(null) }
            ?: return unavailable(generalId, "WORLD_UNAVAILABLE")
        if (world.id != worldId) throw CampForbidden()
        val profile = try { WorldRuleProfile.resolve(world.config) } catch (_: IllegalArgumentException) { null }
        if (profile != null && profile != RuleProfile.HWIHA)
            return RewardReadSnapshot(generalId, "WRONG_RULE_PROFILE", "WRONG_RULE_PROFILE")
        try { WorldFormat.require(world.config, world.meta) } catch (_: IllegalArgumentException) {
            return RewardReadSnapshot(generalId, "UNSUPPORTED_WORLD_FORMAT", "UNSUPPORTED_WORLD_FORMAT")
        }
        if (world.currentYear <= 0 || world.currentMonth !in 1..12 || world.currentPhase !in 1..3)
            return unavailable(generalId, "DATE_UNAVAILABLE")
        val date = RewardSnapshotDto(world.currentYear, world.currentMonth, world.currentPhase)
        val people = storage { generals.findAll() }
        val cards = storage { retainers.findAll() }
        val cityRows = storage { cities.findAll() }
        val nation = if (actor.nationId > 0) storage { nations.findById(actor.nationId).orElse(null) } else null
        if (people.any { it.worldId != worldId } || cards.any { it.worldId != worldId } ||
            cityRows.any { it.worldId != worldId } || nation?.worldId?.let { it != worldId } == true ||
            people.map { it.id }.distinct().size != people.size || cards.map { it.id }.distinct().size != cards.size ||
            cityRows.map { it.id }.distinct().size != cityRows.size || cards.any { it.id <= 0 || it.loyalty !in 0..100 } ||
            people.singleOrNull { it.id == actor.id }?.let { it.nationId != actor.nationId || it.userId != actor.userId } != false)
            return unavailable(generalId, "ROSTER_INVALID", date)
        // Resolver failures must cross the transaction boundary unchanged; never turn them into a rollback-only success.
        val selected = artifacts.resolve() ?: return unavailable(generalId, "ARTIFACTS_UNAVAILABLE", date)
        if (selected.world.id != worldId || selected.cities.any { it.worldId != worldId })
            return unavailable(generalId, "ROSTER_INVALID", date)
        val bundle = selected.artifacts ?: return unavailable(generalId, "ARTIFACTS_UNAVAILABLE", date)
        val queued = try {
            QueuedReward.read(actor.meta)?.let { RewardQueuedDto("QUEUED", it.retainerId, it.money) }
                ?: RewardQueuedDto("NONE", null, null)
        } catch (_: IllegalArgumentException) { RewardQueuedDto("UNAVAILABLE", null, null) }
        return RewardReadSnapshot(generalId, "READY", date = date, payerNationId = actor.nationId,
            payerNationPresent = nation != null, capitalCityId = nation?.capitalCityId, queued = queued,
            cards = cards.filter { it.masterGeneralId == generalId && it.generalId != null }.sortedBy { it.id }
                .map { RewardCard(it.id, it.generalId!!, it.loyalty) },
            people = people.associate { it.id to RewardPerson(it.id, it.name, it.cityId) },
            cities = cityRows.map { RewardCity(it.id, it.nationId, it.supplyState != 0, it.meta) },
            countyIds = bundle.projection.administrativeCountyIds)
    }

    private fun unavailable(id: Int, reason: String, date: RewardSnapshotDto? = null) =
        RewardReadSnapshot(id, "UNAVAILABLE", reason, date)

    private fun <T> storage(read: () -> T): T = try { read() }
        catch (cause: DataAccessException) { throw RewardStorageUnavailable(cause) }
}
