package opensamguk.gameapi.creation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
import opensamguk.infra.seed.ResolvedWorldArtifacts
import opensamguk.logic.creation.CreationKind
import opensamguk.logic.creation.CreationNameRule
import opensamguk.logic.creation.CreationSelectionPolicy
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.input.WorldRuleProfile
import opensamguk.logic.world.WorldMapVariant
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class GeneralCreationOptionsService(
    private val artifacts: ActiveWorldArtifactResolver,
    private val geography: CityGeography,
    private val objectMapper: ObjectMapper,
    processWorld: GameApiProcessWorld,
) {
    private val worldId = processWorld.worldId.value
    private data class TileCell(val col: Int, val row: Int)
    private val cellsByVariant = ConcurrentHashMap<WorldMapVariant, Map<Int, TileCell>>()

    fun options(): GeneralCreationOptionsDto {
        val policy = runCatching { CreationSelectionPolicy.load() }.getOrNull()
            ?: throw CreationOptionsUnavailable()
        val selected = runCatching { artifacts.resolve() }.getOrNull() ?: throw CreationOptionsUnavailable()
        val bundle = selected.artifacts ?: throw CreationOptionsUnavailable()
        val places = runCatching { geography.places(bundle) }.getOrNull()
            ?: throw CreationOptionsUnavailable()
        val cells = runCatching { cellsByVariant.computeIfAbsent(bundle.variant) { canonicalCells(bundle) } }
            .getOrNull() ?: throw CreationOptionsUnavailable()
        val running = selected.world.status == "OPEN" && selected.world.isunited == 0 &&
            WorldRuleProfile.resolve(selected.world.config) == RuleProfile.HWIHA
        val counties = selected.cities.sortedBy { it.id }.map { city ->
            val place = places[city.id]
            val cell = cells[city.id]
            val mapped = city.id in bundle.projection.administrativeCountyIds &&
                bundle.projection.bindingsByCityId[city.id]?.landProvinceId != null &&
                cell != null
            GeneralCreationCountyDto(
                cityId = city.id,
                name = place?.displayName ?: city.name,
                commanderyId = place?.commanderyHanja,
                commanderyName = place?.commanderyName,
                provinceName = CityConst.regionMap[city.region]?.toString(),
                cellCol = cell?.col,
                cellRow = cell?.row,
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

    /** The runtime map x/y is scaled for display; only the pinned tiles contain map cells. */
    private fun canonicalCells(bundle: ResolvedWorldArtifacts): Map<Int, TileCell> {
        val mapCities = objectMapper.readTree(bundle.artifactBytes(CityGeography.RUNTIME_MAP)).path("cities")
        val tiles = objectMapper.readTree(bundle.artifactBytes(CityGeography.TILES))
        val tileCities = tiles.path("cities")
        val cols = tiles.path("_meta").path("cols").takeIf(JsonNode::isIntegralNumber)?.intValue()
            ?: error("Canonical map width unavailable")
        val rows = tiles.path("_meta").path("rows").takeIf(JsonNode::isIntegralNumber)?.intValue()
            ?: error("Canonical map height unavailable")
        check(mapCities.isArray && tileCities.isArray && cols > 0 && rows > 0) {
            "Canonical map cells unavailable"
        }
        val physicalCities = tileCities.associateBy { it.path("id").asText() }
        check(physicalCities.size == tileCities.size() && "" !in physicalCities) {
            "Canonical physical place identities unavailable"
        }
        return mapCities.mapNotNull { city ->
            val id = city.path("id").takeIf(JsonNode::isIntegralNumber)?.intValue() ?: return@mapNotNull null
            // A physical place can be a verified county seat even when its province has no cityIndex.
            if (id !in bundle.projection.administrativeCountyIds) return@mapNotNull null
            val physicalRef = city.path("physicalPlaceRef").asText()
            if (physicalRef.isBlank() ||
                physicalRef != bundle.projection.bindingsByCityId[id]?.physicalPlaceRef)
                return@mapNotNull null
            val tileCity = physicalCities[physicalRef.substringAfterLast(':')] ?: return@mapNotNull null
            val col = tileCity.path("col").takeIf(JsonNode::isIntegralNumber)?.intValue()
                ?.takeIf { it in 0 until cols } ?: return@mapNotNull null
            val row = tileCity.path("row").takeIf(JsonNode::isIntegralNumber)?.intValue()
                ?.takeIf { it in 0 until rows } ?: return@mapNotNull null
            id to TileCell(col, row)
        }.toMap()
    }
}

class CreationOptionsUnavailable : RuntimeException("CREATION_POLICY_UNAVAILABLE")
