package com.opensam.controller

import com.opensam.service.NationService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/nations/{nationId}")
class NpcPolicyController(
    private val nationService: NationService,
) {
    @GetMapping("/npc-policy")
    fun getNpcPolicy(@PathVariable nationId: Long): ResponseEntity<Map<String, Any>> {
        val policy = nationService.getNpcPolicy(nationId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(policy)
    }

    @PutMapping("/npc-policy")
    fun updateNpcPolicy(
        @PathVariable nationId: Long,
        @RequestBody policy: Map<String, Any>,
    ): ResponseEntity<Void> {
        if (!nationService.updateNpcPolicy(nationId, policy)) return ResponseEntity.notFound().build()
        return ResponseEntity.ok().build()
    }

    @PutMapping("/npc-priority")
    fun updateNpcPriority(
        @PathVariable nationId: Long,
        @RequestBody priority: Map<String, Any>,
    ): ResponseEntity<Void> {
        if (!nationService.updateNpcPriority(nationId, priority)) return ResponseEntity.notFound().build()
        return ResponseEntity.ok().build()
    }
}
