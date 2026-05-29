package opensamguk.common.josa
import kotlin.test.Test; import kotlin.test.assertEquals; import kotlin.test.assertFailsWith
class JosaUtilTest {
    @Test fun `eun-neun with jongsung`() = assertEquals("은", JosaUtil.pick("한국", "은"))
    @Test fun `eun-neun without jongsung`() = assertEquals("는", JosaUtil.pick("사과", "은"))
    @Test fun `i-ga with jongsung`() = assertEquals("이", JosaUtil.pick("한국", "이"))
    @Test fun `eul-reul without jongsung`() = assertEquals("를", JosaUtil.pick("사과", "을"))
    @Test fun `gwa-wa`() = assertEquals("과", JosaUtil.pick("한국", "과"))
    @Test fun `ro after rieul drops to ro`() = assertEquals("로", JosaUtil.pick("서울", "으로"))
    @Test fun `ro after non-rieul jongsung`() = assertEquals("으로", JosaUtil.pick("한국", "으로"))
    @Test fun `normalization pick by value equals pick by key`() = assertEquals(JosaUtil.pick("한국", "은"), JosaUtil.pick("한국", "는"))
    @Test fun `normalization parenthesized form`() = assertEquals(JosaUtil.pick("한국", "은"), JosaUtil.pick("한국", "(은)는"))
    @Test fun `explicit woJongsung bypasses map`() = assertEquals("AA", JosaUtil.pick("사과", "BB", "AA"))
    @Test fun `put concatenates`() = assertEquals("한국은", JosaUtil.put("한국", "은"))
    @Test fun `null text treated as empty`() = assertEquals("는", JosaUtil.pick(null, "은"))
    @Test fun `unknown josa with empty woJongsung throws exact message`() {
        val ex = assertFailsWith<IllegalArgumentException> { JosaUtil.pick("진", "부터") }
        assertEquals("올바르지 않은 조사 지정", ex.message)
    }
}
