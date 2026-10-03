package opensamguk.gateway.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import opensamguk.gateway.dto.EnvProxyResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.HttpURLConnection
import java.time.Duration
import java.time.Instant

/** A bounded control client, independent of the observation and deployer clients. */
@Component
class EngineControlClient @Autowired constructor(
    private val targets: EngineControlTargets,
    private val mapper: ObjectMapper,
) {
    private val responseMapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private var rest = RestClient.builder().requestFactory(requestFactory()).build()

    internal constructor(targets: EngineControlTargets, mapper: ObjectMapper, builder: RestClient.Builder) :
        this(targets, mapper) {
        rest = builder.build()
    }

    fun post(server: ServerDef, path: String, body: Any? = null): EnvProxyResponse {
        if (path !in PATHS) return rejected(400)
        val target = targets.resolve(server) ?: return rejected(503)
        return try {
            val request = rest.method(HttpMethod.POST).uri(target.origin + path)
                .headers(target::headers)
            val prepared = if (body == null) request else request.contentType(MediaType.APPLICATION_JSON).body(body)
            prepared.exchange { _, response ->
                try {
                    val status = response.statusCode.value()
                    if (status !in 200..299) {
                        // Do not read or forward upstream error bodies or Location/other headers.
                        rejected(when (status) {
                            401, 403 -> 503 // Internal control authentication is not user-session expiry.
                            in SAFE_ERRORS -> status
                            else -> 502
                        })
                    } else {
                        val bytes = response.body.readNBytes(MAX_BODY + 1)
                        if (bytes.size > MAX_BODY || !MediaType.APPLICATION_JSON.isCompatibleWith(
                                response.headers.contentType ?: MediaType.APPLICATION_OCTET_STREAM,
                            )
                        ) {
                            rejected(502)
                        } else {
                            val result = safeResult(path, responseMapper.readTree(bytes))
                            if (result == null) rejected(502) else EnvProxyResponse(200, mapper.writeValueAsString(result))
                        }
                    }
                } finally {
                    // Close the stream before response.close() can drain a remaining oversized/error body.
                    runCatching { response.body.close() }
                }
            } ?: rejected(502)
        } catch (_: Exception) {
            // Neither exception messages/stacktraces nor arbitrary upstream bodies enter logs or DTOs.
            rejected(502)
        }
    }

    private fun safeResult(path: String, node: JsonNode): Map<String, Any?>? {
        if (!node.isObject) return null
        if (path != "/admin/turn-daemon/catch-up") {
            if (!node.path("paused").isBoolean || !node.path("changed").isBoolean) return null
            val paused = node.path("paused").asBoolean()
            return mapOf("paused" to paused, "changed" to node.path("changed").asBoolean(),
                "statusLabel" to if (paused) "동결중" else "가동중")
        }
        if (!node.path("active").isBoolean || !node.path("multiplier").isInt ||
            node.path("multiplier").asInt() !in setOf(2, 4)
        ) return null
        val result = linkedMapOf<String, Any?>("active" to node.path("active").asBoolean(),
            "multiplier" to node.path("multiplier").asInt())
        for (key in listOf("backlogSeconds", "remainingSeconds", "initialBacklogSeconds", "recoveredSeconds")) {
            val value = node.path(key)
            if (!value.isIntegralNumber || !value.canConvertToLong() || value.asLong() < 0) return null
            result[key] = value.asLong()
        }
        val eta = node.path("etaAt")
        result["etaAt"] = when {
            eta.isNull -> null
            eta.isTextual && eta.asText().length <= 64 -> runCatching { Instant.parse(eta.asText()).toString() }.getOrNull()
                ?: return null
            else -> return null
        }
        return result
    }

    private fun rejected(status: Int): EnvProxyResponse = EnvProxyResponse(
        status, if (status == 503) {
            "{\"code\":\"ENGINE_CONTROL_UNAVAILABLE\",\"message\":\"턴 데몬 제어 요청을 완료할 수 없습니다.\",\"status\":503}"
        } else {
            "{\"ok\":false,\"message\":\"턴 데몬 제어 요청을 완료할 수 없습니다.\",\"status\":$status}"
        },
    )

    internal companion object {
        private const val MAX_BODY = 16384
        private val PATHS = setOf("/admin/turn-daemon/pause", "/admin/turn-daemon/resume", "/admin/turn-daemon/catch-up")
        private val SAFE_ERRORS = setOf(400, 409, 503)
        internal fun requestFactory(): SimpleClientHttpRequestFactory = object : SimpleClientHttpRequestFactory() {
            init {
                setConnectTimeout(Duration.ofSeconds(5))
                setReadTimeout(Duration.ofSeconds(10))
            }
            override fun prepareConnection(connection: HttpURLConnection, httpMethod: String) {
                super.prepareConnection(connection, httpMethod)
                connection.instanceFollowRedirects = false
            }
        }
    }
}
