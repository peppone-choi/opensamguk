package com.opensam.controller

import com.opensam.command.CommandResult
import com.opensam.dto.*
import com.opensam.service.CommandService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class CommandController(
    private val commandService: CommandService,
) {
    @GetMapping("/generals/{generalId}/turns")
    fun listGeneralTurns(@PathVariable generalId: Long): ResponseEntity<List<GeneralTurnResponse>> {
        return ResponseEntity.ok(
            commandService.listGeneralTurns(generalId).map { GeneralTurnResponse.from(it) }
        )
    }

    @PostMapping("/generals/{generalId}/turns")
    fun reserveGeneralTurns(
        @PathVariable generalId: Long,
        @RequestBody request: ReserveTurnsRequest,
    ): ResponseEntity<List<GeneralTurnResponse>> {
        return try {
            val saved = commandService.reserveGeneralTurns(generalId, request.turns)
            ResponseEntity.status(HttpStatus.CREATED).body(saved.map { GeneralTurnResponse.from(it) })
        } catch (e: IllegalStateException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        }
    }

    @PostMapping("/generals/{generalId}/execute")
    fun executeCommand(
        @PathVariable generalId: Long,
        @RequestBody request: ExecuteRequest,
    ): ResponseEntity<CommandResult> {
        val loginId = SecurityContextHolder.getContext().authentication?.name
            ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        if (!commandService.verifyOwnership(generalId, loginId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        val result = commandService.executeCommand(generalId, request.actionCode, request.arg)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(result)
    }

    @PostMapping("/generals/{generalId}/execute-nation")
    fun executeNationCommand(
        @PathVariable generalId: Long,
        @RequestBody request: ExecuteRequest,
    ): ResponseEntity<CommandResult> {
        val loginId = SecurityContextHolder.getContext().authentication?.name
            ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        if (!commandService.verifyOwnership(generalId, loginId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        val result = commandService.executeNationCommand(generalId, request.actionCode, request.arg)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(result)
    }

    @GetMapping("/generals/{generalId}/command-table")
    fun getCommandTable(@PathVariable generalId: Long): ResponseEntity<Map<String, List<CommandTableEntry>>> {
        val table = commandService.getCommandTable(generalId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(table)
    }

    @GetMapping("/generals/{generalId}/nation-command-table")
    fun getNationCommandTable(@PathVariable generalId: Long): ResponseEntity<Map<String, List<CommandTableEntry>>> {
        val table = commandService.getNationCommandTable(generalId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(table)
    }

    @PostMapping("/generals/{generalId}/turns/repeat")
    fun repeatTurns(
        @PathVariable generalId: Long,
        @RequestBody request: RepeatRequest,
    ): ResponseEntity<List<GeneralTurnResponse>> {
        return try {
            val saved = commandService.repeatTurns(generalId, request.count)
                ?: return ResponseEntity.notFound().build()
            ResponseEntity.ok(saved.map { GeneralTurnResponse.from(it) })
        } catch (e: IllegalStateException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        }
    }

    @PostMapping("/generals/{generalId}/turns/push")
    fun pushTurns(
        @PathVariable generalId: Long,
        @RequestBody request: PushRequest,
    ): ResponseEntity<List<GeneralTurnResponse>> {
        return try {
            val saved = commandService.pushTurns(generalId, request.amount)
                ?: return ResponseEntity.notFound().build()
            ResponseEntity.ok(saved.map { GeneralTurnResponse.from(it) })
        } catch (e: IllegalStateException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        }
    }

    @GetMapping("/nations/{nationId}/turns")
    fun listNationTurns(
        @PathVariable nationId: Long,
        @RequestParam(defaultValue = "12") officerLevel: Short,
    ): ResponseEntity<List<NationTurnResponse>> {
        return ResponseEntity.ok(
            commandService.listNationTurns(nationId, officerLevel).map { NationTurnResponse.from(it) }
        )
    }

    @PostMapping("/nations/{nationId}/turns")
    fun reserveNationTurns(
        @PathVariable nationId: Long,
        @RequestParam generalId: Long,
        @RequestBody request: ReserveTurnsRequest,
    ): ResponseEntity<List<NationTurnResponse>> {
        val loginId = SecurityContextHolder.getContext().authentication?.name
            ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        if (!commandService.verifyOwnership(generalId, loginId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        return try {
            val saved = commandService.reserveNationTurns(generalId, nationId, request.turns)
                ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
            ResponseEntity.status(HttpStatus.CREATED).body(saved.map { NationTurnResponse.from(it) })
        } catch (e: IllegalStateException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        }
    }
}
