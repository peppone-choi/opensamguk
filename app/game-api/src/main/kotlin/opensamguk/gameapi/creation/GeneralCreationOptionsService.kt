package opensamguk.gameapi.creation

import opensamguk.common.constants.CityConst
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.dto.GeneralCreationCountyDto
import opensamguk.gameapi.dto.GeneralCreationModeDto
import opensamguk.gameapi.dto.GeneralCreationNameRuleDto
import opensamguk.gameapi.dto.GeneralCreationOptionDto
import opensamguk.gameapi.dto.GeneralCreationOptionsDto
import opensamguk.gameapi.dto.GeneralCreationPolicyDto
import opensamguk.gameapi.dto.GeneralCreationStatRuleDto
import opensamguk.gameapi.read.ActiveWorldArtifactResolver
import opensamguk.gameapi.read.CityGeography
import opensamguk.infra.seed.MapJson
import opensamguk.logic.creation.CreationKind
import opensamguk.logic.creation.CreationNameRule
import opensamguk.logic.creation.CreationSelectionPolicy
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.input.WorldRuleProfile
import org.springframework.stereotype.Service

@Service
class GeneralCreationOptionsService(
    private val artifacts: ActiveWorldArtifactResolver,
    private val geography: CityGeography,
    processWorld: GameApiProcessWorld,
) {
    private val worldId = processWorld.worldId.value

    fun options(): GeneralCreationOptionsDto {
        val policy = runCatching { CreationSelectionPolicy.load() }.getOrNull()
            ?: throw CreationOptionsUnavailable()
        val selected = runCatching { artifacts.resolve() }.getOrNull() ?: throw CreationOptionsUnavailable()
        val bundle = selected.artifacts ?: throw CreationOptionsUnavailable()
        val places = runCatching { geography.places(bundle) }.getOrNull()
            ?: throw CreationOptionsUnavailable()
        val map = runCatching {
            MapJson.loadMap(bundle.artifactBytes(CityGeography.RUNTIME_MAP).toString(Charsets.UTF_8))
        }.getOrNull() ?: throw CreationOptionsUnavailable()
        val coordinates = map.cities.associateBy { it.id }
        val running = selected.world.status == "OPEN" && selected.world.isunited == 0 &&
            WorldRuleProfile.resolve(selected.world.config) == RuleProfile.HWIHA
        val counties = selected.cities.sortedBy { it.id }.map { city ->
            val place = places[city.id]
            val coordinate = coordinates[city.id]
            val col = coordinate?.x?.let { cell(it, map.width) }
            val row = coordinate?.y?.let { cell(it, map.height) }
            val mapped = city.id in bundle.projection.administrativeCountyIds &&
                bundle.projection.bindingsByCityId[city.id]?.landProvinceId != null &&
                col != null && row != null
            GeneralCreationCountyDto(
                cityId = city.id,
                name = place?.displayName ?: city.name,
                commanderyId = place?.commanderyHanja,
                commanderyName = place?.commanderyName,
                provinceName = CityConst.regionMap[city.region]?.toString(),
                cellCol = col,
                cellRow = row,
                available = running && mapped,
                reason = when {
                    !running -> "CREATION_POLICY_UNAVAILABLE"
                    !mapped -> "INVALID_NATIVE_COUNTY"
                    else -> null
                },
            )
        }
        return GeneralCreationOptionsDto(
            worldId = worldId,
            statRule = GeneralCreationStatRuleDto(policy.statRule.minimum, policy.statRule.maximum,
                policy.statRule.exactTotal),
            nameRule = GeneralCreationNameRuleDto(1, CreationNameRule.APPROVED.maxCodePoints, "NFC_TRIM",
                "HANGUL_HAN_LATIN_LETTERS_INTERNAL_SINGLE_SPACE_OR_MIDDLE_DOT", "WORLD_NFC_ROOT_CASEFOLD"),
            policy = GeneralCreationPolicyDto(
                customAllowed = running && policy.modes.any { it.kind == CreationKind.CUSTOM && it.allowed },
                historicalAllowed = running && policy.modes.any { it.kind == CreationKind.HISTORICAL && it.allowed },
                reason = if (running) null else "CREATION_POLICY_UNAVAILABLE",
            ),
            modes = policy.modes.map { GeneralCreationModeDto(it.kind.name, running && it.allowed,
                if (running && it.allowed) null else "CREATION_POLICY_UNAVAILABLE") },
            ideologies = policy.ideologies.map { GeneralCreationOptionDto(it.id, it.displayNameKo) },
            traits = policy.traits.map { GeneralCreationOptionDto(it.id, it.displayNameKo) },
            nativeCounties = counties,
        )
    }

    private fun cell(value: Double, size: Int): Int? = value.takeIf {
        it.isFinite() && it >= 0.0 && it < size.toDouble() && it % 1.0 == 0.0
    }?.toInt()
}

class CreationOptionsUnavailable : RuntimeException("CREATION_POLICY_UNAVAILABLE")
