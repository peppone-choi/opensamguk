package opensamguk.gateway.profile

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.util.HexFormat

enum class SharedProfileIconScope {
    EXISTING_SHARED_CDN,
    BUNDLED_CLEARED,
}

data class SharedProfileIconEntry(
    val iconId: String,
    val canonicalId: String,
    val canonicalFilename: String,
    val portraitAssetId: String,
    val sha256: String,
    val mediaType: String,
    val width: Int,
    val height: Int,
    val operationalFallback: Boolean,
    val clearedFallback: Boolean,
    val scope: SharedProfileIconScope,
    val sourceRepository: String,
    val sourceRevision: String,
    val sourcePath: String,
    val deliveryUrl: String?,
    val resourcePath: String?,
    val provenanceStatus: String = "pinned-immutable-byte-evidence",
    val licenseStatus: String = "unknown",
    val redistributionStatus: String,
)

class SharedProfileIconCatalog private constructor(
    entries: List<SharedProfileIconEntry>,
    expectedCount: Int,
    expectedClearedFallbackCount: Int,
    bundledResourceInventory: List<String>,
    requireOperationalFallback: Boolean,
) {
    private val byId: Map<String, SharedProfileIconEntry>

    constructor(entries: List<SharedProfileIconEntry>) : this(
        entries = entries,
        expectedCount = entries.size,
        expectedClearedFallbackCount = entries.count { it.clearedFallback },
        bundledResourceInventory = entries
            .filter { it.scope == SharedProfileIconScope.BUNDLED_CLEARED }
            .mapNotNull { it.resourcePath }
            .sorted(),
        requireOperationalFallback = entries.isNotEmpty(),
    )

    init {
        require(expectedCount >= 0 && entries.size == expectedCount) {
            "Shared profile icon expected_count does not match entries"
        }
        require(entries == entries.sortedWith(compareBy(SharedProfileIconEntry::scope, SharedProfileIconEntry::iconId))) {
            "Shared profile icon entries are not deterministically ordered"
        }
        require(entries.map { it.iconId }.distinct().size == entries.size) { "Duplicate shared profile icon_id" }
        require(entries.map { it.canonicalId }.distinct().size == entries.size) { "Duplicate shared profile canonical_id" }
        require(entries.map { it.canonicalFilename }.distinct().size == entries.size) {
            "Duplicate shared profile canonical_filename"
        }
        require(entries.map { it.sha256 }.distinct().size == entries.size) { "Duplicate shared profile sha256" }
        require(!requireOperationalFallback || entries.count { it.operationalFallback } == 1) {
            "Exactly one operational shared profile fallback is required"
        }
        require(expectedClearedFallbackCount >= 0 && entries.count { it.clearedFallback } == expectedClearedFallbackCount) {
            "Shared profile expected_cleared_fallback_count does not match entries"
        }
        require(bundledResourceInventory == bundledResourceInventory.sorted()) {
            "Shared profile bundled resource inventory is not deterministic"
        }
        require(bundledResourceInventory.distinct().size == bundledResourceInventory.size) {
            "Duplicate shared profile bundled resource inventory path"
        }
        val referencedBundledResources = entries
            .filter { it.scope == SharedProfileIconScope.BUNDLED_CLEARED }
            .map { requireNotNull(it.resourcePath) }
            .sorted()
        require(bundledResourceInventory == referencedBundledResources) {
            "Shared profile bundled resource inventory does not match bundled entries"
        }
        entries.forEach(::validate)
        require(entries.filter { it.clearedFallback }.all { it.scope == SharedProfileIconScope.BUNDLED_CLEARED }) {
            "Cleared shared profile fallback must be a bundled cleared asset"
        }
        byId = entries.associateByTo(LinkedHashMap()) { it.iconId }
    }

    fun isAllowedSharedIconId(iconId: String?): Boolean = iconId != null && byId.containsKey(iconId)

    fun resolveSharedIcon(iconId: String?): SharedProfileIconEntry? = iconId?.let(byId::get)

    fun entries(): List<SharedProfileIconEntry> = byId.values.toList()

    fun operationalFallback(): SharedProfileIconEntry? = byId.values.singleOrNull { it.operationalFallback }

    fun hasClearedEligibleFallback(): Boolean = byId.values.any { it.clearedFallback }

    fun hasReleaseBlockingRights(): Boolean = byId.values.any {
        it.licenseStatus == LICENSE_UNKNOWN || it.redistributionStatus == REDISTRIBUTION_UNKNOWN
    }

    private fun validate(entry: SharedProfileIconEntry) {
        require(ID.matches(entry.iconId))
        require(ID.matches(entry.canonicalId))
        require(FILE_NAME.matches(entry.canonicalFilename))
        require(!MANAGED_FILE.matches(entry.canonicalFilename))
        require(PORTRAIT_ASSET_ID.matches(entry.portraitAssetId))
        require(SHA256.matches(entry.sha256))
        require(entry.portraitAssetId == "pa_${entry.sha256}")
        require(entry.mediaType in MEDIA_TYPES) { "Unsupported shared profile icon media_type" }
        require(entry.width == entry.height && entry.width in 64..128)
        require(mediaTypeFor(entry.canonicalFilename) == entry.mediaType) {
            "Shared profile icon extension and media_type do not match"
        }
        require(REVISION.matches(entry.sourceRevision))
        require(entry.provenanceStatus == PROVENANCE_PINNED_BYTE_EVIDENCE) {
            "Unsupported shared profile provenance_status"
        }
        require(entry.licenseStatus in LICENSE_STATUSES) { "Unsupported shared profile license_status" }
        require(entry.redistributionStatus in REDISTRIBUTION_STATUSES) {
            "Unsupported shared profile redistribution_status"
        }
        when (entry.scope) {
            SharedProfileIconScope.EXISTING_SHARED_CDN -> validateExistingSharedCdn(entry)
            SharedProfileIconScope.BUNDLED_CLEARED -> validateBundledMetadata(entry)
        }
    }

    private fun validateExistingSharedCdn(entry: SharedProfileIconEntry) {
        require(entry.licenseStatus == LICENSE_UNKNOWN)
        require(entry.redistributionStatus == REDISTRIBUTION_UNKNOWN)
        require(entry.resourcePath == null)
        require(entry.sourcePath == "icons/${entry.canonicalFilename}")
        val repositoryPrefix = "https://github.com/"
        require(entry.sourceRepository.startsWith(repositoryPrefix))
        val repositorySlug = entry.sourceRepository.removePrefix(repositoryPrefix).removeSuffix(".git")
        require(REPOSITORY_SLUG.matches(repositorySlug))
        val expectedDelivery =
            "https://cdn.jsdelivr.net/gh/$repositorySlug@${entry.sourceRevision}/${entry.sourcePath}"
        require(entry.deliveryUrl == expectedDelivery)
    }

    private fun validateBundledMetadata(entry: SharedProfileIconEntry) {
        require(entry.licenseStatus == LICENSE_CLEARED)
        require(entry.redistributionStatus == REDISTRIBUTION_CLEARED)
        require(entry.deliveryUrl == null)
        val resourcePath = requireNotNull(entry.resourcePath)
        require(RESOURCE_PATH.matches(resourcePath) && !resourcePath.contains(".."))
    }

    companion object {
        private val ID = Regex("[a-z0-9][a-z0-9._:-]{0,63}")
        private val FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.(avif|webp|jpg|png|gif)")
        private val MANAGED_FILE = Regex("[0-9a-f]{8}\\.(avif|webp|jpg|png|gif)")
        private val PORTRAIT_ASSET_ID = Regex("pa_[0-9a-f]{64}")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val REVISION = Regex("[0-9a-f]{40}")
        private val REPOSITORY_SLUG = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
        private val RESOURCE_PATH = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,255}")
        private val MEDIA_TYPES = setOf("image/avif", "image/webp", "image/jpeg", "image/png", "image/gif")
        private const val PROVENANCE_PINNED_BYTE_EVIDENCE = "pinned-immutable-byte-evidence"
        private const val LICENSE_UNKNOWN = "unknown"
        private const val LICENSE_CLEARED = "cleared"
        private const val REDISTRIBUTION_UNKNOWN = "unknown"
        private const val REDISTRIBUTION_CLEARED = "cleared-for-redistribution"
        private val LICENSE_STATUSES = setOf(LICENSE_UNKNOWN, LICENSE_CLEARED)
        private val REDISTRIBUTION_STATUSES = setOf(REDISTRIBUTION_UNKNOWN, REDISTRIBUTION_CLEARED)

        fun fromClasspath(resourcePath: String): SharedProfileIconCatalog {
            val classLoader = SharedProfileIconCatalog::class.java.classLoader
            val manifestBytes = classLoader.getResourceAsStream(resourcePath)?.use { it.readAllBytes() }
                ?: throw IllegalArgumentException("Shared profile icon manifest is missing")
            return fromManifestBytes(manifestBytes) { path ->
                classLoader.getResourceAsStream(path)?.use { it.readAllBytes() }
            }
        }

        internal fun fromManifestBytes(
            manifestBytes: ByteArray,
            resourceLoader: (String) -> ByteArray?,
        ): SharedProfileIconCatalog {
            val manifest = ObjectMapper().readTree(manifestBytes)
            require(manifest.path("version").asInt(-1) == 1)
            val expectedCount = requiredInt(manifest, "expected_count")
            val expectedClearedFallbackCount = requiredInt(manifest, "expected_cleared_fallback_count")
            val bundledResourceInventory = requiredTextArray(manifest, "bundled_resource_inventory")
            val existing = manifest.path("existing_shared_cdn")
            val bundled = manifest.path("bundled_cleared")
            require(existing.isArray && bundled.isArray)
            val entries = existing.map { parseEntry(it, SharedProfileIconScope.EXISTING_SHARED_CDN) } +
                bundled.map { parseEntry(it, SharedProfileIconScope.BUNDLED_CLEARED) }
            val catalog = SharedProfileIconCatalog(
                entries = entries,
                expectedCount = expectedCount,
                expectedClearedFallbackCount = expectedClearedFallbackCount,
                bundledResourceInventory = bundledResourceInventory,
                requireOperationalFallback = true,
            )
            entries.filter { it.scope == SharedProfileIconScope.BUNDLED_CLEARED }
                .forEach { verifyBundledAsset(it, resourceLoader) }
            return catalog
        }

        private fun parseEntry(node: JsonNode, scope: SharedProfileIconScope) = SharedProfileIconEntry(
            iconId = requiredText(node, "icon_id"),
            canonicalId = requiredText(node, "canonical_id"),
            canonicalFilename = requiredText(node, "canonical_filename"),
            portraitAssetId = requiredText(node, "portrait_asset_id"),
            sha256 = requiredText(node, "sha256"),
            mediaType = requiredText(node, "media_type"),
            width = requiredInt(node, "width"),
            height = requiredInt(node, "height"),
            operationalFallback = requiredBoolean(node, "operational_fallback"),
            clearedFallback = requiredBoolean(node, "cleared_fallback"),
            scope = scope,
            sourceRepository = requiredText(node, "source_repository"),
            sourceRevision = requiredText(node, "source_revision"),
            sourcePath = requiredText(node, "source_path"),
            deliveryUrl = optionalText(node, "delivery_url"),
            resourcePath = optionalText(node, "resource_path"),
            provenanceStatus = requiredText(node, "provenance_status"),
            licenseStatus = requiredText(node, "license_status"),
            redistributionStatus = requiredText(node, "redistribution_status"),
        )

        private fun verifyBundledAsset(
            entry: SharedProfileIconEntry,
            resourceLoader: (String) -> ByteArray?,
        ) {
            val resourcePath = requireNotNull(entry.resourcePath)
            val bytes = resourceLoader(resourcePath)
                ?: throw IllegalArgumentException("Bundled shared profile icon is missing")
            val actualSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            require(actualSha == entry.sha256) { "Bundled shared profile icon hash mismatch" }
            val decoded = ProfileIconDecoder(51_200).decode(bytes)
            require(decoded.extension == entry.canonicalFilename.substringAfterLast('.')) {
                "Bundled shared profile icon extension mismatch"
            }
            require(decoded.mediaType == entry.mediaType) { "Bundled shared profile icon media_type mismatch" }
            require(decoded.width == entry.width && decoded.height == entry.height) {
                "Bundled shared profile icon dimensions mismatch"
            }
        }

        private fun mediaTypeFor(fileName: String): String = when (fileName.substringAfterLast('.')) {
            "avif" -> "image/avif"
            "webp" -> "image/webp"
            "jpg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            else -> throw IllegalArgumentException("Unsupported shared profile icon format")
        }

        private fun requiredText(node: JsonNode, field: String): String =
            node.path(field).takeIf { it.isTextual }?.asText()
                ?: throw IllegalArgumentException("Shared profile icon manifest field is missing: $field")

        private fun optionalText(node: JsonNode, field: String): String? =
            node.path(field).takeIf { it.isTextual }?.asText()

        private fun requiredInt(node: JsonNode, field: String): Int =
            node.path(field).takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt()
                ?: throw IllegalArgumentException("Shared profile icon manifest integer field is missing: $field")

        private fun requiredBoolean(node: JsonNode, field: String): Boolean =
            node.path(field).takeIf { it.isBoolean }?.asBoolean()
                ?: throw IllegalArgumentException("Shared profile icon manifest boolean field is missing: $field")

        private fun requiredTextArray(node: JsonNode, field: String): List<String> {
            val array = node.path(field)
            require(array.isArray)
            return array.map { value ->
                value.takeIf { it.isTextual }?.asText()
                    ?: throw IllegalArgumentException("Shared profile icon manifest inventory is invalid")
            }
        }
    }
}
