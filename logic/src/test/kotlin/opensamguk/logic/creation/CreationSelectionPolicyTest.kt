package opensamguk.logic.creation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CreationSelectionPolicyTest {
    @Test fun `배포 클래스패스 선택 원장이 능력치와 모드를 고정한다`() {
        val policy = assertNotNull(CreationSelectionPolicy.load())
        assertEquals(CreationStatRule(20, 85, 300), policy.statRule)
        assertEquals(listOf(CreationKind.CUSTOM, CreationKind.HISTORICAL), policy.modes.map { it.kind })
        assertTrue(policy.modes.all { it.allowed })
        assertEquals(6, policy.ideologies.size)
        assertEquals(6, policy.traits.size)
    }
}
