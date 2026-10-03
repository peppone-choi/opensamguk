package opensamguk.engine.security

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import opensamguk.engine.status.StatusController
import org.springframework.web.filter.GenericFilterBean
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.springframework.web.util.ServletRequestPathUtils

/** Runs for every dispatch; MVC lookup selects the protected handler before any invocation. */
class TurnDaemonControlFilter(
    private val binding: TurnDaemonControlBinding,
    private val mappings: RequestMappingHandlerMapping,
) : GenericFilterBean() {
    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val http = request as HttpServletRequest
        val reply = response as HttpServletResponse
        // Observation endpoints and non-writing methods do not depend on control configuration/lookup.
        if (http.method != "POST") {
            chain.doFilter(request, response)
            return
        }
        val rawPath = http.requestURI
        // Reject ambiguous write paths, rather than turning them into a canonical control operation.
        if (rawPath.any { it == '%' || it == ';' || it == '\\' } || "//" in rawPath ||
            rawPath.split('/').any { it == "." || it == ".." }
        ) {
            reject(reply, 400)
            return
        }
        val handler = try {
            // FORWARD/ERROR must not retain the path parsed during the previous dispatch.
            ServletRequestPathUtils.parseAndCache(http)
            mappings.getHandler(http)?.handler as? HandlerMethod
        } catch (_: HttpRequestMethodNotSupportedException) {
            if (rawPath.removePrefix(http.contextPath).trimEnd('/') in CONTROL_PATHS.values) {
                reject(reply, 503)
            } else {
                // Preserve MVC's ordinary 405 for a POST to an observation/unrelated route.
                chain.doFilter(request, response)
            }
            return
        } catch (_: Exception) {
            reject(reply, 503)
            return
        }
        if (handler != null && StatusController::class.java.isAssignableFrom(handler.beanType) &&
            handler.method.name in CONTROL_PATHS
        ) {
            val expectedPath = CONTROL_PATHS.getValue(handler.method.name)
            val parsedPath = ServletRequestPathUtils.getParsedRequestPath(http).pathWithinApplication().value()
            if (parsedPath != expectedPath || rawPath != http.contextPath + expectedPath) {
                reject(reply, 400)
                return
            }
            if (!binding.permits(http)) {
                reject(reply, 401)
                return
            }
        } else if (rawPath.removePrefix(http.contextPath).trimEnd('/') in CONTROL_PATHS.values) {
            // A mapping disagreement must never make the known control paths public.
            reject(reply, 400)
            return
        }
        chain.doFilter(request, response)
    }

    private fun reject(response: HttpServletResponse, status: Int) {
        response.status = status
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        response.setHeader("Cache-Control", "no-store")
        response.writer.write("{\"message\":\"turn daemon control request rejected\",\"status\":$status}")
    }

    private companion object {
        val CONTROL_PATHS = mapOf(
            "pause" to "/admin/turn-daemon/pause",
            "resume" to "/admin/turn-daemon/resume",
            "catchUp" to "/admin/turn-daemon/catch-up",
        )
    }
}
