package opensamguk.gameapi.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class PublicCommandCatalogMetadata(
    val canonicalId: String,
    val normalizedIntentId: String?,
    val layer: String,
    val sourceRing: String,
    val authorityPolicyId: String,
    val adapterPolicy: String,
    val parityStatus: String,
    val contractStatus: String,
    val deliveryState: String,
)

/**
 * Runtime index over the Stage 0 public-alpha command catalog.
 *
 * The checked-in JSON remains the single source of truth. Gradle packages that exact file as a
 * game-api resource; this index only projects the legacy aliases needed by the existing available
 * command endpoints. Missing or duplicate legacy codes fail closed instead of silently falling
 * back to a second metadata table.
 */
object PublicCommandCatalogIndex {
    private const val RESOURCE = "command-catalog/public-alpha-command-catalog.json"

    private val byLegacySurface: Map<Pair<String, String>, PublicCommandCatalogMetadata> by lazy {
        val payload = checkNotNull(PublicCommandCatalogIndex::class.java.classLoader.getResource(RESOURCE)) {
            "public command catalog resource is missing: $RESOURCE"
        }.readText()
        parse(payload)
    }

    fun requireLegacyCode(sourceRing: String, legacyCode: String): PublicCommandCatalogMetadata =
        requireNotNull(byLegacySurface[sourceRing to legacyCode]) {
            "legacy command is missing from the public command catalog: $sourceRing/$legacyCode"
        }

    internal fun parse(payload: String): Map<Pair<String, String>, PublicCommandCatalogMetadata> {
        val root = Json.parseToJsonElement(payload).jsonObject
        val result = linkedMapOf<Pair<String, String>, PublicCommandCatalogMetadata>()
        root.getValue("commands").jsonArray.forEach { element ->
            val command = element.jsonObject
            val surfaces = command["legacySurfaces"]?.jsonArray ?: return@forEach
            surfaces.forEach { surfaceElement ->
                val surface = surfaceElement.jsonObject
                val legacyCode = surface.getValue("legacyCode").jsonPrimitive.content
                val sourceRing = sourceRing(surface.getValue("ring").jsonPrimitive.content)
                val metadata = PublicCommandCatalogMetadata(
                    canonicalId = command.getValue("canonicalId").jsonPrimitive.content,
                    normalizedIntentId = command["normalizedIntentId"]
                        ?.takeUnless { it is JsonNull }
                        ?.jsonPrimitive
                        ?.content,
                    layer = command.getValue("layer").jsonPrimitive.content,
                    sourceRing = sourceRing,
                    authorityPolicyId = command.getValue("authority")
                        .jsonObject
                        .getValue("policy")
                        .jsonPrimitive
                        .content,
                    adapterPolicy = surface.getValue("adapterPolicy").jsonPrimitive.content,
                    parityStatus = surface.getValue("parityStatus").jsonPrimitive.content,
                    contractStatus = command.getValue("contractStatus").jsonPrimitive.content,
                    deliveryState = command.getValue("deliveryState").jsonPrimitive.content,
                )
                check(result.putIfAbsent(sourceRing to legacyCode, metadata) == null) {
                    "duplicate legacy command in the public command catalog: $sourceRing/$legacyCode"
                }
            }
        }
        return result
    }

    private fun sourceRing(ring: String): String = when (ring) {
        "general_turn" -> "GENERAL_TURN"
        "nation_turn" -> "NATION_TURN"
        else -> error("unknown legacy source ring in the public command catalog: $ring")
    }
}
