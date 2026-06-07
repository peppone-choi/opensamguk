package opensamguk.logic.actions.instant

import opensamguk.logic.actions.instant.inherit.InheritActionRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Area4 FOUNDATION SMOKE — [InstantActionRegistry] + [InheritActionRegistry]가 4개 instant/inherit
 * 명령 에이전트(DieOnPrestart/DropItem/InstantRetreat + CheckOwner; ResetStat 후속)가 소비할 **공개
 * API**(분류기 + 인테이크 배선 계약)를 정확히 노출하는지 증명한다. (명령 본체 launch()/resolve()는
 * Impl 페이즈 소유 — 여기선 분류기 seam만.) DiplomacySeamTest의 instant-nation registry 스모크와 동형.
 */
class InstantActionRegistryTest {

    @Test
    fun `instant-action code set is exactly the three General-instant handlers`() {
        assertEquals(
            linkedSetOf("DieOnPrestart", "DropItem", "InstantRetreat"),
            InstantActionRegistry.INSTANT_ACTION_CODES,
        )
        assertTrue(InstantActionRegistry.isInstantAction("DropItem"))
        assertFalse(InstantActionRegistry.isInstantAction("ResetStat")) // inherit-action, 다른 레지스트리.
        assertFalse(InstantActionRegistry.isInstantAction("che_불가침수락")) // 예약 외교 수락(Model A).
    }

    @Test
    fun `instant-action intake wiring covers every code (variant already in common)`() {
        // 각 코드가 인테이크 배선 계약(어느 typed TurnDaemonCommand variant로 매핑되는가)을 갖는다.
        assertEquals(InstantActionRegistry.INSTANT_ACTION_CODES, InstantActionRegistry.INTAKE_WIRING.keys)
        assertTrue(InstantActionRegistry.INTAKE_WIRING.getValue("DieOnPrestart").contains("TurnDaemonCommand.DieOnPrestart"))
        assertTrue(InstantActionRegistry.INTAKE_WIRING.getValue("DropItem").contains("itemType"))
    }

    @Test
    fun `inherit-action code set is exactly the two un-ported handlers`() {
        assertEquals(
            linkedSetOf("ResetStat", "CheckOwner"),
            InheritActionRegistry.INHERIT_ACTION_CODES,
        )
        assertTrue(InheritActionRegistry.isInheritAction("ResetStat"))
        assertFalse(InheritActionRegistry.isInheritAction("DieOnPrestart")) // General-instant, 다른 레지스트리.
    }

    @Test
    fun `inherit-action RNG classification — only ResetStat is RNG-bearing`() {
        // ResetStat: nextRangeInt(3,5) → choiceUsingWeight 루프(골든 Y). CheckOwner: 0-draw.
        assertTrue(InheritActionRegistry.isRngBearing("ResetStat"))
        assertFalse(InheritActionRegistry.isRngBearing("CheckOwner"))
    }

    @Test
    fun `inherit-action intake wiring flags both variants as NEW (not yet in common)`() {
        assertEquals(InheritActionRegistry.INHERIT_ACTION_CODES, InheritActionRegistry.INTAKE_WIRING.keys)
        // General-instant 3종과 달리 ResetStat/CheckOwner variant는 common에 신설 필요.
        assertTrue(InheritActionRegistry.INTAKE_WIRING.getValue("ResetStat").contains("NEW variant"))
        assertTrue(InheritActionRegistry.INTAKE_WIRING.getValue("CheckOwner").contains("NEW variant"))
    }
}
