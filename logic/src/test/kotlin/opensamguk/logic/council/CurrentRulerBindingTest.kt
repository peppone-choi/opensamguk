package opensamguk.logic.council

import opensamguk.logic.input.PoliticalInput
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CurrentRulerBindingTest {
    @Test
    fun `명시 정치 영수증을 보존하고 직함이나 주공 표지를 군주 신원으로 합성하지 않는다`() {
        assertNull(CurrentRulerBinding.read(mapOf("lord" to true, "officerLevel" to 12)))
        val original = mapOf<String, Any?>("other" to "보존")
        val written = CurrentRulerBinding.with(original, 101, "receipt-1", PoliticalInput.RISE)
        assertEquals("보존", written["other"])
        assertEquals(CurrentRulerBinding(101, "receipt-1", PoliticalInput.RISE), CurrentRulerBinding.read(written))
        assertFalse(original.containsKey(CurrentRulerBinding.META_KEY))
    }

    @Test
    fun `세력 이탈 사망 주공 상실과 다른 사람이면 저장 신원이 있어도 일치하지 않는다`() {
        val binding = CurrentRulerBinding(101, "receipt-1", PoliticalInput.RISE)
        assertTrue(binding.agreesWith(101, 3, 3, 0, mapOf("lord" to true)))
        assertFalse(binding.agreesWith(101, 0, 3, 0, mapOf("lord" to true)))
        assertFalse(binding.agreesWith(101, 4, 3, 0, mapOf("lord" to true)))
        assertFalse(binding.agreesWith(101, 3, 3, 5, mapOf("lord" to true)))
        assertFalse(binding.agreesWith(101, 3, 3, 0, emptyMap()))
        assertFalse(binding.agreesWith(102, 3, 3, 0, mapOf("lord" to true)))
    }

    @Test
    fun `손상된 타입 부재 영수증 낯선 입력과 추가 필드를 허용으로 바꾸지 않는다`() {
        val good = mapOf<String, Any?>("generalId" to 101, "revision" to "receipt-1", "sourceInputId" to PoliticalInput.RISE)
        listOf(null, "101", good + ("generalId" to 101.5), good + ("revision" to ""),
            good + ("sourceInputId" to "legacy-chief"), good + ("permission" to 2)).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                CurrentRulerBinding.read(mapOf(CurrentRulerBinding.META_KEY to value))
            }
        }
    }
}
