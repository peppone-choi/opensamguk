package opensamguk.common.josa
import kotlin.test.Test; import kotlin.test.assertEquals; import kotlin.test.assertFalse; import kotlin.test.assertTrue
class HasJongsungTest {
    @Test fun `hangul with jongsung`() = assertTrue(JosaDetect.hasJongsung("한국", false))
    @Test fun `hangul without jongsung`() = assertFalse(JosaDetect.hasJongsung("사과", false))
    @Test fun `rieul jongsung is true normally`() = assertTrue(JosaDetect.hasJongsung("서울", false))
    @Test fun `rieul jongsung is false for ro`() = assertFalse(JosaDetect.hasJongsung("서울", true))
    @Test fun `compat jamo consonant`() = assertTrue(JosaDetect.hasJongsung("ㄱ", false))
    @Test fun `compat jamo rieul for ro`() = assertFalse(JosaDetect.hasJongsung("ㄹ", true))
    @Test fun `digit 0 has jongsung`() = assertTrue(JosaDetect.hasJongsung("100", false))
    @Test fun `digit 2 no jongsung`() = assertFalse(JosaDetect.hasJongsung("12", false))
    @Test fun `digit 1 rieul for ro`() = assertFalse(JosaDetect.hasJongsung("1", true))
    @Test fun `latin consonant has jongsung`() = assertTrue(JosaDetect.hasJongsung("Kim", false))
    @Test fun `latin vowel no jongsung`() = assertFalse(JosaDetect.hasJongsung("Lee", false))
    @Test fun `in-range hanja true`() = assertTrue(JosaDetect.hasJongsung("一", false))
    @Test fun `boundary hanja U5ED3 true`() = assertTrue(JosaDetect.hasJongsung("廓", false))
    @Test fun `out-of-range hanja stripped uses preceding`() = assertTrue(JosaDetect.hasJongsung("국廔", false))
    @Test fun `trailing punctuation and space stripped`() = assertTrue(JosaDetect.hasJongsung("한국!! ", false))
    @Test fun `empty string false`() = assertFalse(JosaDetect.hasJongsung("", false))
    @Test fun `getLastChar picks last non-space token`() = assertEquals("국", JosaDetect.getLastChar("대한 민국 "))
}
