package opensamguk.gameapi.creation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.common.constants.CityConst
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.read.ActiveWorldArtifactResolver
import opensamguk.gameapi.read.CityGeography
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.infra.seed.ResolvedWorldArtifacts
import opensamguk.logic.creation.CreationKind
import opensamguk.logic.creation.CreationNameRule
import opensamguk.logic.creation.CreationSelectionPolicy
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.input.WorldRuleProfile
import opensamguk.logic.world.WorldMapVariant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.ConcurrentHashMap

@Service
class GeneralCreationOptionsService(
    private val artifacts: ActiveWorldArtifactResolver,
    private val geography: CityGeography,
    private val generals: GeneralReadRepository,
    private val objectMapper: ObjectMapper,
    processWorld: GameApiProcessWorld,
) {
    private val worldId = processWorld.worldId.value
    private data class TileCell(val col: Int, val row: Int)
    private val cellsByVariant = ConcurrentHashMap<WorldMapVariant, Map<Int, TileCell>>()

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun options(): GeneralCreationOptionsDto {
        val policy = runCatching { CreationSelectionPolicy.load() }.getOrNull()
            ?: throw CreationOptionsUnavailable()
        val selected = runCatching { artifacts.resolve() }.getOrNull() ?: throw CreationOptionsUnavailable()
        val bundle = selected.artifacts ?: throw CreationOptionsUnavailable()
        val places = runCatching { geography.places(bundle) }.getOrNull()
            ?: throw CreationOptionsUnavailable()
        val cells = runCatching { cellsByVariant.computeIfAbsent(bundle.variant) { canonicalCells(bundle) } }
            .getOrNull() ?: throw CreationOptionsUnavailable()
        val max = (selected.world.config["maxgeneral"] as? Number)?.toInt()
            ?.takeIf { it > 0 } ?: throw CreationOptionsUnavailable()
        val used = runCatching { generals.countByNpcStateLessThan(2) }.getOrNull()
            ?.takeIf { it >= 0 } ?: throw CreationOptionsUnavailable()
        val creationBlock = when (val value = selected.world.config["block_general_create"]) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        } ?: 0
        val running = selected.world.status == "OPEN" && selected.world.isunited == 0 &&
            WorldRuleProfile.resolve(selected.world.config) == RuleProfile.HWIHA && (creationBlock and 1) == 0
        val capacityOpen = used < max
        val admissionOpen = running && capacityOpen
        val customOpen = admissionOpen && policy.modes.any { it.kind == CreationKind.CUSTOM && it.allowed }
        val historicalOpen = admissionOpen && policy.modes.any { it.kind == CreationKind.HISTORICAL && it.allowed }
        fun role(path: String, name: String, allowed: Boolean, closedReason: String) =
            GeneralCreationRoleDto(path, name, allowed, if (allowed) null else closedReason)
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
                available = admissionOpen && mapped,
                reason = when {
                    !admissionOpen -> "CREATION_POLICY_UNAVAILABLE"
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
                customAllowed = customOpen,
                historicalAllowed = historicalOpen,
                reason = if (admissionOpen) null else "CREATION_POLICY_UNAVAILABLE",
            ),
            playerCap = GeneralCreationPlayerCapDto(used, max),
            roles = listOf(
                role("CUSTOM", "RETAINER", customOpen, "CREATION_POLICY_UNAVAILABLE"),
                role("CUSTOM", "PRE_LORD", false, "ROLE_UNAVAILABLE"),
            ) + listOf("LORD", "MID", "RETAINER", "PRE_LORD", "RONIN").map { name ->
                // Historical rows describe path policy; each person's eligibility is in /historical.
                role("HISTORICAL", name, historicalOpen, "CREATION_POLICY_UNAVAILABLE")
            },
            modes = policy.modes.map { GeneralCreationModeDto(it.kind.name, admissionOpen && it.allowed,
                if (admissionOpen && it.allowed) null else "CREATION_POLICY_UNAVAILABLE") },
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
