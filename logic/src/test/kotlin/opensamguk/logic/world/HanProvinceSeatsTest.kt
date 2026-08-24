package opensamguk.logic.world

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 7-2단계 실측 고정 — han 郡治 총합·州 분포.
 * `docs/superpowers/specs/2026-08-19-nation-rank-three-axis.md` §2 는 175 를 말하지만,
 * 그 뒤 phantom/중복 郡 노드 병합·삭제(풍익군→좌풍익·북평군→우북평군·서릉군→강하군·
 * 의도군→이릉·신성군→방릉·파서군→낭중)으로 175 → 172 로 줄었다. 서릉군 삭제는
 * 별도 커밋으로 분리돼 있었다(U49: 서릉군이 江夏郡 縣인지 孫吳가 개명한 夷陵인지
 * 미확정 — 삭제의 안전성 자체엔 영향 없다, 삭제 대상엔 참조가 없다). 여기 값은
 * [HanCityConstVariant.seatCountByProvince] 를 실제로 돌려 나온 실측값이다.
 */
class HanProvinceSeatsTest {

    @Test
    fun `han seatCountByProvince sums to the actual gunchi count`() {
        val seats = HanCityConstVariant.seatCountByProvince
        val actualGunchiCount = HanCityConstVariant.all().values.count { HanCityConstVariant.countsForNationLevel(it.level) }
        assertEquals(actualGunchiCount, seats.values.sum())
        // 실측: {1=8, 2=8, 3=13, 4=8, 5=7, 6=7, 7=16, 8=13, 9=19, 10=17, 11=12, 12=14, 13=8, 14=22} = 172.
        assertEquals(172, seats.values.sum())
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
