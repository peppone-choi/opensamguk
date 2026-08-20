package opensamguk.logic.world.rank

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [OfficerTitles] 단위 테스트 — han 전용 수뇌·공통칸 관직표(§9.3/§9.4/§9.5).
 * 값의 출처: 스펙 2026-08-19-nation-rank-three-axis.md.
 */
class OfficerTitlesTest {

    @Test
    fun `cityKindOf - 요동속국은 국보다 속국이 먼저 매칭된다`() {
        assertEquals(CityKind.SOKGUK, OfficerTitles.cityKindOf("요동속국", 4, 12))
    }

    @Test
    fun `cityKindOf 와 commonTitle - 王國과 郡`() {
        assertEquals(CityKind.WANGGUK, OfficerTitles.cityKindOf("양국", 4, 2))
        assertEquals("상", OfficerTitles.commonTitle(CityKind.WANGGUK, 4))
        assertEquals("태수", OfficerTitles.commonTitle(CityKind.GUN, 4))
        assertEquals("군승", OfficerTitles.commonTitle(CityKind.GUN, 3))
        assertEquals("장사", OfficerTitles.commonTitle(CityKind.WANGGUK, 3))
    }

    @Test
    fun `cityKindOf 와 commonTitle - 영현 장현`() {
        assertEquals(CityKind.YEONGHYEON, OfficerTitles.cityKindOf("남양군", 10, 7))
        assertEquals("현령", OfficerTitles.commonTitle(CityKind.YEONGHYEON, 4))
        assertEquals(CityKind.JANGHYEON, OfficerTitles.cityKindOf("남양군", 11, 7))
        assertEquals("현장", OfficerTitles.commonTitle(CityKind.JANGHYEON, 4))
    }

    @Test
    fun `cityKindOf - 동이는 region 14 로 판정하고 jun cityLevel 을 덮는다`() {
        assertEquals(CityKind.DONGYI, OfficerTitles.cityKindOf("백제국", 4, NationRank.DONGYI_REGION_ID))
        assertEquals("군장", OfficerTitles.commonTitle(CityKind.DONGYI, 4))
        assertEquals("소군장", OfficerTitles.commonTitle(CityKind.DONGYI, 3))
        assertNull(OfficerTitles.commonTitle(CityKind.DONGYI, 2))
    }

    @Test
    fun `chiefTitle - 중앙관은 12번 칸만 덮는다`() {
        assertEquals("승상", OfficerTitles.chiefTitle(RankTrack.WANG, 12, CentralOffice.SEUNGSANG))
        assertEquals("상", OfficerTitles.chiefTitle(RankTrack.WANG, 11, CentralOffice.SEUNGSANG))
    }

    @Test
    fun `chiefTitle - 群盜는 12 11 만 있고 10 이하는 null`() {
        assertEquals("수", OfficerTitles.chiefTitle(RankTrack.BANDIT, 12, CentralOffice.NONE))
        assertEquals("소수", OfficerTitles.chiefTitle(RankTrack.BANDIT, 11, CentralOffice.NONE))
        assertNull(OfficerTitles.chiefTitle(RankTrack.BANDIT, 10, CentralOffice.NONE))
    }

    @Test
    fun `chiefTitle - 黃巾은 12부터 5까지 자체 체계를 갖는다`() {
        assertEquals("천공장군", OfficerTitles.chiefTitle(RankTrack.HERETIC, 12, CentralOffice.NONE))
        assertEquals("지공장군", OfficerTitles.chiefTitle(RankTrack.HERETIC, 11, CentralOffice.NONE))
        assertEquals("제자", OfficerTitles.chiefTitle(RankTrack.HERETIC, 5, CentralOffice.NONE))
        // 중앙관은 정통성 없는 세력에 부여되지 않지만, 함수 자체는 12 칸만 방어적으로 덮는다.
        assertEquals("승상", OfficerTitles.chiefTitle(RankTrack.HERETIC, 12, CentralOffice.SEUNGSANG))
    }

    @Test
    fun `trackOf - spine 과 정통성에서 축을 고른다`() {
        val wangRank = NationRank.compute(
            seatCount = 41,
            seatsByProvince = mapOf(1 to 41),
            totalSeatsByProvince = mapOf(1 to 50),
            legitimacy = Legitimacy.ORTHODOX,
            holdsEmperor = false,
        )
        assertEquals(RankTrack.WANG, OfficerTitles.trackOf(wangRank, Legitimacy.ORTHODOX))

        val taesuRank = NationRank.compute(
            seatCount = 1,
            seatsByProvince = mapOf(1 to 1),
            totalSeatsByProvince = mapOf(1 to 10),
            legitimacy = Legitimacy.ORTHODOX,
            holdsEmperor = false,
        )
        assertEquals(RankTrack.TAESU, OfficerTitles.trackOf(taesuRank, Legitimacy.ORTHODOX))

        val banditRank = NationRank.compute(
            seatCount = 175,
            seatsByProvince = mapOf(1 to 175),
            totalSeatsByProvince = mapOf(1 to 175),
            legitimacy = Legitimacy.BANDIT,
            holdsEmperor = false,
        )
        assertEquals(RankTrack.BANDIT, OfficerTitles.trackOf(banditRank, Legitimacy.BANDIT))

        val hereticRank = NationRank.compute(
            seatCount = 175,
            seatsByProvince = mapOf(1 to 175),
            totalSeatsByProvince = mapOf(1 to 175),
            legitimacy = Legitimacy.HERETIC,
            holdsEmperor = false,
        )
        assertEquals(RankTrack.HERETIC, OfficerTitles.trackOf(hereticRank, Legitimacy.HERETIC))
    }
}
