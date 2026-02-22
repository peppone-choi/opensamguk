package com.opensam.gateway.config

import com.opensam.gateway.service.WorldRouteRegistry
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient

// Catch-all controller that proxies unmatched /api/** requests to the active game-app.
// Spring MVC routes by path specificity: explicit controllers (AuthController,
// WorldController, GameProxyController, etc.) always match first. Only paths that
// no other controller handles fall through to this wildcard mapping.
// Using a @RestController (not a servlet Filter) ensures Spring Security CORS
// headers are applied to the proxied response correctly.
@RestController
class GameApiCatchAllController(
    private val webClientBuilder: WebClient.Builder,
    private val worldRouteRegistry: WorldRouteRegistry,
) {
    private val requestSkipHeaders = setOf("host", "content-length")
    private val responseSkipHeaders = setOf("transfer-encoding", "connection", "keep-alive", "access-control-allow-origin", "access-control-allow-credentials", "access-control-allow-methods", "access-control-allow-headers", "access-control-expose-headers", "access-control-max-age")

    @RequestMapping("/api/**")
    fun proxyToGameApp(
        request: HttpServletRequest,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<ByteArray> {
        val baseUrl = pickGameAppBaseUrl()
            ?: return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body("""{"message":"No active game instance available"}""".toByteArray())

        val targetUrl = buildTargetUrl(baseUrl, request.requestURI, request.queryString)
        val method = HttpMethod.valueOf(request.method)
        val webClient = webClientBuilder.build()

        val baseSpec = webClient
            .method(method)
            .uri(targetUrl)
            .headers { headers ->
                val headerNames = request.headerNames
                while (headerNames.hasMoreElements()) {
                    val headerName = headerNames.nextElement()
                    if (headerName.lowercase() in requestSkipHeaders) continue
                    val values = request.getHeaders(headerName)
                    while (values.hasMoreElements()) {
                        headers.add(headerName, values.nextElement())
                    }
                }
            }

        val requestSpec = if (acceptsBody(method) && body != null && body.isNotEmpty()) {
            baseSpec.bodyValue(body)
        } else {
            baseSpec
        }

        return try {
            val proxyResponse = requestSpec.exchangeToMono { clientResponse ->
                clientResponse.bodyToMono(ByteArray::class.java)
                    .defaultIfEmpty(ByteArray(0))
                    .map { responseBody ->
                        val headers = HttpHeaders()
                        clientResponse.headers().asHttpHeaders().forEach { (headerName, values) ->
                            if (headerName.lowercase() !in responseSkipHeaders) {
                                headers.addAll(headerName, values)
                            }
                        }
                        ResponseEntity.status(clientResponse.statusCode())
                            .headers(headers)
                            .body(responseBody)
                    }
            }.block()

            proxyResponse ?: ResponseEntity.status(HttpStatus.BAD_GATEWAY).build()
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body("""{"message":"Game proxy error: ${e.message}"}""".toByteArray())
        }
    }

    private fun pickGameAppBaseUrl(): String? {
        return worldRouteRegistry.snapshot().values.firstOrNull()
    }

    private fun acceptsBody(method: HttpMethod): Boolean {
        return method != HttpMethod.GET && method != HttpMethod.HEAD && method != HttpMethod.OPTIONS
    }

    private fun buildTargetUrl(baseUrl: String, path: String, queryString: String?): String {
        return if (queryString.isNullOrBlank()) {
            "$baseUrl$path"
        } else {
            "$baseUrl$path?$queryString"
        }
    }
}
