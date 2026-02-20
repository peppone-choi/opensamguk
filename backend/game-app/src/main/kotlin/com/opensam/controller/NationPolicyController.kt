package com.opensam.controller

import com.opensam.dto.NationPolicyInfo
import com.opensam.dto.UpdateNoticeRequest
import com.opensam.dto.UpdatePolicyRequest
import com.opensam.dto.UpdateScoutMsgRequest
import com.opensam.service.NationService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/nations/{nationId}")
class NationPolicyController(
    private val nationService: NationService,
) {
    @GetMapping("/policy")
    fun getPolicy(@PathVariable nationId: Long): ResponseEntity<NationPolicyInfo> {
        val policy = nationService.getPolicy(nationId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(policy)
    }

    @PatchMapping("/policy")
    fun updatePolicy(
        @PathVariable nationId: Long,
        @RequestBody request: UpdatePolicyRequest,
    ): ResponseEntity<Void> {
        if (!nationService.updatePolicy(nationId, request.rate, request.bill, request.secretLimit, request.strategicCmdLimit)) {
            return ResponseEntity.notFound().build()
        }
        return ResponseEntity.ok().build()
    }

    @PatchMapping("/notice")
    fun updateNotice(
        @PathVariable nationId: Long,
        @RequestBody request: UpdateNoticeRequest,
    ): ResponseEntity<Void> {
        if (!nationService.updateNotice(nationId, request.notice)) return ResponseEntity.notFound().build()
        return ResponseEntity.ok().build()
    }

    @PatchMapping("/scout-msg")
    fun updateScoutMsg(
        @PathVariable nationId: Long,
        @RequestBody request: UpdateScoutMsgRequest,
    ): ResponseEntity<Void> {
        if (!nationService.updateScoutMsg(nationId, request.scoutMsg)) return ResponseEntity.notFound().build()
        return ResponseEntity.ok().build()
    }
}
