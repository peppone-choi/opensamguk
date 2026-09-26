package opensamguk.logic.identity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class IdentityAudience {
    HAN_COURT, LOCAL_ELITES, COMMONERS, RELIGIOUS_ADHERENTS,
    MILITARY_FOLLOWERS, FRONTIER_COMMUNITIES,
}

enum class IdentityStage {
    MOVEMENT, CONFEDERATION, TERRITORIAL_REGIME, BUREAUCRATIC_STATE, DYNASTIC_CLAIM,
}

enum class IdentityNetworkVisibility { PUBLIC, COVERT, SUPPRESSED }
enum class IdentityPlaceKind { CITY, ROUTE, HIDEOUT }
enum class HostNationRelation { OWN, ALLIED, FOREIGN, UNCLAIMED }
enum class IdentityContentProfile { CHRONICLE, CLASSIC }

/** State values come from a scenario or a settled turn, never from the preset display name. */
data class NetworkPresence(
    val networkId: String,
    val kind: String,
    val factionId: Int,
    val placeId: String,
    val placeKind: IdentityPlaceKind,
    val visibility: IdentityNetworkVisibility,
    val adherents: Long,
    val leadership: Int,
    val cohesion: Int,
    val supplies: Long,
    val localSupport: Int,
    val hostNationRelation: HostNationRelation,
) {
    init {
        require(networkId.isNotBlank() && kind.isNotBlank() && factionId > 0 && placeId.isNotBlank())
        require(adherents >= 0 && leadership >= 0 && cohesion >= 0 && supplies >= 0 && localSupport >= 0)
    }
}

data class IdentityNetworkSeed(val kind: String, val placeKind: IdentityPlaceKind) {
    init { require(kind.isNotBlank()) }
}

data class FactionIdentityProfile(
    val factionId: Int,
    val presetId: String,
    val stage: IdentityStage,
    val legitimacyByAudience: Map<IdentityAudience, Int>,
    val governanceForms: Set<String>,
    val traditions: Set<String>,
    val activePolicies: Set<String>,
    val institutionalTensions: Set<String>,
    val contentProfile: IdentityContentProfile,
    val version: Int = 1,
) {
    init {
        require(factionId > 0 && presetId.startsWith("identity."))
        require(legitimacyByAudience.keys == IdentityAudience.entries.toSet())
        require(legitimacyByAudience.values.all { it >= 0 })
        require((governanceForms + traditions + activePolicies + institutionalTensions).none { it.isBlank() })
        require(version > 0)
    }
}

data class IdentityCoreTemplate(
    val source: IdentityPresetSource,
    val initialStage: IdentityStage,
    val governanceSeeds: Set<String>,
    val traditionSeeds: Set<String>,
    val startingNetworks: Set<IdentityNetworkSeed>,
    val commandCapabilities: Set<String>,
    val facilityCapabilities: Set<String>,
    val recruitmentCapabilities: Set<String>,
    val diplomacyCapabilities: Set<String>,
) {
    init {
        require(startingNetworks.isNotEmpty())
        require(governanceSeeds.isNotEmpty() && traditionSeeds.isNotEmpty())
        require(commandCapabilities.isNotEmpty() && facilityCapabilities.isNotEmpty())
        require(recruitmentCapabilities.isNotEmpty() && diplomacyCapabilities.isNotEmpty())
    }

    fun seedProfile(
        factionId: Int,
        legitimacyByAudience: Map<IdentityAudience, Int>,
        contentProfile: IdentityContentProfile,
    ): FactionIdentityProfile = FactionIdentityProfile(
        factionId = factionId,
        presetId = source.id,
        stage = initialStage,
        legitimacyByAudience = legitimacyByAudience,
        governanceForms = governanceSeeds,
        traditions = traditionSeeds,
        activePolicies = emptySet(),
        institutionalTensions = emptySet(),
        contentProfile = contentProfile,
    )

    /** Scenario supplies concrete places and measures; a template only constrains their kind. */
    fun seedState(
        factionId: Int,
        legitimacyByAudience: Map<IdentityAudience, Int>,
        contentProfile: IdentityContentProfile,
        networks: List<NetworkPresence>,
    ): FactionIdentityState {
        require(networks.isNotEmpty())
        require(networks.all { IdentityNetworkSeed(it.kind, it.placeKind) in startingNetworks })
        return FactionIdentityState(seedProfile(factionId, legitimacyByAudience, contentProfile), networks)
    }
}

/** Network ownership is independent of a city's current political owner. */
data class FactionIdentityState(
    val profile: FactionIdentityProfile,
    val networks: List<NetworkPresence>,
) {
    init {
        require(networks.all { it.factionId == profile.factionId })
        require(networks.map { it.networkId }.distinct().size == networks.size)
    }

    /** Applies an already adjudicated adoption; keeps old networks and displaced institutions visible. */
    fun adopt(template: IdentityCoreTemplate, approvedStage: IdentityStage): FactionIdentityState {
        val displaced = (profile.governanceForms - template.governanceSeeds) +
            (profile.traditions - template.traditionSeeds)
        return copy(profile = profile.copy(
            presetId = template.source.id,
            stage = approvedStage,
            governanceForms = template.governanceSeeds,
            traditions = template.traditionSeeds,
            institutionalTensions = profile.institutionalTensions + displaced,
            version = profile.version + 1,
        ))
    }
}

/** The three first profiles are gameplay templates linked to the separate S6-9a evidence ledger. */
object IdentityCoreTemplates {
    private val firstIds = setOf("identity.confucian", "identity.taiping", "identity.bandit")

    fun parse(payload: String, sourcePayload: String): List<IdentityCoreTemplate> {
        val sources = IdentityPresetSources.parse(sourcePayload).associateBy { it.id }
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root.getValue("schemaVersion").jsonPrimitive.content == "1")
        val templates = root.getValue("rows").jsonArray.map { node ->
            val row = node.jsonObject
            val id = row.getValue("id").jsonPrimitive.content
            require(id in firstIds)
            val decision = row.getValue("designDecision").jsonObject
            require(decision.getValue("status").jsonPrimitive.content == "CONFIRMED")
            require(decision.getValue("decidedBy").jsonPrimitive.content == "구현 에이전트")
            require(decision.getValue("decidedAt").jsonPrimitive.content.isNotBlank())
            require(decision.getValue("basis").jsonPrimitive.content.isNotBlank())
            val networkSeeds = row.getValue("startingNetworks").jsonArray.map { seedNode ->
                val seed = seedNode.jsonObject
                IdentityNetworkSeed(
                    seed.getValue("kind").jsonPrimitive.content,
                    IdentityPlaceKind.valueOf(seed.getValue("placeKind").jsonPrimitive.content),
                )
            }
            require(networkSeeds.isNotEmpty() && networkSeeds.distinct().size == networkSeeds.size)
            IdentityCoreTemplate(
                source = sources.getValue(id),
                initialStage = IdentityStage.valueOf(row.getValue("initialStage").jsonPrimitive.content),
                governanceSeeds = strings(row, "governanceSeeds"),
                traditionSeeds = strings(row, "traditionSeeds"),
                startingNetworks = networkSeeds.toSet(),
                commandCapabilities = strings(row, "commandCapabilities"),
                facilityCapabilities = strings(row, "facilityCapabilities"),
                recruitmentCapabilities = strings(row, "recruitmentCapabilities"),
                diplomacyCapabilities = strings(row, "diplomacyCapabilities"),
            )
        }
        require(templates.map { it.source.id }.toSet() == firstIds && templates.size == firstIds.size)
        return templates.sortedBy { it.source.id }
    }

    private fun strings(row: JsonObject, key: String): Set<String> {
        val values = row.getValue(key).jsonArray.map { it.jsonPrimitive.content }
        require(values.isNotEmpty() && values.all { it.isNotBlank() } && values.distinct().size == values.size)
        return values.toSet()
    }
}
