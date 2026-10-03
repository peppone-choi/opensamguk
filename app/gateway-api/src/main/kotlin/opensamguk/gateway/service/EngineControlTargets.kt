package opensamguk.gateway.service

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import java.util.Base64

/** Secret-bearing configuration never enters ServerDef, API DTOs or diagnostic strings. */
@Component
class EngineControlTargets(
    @Value("\${ENGINE_CONTROL_TARGETS_JSON:}") raw: String,
    mapper: ObjectMapper,
) {
    class Target internal constructor(
        val serverId: String,
        val origin: String,
        val worldId: Int,
        val generation: Int,
        val revision: String,
        @field:JsonIgnore private val credential: String,
    ) {
        internal fun headers(headers: HttpHeaders) {
            headers.setBearerAuth(credential)
            headers.set("X-Opensamguk-Control-Server-Id", serverId)
            headers.set("X-Opensamguk-Control-World-Id", worldId.toString())
            headers.set("X-Opensamguk-Control-Generation", generation.toString())
            headers.set("X-Opensamguk-Control-Revision", revision)
        }

        override fun toString(): String = "EngineControlTarget(serverId=$serverId)"
    }

    private val targets: Map<String, Target> = runCatching {
        require(raw.length in 1..262144)
        val root = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw)
        require(root.isArray && root.size() in 1..128)
        val seenCredentials = HashSet<String>()
        val result = LinkedHashMap<String, Target>()
        for (node in root) {
            require(node.isObject && node.fieldNames().asSequence().toSet() == FIELDS)
            fun text(key: String): String {
                require(node.path(key).isTextual)
                return node.path(key).asText()
            }
            fun number(key: String): Int {
                val value = node.path(key)
                require(value.isIntegralNumber && value.canConvertToInt())
                return value.asInt()
            }
            val id = text("serverId")
            val origin = text("origin")
            val world = number("worldId")
            val generation = number("generation")
            val revision = text("revision")
            val credential = text("credential")
            require(Regex("[a-z0-9]{1,48}").matches(id) && origin == "http://s$id-game-engine:8082")
            require(world > 0 && generation >= 0 && Regex("[A-Za-z0-9_-]{1,64}").matches(revision))
            require(Regex("[A-Za-z0-9_-]{43}").matches(credential))
            val decoded = Base64.getUrlDecoder().decode(credential)
            require(decoded.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == credential)
            require(seenCredentials.add(credential) && id !in result)
            result[id] = Target(id, origin, world, generation, revision, credential)
        }
        result.toMap()
    }.getOrDefault(emptyMap())

    fun resolve(server: ServerDef): Target? = targets[server.id]?.takeIf {
        server.gameEngineUrl == it.origin && server.generation == it.generation
    }

    override fun toString(): String = "EngineControlTargets(count=${targets.size})"

    private companion object {
        val FIELDS = setOf("serverId", "origin", "worldId", "generation", "revision", "credential")
    }
}
