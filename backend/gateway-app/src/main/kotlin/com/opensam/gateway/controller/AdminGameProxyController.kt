package com.opensam.gateway.controller

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

@RestController
@RequestMapping("/api/admin")
class AdminGameProxyController(
    private val webClientBuilder: WebClient.Builder,
    private val worldRouteRegistry: WorldRouteRegistry,
) {
    private val requestSkipHeaders = setOf("host", "content-length")
    private val responseSkipHeaders = setOf("transfer-encoding", "connection", "keep-alive")

    @RequestMapping("/**")
    fun proxyToGameAdmin(
        request: HttpServletRequest,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<ByteArray> {
        // Local admin endpoints are implemented with more specific mappings:
        // - /api/admin/users/**
        // - /api/admin/system-flags
        // - /api/admin/scrub
        // - /api/admin/game-versions/**

        val baseUrl = worldRouteRegistry.snapshot().values.firstOrNull()
            ?: return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("World route not found".toByteArray())

        val routedPath = extractRoutedPath(request)
        val targetUrl = buildTargetUrl(baseUrl, routedPath, request.queryString)
        val webClient = webClientBuilder.build()

        val method = HttpMethod.valueOf(request.method)
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

        val response = requestSpec.exchangeToMono { clientResponse ->
            clientResponse.bodyToMono(ByteArray::class.java)
                .defaultIfEmpty(ByteArray(0))
                .map { responseBody ->
                    val headers = HttpHeaders()
                    clientResponse.headers().asHttpHeaders().forEach { (headerName, values) ->
                        if (headerName.lowercase() !in responseSkipHeaders) {
                            headers.addAll(headerName, values)
                        }
                    }
                    ResponseEntity.status(clientResponse.statusCode()).headers(headers).body(responseBody)
                }
        }.block()

        return response ?: ResponseEntity.status(HttpStatus.BAD_GATEWAY).build()
    }

    private fun acceptsBody(method: HttpMethod): Boolean {
        return method != HttpMethod.GET && method != HttpMethod.HEAD && method != HttpMethod.OPTIONS
    }

    private fun extractRoutedPath(request: HttpServletRequest): String {
        val pathWithinApp = request.requestURI.removePrefix(request.contextPath)
        val marker = "/api/admin"
        val markerIndex = pathWithinApp.indexOf(marker)
        if (markerIndex < 0) return "/"

        val afterPrefix = pathWithinApp.substring(markerIndex + marker.length)
        return if (afterPrefix.isBlank()) "/" else afterPrefix
    }

    private fun buildTargetUrl(baseUrl: String, routedPath: String, queryString: String?): String {
        val normalizedPath = if (routedPath.startsWith('/')) routedPath else "/$routedPath"
        return if (queryString.isNullOrBlank()) {
            "$baseUrl/api/admin$normalizedPath"
        } else {
            "$baseUrl/api/admin$normalizedPath?$queryString"
        }
    }
}
