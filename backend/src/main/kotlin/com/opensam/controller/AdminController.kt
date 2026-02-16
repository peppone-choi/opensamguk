package com.opensam.controller

import com.opensam.dto.AdminDashboard
import com.opensam.dto.AdminGeneralAction
import com.opensam.dto.AdminUserAction
import com.opensam.dto.NationStatistic
import com.opensam.dto.TimeControlRequest
import com.opensam.entity.AppUser
import com.opensam.entity.General
import com.opensam.service.AdminService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val adminService: AdminService,
) {
    @GetMapping("/dashboard")
    fun getDashboard(): ResponseEntity<AdminDashboard> {
        return ResponseEntity.ok(adminService.getDashboard())
    }

    @PatchMapping("/settings")
    fun updateSettings(@RequestBody settings: Map<String, Any>): ResponseEntity<Void> {
        if (!adminService.updateSettings(settings)) return ResponseEntity.notFound().build()
        return ResponseEntity.ok().build()
    }

    @GetMapping("/generals")
    fun listAllGenerals(): ResponseEntity<List<General>> {
        return ResponseEntity.ok(adminService.listAllGenerals())
    }

    @PostMapping("/generals/{id}/action")
    fun generalAction(
        @PathVariable id: Long,
        @RequestBody action: AdminGeneralAction,
    ): ResponseEntity<Void> {
        if (!adminService.generalAction(id, action.type)) return ResponseEntity.notFound().build()
        return ResponseEntity.ok().build()
    }

    @GetMapping("/statistics")
    fun getStatistics(): ResponseEntity<List<NationStatistic>> {
        return ResponseEntity.ok(adminService.getStatistics())
    }

    @GetMapping("/generals/{id}/logs")
    fun getGeneralLogs(@PathVariable id: Long): ResponseEntity<List<Any>> {
        return ResponseEntity.ok(adminService.getGeneralLogs(id))
    }

    @GetMapping("/diplomacy")
    fun getDiplomacyMatrix(): ResponseEntity<List<Any>> {
        return ResponseEntity.ok(adminService.getDiplomacyMatrix())
    }

    @PostMapping("/time-control")
    fun timeControl(@RequestBody request: TimeControlRequest): ResponseEntity<Void> {
        if (!adminService.timeControl(request.year, request.month, request.locked)) {
            return ResponseEntity.notFound().build()
        }
        return ResponseEntity.ok().build()
    }

    @GetMapping("/users")
    fun listUsers(): ResponseEntity<List<AppUser>> {
        return ResponseEntity.ok(adminService.listUsers())
    }

    @PostMapping("/users/{id}/action")
    fun userAction(
        @PathVariable id: Long,
        @RequestBody action: AdminUserAction,
    ): ResponseEntity<Void> {
        if (!adminService.userAction(id, action.type)) return ResponseEntity.notFound().build()
        return ResponseEntity.ok().build()
    }
}
