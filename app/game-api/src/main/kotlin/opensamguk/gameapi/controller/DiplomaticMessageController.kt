package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.AcceptDiplomaticMessageResponse
import opensamguk.gameapi.dto.DeclineDiplomaticMessageResponse
import opensamguk.infra.read.MessageRepository
import opensamguk.logic.messaging.DiplomaticActionType
import opensamguk.logic.util.jsonDecodeAny
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * 외교 메시지 수락/거절 API — P6 P7-coupled.
 *
 * 수락 시 해당 외교 명령 키(예: `che_불가침수락`)를 반환하며,
 * 클라이언트는 기존 `/api/command/{code}` 흐름으로 nation command를 예약·실행한다.
 * 메시지는 즉시 invalid(만료) 처리된다.
 *
 * TODO(P8): engine-layer daemon command로 이관하여 수락·거절을 턴 데몬이 일괄 처리.
 */
@RestController
@RequestMapping("/api/messages")
class DiplomaticMessageController(
    private val messageRepository: MessageRepository,
) {

    @PostMapping("/{id}/accept")
    fun accept(
        @PathVariable id: Int,
        @RequestParam generalId: Int,
    ): ResponseEntity<Any> {
        val msg = messageRepository.findById(id).orElse(null)
            ?: return ResponseEntity.notFound().build()

        val body = parseDiplomaticBody(msg.message)
            ?: return ResponseEntity.badRequest().body(mapOf("error" to "외교 메시지 형식이 아닙니다."))

        val commandKey = body.acceptCommandKey
            ?: return ResponseEntity.badRequest().body(mapOf("error" to "지원하지 않는 외교 액션입니다."))

        // 메시지 invalid 처리
        msg.validUntil = Instant.now().minusSeconds(1)
        messageRepository.save(msg)

        return ResponseEntity.ok(
            AcceptDiplomaticMessageResponse(
                status = "accepted",
                commandKey = commandKey,
                commandArgs = body.args,
            )
        )
    }

    @PostMapping("/{id}/decline")
    fun decline(
        @PathVariable id: Int,
        @RequestParam generalId: Int,
    ): ResponseEntity<DeclineDiplomaticMessageResponse> {
        val msg = messageRepository.findById(id).orElse(null)
            ?: return ResponseEntity.notFound().build()

        msg.validUntil = Instant.now().minusSeconds(1)
        messageRepository.save(msg)

        return ResponseEntity.ok(DeclineDiplomaticMessageResponse(status = "declined"))
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private data class ParsedBody(
        val acceptCommandKey: String?,
        val args: Map<String, Any?>,
    )

    @Suppress("UNCHECKED_CAST")
    private fun parseDiplomaticBody(json: String): ParsedBody? {
        return try {
            val map = jsonDecodeAny(json) as? Map<String, Any?> ?: return null
            val option = map["option"] as? Map<String, Any?> ?: return null
            val actionCode = option["action"] as? String ?: return null

            val actionType = DiplomaticActionType.entries.find { it.code == actionCode }
                ?: return null

            val fromNationId = (option["fromNationId"] as? Number)?.toInt() ?: 0
            val args = linkedMapOf<String, Any?>("destNationID" to fromNationId)

            // 불가침의 경우 year/month 추가
            if (actionType == DiplomaticActionType.NO_AGGRESSION) {
                val rawArgs = option["args"] as? Map<String, Any?>
                rawArgs?.get("year")?.let { args["year"] = it }
                rawArgs?.get("month")?.let { args["month"] = it }
            }

            val commandKey = when (actionType) {
                DiplomaticActionType.NO_AGGRESSION -> "che_불가침수락"
                DiplomaticActionType.CANCEL_NA -> "che_불가침파기수락"
                DiplomaticActionType.STOP_WAR -> "che_종전수락"
            }

            ParsedBody(commandKey, args)
        } catch (_: Exception) {
            null
        }
    }
}
