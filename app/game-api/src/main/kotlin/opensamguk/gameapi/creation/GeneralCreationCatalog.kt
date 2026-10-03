package opensamguk.gameapi.creation

import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.common.constants.CityConst
import opensamguk.gameapi.dto.GeneralCreationCountyDto
import opensamguk.gameapi.dto.GeneralCreationModeDto
import opensamguk.gameapi.dto.GeneralCreationNameRuleDto
import opensamguk.gameapi.dto.GeneralCreationOptionDto
import opensamguk.gameapi.dto.GeneralCreationOptionsDto
import opensamguk.gameapi.dto.GeneralCreationPolicyDto
import opensamguk.gameapi.dto.GeneralCreationStatRuleDto
import opensamguk.gameapi.dto.GeneralCreationStatsDto
import opensamguk.gameapi.dto.HistoricalCreationPageDto
import opensamguk.gameapi.dto.HistoricalCreationPersonDto
import opensamguk.gameapi.read.ActiveWorldArtifactResolver
import opensamguk.gameapi.read.CityGeography
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.RetainerReadRepository
import opensamguk.gameapi.read.SpatialStateReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.read.processRuleProfile
import opensamguk.logic.creation.CreationNameRule
import opensamguk.logic.creation.CreationAdmission
import opensamguk.logic.creation.CreationKind
import opensamguk.logic.creation.CreationSelectionPolicy
import opensamguk.logic.input.NativeCountyLedger
import opensamguk.logic.input.PersonPolicyState
import opensamguk.logic.input.RuleProfile
import opensamguk.infra.seed.MapJson
import org.springframework.stereotype.Service

@Service
class GeneralCreationCatalog(
    private val worlds: WorldStateReadRepository,
    private val generals: GeneralReadRepository,
    private val artifacts: ActiveWorldArtifactResolver,
    private val geography: CityGeography,
    private val retainers: RetainerReadRepository,
    private val spatial: SpatialStateReadRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId = processWorld.worldId.value
    private val nameRule: CreationNameRule = CreationNameRule.APPROVED

    fun options(): GeneralCreationOptionsDto {
        val policy = runCatching { CreationSelectionPolicy.load() }.getOrNull()
            ?: throw CreationAdmissionException("CREATION_POLICY_UNAVAILABLE")
        val rule = nameRule
        val selected = artifacts.resolve() ?: throw CreationAdmissionException("CREATION_POLICY_UNAVAILABLE")
        val bundle = selected.artifacts ?: throw CreationAdmissionException("CREATION_POLICY_UNAVAILABLE")
        val places = runCatching { geography.places(bundle) }.getOrNull()
            ?: throw CreationAdmissionException("CREATION_POLICY_UNAVAILABLE")
        val map = runCatching { MapJson.loadMap(
            bundle.artifactBytes(CityGeography.RUNTIME_MAP).toString(Charsets.UTF_8)) }.getOrNull()
            ?: throw CreationAdmissionException("CREATION_POLICY_UNAVAILABLE")
        val coordinates = map.cities.associateBy { it.id }
        fun cell(value: Double, size: Int): Int? = value.takeIf {
            it.isFinite() && it >= 0.0 && it < size.toDouble() && it % 1.0 == 0.0
        }?.toInt()
        val running = selected.world.status == "OPEN" && selected.world.isunited == 0 &&
            worlds.processRuleProfile() == RuleProfile.HWIHA
        val counties = selected.cities.sortedBy { it.id }.map { city ->
            val place = places[city.id]
            val coordinate = coordinates[city.id]
            val col = coordinate?.x?.let { cell(it, map.width) }
            val row = coordinate?.y?.let { cell(it, map.height) }
            val mapped = city.id in bundle.projection.administrativeCountyIds &&
                bundle.projection.bindingsByCityId[city.id]?.landProvinceId != null &&
                col != null && row != null
            GeneralCreationCountyDto(
                cityId = city.id, name = place?.displayName ?: city.name,
                commanderyId = place?.commanderyHanja, commanderyName = place?.commanderyName,
                provinceName = CityConst.regionMap[city.region]?.toString(), cellCol = col, cellRow = row,
                available = running && mapped,
                reason = when { !running -> "CREATION_POLICY_UNAVAILABLE"
                    !mapped -> "INVALID_NATIVE_COUNTY" else -> null },
            )
        }
        return GeneralCreationOptionsDto(
            worldId = worldId,
            statRule = GeneralCreationStatRuleDto(policy.statRule.minimum, policy.statRule.maximum,
                policy.statRule.exactTotal),
            nameRule = GeneralCreationNameRuleDto(1, rule.maxCodePoints, "NFC_TRIM",
                "HANGUL_HAN_LATIN_LETTERS_INTERNAL_SINGLE_SPACE_OR_MIDDLE_DOT", "WORLD_NFC_ROOT_CASEFOLD"),
            policy = GeneralCreationPolicyDto(
                customAllowed = running && policy.modes.any { it.kind.name == "CUSTOM" && it.allowed },
                historicalAllowed = running && policy.modes.any { it.kind.name == "HISTORICAL" && it.allowed },
                reason = if (running) null else "CREATION_POLICY_UNAVAILABLE",
            ),
            modes = policy.modes.map { GeneralCreationModeDto(it.kind.name, running && it.allowed,
                if (running && it.allowed) null else "CREATION_POLICY_UNAVAILABLE") },
            ideologies = policy.ideologies.map { GeneralCreationOptionDto(it.id, it.displayNameKo) },
            traits = policy.traits.map { GeneralCreationOptionDto(it.id, it.displayNameKo) },
            nativeCounties = counties,
        )
    }

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
        val bound = retainers.boundGeneralIds()
        val running = world.status == "OPEN" && world.isunited == 0 &&
            worlds.processRuleProfile() == RuleProfile.HWIHA &&
            policy.modes.any { it.kind == CreationKind.HISTORICAL && it.allowed }
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
                    person.cityId > 0 && person.id in positions && person.id !in bound && running
                HistoricalCreationPersonDto(person.id, CreationNameRule.stripLegacyNpcMarker(person.name),
                    null, person.picture,
                    GeneralCreationStatsDto(person.leadership, person.strength, person.intel,
                        person.politics, person.charm), person.nationId.takeIf { it > 0 }, appeared,
                    available, when {
                        !appeared -> "HISTORICAL_PERSON_NOT_APPEARED"
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
