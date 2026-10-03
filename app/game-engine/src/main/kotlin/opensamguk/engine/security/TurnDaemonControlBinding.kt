package opensamguk.engine.security

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import opensamguk.engine.config.EngineProcessWorld
import java.security.MessageDigest
import java.util.Base64

/** Immutable per-process control binding. Invalid or missing configuration closes writes only. */
class TurnDaemonControlBinding(raw: String, mapper: ObjectMapper, processWorld: EngineProcessWorld) {
    private class Binding(
        val pins: Map<String, String>,
        val digest: ByteArray,
    )

    private val binding: Binding? = runCatching {
        require(raw.length in 1..4096)
        val json = mapper.copy()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .readTree(raw)
        require(json.isObject && json.fieldNames().asSequence().toSet() == FIELDS)
        fun text(key: String): String {
            val value = json.path(key)
            require(value.isTextual)
            return value.asText()
        }
        fun number(key: String): Int {
            val value = json.path(key)
            require(value.isIntegralNumber && value.canConvertToInt())
            return value.asInt()
        }
        val server = text("serverId")
        val world = number("worldId")
        val generation = number("generation")
        val revision = text("revision")
        val credential = text("credential")
        require(SERVER.matches(server) && world > 0 && generation >= 0 && REVISION.matches(revision))
        require(world == processWorld.worldId.value)
        require(validCredential(credential))
        Binding(
            mapOf(
                SERVER_HEADER to server, WORLD_HEADER to world.toString(),
                GENERATION_HEADER to generation.toString(), REVISION_HEADER to revision,
            ),
            digest(credential),
        )
    }.getOrNull()

    fun permits(request: HttpServletRequest): Boolean {
        val expected = binding ?: return false
        val authorization = singleHeader(request, "Authorization") ?: return false
        if (!authorization.startsWith("Bearer ")) return false
        val credential = authorization.removePrefix("Bearer ")
        if (!validCredential(credential)) return false
        // Digest length is fixed; neither secrets nor parser exceptions escape this component.
        val matches = MessageDigest.isEqual(expected.digest, digest(credential))
        return matches && expected.pins.all { (name, value) -> singleHeader(request, name) == value }
    }

    override fun toString(): String = "TurnDaemonControlBinding(configured=${binding != null})"

    private fun singleHeader(request: HttpServletRequest, name: String): String? {
        val values = request.getHeaders(name)?.toList().orEmpty()
        return values.singleOrNull()?.takeIf { it.length <= 512 }
    }

    private companion object {
        val FIELDS = setOf("serverId", "worldId", "generation", "revision", "credential")
        val SERVER = Regex("[a-z0-9]{1,48}")
        val REVISION = Regex("[A-Za-z0-9_-]{1,64}")
        val CREDENTIAL = Regex("[A-Za-z0-9_-]{43}")
        const val SERVER_HEADER = "X-Opensamguk-Control-Server-Id"
        const val WORLD_HEADER = "X-Opensamguk-Control-World-Id"
        const val GENERATION_HEADER = "X-Opensamguk-Control-Generation"
        const val REVISION_HEADER = "X-Opensamguk-Control-Revision"
        fun digest(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII))
        fun validCredential(value: String): Boolean = CREDENTIAL.matches(value) && runCatching {
            val bytes = Base64.getUrlDecoder().decode(value)
            bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value
        }.getOrDefault(false)
    }
}
