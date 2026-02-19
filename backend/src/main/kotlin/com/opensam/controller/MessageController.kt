package com.opensam.controller

import com.opensam.dto.ContactInfo
import com.opensam.dto.DiplomacyRespondRequest
import com.opensam.dto.MessageResponse
import com.opensam.dto.SendMessageRequest
import com.opensam.service.MessageService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/messages")
class MessageController(
    private val messageService: MessageService,
) {
    @GetMapping
    fun getMessages(
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) worldId: Long?,
        @RequestParam(required = false) nationId: Long?,
        @RequestParam(required = false) generalId: Long?,
        @RequestParam(required = false, defaultValue = "0") officerLevel: Short,
    ): ResponseEntity<List<MessageResponse>> {
        val messages = when (type?.lowercase()) {
            "public" -> messageService.getPublicMessages(requireParam(worldId, "worldId"))
            "national" -> messageService.getNationalMessages(requireParam(nationId, "nationId"))
            "private" -> messageService.getPrivateMessages(requireParam(generalId, "generalId"))
            "diplomacy" -> messageService.getDiplomacyMessages(requireParam(nationId, "nationId"), officerLevel)
            null -> {
                val targetGeneralId = requireParam(generalId, "generalId")
                messageService.getMessages(targetGeneralId)
            }
            else -> throw IllegalArgumentException("Unsupported mailbox type: $type")
        }

        return ResponseEntity.ok(messages.map { MessageResponse.from(it) })
    }

    @GetMapping("/board")
    fun getBoardMessages(@RequestParam worldId: Long): ResponseEntity<List<MessageResponse>> {
        return ResponseEntity.ok(messageService.getBoardMessages(worldId).map { MessageResponse.from(it) })
    }

    @GetMapping("/secret-board")
    fun getSecretBoardMessages(
        @RequestParam worldId: Long,
        @RequestParam nationId: Long,
    ): ResponseEntity<List<MessageResponse>> {
        return ResponseEntity.ok(messageService.getSecretBoardMessages(worldId, nationId).map { MessageResponse.from(it) })
    }

    @PostMapping
    fun sendMessage(@RequestBody request: SendMessageRequest): ResponseEntity<MessageResponse> {
        val message = messageService.sendMessage(
            worldId = request.worldId,
            mailboxCode = request.mailboxCode,
            mailboxType = request.mailboxType,
            messageType = request.messageType,
            srcId = request.srcId,
            destId = request.destId,
            officerLevel = request.officerLevel,
            payload = request.payload,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(MessageResponse.from(message))
    }

    @DeleteMapping("/{id}")
    fun deleteMessage(@PathVariable id: Long): ResponseEntity<Void> {
        messageService.deleteMessage(id)
        return ResponseEntity.noContent().build()
    }

    @PatchMapping("/{id}/read")
    fun markAsRead(@PathVariable id: Long): ResponseEntity<Void> {
        messageService.markAsRead(id)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/recent")
    fun getRecentMessages(@RequestParam sequence: Long): ResponseEntity<List<MessageResponse>> {
        return ResponseEntity.ok(messageService.getRecentMessages(sequence).map { MessageResponse.from(it) })
    }

    @PostMapping("/{id}/diplomacy-respond")
    fun respondDiplomacy(
        @PathVariable id: Long,
        @RequestBody request: DiplomacyRespondRequest,
    ): ResponseEntity<Void> {
        messageService.respondDiplomacy(id, request.accept)
        return ResponseEntity.ok().build()
    }

    private fun <T> requireParam(value: T?, name: String): T {
        return value ?: throw IllegalArgumentException("$name is required")
    }
}

@RestController
@RequestMapping("/api")
class ContactController(
    private val messageService: MessageService,
) {
    @GetMapping("/worlds/{worldId}/contacts")
    fun getContacts(@PathVariable worldId: Long): ResponseEntity<List<ContactInfo>> {
        return ResponseEntity.ok(messageService.getContacts(worldId))
    }
}
