package opensamguk.logic.actions.instant.inherit

import opensamguk.common.constants.GameConst
import opensamguk.logic.messaging.MessageDraft
import opensamguk.logic.messaging.MessageTarget
import opensamguk.logic.messaging.MessageType
import java.time.LocalDateTime

/**
 * 유산 액션 `CheckOwner` — 유산 포인트 1000을 써서 상대 장수의 본래 유저(소유자)명을 확인한다.
 *
 * PHP 정본: `legacy/devsam-core/hwe/sammo/API/InheritAction/CheckOwner.php` (`launch()` verbatim).
 *
 * inherit-action(즉시 액션)이라 [opensamguk.logic.actions.CommandRegistry] 예약 커맨드가 아니라
 * Model B(typed daemon-command intake)로 흐른다 — 자세한 dispatch 계약은 [InheritActionRegistry].
 * [InheritResets]/[opensamguk.logic.actions.intake.InheritBuys]와 동일하게, 이 리졸버는 Spring/DB/World
 * 없이 PHP `launch()`가 읽는 환경 입력(상대 장수 행·소유자명·소유자/국가 표시정보·`previous` 잔액·isunited)을
 * 받아 엔진 핸들러가 적용할 [CheckOwnerOutcome]만 산출한다.
 *
 * ## RNG (parity)
 * **draws NOTHING** — 0-draw. 전부 검증/판정 + 결정적 메시지 2건 발송이다(골든 N).
 *
 * ## 부수효과 (CheckOwner.php:54-152 실행 순서, byte-exact)
 *  1. (검증 게이트 — [resolve]의 deny 순서 참조.)
 *  2. UserLogger push `"{reqPoint} 포인트로 장수 소유자 확인"` tag `inheritPoint`
 *     ([CheckOwnerOutcome.Applied.log], 유산 reset/buy와 동일 채널).
 *  3. 요청자에게 private 메시지: `"{대상장수명}의 소유자는 {소유자명} 입니다."`
 *  4. `previous` 잔액 -= reqPoint, `inherit_point_spent_dynamic` += reqPoint.
 *  5. 대상 장수에게 private 메시지: `"소유자명이 누군가에 의해 확인되었습니다."`
 *
 * 두 메시지 모두 `MessageType::private`, src = 시스템 타깃 `(0,'',0,'System','#000000')`, time = now,
 * validUntil = `9999-12-31` (영구), option 없음(빈 배열). PHP 메시지 발송 순서(요청자 → [잔액/랭크] →
 * 대상)를 그대로 보존한다.
 */
object CheckOwner {

    /** PHP `new \DateTime('9999-12-31')` — 영구 메시지 만료 시각. */
    val FOREVER: LocalDateTime = LocalDateTime.of(9999, 12, 31, 0, 0, 0)

    /** PHP `MessageTarget(0, '', 0, 'System', '#000000')` — 시스템 발신 타깃. */
    /** PHP 6번째 인자 `$icon=''` 생략 — 빈 아이콘은 다운스트림에서 공유 default.jpg 경로로 해석된다(생성자 폴백 패러티). */
    val SYSTEM_SRC: MessageTarget = MessageTarget(
        generalId = 0,
        generalName = "",
        nationId = 0,
        nationName = "System",
        color = "#000000",
        icon = "",
    )

    /**
     * CheckOwner.php `launch()`. 게이트 통과 시 reqPoint(=[GameConst.inheritCheckOwnerPoint], 1000)를
     * `previous`에서 차감하고 private 메시지 2건을 발송한다. 뽑지 않음(0-draw).
     *
     * 게이트 입력(엔진 핸들러가 상대 장수 행 + 세션 + KV에서 해석해 주입):
     * @param actingGeneralId   세션 장수 ID (`$generalID`)
     * @param destGeneralId     확인 대상 장수 ID (`args.destGeneralID`)
     * @param actingOwnerMatches `userID == general.owner` (세션 정합; false ⇒ 재로그인 deny)
     * @param destExists        대상 장수 행 존재 여부 (`!destRawGeneral` ⇒ deny)
     * @param destOwner         대상 장수 `owner` (0/null ⇒ NPC deny). 멤버 번호.
     * @param isUnited          game_env `isunited` (천하통일 ⇒ deny)
     * @param previousPoint     소유자의 `inheritance_{userID}.previous[0]` 잔액
     *
     * 메시지 표시 입력(PHP가 행/정적정보에서 읽어 [MessageTarget]을 구성하는 값):
     * @param destGeneralName     대상 장수명 (`destRawGeneral.name`)
     * @param destGeneralOwnerName 대상 장수의 소유자(유저)명. PHP 폴백: `owner_name` ?? member.name ?? '알수없음'
     *                            (엔진 핸들러가 폴백 적용 후 주입).
     * @param acting             요청자 본인 표시 타깃(국가명/색/아이콘 포함) — 요청자 수신 메시지의 dest.
     * @param destDisplay        대상 장수 표시 타깃(국가명/색/아이콘 포함) — 알림 메시지의 dest.
     * @param now                메시지 `time` (PHP `new \DateTime()`; 결정성 위해 주입).
     */
    fun resolve(
        actingGeneralId: Int,
        destGeneralId: Int,
        actingOwnerMatches: Boolean,
        destExists: Boolean,
        destOwner: Int,
        isUnited: Boolean,
        previousPoint: Double,
        destGeneralName: String,
        destGeneralOwnerName: String,
        acting: MessageTarget,
        destDisplay: MessageTarget,
        now: LocalDateTime,
    ): CheckOwnerOutcome {
        // CheckOwner.php:58 — 자기 자신 확인 금지.
        if (actingGeneralId == destGeneralId) return CheckOwnerOutcome.Denied("자신의 정보는 확인할 수 없습니다.")
        // CheckOwner.php:63 — 세션 정합(userID == general.owner).
        if (!actingOwnerMatches) return CheckOwnerOutcome.Denied("로그인 상태가 이상합니다. 다시 로그인해 주세요.")
        // CheckOwner.php:70 — 대상 장수 행 존재.
        if (!destExists) return CheckOwnerOutcome.Denied("대상 장수가 존재하지 않습니다.")
        // CheckOwner.php:74 — 대상이 NPC(소유자 없음)면 확인 불가.
        if (destOwner == 0) return CheckOwnerOutcome.Denied("대상 장수는 NPC입니다.")
        // CheckOwner.php:79 — 천하통일 시 불가.
        if (isUnited) return CheckOwnerOutcome.Denied("이미 천하가 통일되었습니다.")

        val reqPoint = GameConst.inheritCheckOwnerPoint
        // CheckOwner.php:87 — 잔액 가드.
        if (previousPoint < reqPoint) return CheckOwnerOutcome.Denied("충분한 유산 포인트를 가지고 있지 않습니다.")

        // CheckOwner.php:114-123 — 요청자 수신 private 메시지(소유자명 통지).
        val toActing = MessageDraft(
            msgType = MessageType.PRIVATE,
            src = SYSTEM_SRC,
            dest = acting,
            text = "${destGeneralName}의 소유자는 $destGeneralOwnerName 입니다.",
            time = now,
            validUntil = FOREVER,
            option = null,
        )

        // CheckOwner.php:140-149 — 대상 장수 수신 private 메시지(확인 사실 통지).
        val toDest = MessageDraft(
            msgType = MessageType.PRIVATE,
            src = SYSTEM_SRC,
            dest = destDisplay,
            text = "소유자명이 누군가에 의해 확인되었습니다.",
            time = now,
            validUntil = FOREVER,
            option = null,
        )

        return CheckOwnerOutcome.Applied(
            spent = reqPoint,
            remainingPrevious = previousPoint - reqPoint,
            // CheckOwner.php:92 — UserLogger push(tag inheritPoint).
            log = "$reqPoint 포인트로 장수 소유자 확인",
            // 발송 순서 보존: 요청자 → [잔액/랭크 차감] → 대상.
            messages = listOf(toActing, toDest),
        )
    }
}

/** CheckOwner가 산출하는 효과(엔진 핸들러가 적용). */
sealed interface CheckOwnerOutcome {
    /** 게이트/검증 거부 — PHP-충실 사유 문자열(API `launch` 반환값). */
    data class Denied(val reason: String) : CheckOwnerOutcome

    /**
     * 성공: [spent](=1000) 포인트 차감(`previous` 새값 = [remainingPrevious]),
     * `inherit_point_spent_dynamic` += [spent], [log] 한 줄을 유산 로그(tag `inheritPoint`)에 push,
     * [messages] private 2건([0]=요청자 통지, [1]=대상 통지)을 PHP 순서대로 발송한다.
     */
    data class Applied(
        val spent: Int,
        val remainingPrevious: Double,
        val log: String,
        val messages: List<MessageDraft>,
    ) : CheckOwnerOutcome
}
