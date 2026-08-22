package opensamguk.engine.intake

import opensamguk.common.wire.DiploLetterResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.common.josa.JosaUtil
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.TurnGeneral
import opensamguk.infra.read.DiplomacyLetterReadRow
import opensamguk.infra.read.DiplomacyLetterRepository
import opensamguk.logic.actions.intake.SecretPermission
import opensamguk.logic.message.Message
import opensamguk.logic.message.MessageRowDraft
import opensamguk.logic.message.MessageTarget
import opensamguk.logic.message.MessageType
import opensamguk.logic.util.jsonDecode
import opensamguk.logic.util.jsonEncode
import java.time.Instant

/**
 * 외교 서신 (diplomacy_letter) intake 핸들러 — `j_diplomacy_send_letter.php` /
 * `_rollback_letter.php` / `_destroy_letter.php` `launch()` 본문의 faithful 포팅 (W5d 외교 서신).
 * per-run (world + recorder + diplomacy-letter read seam), [BoardHandler]+[VoteHandler]를 미러링.
 *
 * 부수 효과는 [ChangeRecorder]를 통해서만 기록한다(one-daemon-write):
 *  - `diplomacy_letter` INSERT(발송, id 선할당=newLetterNo) + state/aux UPDATE(prev→replaced /
 *    회수→cancelled / 파기 state_opt·cancelled) — recorder W5d 채널 (board/auction과 동일, world-state 효과 아님).
 *  - `message` INSERT(diplomacy type, 양측) — logic [Message] 라우팅(receiver-먼저-sender, body fold).
 *  - `aux` jsonb는 LinkedHashMap 삽입 순서 보존: 발송은 `{src,dest}`, replaced/cancelled/destroy는 기존
 *    `{src,dest}` 뒤에 `reason` 또는 `state_opt`를 **끝에** 추가(PHP `$aux['reason']=…` append 시맨틱).
 *
 * **state 케이싱.** read seam은 PHP 소문자('proposed'/'activated'/…)로 정규화해 반환하고, 이 핸들러는
 * PHP 그대로 소문자 비교로 게이트한다. write 시점에는 opensamguk enum 대문자('PROPOSED'/…)로 싣고
 * executor가 `CAST(... AS diplomacy_letter_state)`로 바인딩한다([letterStateEnum] 헬퍼가 변환).
 *
 * **generalIcon(메시지/aux 아이콘) — 설정 측 관심사.** PHP `GetImageURL($me['imgsvr'],$me['picture'])`는
 * config(공유 아이콘 경로)에 의존하는 문자열로, [MessageTarget] 격리 노트대로 daemon-golden 동결 회귀 대상이
 * 아니다(P7/config request-side 해소). 게이트웨이가 인테이크 시 `general.meta["icon"]`을 실어 보내면 그
 * 값을 쓰고, 부재 시 빈 문자열을 쓴다(발명 금지).
 *
 * [diplomacyLetterRepository]는 nullable — null이면 stub-empty(서신 없음)로 동작한다.
 */
class DiplomacyLetterHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val diplomacyLetterRepository: DiplomacyLetterRepository? = null,
    private val nowProvider: () -> Instant = Instant::now,
) {

    // ── j_diplomacy_send_letter.php ─────────────────────────────────────────────────────────────
    fun handleSend(c: TurnDaemonCommand.DiploSendLetter): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return fail("diploSendLetter", c.generalId, reason = "장수가 없습니다.")
        if (AccessLogThrottle(world, recorder, nowProvider).increaseAndBlocked(c.generalId)) {
            return fail("diploSendLetter", c.generalId, reason = "접속 제한입니다.")
        }

        var destNationNo = c.destNationId

        // PHP :40 — 자국으로 보낼 수 없음.
        if (destNationNo == me.nationId) {
            return fail("diploSendLetter", c.generalId, reason = "자국으로 보낼 수 없습니다.")
        }

        // PHP :47 — brief/detail가 (Util::getPost) null이면 '올바르지 않은 입력입니다.'. 데몬 wire는 기본 ""
        // (non-null)이라 PHP의 null 구분은 인테이크 검증에서 흡수되고, 여기선 blank-brief를 별도 게이트한다.
        // PHP :54-55 trim → :57 `!$textBrief`면 '요약문이 비어있습니다'.
        val textBrief = c.textBrief.trim()
        val textDetail = c.textDetail.trim()
        if (textBrief.isEmpty()) {
            return fail("diploSendLetter", c.generalId, reason = "요약문이 비어있습니다")
        }

        // PHP :65 — checkSecretPermission(me) < 4면 수뇌부 deny.
        if (SecretPermission.check(PerTurnOverlay.toLogicGeneral(me)) < 4) {
            return fail("diploSendLetter", c.generalId, reason = "권한이 부족합니다. 수뇌부가 아닙니다.")
        }

        val srcNationNo = me.nationId

        // PHP :75-121 — prevNo 체인 처리. (크로스-모듈 nullable 스마트캐스트 회피 — 로컬 캡처)
        val prevNo = c.prevLetterNo
        if (prevNo != null) {
            val prevLetter = repo()?.findLetter(prevNo)
                ?: return fail("diploSendLetter", c.generalId, reason = "이전 문서가 없습니다.")

            // PHP :77-82 — src_nation_id IN (src,dest) AND dest_nation_id IN (src,dest).
            val membersOk = prevLetter.srcNationId in setOf(srcNationNo, destNationNo) &&
                prevLetter.destNationId in setOf(srcNationNo, destNationNo)
            if (!membersOk) {
                return fail("diploSendLetter", c.generalId, reason = "이전 문서가 없습니다.")
            }

            // PHP :92-100 — prev_no = prevNo AND state != cancelled 인 후속 문서가 있으면 deny.
            if (repo()!!.countNewerLetters(prevNo) > 0) {
                return fail("diploSendLetter", c.generalId, reason = "해당 문서에 대한 새로운 문서가 이미 있습니다.")
            }

            // PHP :102-107 — destNationNo 재계산(prev의 상대국으로).
            destNationNo = if (prevLetter.srcNationId != srcNationNo) prevLetter.srcNationId else prevLetter.destNationId

            // PHP :109-120 — prev.state == 'proposed'면 replaced로 전환(aux 끝에 reason append).
            if (prevLetter.state == "proposed") {
                val prevAux = jsonDecode(prevLetter.auxJson)
                val nextAux = LinkedHashMap<String, Any?>(prevAux)
                nextAux["reason"] = reasonMap(me.id, action = "new_letter", reason = "new_letter")
                recorder.recordDiplomacyLetterUpdate(
                    prevNo,
                    linkedMapOf(
                        "state" to letterStateEnum("replaced"),
                        "aux" to jsonEncode(nextAux),
                    ),
                )
            }
        }

        // PHP :123-129 — src/dest 국가 두 행 조회. 둘 다 있어야 한다.
        val srcNation = world.getNationById(srcNationNo)
        val destNation = world.getNationById(destNationNo)
        if (srcNation == null || destNation == null) {
            return fail("diploSendLetter", c.generalId, reason = "올바르지 않은 국가입니다.")
        }

        val icon = generalIcon(me)

        // PHP :143-165 — diplomacy_letter INSERT. aux는 LinkedHashMap 삽입 순서 보존: src 먼저, dest 나중.
        val aux = linkedMapOf<String, Any?>(
            "src" to linkedMapOf<String, Any?>(
                "nationName" to srcNation.name,
                "nationColor" to srcNation.color,
                "generalName" to me.name,
                "generalIcon" to icon,
            ),
            "dest" to linkedMapOf<String, Any?>(
                "nationName" to destNation.name,
                "nationColor" to destNation.color,
            ),
        )
        val newLetterNo = recorder.recordDiplomacyLetterInsert(
            linkedMapOf(
                "src_nation_id" to srcNation.id,
                "dest_nation_id" to destNation.id,
                "prev_id" to c.prevLetterNo,
                "state" to letterStateEnum("proposed"),
                "text_brief" to textBrief,
                "text_detail" to textDetail,
                "date" to nowString(),
                "src_signer" to me.id,
                "dest_signer" to null,
                "aux" to jsonEncode(aux),
            ),
        )

        // PHP :168-192 — diplomacy 메시지(양측). text는 prevNo 유무로 분기 + Josa 이(가).
        val josaYi = JosaUtil.pick(newLetterNo.toString(), "이")
        val msgText = if (c.prevLetterNo != null) {
            "문서 #${c.prevLetterNo}의 새로운 외교 문서 #$newLetterNo$josaYi 준비되었습니다. 외교부에서 확인해주세요."
        } else {
            "새로운 외교 문서 #$newLetterNo$josaYi 준비되었습니다. 외교부에서 확인해주세요."
        }
        routeDiplomacyMessage(
            srcGeneralId = me.id, srcGeneralName = me.name, srcIcon = icon,
            srcNation = srcNation, destNation = destNation, text = msgText,
        )

        return DiploLetterResult(type = "diploSendLetter", ok = true, generalId = c.generalId, letterNo = newLetterNo)
    }

    // ── j_diplomacy_rollback_letter.php ─────────────────────────────────────────────────────────
    fun handleRollback(c: TurnDaemonCommand.DiploRollbackLetter): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return fail("diploRollbackLetter", c.generalId, letterNo = c.letterNo, reason = "장수가 없습니다.")
        if (AccessLogThrottle(world, recorder, nowProvider).increaseAndBlocked(c.generalId)) {
            return fail("diploRollbackLetter", c.generalId, letterNo = c.letterNo, reason = "접속 제한입니다.")
        }

        // PHP :41 — 수뇌부 게이트(letterNo null 검사는 wire Int라 항상 통과; PHP order는 limit→permission→read).
        if (SecretPermission.check(PerTurnOverlay.toLogicGeneral(me)) < 4) {
            return fail("diploRollbackLetter", c.generalId, letterNo = c.letterNo, reason = "권한이 부족합니다. 수뇌부가 아닙니다.")
        }

        // PHP :49 — WHERE no AND src_nation_id = me.nation AND state = 'proposed'.
        val letter = repo()?.findLetter(c.letterNo)
        if (letter == null || letter.srcNationId != me.nationId || letter.state != "proposed") {
            return fail("diploRollbackLetter", c.generalId, letterNo = c.letterNo, reason = "서신이 없습니다.")
        }

        // PHP :69-77 — state=cancelled + aux 끝에 reason append(회수).
        val nextAux = LinkedHashMap<String, Any?>(jsonDecode(letter.auxJson))
        nextAux["reason"] = reasonMap(me.id, action = "cancelled", reason = "회수")
        recorder.recordDiplomacyLetterUpdate(
            c.letterNo,
            linkedMapOf(
                "state" to letterStateEnum("cancelled"),
                "aux" to jsonEncode(nextAux),
            ),
        )

        // PHP :78-89 — "외교 서신(#{letterNo})이 회수되었습니다." (양측 diplomacy 메시지).
        val (srcNation, destNation) = nationsFor(letter) ?: return fail(
            "diploRollbackLetter", c.generalId, letterNo = c.letterNo, reason = "올바르지 않은 국가입니다.",
        )
        routeDiplomacyMessage(
            srcGeneralId = me.id, srcGeneralName = me.name, srcIcon = generalIcon(me),
            srcNation = srcNation, destNation = destNation,
            text = "외교 서신(#${c.letterNo})이 회수되었습니다.",
        )

        return DiploLetterResult(type = "diploRollbackLetter", ok = true, generalId = c.generalId, letterNo = c.letterNo)
    }

    // ── j_diplomacy_destroy_letter.php ──────────────────────────────────────────────────────────
    fun handleDestroy(c: TurnDaemonCommand.DiploDestroyLetter): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return fail("diploDestroyLetter", c.generalId, letterNo = c.letterNo, reason = "장수가 없습니다.")
        if (AccessLogThrottle(world, recorder, nowProvider).increaseAndBlocked(c.generalId)) {
            return fail("diploDestroyLetter", c.generalId, letterNo = c.letterNo, reason = "접속 제한입니다.")
        }

        // PHP :41 — 수뇌부 게이트.
        if (SecretPermission.check(PerTurnOverlay.toLogicGeneral(me)) < 4) {
            return fail("diploDestroyLetter", c.generalId, letterNo = c.letterNo, reason = "권한이 부족합니다. 수뇌부가 아닙니다.")
        }

        // PHP :49 — WHERE no AND (src OR dest = me.nation) AND state = 'activated'.
        val letter = repo()?.findLetter(c.letterNo)
        if (letter == null ||
            (letter.srcNationId != me.nationId && letter.destNationId != me.nationId) ||
            letter.state != "activated"
        ) {
            return fail("diploDestroyLetter", c.generalId, letterNo = c.letterNo, reason = "서신이 없습니다.")
        }

        val stateOpt = letter.stateOpt

        // PHP :63-69 — 이미 자기 쪽 파기 신청을 한 경우 deny.
        val isSrc = letter.srcNationId == me.nationId
        val alreadyRequestedByMe =
            (stateOpt == "try_destroy_src" && isSrc) || (stateOpt == "try_destroy_dest" && !isSrc)
        if (alreadyRequestedByMe) {
            return fail("diploDestroyLetter", c.generalId, letterNo = c.letterNo, reason = "이미 파기 신청을 했습니다.")
        }

        // 메시지 src/dest: PHP :76-83 — 보내는 쪽(나) → 받는 쪽(상대). isSrc면 src/dest 그대로, 아니면 swap.
        val (rowSrcNation, rowDestNation) = nationsFor(letter) ?: return fail(
            "diploDestroyLetter", c.generalId, letterNo = c.letterNo, reason = "올바르지 않은 국가입니다.",
        )
        val msgSrcNation = if (isSrc) rowSrcNation else rowDestNation
        val msgDestNation = if (isSrc) rowDestNation else rowSrcNation

        val msgText: String
        if (stateOpt == "try_destroy_src" || stateOpt == "try_destroy_dest") {
            // PHP :89-110 — 2단계: 상대가 이미 신청 → cancelled. prev_no 체인은 cascade하지 않는다
            // (Q-W5d1: PHP while-loop의 deleteAux는 $db->update가 없는 dead code — TOP 서신만 cancelled).
            // aux는 변경 없이 그대로 재기록(PHP `Json::encode($aux)` — 신규 키 없음).
            recorder.recordDiplomacyLetterUpdate(
                c.letterNo,
                linkedMapOf(
                    "state" to letterStateEnum("cancelled"),
                    "aux" to jsonEncode(jsonDecode(letter.auxJson)),
                ),
            )
            msgText = "외교 서신(#${c.letterNo})을 파기했습니다."
        } else {
            // PHP :111-123 — 1단계: state_opt 적재(aux 끝에 append) + 상태는 activated 유지.
            val nextAux = LinkedHashMap<String, Any?>(jsonDecode(letter.auxJson))
            nextAux["state_opt"] = if (isSrc) "try_destroy_src" else "try_destroy_dest"
            recorder.recordDiplomacyLetterUpdate(
                c.letterNo,
                linkedMapOf("aux" to jsonEncode(nextAux)),
            )
            msgText = "외교 서신(#${c.letterNo})을 파기 요청합니다."
        }

        routeDiplomacyMessage(
            srcGeneralId = me.id, srcGeneralName = me.name, srcIcon = generalIcon(me),
            srcNation = msgSrcNation, destNation = msgDestNation, text = msgText,
        )

        return DiploLetterResult(type = "diploDestroyLetter", ok = true, generalId = c.generalId, letterNo = c.letterNo)
    }

    fun handleRespond(c: TurnDaemonCommand.DiploRespondLetter): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return fail("diploRespondLetter", c.generalId, letterNo = c.letterNo, reason = "장수가 없습니다.")
        if (AccessLogThrottle(world, recorder, nowProvider).increaseAndBlocked(c.generalId)) {
            return fail("diploRespondLetter", c.generalId, letterNo = c.letterNo, reason = "접속 제한입니다.")
        }

        if (SecretPermission.check(PerTurnOverlay.toLogicGeneral(me)) < 4) {
            return fail("diploRespondLetter", c.generalId, letterNo = c.letterNo, reason = "권한이 부족합니다. 수뇌부가 아닙니다.")
        }

        val letter = repo()?.findLetter(c.letterNo)
        if (letter == null || letter.destNationId != me.nationId || letter.state != "proposed") {
            return fail("diploRespondLetter", c.generalId, letterNo = c.letterNo, reason = "서신이 없습니다.")
        }

        val aux = LinkedHashMap<String, Any?>(jsonDecode(letter.auxJson))
        if (c.isAgree) {
            @Suppress("UNCHECKED_CAST")
            val dest = LinkedHashMap((aux["dest"] as? Map<String, Any?>) ?: emptyMap())
            dest["generalName"] = me.name
            dest["generalIcon"] = generalIcon(me)
            aux["dest"] = dest
            recorder.recordDiplomacyLetterUpdate(
                c.letterNo,
                linkedMapOf(
                    "state" to letterStateEnum("activated"),
                    "dest_signer" to me.id,
                    "aux" to jsonEncode(aux),
                ),
            )
            var prevLetterNo = letter.prevNo
            while (prevLetterNo != null) {
                recorder.recordDiplomacyLetterUpdate(prevLetterNo, linkedMapOf("state" to letterStateEnum("replaced")))
                prevLetterNo = repo()?.findLetter(prevLetterNo)?.prevNo
            }
        } else {
            aux["reason"] = reasonMap(me.id, action = "disagree", reason = c.reason.trim())
            recorder.recordDiplomacyLetterUpdate(
                c.letterNo,
                linkedMapOf(
                    "state" to letterStateEnum("cancelled"),
                    "aux" to jsonEncode(aux),
                ),
            )
        }

        val (srcNation, destNation) = nationsFor(letter)
            ?: return fail("diploRespondLetter", c.generalId, letterNo = c.letterNo, reason = "올바르지 않은 국가입니다.")
        val text = if (c.isAgree) {
            "외교 서신( #${c.letterNo})이 승인되었습니다."
        } else {
            buildString {
                append("외교 서신(#${c.letterNo})이 거부되었습니다.")
                val trimmed = c.reason.trim()
                if (trimmed.isNotEmpty()) append(" 이유 : ").append(trimmed)
            }
        }
        routeMessage(
            type = MessageType.DIPLOMACY,
            srcGeneralId = me.id,
            srcGeneralName = me.name,
            srcIcon = generalIcon(me),
            srcNation = destNation,
            destNation = srcNation,
            text = text,
        )
        routeMessage(
            type = MessageType.NATIONAL,
            srcGeneralId = me.id,
            srcGeneralName = me.name,
            srcIcon = generalIcon(me),
            srcNation = destNation,
            destNation = srcNation,
            text = text,
        )
        return DiploLetterResult(type = "diploRespondLetter", ok = true, generalId = c.generalId, letterNo = c.letterNo)
    }

    // ── 부수 효과 헬퍼 ───────────────────────────────────────────────────────────────────────────

    /**
     * diplomacy 메시지를 양측(발신국→수신국) mailbox 채널로 라우팅한다 — logic [Message]가
     * receiver-먼저-sender 2행으로 분해하고, recorder가 in-memory id를 선할당해 body 역참조를 fold한다
     * ([ProcessNationCommand.routeMessage]와 동일). dest는 generalId 0의 국가 타겟(`MessageTarget`).
     */
    private fun routeDiplomacyMessage(
        srcGeneralId: Int,
        srcGeneralName: String,
        srcIcon: String,
        srcNation: Nation,
        destNation: Nation,
        text: String,
    ) = routeMessage(MessageType.DIPLOMACY, srcGeneralId, srcGeneralName, srcIcon, srcNation, destNation, text)

    private fun routeMessage(
        type: MessageType,
        srcGeneralId: Int,
        srcGeneralName: String,
        srcIcon: String,
        srcNation: Nation,
        destNation: Nation,
        text: String,
    ) {
        val src = MessageTarget(srcGeneralId, srcGeneralName, srcNation.id, srcNation.name, srcNation.color, srcIcon)
        val dest = MessageTarget(0, "", destNation.id, destNation.name, destNation.color)
        val now = nowString()
        val unlimited = UNLIMITED
        val message = Message(type, src, dest, text, now, unlimited, linkedMapOf("deletable" to false))
        val drafts = message.send()
        var receiverId: Int? = null
        for (draft in drafts) {
            val option = LinkedHashMap(draft.option ?: emptyMap())
            if (draft.whichRow == MessageRowDraft.Row.RECEIVER) {
                receiverId = recorder.recordMessageInsert(
                    mailbox = draft.mailbox, type = draft.type.value, srcId = draft.srcId, destId = draft.destId,
                    time = message.date, validUntil = message.validUntil,
                    bodyJson = encodeMessageBody(draft, option, receiverIdToFold = null),
                )
            } else {
                recorder.recordMessageInsert(
                    mailbox = draft.mailbox, type = draft.type.value, srcId = draft.srcId, destId = draft.destId,
                    time = message.date, validUntil = message.validUntil,
                    bodyJson = encodeMessageBody(draft, option, receiverIdToFold = receiverId),
                )
            }
        }
    }

    /** Byte-faithful `{src,dest,text,option}` jsonb (PHP `sendRaw`) — [ProcessNationCommand]와 동일. */
    private fun encodeMessageBody(
        draft: MessageRowDraft,
        option: Map<String, Any?>,
        receiverIdToFold: Int?,
    ): String {
        val opt = LinkedHashMap<String, Any?>(option)
        if (receiverIdToFold != null) opt["receiverMessageID"] = receiverIdToFold
        val body = linkedMapOf<String, Any?>(
            "src" to draft.src.toArray(),
            "dest" to draft.dest.toArray(),
            "text" to draft.text,
            "option" to (if (draft.option == null) null else opt),
        )
        return jsonEncode(body)
    }

    /** 서신 행의 src/dest 국가를 world에서 조회(둘 다 있어야 한다). 없으면 null. */
    private fun nationsFor(letter: DiplomacyLetterReadRow): Pair<Nation, Nation>? {
        val src = world.getNationById(letter.srcNationId) ?: return null
        val dest = world.getNationById(letter.destNationId) ?: return null
        return src to dest
    }

    /** PHP `{who,action,reason}` reason 블록 — 삽입 순서 보존. */
    private fun reasonMap(who: Int, action: String, reason: String): Map<String, Any?> =
        linkedMapOf("who" to who, "action" to action, "reason" to reason)

    /**
     * PHP 소문자 state('proposed'/'replaced'/'cancelled'/'activated')를 opensamguk enum 대문자로 변환.
     * executor가 `CAST(... AS diplomacy_letter_state)`로 바인딩한다.
     */
    private fun letterStateEnum(phpState: String): String = phpState.uppercase()

    /**
     * 메시지/aux의 generalIcon — 게이트웨이가 인테이크 시 실어 보낸 `general.meta["icon"]`을 쓰고, 부재 시
     * 빈 문자열(config 측 default-icon 해소는 P7/request-side, daemon-golden 비대상 — MessageTarget 격리).
     */
    private fun generalIcon(me: TurnGeneral): String = me.meta["icon"] as? String ?: ""

    /** 데몬 현재 시각(world lastTurnTime) — PHP `TimeUtil::now()`/`new DateTime()` 대응(wall-clock 격리). */
    private fun nowString(): String = world.getState().lastTurnTime.toString()

    private fun repo(): DiplomacyLetterRepository? = diplomacyLetterRepository

    private fun fail(type: String, generalId: Int, letterNo: Int? = null, reason: String): DiploLetterResult =
        DiploLetterResult(type = type, ok = false, generalId = generalId, letterNo = letterNo, reason = reason)

    private companion object {
        /** PHP `new \DateTime('9999-12-31')` 무기한 유효 sentinel. */
        const val UNLIMITED = "9999-12-31"
    }
}
