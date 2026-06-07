package opensamguk.logic.actions.instant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GOLDEN — DropItem ([DropItem]) instant-action, draw COUNT = 0.
 *
 * PHP `legacy/devsam-core/hwe/sammo/API/General/DropItem.php` `launch()`는 RNG를 단 한 번도 끌지 않는다
 * (draw COUNT = 0). docker 골든 불필요 — 본 테스트가 골든을 대신한다:
 *
 *  - **0-draw (구조적)**: [DropItem.drop]은 `RandUtil`/`LiteHashDrbg`를 **인자로 받지도, 참조하지도
 *    않는다**(InheritResets 패턴의 순수 입력→Outcome). 시그니처상 draw가 불가능하므로 0-draw가 강제된다.
 *  - **effect**: deny(슬롯 비어있음) / 슬롯 clear(`itemType`을 'None'으로).
 *  - **log byte-exact**: 장수 action 로그 본문 + (비구매 아이템) global action/history 로그 본문 2건.
 *    로그 프리픽스(`<C>●</>{month}월:` 등)는 엔진 핸들러 경계가 붙이므로 본체는 BODY만 산출 — 본 테스트는
 *    BODY를 byte-exact로 검증한다.
 *
 * josa(JosaUtil): '청룡언월도'+'을'→'를'(받침 없음), '방천화극'+'을'→'을'(받침 ㄱ),
 * '여포'+'이'→'가'(받침 없음).
 */
class DropItemGoldenTest {

    // ── deny: 슬롯이 비어 있음(None 클래스) ───────────────────────────────────────────────────────
    @Test
    fun `dropping an empty slot is denied with the PHP-exact reason`() {
        val outcome = DropItem.drop(
            itemType = "weapon",
            itemRawClassName = "None",     // $item->getRawClassName() === 'None'
            itemName = "-",
            itemRawName = "-",
            itemBuyable = true,
            generalName = "여포",
            nationName = "동탁군",
        )
        assertTrue(outcome is DropItemOutcome.Denied)
        assertEquals("아이템을 가지고 있지 않습니다.", (outcome as DropItemOutcome.Denied).reason)
    }

    // ── 구매가능(일반 +stat) 아이템: 슬롯 clear + action 로그만, broadcast 없음 ──────────────────────
    @Test
    fun `dropping a buyable stat item clears the slot and logs only the actor action line`() {
        val outcome = DropItem.drop(
            itemType = "weapon",
            itemRawClassName = "che_무기_01_청룡언월도",  // None 아님 → 실제 아이템
            itemName = "청룡언월도(+5)",                  // $item->getName() — 표시명(장식 포함)
            itemRawName = "청룡언월도",                   // $item->getRawName() — josa 선택용 원명
            itemBuyable = true,                          // 구매가능 → 망실 broadcast 없음
            generalName = "여포",
            nationName = "동탁군",
        )
        assertTrue(outcome is DropItemOutcome.Applied)
        val applied = outcome as DropItemOutcome.Applied

        // 슬롯 clear: itemType 그대로(핸들러가 해당 슬롯을 'None'으로 set).
        assertEquals("weapon", applied.clearedSlot)

        // DropItem.php:59 — '<C>{getName()}</>{josa-을} 버렸습니다.' josa는 원명 '청룡언월도'(받침X)→'를'.
        assertEquals("<C>청룡언월도(+5)</>를 버렸습니다.", applied.actionLog)

        // 구매가능 → broadcast 2건 없음.
        assertNull(applied.globalActionLog)
        assertNull(applied.globalHistoryLog)
    }

    // ── 비구매(진귀) 아이템: 슬롯 clear + action 로그 + 망실 broadcast 2건 ──────────────────────────
    @Test
    fun `dropping a non-buyable rare item also broadcasts the loss (global action + history)`() {
        val outcome = DropItem.drop(
            itemType = "weapon",
            itemRawClassName = "che_무기_보물_방천화극",
            itemName = "방천화극(보물)",                   // 표시명
            itemRawName = "방천화극",                      // josa 선택용 원명(받침 ㄱ)→'을'
            itemBuyable = false,                          // 비구매 → 망실 broadcast
            generalName = "여포",                          // '여포'+'이'→'가'(받침 없음)
            nationName = "동탁군",
        )
        assertTrue(outcome is DropItemOutcome.Applied)
        val applied = outcome as DropItemOutcome.Applied

        assertEquals("weapon", applied.clearedSlot)

        // DropItem.php:59 — action 로그('방천화극'+'을'→받침 ㄱ→'을').
        assertEquals("<C>방천화극(보물)</>을 버렸습니다.", applied.actionLog)

        // DropItem.php:64 — global action.
        assertEquals(
            "<Y>여포</>가 <C>방천화극(보물)</>을 잃었습니다!",
            applied.globalActionLog,
        )
        // DropItem.php:65 — global history(망실).
        assertEquals(
            "<R><b>【망실】</b></><D><b>동탁군</b></>의 <Y>여포</>가 <C>방천화극(보물)</>을 잃었습니다!",
            applied.globalHistoryLog,
        )
    }

    // ── 다른 슬롯 키도 그대로 전달(horse/book/item) ───────────────────────────────────────────────
    @Test
    fun `cleared slot echoes the requested itemType verbatim`() {
        for (slot in listOf("horse", "weapon", "book", "item")) {
            val outcome = DropItem.drop(
                itemType = slot,
                itemRawClassName = "che_${slot}_01_x",
                itemName = "x(+1)",
                itemRawName = "x",
                itemBuyable = true,
                generalName = "여포",
                nationName = "동탁군",
            )
            assertEquals(slot, (outcome as DropItemOutcome.Applied).clearedSlot)
        }
    }
}
