package com.opensam.controller

import com.opensam.command.CommandResult
import com.opensam.dto.ItemDiscardRequest
import com.opensam.dto.ItemEquipRequest
import com.opensam.dto.ItemGiveRequest
import com.opensam.dto.ItemUnequipRequest
import com.opensam.dto.ItemUseRequest
import com.opensam.service.CommandService
import com.opensam.service.ItemService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class ItemController(
    private val commandService: CommandService,
    private val itemService: ItemService,
) {
    @PostMapping("/generals/{generalId}/items/equip")
    fun equipItem(
        @PathVariable generalId: Long,
        @RequestBody request: ItemEquipRequest,
    ): ResponseEntity<CommandResult> {
        val loginId = SecurityContextHolder.getContext().authentication?.name
            ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        if (!commandService.verifyOwnership(generalId, loginId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        val result = itemService.buyItem(generalId, request.itemCode, request.itemType)
        return ResponseEntity.ok(CommandResult(result.success, listOf(result.message)))
    }

    @PostMapping("/generals/{generalId}/items/unequip")
    fun unequipItem(
        @PathVariable generalId: Long,
        @RequestBody request: ItemUnequipRequest,
    ): ResponseEntity<CommandResult> {
        val loginId = SecurityContextHolder.getContext().authentication?.name
            ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        if (!commandService.verifyOwnership(generalId, loginId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        val result = itemService.unequipItem(generalId, request.itemType)
        return ResponseEntity.ok(CommandResult(result.success, listOf(result.message)))
    }

    @PostMapping("/generals/{generalId}/items/use")
    fun useItem(
        @PathVariable generalId: Long,
        @RequestBody request: ItemUseRequest,
    ): ResponseEntity<CommandResult> {
        val loginId = SecurityContextHolder.getContext().authentication?.name
            ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        if (!commandService.verifyOwnership(generalId, loginId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        val result = itemService.consumeItem(generalId, request.itemType, request.itemCode)
        return ResponseEntity.ok(CommandResult(result.success, listOf(result.message)))
    }

    @PostMapping("/generals/{generalId}/items/discard")
    fun discardItem(
        @PathVariable generalId: Long,
        @RequestBody request: ItemDiscardRequest,
    ): ResponseEntity<CommandResult> {
        val loginId = SecurityContextHolder.getContext().authentication?.name
            ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        if (!commandService.verifyOwnership(generalId, loginId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        val result = itemService.discardItem(generalId, request.itemType)
        return ResponseEntity.ok(CommandResult(result.success, listOf(result.message)))
    }

    @PostMapping("/generals/{generalId}/items/give")
    fun giveItem(
        @PathVariable generalId: Long,
        @RequestBody request: ItemGiveRequest,
    ): ResponseEntity<CommandResult> {
        val loginId = SecurityContextHolder.getContext().authentication?.name
            ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        if (!commandService.verifyOwnership(generalId, loginId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        val result = itemService.giveItem(generalId, request.targetGeneralId, request.itemType)
        return ResponseEntity.ok(CommandResult(result.success, listOf(result.message)))
    }
}
