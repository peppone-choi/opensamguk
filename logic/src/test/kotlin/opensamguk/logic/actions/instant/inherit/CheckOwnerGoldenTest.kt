package opensamguk.logic.actions.instant.inherit

import opensamguk.logic.messaging.MessageTarget
import opensamguk.logic.messaging.MessageType
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * CheckOwner 골든 — `API/InheritAction/CheckOwner.php` byte-parity.
 *
 * 0-draw(RNG 미사용) 결정적 inherit-action. 검증 게이트 deny 사유/순서 + 성공 시 1000pt 차감 +
 * `inheritPoint` 로그 + private 메시지 2건(요청자 통지 → 대상 통지)의 텍스트/타입/src/validUntil이
 * PHP grand truth와 정확히 일치하는지 검증한다. 뽑지 않으므로(0-draw) RNG 골든 캡처 불필요.
 */
class CheckOwnerGoldenTest {

    // 요청자/대상 표시 타깃(엔진 핸들러가 행/정적정보에서 해석해 주입하는 값의 대표).
    private val acting = MessageTarget(
        generalId = 10, generalName = "조운", nationId = 5,
        nationName = "촉", color = "#00FF00", icon = "img/zhaoyun.png",
    )
    private val destDisplay = MessageTarget(
        generalId = 20, generalName = "장합", nationId = 7,
        nationName = "위", color = "#0000FF", icon = "img/zhanghe.png",
    )
    private val now = LocalDateTime.of(2026, 6, 8, 1, 0, 0)

    private fun resolveOk(previousPoint: Double = 1000.0) = CheckOwner.resolve(
        actingGeneralId = 10,
        destGeneralId = 20,
        actingOwnerMatches = true,
        destExists = true,
        destOwner = 42,
        isUnited = false,
        previousPoint = previousPoint,
        destGeneralName = "장합",
        destGeneralOwnerName = "peppone",
        acting = acting,
        destDisplay = destDisplay,
        now = now,
    )

    // ── 성공 ────────────────────────────────────────────────────────────────────────

    @Test
    fun `성공 — 1000pt 차감 + 로그 + 메시지 2건`() {
        val a = assertIs<CheckOwnerOutcome.Applied>(resolveOk(previousPoint = 1000.0))
        assertEquals(1000, a.spent)
        assertEquals(0.0, a.remainingPrevious)
        // CheckOwner.php:92 — UserLogger push(tag inheritPoint).
        assertEquals("1000 포인트로 장수 소유자 확인", a.log)

        // 정확히 2건, PHP 발송 순서(요청자 → 대상).
        assertEquals(2, a.messages.size)

        val toActing = a.messages[0]
        assertEquals(MessageType.PRIVATE, toActing.msgType)
        assertEquals(CheckOwner.SYSTEM_SRC, toActing.src)
        assertEquals(acting, toActing.dest)
        // CheckOwner.php:118 — "{대상명}의 소유자는 {소유자명} 입니다."
        assertEquals("장합의 소유자는 peppone 입니다.", toActing.text)
        assertEquals(now, toActing.time)
        assertEquals(CheckOwner.FOREVER, toActing.validUntil)
        assertEquals(null, toActing.option)

        val toDest = a.messages[1]
        assertEquals(MessageType.PRIVATE, toDest.msgType)
        assertEquals(CheckOwner.SYSTEM_SRC, toDest.src)
        assertEquals(destDisplay, toDest.dest)
        // CheckOwner.php:144 — 대상 통지(고정 문자열).
        assertEquals("소유자명이 누군가에 의해 확인되었습니다.", toDest.text)
        assertEquals(now, toDest.time)
        assertEquals(CheckOwner.FOREVER, toDest.validUntil)
        assertEquals(null, toDest.option)
    }

    @Test
    fun `성공 — 잔액 초과 보유 시 차분만 차감`() {
        val a = assertIs<CheckOwnerOutcome.Applied>(resolveOk(previousPoint = 2500.0))
        assertEquals(1000, a.spent)
        assertEquals(1500.0, a.remainingPrevious)
    }

    @Test
    fun `시스템 src + 영구 validUntil 상수`() {
        assertEquals(MessageTarget(0, "", 0, "System", "#000000", ""), CheckOwner.SYSTEM_SRC)
        assertEquals(LocalDateTime.of(9999, 12, 31, 0, 0, 0), CheckOwner.FOREVER)
    }

    // ── deny (PHP launch 순서대로) ────────────────────────────────────────────────────

    @Test
    fun `deny — 자기 자신 (최우선)`() {
        val out = CheckOwner.resolve(
            actingGeneralId = 10, destGeneralId = 10,
            actingOwnerMatches = false, destExists = false, destOwner = 0,
            isUnited = true, previousPoint = 0.0,
            destGeneralName = "x", destGeneralOwnerName = "x",
            acting = acting, destDisplay = destDisplay, now = now,
        )
        assertEquals("자신의 정보는 확인할 수 없습니다.", assertIs<CheckOwnerOutcome.Denied>(out).reason)
    }

    @Test
    fun `deny — 세션 정합 실패 (자기자신 다음)`() {
        val out = CheckOwner.resolve(
            actingGeneralId = 10, destGeneralId = 20,
            actingOwnerMatches = false, destExists = false, destOwner = 0,
            isUnited = true, previousPoint = 0.0,
            destGeneralName = "x", destGeneralOwnerName = "x",
            acting = acting, destDisplay = destDisplay, now = now,
        )
        assertEquals("로그인 상태가 이상합니다. 다시 로그인해 주세요.", assertIs<CheckOwnerOutcome.Denied>(out).reason)
    }

    @Test
    fun `deny — 대상 장수 부재`() {
        val out = CheckOwner.resolve(
            actingGeneralId = 10, destGeneralId = 20,
            actingOwnerMatches = true, destExists = false, destOwner = 0,
            isUnited = true, previousPoint = 0.0,
            destGeneralName = "x", destGeneralOwnerName = "x",
            acting = acting, destDisplay = destDisplay, now = now,
        )
        assertEquals("대상 장수가 존재하지 않습니다.", assertIs<CheckOwnerOutcome.Denied>(out).reason)
    }

    @Test
    fun `deny — 대상 NPC (owner 0)`() {
        val out = CheckOwner.resolve(
            actingGeneralId = 10, destGeneralId = 20,
            actingOwnerMatches = true, destExists = true, destOwner = 0,
            isUnited = true, previousPoint = 0.0,
            destGeneralName = "x", destGeneralOwnerName = "x",
            acting = acting, destDisplay = destDisplay, now = now,
        )
        assertEquals("대상 장수는 NPC입니다.", assertIs<CheckOwnerOutcome.Denied>(out).reason)
    }

    @Test
    fun `deny — 천하통일 (NPC 통과 후, 잔액 전)`() {
        // isUnited는 NPC 가드 뒤·잔액 검사 앞. 잔액 0이어도 isUnited가 먼저.
        val out = CheckOwner.resolve(
            actingGeneralId = 10, destGeneralId = 20,
            actingOwnerMatches = true, destExists = true, destOwner = 42,
            isUnited = true, previousPoint = 0.0,
            destGeneralName = "x", destGeneralOwnerName = "x",
            acting = acting, destDisplay = destDisplay, now = now,
        )
        assertEquals("이미 천하가 통일되었습니다.", assertIs<CheckOwnerOutcome.Denied>(out).reason)
    }

    @Test
    fun `deny — 포인트 부족 (999)`() {
        val out = CheckOwner.resolve(
            actingGeneralId = 10, destGeneralId = 20,
            actingOwnerMatches = true, destExists = true, destOwner = 42,
            isUnited = false, previousPoint = 999.0,
            destGeneralName = "x", destGeneralOwnerName = "x",
            acting = acting, destDisplay = destDisplay, now = now,
        )
        assertEquals("충분한 유산 포인트를 가지고 있지 않습니다.", assertIs<CheckOwnerOutcome.Denied>(out).reason)
    }
}
