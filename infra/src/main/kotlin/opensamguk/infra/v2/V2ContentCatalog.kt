package opensamguk.infra.v2

import opensamguk.infra.persistence.MetaJson
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * [names] and [read] are diagnostic-only direct-resource probes. [load] is the sole consumer API and returns
 * only typed ACTIVE metadata after fail-closed validation.
 */
class V2ContentCatalog(location: String = DEFAULT_LOCATION) {

    private val location = validatedDirectory(location)
    private val resolver = PathMatchingResourcePatternResolver()

    fun names(): List<String> = entries().mapNotNull { it.filename }.sorted()

    fun read(name: String): String? {
        if (!isSafeEntryName(name)) return null
        val matches = entries().filter { it.filename == name }
        return when (matches.size) {
            0 -> null
            1 -> matches.single().inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            else -> throw IllegalArgumentException("v2 content metadata is ambiguous: ${name.removeSuffix(".json")}")
        }
    }

    fun load(id: String): V2ContentMetadata {
        require(ENTRY_ID.matches(id)) { "v2 content id is invalid: $id" }
        val raw = read("$id.json") ?: throw IllegalArgumentException("v2 content metadata not found: $id")
        val metadata = decode(raw, id)
        require(metadata.status == V2ContentStatus.ACTIVE) {
            "v2 content '${metadata.id}' is ${metadata.status} and cannot be loaded"
        }
        return metadata
    }

    private fun decode(raw: String, requestedId: String): V2ContentMetadata {
        rejectDuplicateRootKeys(raw)
        val root = MetaJson.decode(raw)
        require(root.keys == APPROVED_METADATA_ROOT_KEYS) {
            "v2 content metadata must contain exactly the approved root keys"
        }
        val metadata = V2ContentMetadata(
            schemaVersion = requiredInt(root, "schemaVersion"),
            id = requiredString(root, "id"),
            status = statusOf(requiredString(root, "status")),
            source = validatedSource(requiredString(root, "source")),
            sha256 = validatedSha256(requiredString(root, "sha256")),
            cityCount = requiredInt(root, "cityCount"),
            scenarioOwnedCityCount = requiredInt(root, "scenarioOwnedCityCount"),
        )
        require(metadata.schemaVersion == V2ContentMetadata.SCHEMA_VERSION) {
            "unsupported v2 content schema version: ${metadata.schemaVersion}"
        }
        require(metadata.id == requestedId) {
            "v2 content id '${metadata.id}' does not match requested id '$requestedId'"
        }
        require(ENTRY_ID.matches(metadata.id)) { "v2 content id is invalid: ${metadata.id}" }
        require(metadata.cityCount >= 0) { "v2 city count must not be negative" }
        require(metadata.scenarioOwnedCityCount in 0..metadata.cityCount) {
            "v2 owned city count must be within the city count"
        }
        return metadata
    }

    private fun entries() = resolver.getResources("classpath*:$location/*.json")

    companion object {
        const val DEFAULT_LOCATION: String = "content/v2"

        private val ENTRY_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")
        private val SAFE_PATH_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val APPROVED_METADATA_ROOT_KEYS = setOf(
            "schemaVersion",
            "id",
            "status",
            "source",
            "sha256",
            "cityCount",
            "scenarioOwnedCityCount",
        )

        private fun isSafeEntryName(name: String): Boolean =
            name.endsWith(".json") && ENTRY_ID.matches(name.removeSuffix(".json"))

        private fun validatedDirectory(directory: String): String {
            require(isSafeClasspathPath(directory)) { "v2 catalog location is unsafe: $directory" }
            return directory
        }

        private fun validatedSource(source: String): String {
            require(source.endsWith(".json") && isSafeClasspathPath(source)) {
                "v2 content source is unsafe: $source"
            }
            return source
        }

        private fun isSafeClasspathPath(value: String): Boolean =
            value.split('/').all(SAFE_PATH_SEGMENT::matches)

        private fun requiredString(root: Map<String, Any?>, field: String): String =
            root[field].let { value ->
                if (value is String && value.isNotBlank()) value
                else throw IllegalArgumentException("v2 content metadata field '$field' must be a non-blank string")
            }

        private fun requiredInt(root: Map<String, Any?>, field: String): Int = when (val value = root[field]) {
            is Int -> value
            is Long -> {
                require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                    "v2 content metadata field '$field' is outside the integer range"
                }
                value.toInt()
            }
            else -> throw IllegalArgumentException("v2 content metadata field '$field' must be an integer")
        }

        private fun statusOf(value: String): V2ContentStatus = when (value) {
            V2ContentStatus.ACTIVE.name -> V2ContentStatus.ACTIVE
            V2ContentStatus.CANDIDATE.name -> V2ContentStatus.CANDIDATE
            V2ContentStatus.EXCLUDED.name -> V2ContentStatus.EXCLUDED
            V2ContentStatus.BUDGET_ONLY.name -> V2ContentStatus.BUDGET_ONLY
            else -> throw IllegalArgumentException("unknown v2 content status: $value")
        }

        private fun validatedSha256(value: String): String {
            require(SHA256.matches(value)) { "v2 content sha256 must be lowercase hexadecimal" }
            return value
        }

        private fun rejectDuplicateRootKeys(raw: String) {
            RootKeyScanner(raw).requireUniqueKeys()
        }

        private class RootKeyScanner(private val raw: String) {
            private var index = 0
            private var depth = 0

            fun requireUniqueKeys() {
                val keys = LinkedHashSet<String>()
                while (index < raw.length) {
                    when (raw[index]) {
                        '{', '[' -> {
                            depth++
                            index++
                        }

                        '}', ']' -> {
                            depth--
                            index++
                        }

                        '"' -> {
                            val value = readString()
                            if (depth == 1 && nextNonWhitespace() == ':') {
                                require(keys.add(value)) {
                                    "v2 content metadata must not contain duplicate keys: $value"
                                }
                            }
                        }

                        else -> index++
                    }
                }
            }

            private fun readString(): String {
                index++
                val value = StringBuilder()
                while (index < raw.length) {
                    when (val character = raw[index++]) {
                        '"' -> return value.toString()
                        '\\' -> value.append(readEscape())
                        else -> value.append(character)
                    }
                }
                throw IllegalArgumentException("unterminated string in v2 content metadata")
            }

            private fun readEscape(): Char {
                require(index < raw.length) { "unterminated escape in v2 content metadata" }
                return when (val escape = raw[index++]) {
                    '"', '\\', '/' -> escape
                    'b' -> '\b'
                    'f' -> '\u000C'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'u' -> readUnicodeEscape()
                    else -> throw IllegalArgumentException("invalid escape in v2 content metadata: \\$escape")
                }
            }

            private fun readUnicodeEscape(): Char {
                require(index + 4 <= raw.length) { "unterminated unicode escape in v2 content metadata" }
                val hex = raw.substring(index, index + 4)
                index += 4
                return hex.toInt(16).toChar()
            }

            private fun nextNonWhitespace(): Char? {
                var cursor = index
                while (cursor < raw.length) {
                    when (raw[cursor]) {
                        ' ', '\t', '\n', '\r' -> cursor++
                        else -> return raw[cursor]
                    }
                }
                return null
            }
        }
    }
}
