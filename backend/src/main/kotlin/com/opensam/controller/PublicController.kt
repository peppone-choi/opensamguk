package com.opensam.controller

import com.opensam.dto.PublicCachedMapResponse
import com.opensam.service.PublicCachedMapService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/public")
class PublicController(
    private val publicCachedMapService: PublicCachedMapService,
) {
    @GetMapping("/cached-map")
    fun getCachedMap(): ResponseEntity<PublicCachedMapResponse> {
        return ResponseEntity.ok(publicCachedMapService.getCachedMap())
    }
}
