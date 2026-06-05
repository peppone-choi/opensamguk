package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.MessageResponse
import opensamguk.gameapi.dto.MsgTarget
import opensamguk.infra.read.MessageRepository
import opensamguk.logic.util.jsonDecode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * 메일함 read API (W3 enriched). PHP grand truth: `Message`/`MessageTarget`.
 *
 * W3에서 [MessageResponse]에 body jsonb를 디코드한 구조화 필드(text/srcTarget/destTarget/option)를
 * 채운다. 디코드는 ContactReader와 동일한 [jsonDecode](PHP-faithful) 사용 — body는
 * `{src:MsgTarget, dest:MsgTarget?, text, option}` 형태.
 *
 * read-only(§7).
 */
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

    private fun opensamguk.infra.entity.MessageEntity.toResponse(): MessageResponse {
        // body jsonb 디코드 — 실패 시 빈 맵(구조화 필드 null, raw message는 보존).
        val body = runCatching { jsonDecode(message) }.getOrDefault(emptyMap())
        @Suppress("UNCHECKED_CAST")
        val srcMap = body["src"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val destMap = body["dest"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val optionMap = body["option"] as? Map<String, Any?>
        return MessageResponse(
            id = id,
            mailbox = mailbox,
            type = type.name,
            src = src,
            dest = dest,
            time = time,
            validUntil = validUntil,
            message = message,
            text = body["text"] as? String,
            srcTarget = srcMap?.toMsgTarget(),
            destTarget = destMap?.toMsgTarget(),
            option = optionMap,
        )
    }

    /** MessageTarget 블록 맵 → [MsgTarget]. PHP `toArray()` 키(`id,name,nation_id,nation,color,icon`). */
    private fun Map<String, Any?>.toMsgTarget() = MsgTarget(
        id = (this["id"] as? Number)?.toInt() ?: 0,
        name = this["name"] as? String ?: "",
        nationId = (this["nation_id"] as? Number)?.toInt() ?: 0,
        nation = this["nation"] as? String ?: "",
        color = this["color"] as? String ?: "",
        icon = this["icon"] as? String,
    )
}
