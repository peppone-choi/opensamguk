package opensamguk.logic.actions.intake

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 유산 포인트 구매 골든 — `BuyHiddenBuff.php` / `BuyRandomUnique.php` byte-parity.
 *
 * 로그 문자열 + 누적-차분 비용 + deny 사유/순서가 PHP grand truth와 정확히 일치하는지 검증한다.
 * 뽑지 않음(결정적) → RNG 골든 불필요. PHP launch 순서: validateArgs(type/level) → prevLevel 가드 →
 * isunited → 잔액. 로그 텍스트는 `TriggerInheritBuff::BUFF_KEY_TEXT`(verbatim).
 */
class InheritBuysTest {

    // ── BuyHiddenBuff ──────────────────────────────────────────────────────────────

    @Test
    fun `fresh L1 회피 — 200 포인트, log, aux`() {
        val out = InheritBuys.buyHiddenBuff(
            type = "warAvoidRatio", level = 1, currentBuffMap = emptyMap(),
            previousPoint = 200.0, isUnited = false,
        )
        val a = assertIs<InheritResetOutcome.Applied>(out)
        assertEquals(200, a.spent)
        assertEquals(0.0, a.remainingPrevious)
        assertEquals("200 포인트로 회피 확률 증가 1 단계 구입", a.log)
        assertEquals(mapOf("inheritBuff" to linkedMapOf("warAvoidRatio" to 1)), a.auxUpdates)
        assertEquals(emptyMap(), a.varUpdates)
    }

    @Test
    fun `upgrade L1 to L2 — 누적차분 400, 추가구입 텍스트`() {
        val out = InheritBuys.buyHiddenBuff(
            type = "warAvoidRatio", level = 2, currentBuffMap = mapOf("warAvoidRatio" to 1),
            previousPoint = 400.0, isUnited = false,
        )
        val a = assertIs<InheritResetOutcome.Applied>(out)
        assertEquals(400, a.spent) // inheritBuffPoints[2]-[1] = 600-200
        assertEquals("400 포인트로 회피 확률 증가 2 단계 추가구입", a.log)
        assertEquals(mapOf("inheritBuff" to linkedMapOf("warAvoidRatio" to 2)), a.auxUpdates)
    }

    @Test
    fun `domestic 성공률 — BUFF_KEY_TEXT 정확`() {
        val out = InheritBuys.buyHiddenBuff(
            type = "domesticSuccessProb", level = 1, currentBuffMap = emptyMap(),
            previousPoint = 999.0, isUnited = false,
        )
        assertEquals("200 포인트로 내정 성공률 증가 1 단계 구입", assertIs<InheritResetOutcome.Applied>(out).log)
    }

    @Test
    fun `oppose 전투계략 L3 fresh — 1200 포인트`() {
        val out = InheritBuys.buyHiddenBuff(
            type = "warMagicTrialProbOppose", level = 3, currentBuffMap = emptyMap(),
            previousPoint = 1200.0, isUnited = false,
        )
        val a = assertIs<InheritResetOutcome.Applied>(out)
        assertEquals(1200, a.spent) // inheritBuffPoints[3]-[0] = 1200
        assertEquals("1200 포인트로 상대 전투계략 시도 확률 감소 3 단계 구입", a.log)
    }

    @Test
    fun `기존 버프맵 보존 — 다른 키 유지`() {
        val out = InheritBuys.buyHiddenBuff(
            type = "warCriticalRatio", level = 1, currentBuffMap = linkedMapOf("warAvoidRatio" to 2),
            previousPoint = 200.0, isUnited = false,
        )
        val a = assertIs<InheritResetOutcome.Applied>(out)
        assertEquals(linkedMapOf("warAvoidRatio" to 2, "warCriticalRatio" to 1), a.auxUpdates["inheritBuff"])
    }

    @Test
    fun `deny — 이미 구입 (prevLevel == level)`() {
        val out = InheritBuys.buyHiddenBuff("warAvoidRatio", 2, mapOf("warAvoidRatio" to 2), 9999.0, false)
        assertEquals("이미 구입했습니다.", assertIs<InheritResetOutcome.Denied>(out).reason)
    }

    @Test
    fun `deny — 더 높은 등급 보유 (prevLevel 초과 level)`() {
        val out = InheritBuys.buyHiddenBuff("warAvoidRatio", 1, mapOf("warAvoidRatio" to 3), 9999.0, false)
        assertEquals("이미 더 높은 등급을 구입했습니다.", assertIs<InheritResetOutcome.Denied>(out).reason)
    }

    @Test
    fun `deny — 천하통일 (prevLevel 가드 통과 후, 잔액 전)`() {
        // isUnited는 prevLevel 가드 뒤·잔액 검사 앞. 잔액 부족이어도 isUnited가 먼저.
        val out = InheritBuys.buyHiddenBuff("warAvoidRatio", 1, emptyMap(), 0.0, true)
        assertEquals("이미 천하가 통일되었습니다.", assertIs<InheritResetOutcome.Denied>(out).reason)
    }

    @Test
    fun `deny — 포인트 부족`() {
        val out = InheritBuys.buyHiddenBuff("warAvoidRatio", 1, emptyMap(), 199.0, false)
        assertEquals("충분한 유산 포인트를 가지고 있지 않습니다.", assertIs<InheritResetOutcome.Denied>(out).reason)
    }

    @Test
    fun `deny — 잘못된 type (FE-도달불가 validateArgs)`() {
        val out = InheritBuys.buyHiddenBuff("notAKey", 1, emptyMap(), 9999.0, false)
        assertEquals("'type' 항목이 올바르지 않습니다.", assertIs<InheritResetOutcome.Denied>(out).reason)
    }

    @Test
    fun `deny — 잘못된 level (범위 밖)`() {
        assertEquals("'level' 항목이 올바르지 않습니다.",
            assertIs<InheritResetOutcome.Denied>(InheritBuys.buyHiddenBuff("warAvoidRatio", 0, emptyMap(), 9999.0, false)).reason)
        assertEquals("'level' 항목이 올바르지 않습니다.",
            assertIs<InheritResetOutcome.Denied>(InheritBuys.buyHiddenBuff("warAvoidRatio", 6, emptyMap(), 9999.0, false)).reason)
    }

    // ── BuyRandomUnique ────────────────────────────────────────────────────────────

    @Test
    fun `randomUnique — 3000 포인트, log, 마커`() {
        val out = InheritBuys.buyRandomUnique(alreadyOrdered = false, previousPoint = 3000.0, isUnited = false, nowMarker = "T")
        val a = assertIs<InheritResetOutcome.Applied>(out)
        assertEquals(3000, a.spent)
        assertEquals(0.0, a.remainingPrevious)
        assertEquals("3000 포인트로 랜덤 유니크 구입", a.log)
        assertEquals(mapOf("inheritRandomUnique" to "T"), a.auxUpdates)
    }

    @Test
    fun `randomUnique deny — 이미 주문 (가드 최우선)`() {
        // alreadyOrdered는 isUnited·잔액보다 먼저.
        val out = InheritBuys.buyRandomUnique(alreadyOrdered = true, previousPoint = 0.0, isUnited = true, nowMarker = "T")
        assertEquals("이미 구입 명령을 내렸습니다. 다음 턴까지 기다려주세요.", assertIs<InheritResetOutcome.Denied>(out).reason)
    }

    @Test
    fun `randomUnique deny — 천하통일 (잔액 전)`() {
        val out = InheritBuys.buyRandomUnique(alreadyOrdered = false, previousPoint = 0.0, isUnited = true, nowMarker = "T")
        assertEquals("이미 천하가 통일되었습니다.", assertIs<InheritResetOutcome.Denied>(out).reason)
    }

    @Test
    fun `randomUnique deny — 포인트 부족`() {
        val out = InheritBuys.buyRandomUnique(alreadyOrdered = false, previousPoint = 2999.0, isUnited = false, nowMarker = "T")
        assertEquals("충분한 유산 포인트를 가지고 있지 않습니다.", assertIs<InheritResetOutcome.Denied>(out).reason)
    }
}
