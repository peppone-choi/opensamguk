package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.MessageResponse
import opensamguk.infra.read.MessageRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api")
class MailboxController(
    private val messageRepository: MessageRepository,
) {

    @GetMapping("/mailbox/{mailbox}")
    fun mailbox(
        @PathVariable mailbox: Int,
    ): ResponseEntity<List<MessageResponse>> {
        val messages = messageRepository.findByMailboxOrderById(mailbox)
            .map { it.toResponse() }
        return ResponseEntity.ok(messages)
    }

    @GetMapping("/mailbox/{mailbox}/unread")
    fun unread(
        @PathVariable mailbox: Int,
    ): ResponseEntity<List<MessageResponse>> {
        val now = Instant.now()
        val messages = messageRepository.findByMailboxAndValidUntilAfter(mailbox, now)
            .map { it.toResponse() }
        return ResponseEntity.ok(messages)
    }

    @GetMapping("/messages/{id}")
    fun message(@PathVariable id: Int): ResponseEntity<MessageResponse> {
        val msg = messageRepository.findById(id)
            .orElse(null) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(msg.toResponse())
    }

    private fun opensamguk.infra.entity.MessageEntity.toResponse() = MessageResponse(
        id = id,
        mailbox = mailbox,
        type = type.name,
        src = src,
        dest = dest,
        time = time,
        validUntil = validUntil,
        message = message,
    )
}
