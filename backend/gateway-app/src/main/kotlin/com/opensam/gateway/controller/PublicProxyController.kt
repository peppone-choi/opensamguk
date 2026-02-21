package com.opensam.gateway.controller

import com.opensam.gateway.service.WorldRouteRegistry
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient

@RestController
@RequestMapping("/api/public")
class PublicProxyController(
    private val webClientBuilder: WebClient.Builder,
    private val worldRouteRegistry: WorldRouteRegistry,
) {
    private val responseSkipHeaders = setOf("transfer-encoding", "connection", "keep-alive")

    @GetMapping("/cached-map")
    fun getCachedMap(): ResponseEntity<ByteArray> {
        val baseUrl = pickAnyGameApp()
            ?: return notAvailableResponse()

        val targetUrl = "$baseUrl/api/public/cached-map"
        return proxyGet(targetUrl)
    }

    private fun pickAnyGameApp(): String? {
        return worldRouteRegistry.snapshot().values.firstOrNull()
    }

    private fun proxyGet(targetUrl: String): ResponseEntity<ByteArray> {
        val webClient = webClientBuilder.build()

        val response = webClient
            .get()
            .uri(targetUrl)
            .exchangeToMono { clientResponse ->
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

    private fun notAvailableResponse(): ResponseEntity<ByteArray> {
        val json = """{"available":false,"worldId":null,"worldName":null,"mapCode":null,"cities":[],"history":[]}"""
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(json.toByteArray())
    }
}
