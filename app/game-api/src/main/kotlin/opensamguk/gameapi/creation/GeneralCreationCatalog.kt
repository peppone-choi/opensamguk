package opensamguk.gameapi.creation

import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.read.ActiveWorldArtifactResolver
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.SpatialStateReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.read.processRuleProfile
import opensamguk.logic.creation.CreationAdmission
import opensamguk.logic.creation.CreationKind
import opensamguk.logic.creation.CreationNameRule
import opensamguk.logic.creation.CreationSelectionPolicy
import opensamguk.logic.input.NativeCountyLedger
import opensamguk.logic.input.PersonPolicyState
import opensamguk.logic.input.RuleProfile
import org.springframework.stereotype.Service

@Service
class GeneralCreationCatalog(
    private val worlds: WorldStateReadRepository,
    private val generals: GeneralReadRepository,
    private val artifacts: ActiveWorldArtifactResolver,
    private val spatial: SpatialStateReadRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId = processWorld.worldId.value

    fun historical(q: String?, nation: Int?, status: String?, sort: String?,
        cursor: String?, limit: Int): HistoricalCreationPageDto {
        if (limit !in 1..100 || (q?.length ?: 0) > 100 || (nation != null && nation < 0) ||
            (status != null && status !in setOf("AVAILABLE", "TAKEN", "NOT_APPEARED")) ||
            (sort != null && sort != "ID_ASC")) throw CreationAdmissionException("INVALID_REQUEST")
        val afterId = cursor?.toIntOrNull()?.takeIf { it >= 0 }
        if (cursor != null && afterId == null) throw CreationAdmissionException("INVALID_REQUEST")
        val world = worlds.findProcessWorld() ?: throw CreationAdmissionException("CREATION_POLICY_UNAVAILABLE")
        val policy = runCatching { CreationSelectionPolicy.load() }.getOrNull()
            ?: throw CreationAdmissionException("CREATION_POLICY_UNAVAILABLE")
        val bundle = artifacts.resolve()?.artifacts
            ?: throw CreationAdmissionException("CREATION_POLICY_UNAVAILABLE")
        val positions = runCatching { spatial.readSnapshot(worldId, bundle.projection.topology)
            .generalPositionSnapshot.statesByGeneralId.keys }.getOrNull()
            ?: throw CreationAdmissionException("CREATION_POLICY_UNAVAILABLE")
        val creationBlock = when (val value = world.config["block_general_create"]) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        } ?: 0
        val running = world.status == "OPEN" && world.isunited == 0 &&
            worlds.processRuleProfile() == RuleProfile.HWIHA &&
            policy.modes.any { it.kind == CreationKind.HISTORICAL && it.allowed } &&
            (creationBlock and 1) == 0
        val query = q?.trim().orEmpty()
        val candidates = generals.findAll().asSequence().filter { person ->
            // A seed identity is needed; a later CUSTOM row never becomes a historical candidate.
            val source = runCatching { PersonPolicyState.read(person.meta) }.getOrNull()?.statSourceId
            "npc_org" in person.meta && source != null &&
                source != NativeCountyLedger.CREATED_GENERAL_SOURCE
        }.filter { it.id > (afterId ?: 0) && (query.isBlank() || it.name.contains(query, ignoreCase = true)) &&
            (nation == null || it.nationId == nation) }
            .sortedBy { it.id }
            .map { person ->
                val appearanceYear = (person.meta["rtk14_appearance_year"] as? Number)?.toInt()
                val appeared = appearanceYear == null || appearanceYear <= world.currentYear
                val taken = person.npcState < 2 || (person.userId?.toLongOrNull() ?: 0) > 0
                val available = appeared && CreationAdmission.historicalAliveInYear(world.currentYear, person.meta) &&
                    person.npcState == 2 && !taken &&
                    person.cityId > 0 && person.id in positions && running
                HistoricalCreationPersonDto(person.id, CreationNameRule.stripLegacyNpcMarker(person.name),
                    null, person.picture,
                    GeneralCreationStatsDto(person.leadership, person.strength, person.intel,
                        person.politics, person.charm), person.nationId.takeIf { it > 0 }, appeared,
                    available, when {
                        !appeared -> "HISTORICAL_PERSON_NOT_APPEARED"
                        !running -> "CREATION_POLICY_UNAVAILABLE"
                        !available -> "HISTORICAL_PERSON_UNAVAILABLE"
                        else -> null
                    }) to taken
            }.filter { (row, taken) -> status == null || when (status) {
                "AVAILABLE" -> row.available
                "TAKEN" -> taken
                else -> !row.appeared
            } }.map { it.first }.take(limit + 1).toList()
        val page = candidates.take(limit)
        return HistoricalCreationPageDto(worldId = worldId, people = page,
            nextCursor = if (candidates.size > limit) page.lastOrNull()?.historicalGeneralId?.toString() else null)
    }
}
