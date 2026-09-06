package opensamguk.logic.retainer

import opensamguk.logic.retainer.RetainerRules.BugokSettleInput
import opensamguk.logic.retainer.RetainerRules.RetainerSettleInput
import opensamguk.logic.retainer.RetainerRules.RetainerSettlement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** spec §8 — 정산 순수 함수 입력→출력 표, 게이트 순서 표, 이름 정규화. 상수 값은 단언하지 않는다(S9). */
class RetainerRulesTest {

    // ── 정산 표: 부곡 ──
    @Test
    fun `bugok settlement table`() {
        val pay = RetainerRules.payFor(300)
        val consumption = RetainerRules.consumptionFor(300)
        // 정상: 군량·급여 충분, 지휘 부장 없음 → 사기 유지, 피로 휴식
        val ok = RetainerRules.settleBugok(BugokSettleInput(300, consumption + 100, 50, 20, 40, pay + 1, null))
        assertEquals(100, ok.provisions); assertEquals(50, ok.morale); assertEquals(pay, ok.goldPaid)
        assertEquals(20 - RetainerRules.FATIGUE_REST, ok.fatigue); assertEquals(40, ok.training)
        // 군량 부족 → 사기 −5, 군량 0, 급여는 지급
        val shortProv = RetainerRules.settleBugok(BugokSettleInput(300, consumption - 1, 50, 0, 40, pay, null))
        assertEquals(0, shortProv.provisions); assertEquals(50 - RetainerRules.MORALE_LOSS_UNPAID, shortProv.morale)
        assertEquals(true, shortProv.shortProvisions); assertEquals(pay, shortProv.goldPaid); assertEquals(0, shortProv.fatigue)
        // 급여 부족 → 전액 아니면 0(부분 지급 없음), 사기 −5
        val shortPay = RetainerRules.settleBugok(BugokSettleInput(300, consumption, 50, 0, 40, pay - 1, null))
        assertEquals(0, shortPay.goldPaid); assertEquals(true, shortPay.shortPay); assertEquals(45, shortPay.morale)
        // 둘 다 부족 → −5 한 번
        val both = RetainerRules.settleBugok(BugokSettleInput(300, 0, 3, 0, 40, 0, null))
        assertEquals(0, both.morale)
        // 훈련 부장 지휘 → 훈련 +2(상한 100)·피로 +10(상한 100)
        val train = RetainerRules.settleBugok(BugokSettleInput(300, consumption, 50, 95, 99, pay, RetainerRules.TASK_TRAIN))
        assertEquals(100, train.fatigue); assertEquals(100, train.training)
        // provisionMonths
        assertEquals(3, RetainerRules.provisionMonths(1000, 300))
        assertEquals(0, RetainerRules.provisionMonths(0, 0))
    }

    // ── 정산 표: 가신 ──
    @Test
    fun `retainer settlement table`() {
        val g = RetainerRules.RETAINER_UPKEEP_GOLD
        val r = RetainerRules.RETAINER_UPKEEP_RICE
        assertIs<RetainerSettlement.Leave>(RetainerRules.settleRetainer(RetainerSettleInput(0, "train", RetainerRules.ORIGIN_RECRUITED, g, r)))
        val tasked = RetainerRules.settleRetainer(RetainerSettleInput(50, "train", RetainerRules.ORIGIN_RECRUITED, g, r)) as RetainerSettlement.Stay
        assertEquals(50 + RetainerRules.LOYALTY_TASKED, tasked.loyalty); assertEquals(g, tasked.goldPaid); assertEquals(r, tasked.ricePaid)
        val idle = RetainerRules.settleRetainer(RetainerSettleInput(50, RetainerRules.TASK_NONE, RetainerRules.ORIGIN_RECRUITED, g, r)) as RetainerSettlement.Stay
        assertEquals(50 + RetainerRules.LOYALTY_IDLE, idle.loyalty)
        val unpaid = RetainerRules.settleRetainer(RetainerSettleInput(50, "train", RetainerRules.ORIGIN_RECRUITED, g - 1, r)) as RetainerSettlement.Stay
        assertEquals(50 + RetainerRules.LOYALTY_TASKED + RetainerRules.LOYALTY_LOSS_UNPAID, unpaid.loyalty)
        assertEquals(0, unpaid.goldPaid); assertEquals(false, unpaid.upkeepPaid)
        val capped = RetainerRules.settleRetainer(RetainerSettleInput(100, "train", RetainerRules.ORIGIN_RECRUITED, g, r)) as RetainerSettlement.Stay
        assertEquals(100, capped.loyalty)
        // EXISTING 은 유지비 없음(다음 절편이지만 산식은 ADR-017 대로)
        val existing = RetainerRules.settleRetainer(RetainerSettleInput(10, RetainerRules.TASK_NONE, RetainerRules.ORIGIN_EXISTING, 0, 0)) as RetainerSettlement.Stay
        assertEquals(9, existing.loyalty); assertEquals(0, existing.goldPaid); assertEquals(true, existing.upkeepPaid)
    }

    // ── 게이트 순서 표(두 조건 동시 위반 → 먼저 나오는 문자열) ──
    @Test
    fun `gate order is pinned per command`() {
        // 서약: 상한 > 중복 > 자금
        assertEquals(RetainerRules.REASON_RETAINERS_FULL, RetainerRules.pledgeDeny(RetainerRules.MAX_RETAINERS, listOf("갑"), "갑", 0))
        assertEquals(RetainerRules.REASON_DUP_NAME, RetainerRules.pledgeDeny(0, listOf("갑"), "갑", 0))
        assertEquals(RetainerRules.REASON_NO_GOLD, RetainerRules.pledgeDeny(0, emptyList(), "갑", RetainerRules.PLEDGE_COST_GOLD - 1))
        assertNull(RetainerRules.pledgeDeny(0, emptyList(), "갑", RetainerRules.PLEDGE_COST_GOLD))
        // 편성 입력: troops < MIN 또는 rice < 0 → 입력
        assertEquals(RetainerRules.REASON_INPUT, RetainerRules.bugokFormInputDeny(RetainerRules.MIN_BUGOK_TROOPS - 1, 0))
        assertEquals(RetainerRules.REASON_INPUT, RetainerRules.bugokFormInputDeny(RetainerRules.MIN_BUGOK_TROOPS, -1))
        assertNull(RetainerRules.bugokFormInputDeny(RetainerRules.MIN_BUGOK_TROOPS, 0))
        // 편성 상태: 상한 > 병력 > 군량
        assertEquals(RetainerRules.REASON_BUGOK_FULL, RetainerRules.bugokFormDeny(RetainerRules.MAX_BUGOK, 0, 100, 0, 1))
        assertEquals(RetainerRules.REASON_NO_TROOPS, RetainerRules.bugokFormDeny(0, 99, 100, 0, 1))
        assertEquals(RetainerRules.REASON_NO_RICE, RetainerRules.bugokFormDeny(0, 100, 100, 0, 1))
        assertNull(RetainerRules.bugokFormDeny(0, 100, 100, 1, 1))
        // 해산: 내 부곡 > 병종
        assertEquals(RetainerRules.REASON_NO_BUGOK, RetainerRules.bugokDisbandDeny(false, 1, 2))
        assertEquals(RetainerRules.REASON_CREW_TYPE, RetainerRules.bugokDisbandDeny(true, 1, 2))
        assertNull(RetainerRules.bugokDisbandDeny(true, 2, 2))
        // 지휘관: 내 부곡 > 내 가신 > 부장
        assertEquals(RetainerRules.REASON_NO_BUGOK, RetainerRules.assignCommanderDeny(false, 1, false, null))
        assertEquals(RetainerRules.REASON_NO_RETAINER, RetainerRules.assignCommanderDeny(true, 1, false, null))
        assertEquals(RetainerRules.REASON_NOT_LIEUTENANT, RetainerRules.assignCommanderDeny(true, 1, true, RetainerRules.RELATION_STAFF))
        assertNull(RetainerRules.assignCommanderDeny(true, 1, true, RetainerRules.RELATION_LIEUTENANT))
        assertNull(RetainerRules.assignCommanderDeny(true, null, false, null)) // 해제
    }

    @Test
    fun `name normalization`() {
        assertEquals(RetainerRules.NameOutcome.Ok("홍길동"), RetainerRules.normalizeName(" 홍길동 "))
        assertEquals(RetainerRules.NameOutcome.Ok("한글"), RetainerRules.normalizeName("한글"))
        // NFC: 분해형 한글 → 조합형
        assertEquals(RetainerRules.NameOutcome.Ok("한글"), RetainerRules.normalizeName("한글"))
        assertEquals(RetainerRules.NameOutcome.Denied(RetainerRules.REASON_INPUT), RetainerRules.normalizeName("홍 길동"))
        assertEquals(RetainerRules.NameOutcome.Denied(RetainerRules.REASON_INPUT), RetainerRules.normalizeName("홍"))
        assertEquals(RetainerRules.NameOutcome.Denied(RetainerRules.REASON_INPUT), RetainerRules.normalizeName("가".repeat(13)))
        assertEquals(RetainerRules.NameOutcome.Denied(RetainerRules.REASON_INPUT), RetainerRules.normalizeName(null))
    }
}
