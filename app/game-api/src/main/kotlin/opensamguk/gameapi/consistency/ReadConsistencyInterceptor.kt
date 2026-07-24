package opensamguk.gameapi.consistency

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import opensamguk.gameapi.config.GameApiProcessWorld
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class ReadConsistencyInterceptor(
    private val barrier: MinVersionBarrier,
    private val classifier: ReadConsistencyClassifier,
    private val objectMapper: ObjectMapper,
    processWorld: GameApiProcessWorld,
) : HandlerInterceptor {
    private val worldId = processWorld.worldId

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (request.method != "GET") return true
        val minVersion = request.getParameter("minVersion") ?: return true
        val requiredVersion = minVersion.toLongOrNull()
        if (requiredVersion == null || requiredVersion < 0) {
            writeJson(
                response,
                HttpStatus.BAD_REQUEST,
                linkedMapOf(
                    "status" to "INVALID_MIN_VERSION",
                    "reason" to "minVersion must be a non-negative integer",
                ),
            )
            return false
        }

        val consistencyClass = classifier.classify(request.requestURI)
        if (consistencyClass == ReadConsistencyClass.EVENTUAL) {
            return true
        }
        val visibility = barrier.await(requiredVersion)
        if (visibility.visible) return true

        writeJson(
            response,
            HttpStatus.CONFLICT,
            linkedMapOf(
                "status" to "VERSION_NOT_VISIBLE",
                "consistencyClass" to consistencyClass.name,
                "worldId" to worldId.value,
                "currentVersion" to visibility.currentVersion,
                "requiredVersion" to visibility.requiredVersion,
                "retryAfterMs" to visibility.retryAfterMs,
            ),
        )
        return false
    }

    private fun writeJson(
        response: HttpServletResponse,
        status: HttpStatus,
        body: Map<String, Any?>,
    ) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(response.writer, body)
    }
}
