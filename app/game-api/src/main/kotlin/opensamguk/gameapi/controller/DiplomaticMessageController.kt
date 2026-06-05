package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.AcceptDiplomaticMessageResponse
import opensamguk.gameapi.dto.DeclineDiplomaticMessageResponse
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.infra.read.MessageRepository
import opensamguk.logic.messaging.DiplomaticActionType
import opensamguk.logic.util.jsonDecodeAny
import opensamguk.logic.util.jsonEncode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * 외교 메시지 수락/거절 API — P6 P7-coupled + F4 mutation 배선.
 *
 * 수락(`POST /{id}/accept`)은 해당 외교 수락 명령(che_종전수락 / che_불가침수락 / che_불가침파기수락)을
 * 서버측에서 [CommandReserveService.reserve]로 **직접 예약**한다. 이는 제의 인테이크
 * ([opensamguk.gameapi.web.CommandController])가 che_* 명령에 쓰는 것과 동일한 sanctioned intake 경로
 * (Model A turn-reserved: general_turn 예약 row 기록 + 데몬 poke)이며, one-daemon-write-rule 위반이 아니다.
 * 클라이언트는 더 이상 반환된 commandKey를 직접 dispatch할 필요가 없다(정보용으로만 유지).
 *
 * 거절(`POST /{id}/decline`)은 메시지 invalidate만 수행하며 어떤 명령도 예약하지 않는다 — PHP
 * `DiplomaticMessage::declineMessage()`도 외교 상태를 바꾸지 않고 메시지만 만료시킨다.
 *
 * **방향(orientation) — 최우선 정합성:** A국이 B국에 제의하면 메시지는 B의 우편함에 도착하고, 저장된
 * `message` jsonb 본문의 `src.nation_id`가 제의국(A), `dest.nation_id`가 수신국(B)이다. B가 수락하면
 * 수락 명령의 resolve()는 `me = 수락국(B)`, `destNationID = args["destNationID"]`를 읽는데, 이 destNationID는
 * **제의국(A)** 이어야 한다. PHP `che_*수락`의 `$this->arg['destNationID']`도 제의국이다. 따라서 여기서
 * destNationID는 메시지 본문의 `src.nation_id`(제의국)에서 유도한다 — 제의가 향했던 `dest`(=B)가 아니다.
 *
 * **destGeneralID — argTest 필수:** PHP `che_불가침수락`/`che_종전수락`/`che_불가침파기수락`의 argTest()는
 * 모두 `destGeneralID`(=제의자 장수 id)를 요구하며, 누락·`<=0`·`==self`이면 거부한다(자기수락 가드).
 * 제의자 장수 id는 메시지 본문 `src.id`(MessageTarget.toArray가 generalID를 "id"로 직렬화)에 있다.
 *
 * **검증(checkDiplomaticMessageValidation 미러):** 예약 전에 PHP의 외교서신 유효성 검사를 재현한다 —
 *  (a) 만료/사용된 서신(`validUntil <= now`) → 400 "유효하지 않은 외교서신입니다." (재생/이중수락 차단),
 *  (b) 우편함 소유권: 수락 장수의 국가가 메시지 dest 국가(수신국)와 같아야 함 — 행동 장수의 국가는
 *      서버측 read repo로 직접 해석(클라 필드 불신). 다르면 → 400.
 *  (c) 불가침 수락의 year/month 범위(1<=month<=12, 둘 다 존재) → 위반 시 400.
 */
@RestController
@RequestMapping("/api/messages")
class DiplomaticMessageController(
    private val messageRepository: MessageRepository,
    private val reserve: CommandReserveService,
    private val generalReadRepository: GeneralReadRepository,
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

        // FIX #4(a) — checkDiplomaticMessageValidation 미러: 만료/사용된 서신은 처리 불가(재생·이중수락 차단).
        if (!msg.validUntil.isAfter(Instant.now())) {
            return ResponseEntity.badRequest().body(mapOf("error" to "유효하지 않은 외교서신입니다."))
        }

        // FIX #4(b) — 우편함 소유권: 수락 장수의 국가가 메시지 dest 국가(수신국)와 일치해야 함.
        // 행동 장수의 국가는 서버측 read repo로 해석(클라 필드 불신). PHP는 general을
        // `WHERE no=receiverID AND nation=dest.nationID`로 조회해 불일치 시 처리 거부.
        val actingNationId = generalReadRepository.findById(generalId).orElse(null)?.nationId
        if (actingNationId == null || actingNationId != body.destNationId) {
            return ResponseEntity.badRequest()
                .body(mapOf("error" to "해당 국가의 외교서신을 처리할 권한이 없습니다."))
        }

        // FIX #1 — destGeneralID(=제의자 장수 id)는 argTest 필수: 누락·<=0·==self 거부.
        // (제의자 장수 id는 본문 src.id이며, 수락 장수(self)와 같을 수 없다.)
        if (body.proposerGeneralId <= 0 || body.proposerGeneralId == generalId) {
            return ResponseEntity.badRequest()
                .body(mapOf("error" to "유효하지 않은 외교서신입니다."))
        }

        // FIX #5 — 불가침 수락 year/month 범위(1<=month<=12, 둘 다 존재) 검증.
        // 범위 위반 명령을 예약하면 엔진에서 조용히 no-op 되므로 사전에 400으로 거부.
        if (body.actionType == DiplomaticActionType.NO_AGGRESSION) {
            val year = body.args["year"] as? Int
            val month = body.args["month"] as? Int
            if (year == null || month == null || month < 1 || month > 12) {
                return ResponseEntity.badRequest()
                    .body(mapOf("error" to "유효하지 않은 외교서신입니다."))
            }
        }

        // 서버측에서 수락 명령을 예약(제의 인테이크와 동일한 sanctioned intake 경로).
        // PHP che_*수락의 getPreReqTurn()/getPostReqTurn() 모두 0 → 즉시/다음 슬롯 처리 ⇒ turnIdx=0
        // (제의 인테이크 CommandController가 쓰는 기본값과 동일).
        reserve.reserve(
            generalId = generalId,
            actionCode = commandKey,
            turnIdx = 0,
            argJson = jsonEncode(body.args),
        )

        // 메시지 invalid 처리(수락된 제의는 만료) — 모든 가드를 통과한 뒤에만 수행.
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
    ): ResponseEntity<Any> {
        val msg = messageRepository.findById(id).orElse(null)
            ?: return ResponseEntity.notFound().build()

        // FIX #4(b) — 거절도 동일한 우편함 소유권 가드: 수락 장수의 국가가 메시지 dest 국가와 일치해야 함.
        // (외교 메시지 형식이 아니면 본문 파싱 실패 → 권한 가드를 적용할 수 없으므로 형식 오류로 거부.)
        val body = parseDiplomaticBody(msg.message)
            ?: return ResponseEntity.badRequest().body(mapOf("error" to "외교 메시지 형식이 아닙니다."))

        val actingNationId = generalReadRepository.findById(generalId).orElse(null)?.nationId
        if (actingNationId == null || actingNationId != body.destNationId) {
            return ResponseEntity.badRequest()
                .body(mapOf("error" to "해당 국가의 외교서신을 처리할 권한이 없습니다."))
        }

        // 거절은 메시지 만료만 — 외교 상태 변화 없음(어떤 명령도 예약하지 않음, PHP declineMessage 동일).
        msg.validUntil = Instant.now().minusSeconds(1)
        messageRepository.save(msg)

        return ResponseEntity.ok(DeclineDiplomaticMessageResponse(status = "declined"))
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private data class ParsedBody(
        val acceptCommandKey: String?,
        val actionType: DiplomaticActionType,
        val destNationId: Int,
        val proposerGeneralId: Int,
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

            // 제의국(제의자) 국가 = 메시지 본문 src.nation_id. 이것이 수락 명령의 destNationID(상대=제의국)가 된다.
            // 제의가 향했던 dest(=수신국=수락국)가 아님 — 방향을 뒤집으면 엉뚱한 국가쌍에 외교가 설정됨.
            val src = map["src"] as? Map<String, Any?> ?: return null
            val proposerNationId = (src["nation_id"] as? Number)?.toInt() ?: return null
            if (proposerNationId < 1) return null

            // 제의자 장수 id = 본문 src.id(MessageTarget.toArray가 generalID를 "id"로 직렬화).
            // che_*수락 argTest의 destGeneralID로 사용된다(누락 시 0 → 가드에서 거부).
            val proposerGeneralId = (src["id"] as? Number)?.toInt() ?: 0

            // 수신국(수락국) 국가 = 메시지 본문 dest.nation_id — 우편함 소유권 가드 대상.
            val dest = map["dest"] as? Map<String, Any?> ?: return null
            val destNationId = (dest["nation_id"] as? Number)?.toInt() ?: return null

            val args = linkedMapOf<String, Any?>(
                "destNationID" to proposerNationId,
                "destGeneralID" to proposerGeneralId,
            )

            // 불가침 수락은 year/month가 추가로 필요(제의 본문 option.args가 아니라 option 자체에 평탄화되어 저장됨).
            if (actionType == DiplomaticActionType.NO_AGGRESSION) {
                (option["year"] as? Number)?.let { args["year"] = it.toInt() }
                (option["month"] as? Number)?.let { args["month"] = it.toInt() }
            }

            val commandKey = when (actionType) {
                DiplomaticActionType.NO_AGGRESSION -> "che_불가침수락"
                DiplomaticActionType.CANCEL_NA -> "che_불가침파기수락"
                DiplomaticActionType.STOP_WAR -> "che_종전수락"
            }

            ParsedBody(commandKey, actionType, destNationId, proposerGeneralId, args)
        } catch (_: Exception) {
            null
        }
    }
}
