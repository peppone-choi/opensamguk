package opensamguk.logic.world

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 7-2단계 실측 고정 — han 郡治 총합·州 분포.
 * `docs/superpowers/specs/2026-08-19-nation-rank-three-axis.md` §2 는 175 를 말하지만,
 * 여기 값은 [HanCityConstVariant.seatCountByProvince] 를 실제로 돌려 나온 실측값이다.
 */
class HanProvinceSeatsTest {

    @Test
    fun `han seatCountByProvince sums to the actual gunchi count`() {
        val seats = HanCityConstVariant.seatCountByProvince
        val actualGunchiCount = HanCityConstVariant.all().values.count { HanCityConstVariant.countsForNationLevel(it.level) }
        assertEquals(actualGunchiCount, seats.values.sum())
        // 실측: {1=9, 2=8, 3=13, 4=8, 5=7, 6=7, 7=17, 8=13, 9=19, 10=17, 11=12, 12=15, 13=8, 14=22} = 175.
        // 스펙 §2 가 말하는 175 와 정확히 일치한다.
        assertEquals(175, seats.values.sum())
    }

    @Test
    fun `han province ids are all within 1 to 14`() {
        val seats = HanCityConstVariant.seatCountByProvince
        assertTrue(seats.isNotEmpty())
        seats.keys.forEach { region ->
            assertTrue(region in 1..14, "region $region out of 1..14")
        }
    }

    @Test
    fun `che variant keeps the default no-op three-axis surface`() {
        assertFalse(CheCityConst.supportsThreeAxisRank)
        assertTrue(CheCityConst.seatCountByProvince.isEmpty())
    }
}
