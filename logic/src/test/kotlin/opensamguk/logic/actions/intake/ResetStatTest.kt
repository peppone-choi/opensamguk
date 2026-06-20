package opensamguk.logic.actions.intake

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ResetStatTest {

    @Test
    fun `explicit bonus spends born stat point and returns final stats and logs`() {
        val out = InheritResets.resetStat(
            userId = 100,
            leadership = 55,
            strength = 55,
            intel = 55,
            inheritBonusStat = listOf(1, 2, 0),
            previousPoint = 2000.0,
            isUnited = false,
            season = 7,
            lastStatReset = listOf(1),
            npcType = 0,
            hiddenSeed = "seed",
        )

        val applied = assertIs<ResetStatOutcome.Applied>(out)
        assertEquals(1000, applied.spent)
        assertEquals(1000.0, applied.remainingPrevious)
        assertEquals(56, applied.nextLeadership)
        assertEquals(57, applied.nextStrength)
        assertEquals(55, applied.nextIntel)
        assertEquals(listOf(1, 7), applied.nextLastStatReset)
        assertEquals(
            listOf(
                "통솔 55, 무력 55, 지력 55 스탯 재설정",
                "1000로 통솔 1, 무력 2, 지력 0 보너스 능력치 적용",
            ),
            applied.logs,
        )
    }

    @Test
    fun `absent bonus draws free random bonus from ResetStat seed`() {
        val out = InheritResets.resetStat(
            userId = 100,
            leadership = 55,
            strength = 55,
            intel = 55,
            inheritBonusStat = null,
            previousPoint = 0.0,
            isUnited = false,
            season = 0,
            lastStatReset = emptyList(),
            npcType = 0,
            hiddenSeed = "seed",
        )

        val applied = assertIs<ResetStatOutcome.Applied>(out)
        assertEquals(0, applied.spent)
        val bonusSum = applied.nextLeadership + applied.nextStrength + applied.nextIntel - 165
        assertTrue(bonusSum in 3..5)
        assertTrue(applied.logs[1].startsWith("통솔 "))
    }

    @Test
    fun `denies npc repeated season invalid bonus and insufficient points`() {
        assertEquals(
            "NPC는 능력치 초기화를 할 수 없습니다.",
            (InheritResets.resetStat(1, 55, 55, 55, null, 9999.0, false, 0, emptyList(), 2, "s")
                as ResetStatOutcome.Denied).reason,
        )
        assertEquals(
            "이번 시즌에 이미 능력치를 초기화하셨습니다.",
            (InheritResets.resetStat(1, 55, 55, 55, null, 9999.0, false, 3, listOf(3), 0, "s")
                as ResetStatOutcome.Denied).reason,
        )
        assertEquals(
            "보너스 능력치 합이 잘못 지정되었습니다. 다시 입력해주세요!",
            (InheritResets.resetStat(1, 55, 55, 55, listOf(1, 1, 0), 9999.0, false, 0, emptyList(), 0, "s")
                as ResetStatOutcome.Denied).reason,
        )
        assertEquals(
            "충분한 유산 포인트를 가지고 있지 않습니다.",
            (InheritResets.resetStat(1, 55, 55, 55, listOf(1, 1, 1), 999.0, false, 0, emptyList(), 0, "s")
                as ResetStatOutcome.Denied).reason,
        )
    }
}
