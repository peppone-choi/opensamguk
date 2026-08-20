package opensamguk.logic.world.rank

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [NationRank] 단위 테스트 — han 전용 3축(爵·官·天子) divergence.
 * 값의 출처: 스펙 2026-08-19-nation-rank-three-axis.md.
 */
class NationRankTest {

    @Test
    fun `규모만으로는 황제가 절대 안 나온다`() {
        val peerage = NationRank.peerageOf(seatCount = 175, legitimacy = Legitimacy.ORTHODOX)
        assertEquals(Peerage.WANG, peerage)
    }

    @Test
    fun `황건적은 郡治 175 여도 관작 둘 다 NONE 이고 spine 이 4를 안 넘는다`() {
        val peerage = NationRank.peerageOf(seatCount = 175, legitimacy = Legitimacy.BANDIT)
        val (office, provinceId) = NationRank.provincialOfficeOf(
            seatsByProvince = mapOf(1 to 175),
            totalSeatsByProvince = mapOf(1 to 175),
            legitimacy = Legitimacy.BANDIT,
        )
        assertEquals(Peerage.NONE, peerage)
        assertEquals(ProvincialOffice.NONE, office)
        assertEquals(null, provinceId)

        val spine = NationRank.spineLevel(peerage, office, seatCount = 175, legitimacy = Legitimacy.BANDIT)
        assertTrue(spine <= 4)
        assertEquals(4, spine)
    }

    @Test
    fun `holdsEmperor false 면 어떤 규모에서도 중앙관이 NONE`() {
        assertEquals(CentralOffice.NONE, NationRank.centralOfficeOf(seatCount = 175, holdsEmperor = false))
        assertEquals(CentralOffice.NONE, NationRank.centralOfficeOf(seatCount = 0, holdsEmperor = false))
    }

    @Test
    fun `holdsEmperor true 면 문턱대로 중앙관이 오른다`() {
        assertEquals(CentralOffice.NONE, NationRank.centralOfficeOf(seatCount = 0, holdsEmperor = true))
        assertEquals(CentralOffice.GUGYEONG, NationRank.centralOfficeOf(seatCount = 1, holdsEmperor = true))
        assertEquals(CentralOffice.SAMGONG, NationRank.centralOfficeOf(seatCount = 13, holdsEmperor = true))
        assertEquals(CentralOffice.DAEJANGGUN, NationRank.centralOfficeOf(seatCount = 28, holdsEmperor = true))
        assertEquals(CentralOffice.SEUNGSANG, NationRank.centralOfficeOf(seatCount = 41, holdsEmperor = true))
    }

    @Test
    fun `주 장악도 경계값 - 정확히 50퍼는 자사`() {
        val (office, _) = NationRank.provincialOfficeOf(
            seatsByProvince = mapOf(1 to 50),
            totalSeatsByProvince = mapOf(1 to 100),
            legitimacy = Legitimacy.ORTHODOX,
        )
        assertEquals(ProvincialOffice.JASA, office)
    }

    @Test
    fun `주 장악도 경계값 - 49점9퍼는 태수`() {
        val (office, _) = NationRank.provincialOfficeOf(
            seatsByProvince = mapOf(1 to 499),
            totalSeatsByProvince = mapOf(1 to 1000),
            legitimacy = Legitimacy.ORTHODOX,
        )
        assertEquals(ProvincialOffice.TAESU, office)
    }

    @Test
    fun `주 장악도 경계값 - 정확히 80퍼는 주목`() {
        val (office, _) = NationRank.provincialOfficeOf(
            seatsByProvince = mapOf(1 to 80),
            totalSeatsByProvince = mapOf(1 to 100),
            legitimacy = Legitimacy.ORTHODOX,
        )
        assertEquals(ProvincialOffice.JUMOK, office)
    }

    @Test
    fun `주 장악도 경계값 - 79점9퍼는 자사`() {
        val (office, _) = NationRank.provincialOfficeOf(
            seatsByProvince = mapOf(1 to 799),
            totalSeatsByProvince = mapOf(1 to 1000),
            legitimacy = Legitimacy.ORTHODOX,
        )
        assertEquals(ProvincialOffice.JASA, office)
    }

    @Test
    fun `동률일 때 주 id 작은 쪽이 뽑힌다`() {
        val (office, provinceId) = NationRank.provincialOfficeOf(
            seatsByProvince = mapOf(3 to 50, 1 to 50, 2 to 50),
            totalSeatsByProvince = mapOf(3 to 100, 1 to 100, 2 to 100),
            legitimacy = Legitimacy.ORTHODOX,
        )
        assertEquals(ProvincialOffice.JASA, office)
        assertEquals(1, provinceId)
    }

    @Test
    fun `후 3등급은 spine을 안 올린다 - 현후 + 태수 - spine 2`() {
        val spine = NationRank.spineLevel(
            peerage = Peerage.HYEONHU,
            office = ProvincialOffice.TAESU,
            seatCount = 13,
            legitimacy = Legitimacy.ORTHODOX,
        )
        assertEquals(2, spine)
    }

    @Test
    fun `spine은 관 축과 작 축의 최댓값`() {
        assertEquals(0, NationRank.spineLevel(Peerage.NONE, ProvincialOffice.NONE, 5, Legitimacy.ORTHODOX))
        assertEquals(1, NationRank.spineLevel(Peerage.NONE, ProvincialOffice.NONE, 0, Legitimacy.ORTHODOX))
        assertEquals(2, NationRank.spineLevel(Peerage.NONE, ProvincialOffice.TAESU, 1, Legitimacy.ORTHODOX))
        assertEquals(3, NationRank.spineLevel(Peerage.NONE, ProvincialOffice.JASA, 20, Legitimacy.ORTHODOX))
        assertEquals(4, NationRank.spineLevel(Peerage.NONE, ProvincialOffice.JUMOK, 40, Legitimacy.ORTHODOX))
        assertEquals(5, NationRank.spineLevel(Peerage.GONG, ProvincialOffice.JUMOK, 28, Legitimacy.ORTHODOX))
        assertEquals(6, NationRank.spineLevel(Peerage.WANG, ProvincialOffice.JUMOK, 41, Legitimacy.ORTHODOX))
        assertEquals(7, NationRank.spineLevel(Peerage.HWANGJE, ProvincialOffice.JUMOK, 41, Legitimacy.ORTHODOX))
    }

    @Test
    fun `banditLabelOf 사다리`() {
        assertEquals("", NationRank.banditLabelOf(0))
        assertEquals("소수", NationRank.banditLabelOf(1))
        assertEquals("수", NationRank.banditLabelOf(13))
        assertEquals("거수", NationRank.banditLabelOf(28))
    }

    @Test
    fun `banditLabelOf 는 BANDIT과 HERETIC 이 다르다`() {
        assertEquals("수", NationRank.banditLabelOf(13, Legitimacy.BANDIT))
        assertEquals("대방", NationRank.banditLabelOf(13, Legitimacy.HERETIC))
        assertEquals("천공장군", NationRank.banditLabelOf(28, Legitimacy.HERETIC))
        // 天公將軍은 太平道 三公將軍의 정점이라 群盜 사다리에 없다.
        assertEquals("거수", NationRank.banditLabelOf(28, Legitimacy.BANDIT))
    }

    @Test
    fun `legitimacyOf - explicit 우선, 그 다음 typeCode 태평도-도적, 그 외 ORTHODOX`() {
        assertEquals(Legitimacy.SELF_STYLED, NationRank.legitimacyOf("군", "군", "SELF_STYLED"))
        // 판정은 세력명이 아니라 typeCode(성향)로 한다 — 스펙 §3.4.
        // 「황건적」이라는 이름이 아니라 태평도라는 성향이 HERETIC 을 만든다.
        assertEquals(Legitimacy.HERETIC, NationRank.legitimacyOf("황건적", "태평도"))
        assertEquals(Legitimacy.HERETIC, NationRank.legitimacyOf("황건적", "che_태평도"))
        assertEquals(Legitimacy.BANDIT, NationRank.legitimacyOf("흑산적", "도적"))
        assertEquals(Legitimacy.BANDIT, NationRank.legitimacyOf("흑산적", "che_도적"))
        // 오두미도(장로)는 도적이 아니다 — 스펙 §3.3.
        assertEquals(Legitimacy.ORTHODOX, NationRank.legitimacyOf("한중", "오두미도"))
        assertEquals(Legitimacy.ORTHODOX, NationRank.legitimacyOf("군", "군"))
    }

    @Test
    fun `HERETIC 은 郡治 175 여도 관작 둘 다 NONE 이고 spine 이 4를 안 넘는다`() {
        val peerage = NationRank.peerageOf(seatCount = 175, legitimacy = Legitimacy.HERETIC)
        val (office, provinceId) = NationRank.provincialOfficeOf(
            seatsByProvince = mapOf(1 to 175),
            totalSeatsByProvince = mapOf(1 to 175),
            legitimacy = Legitimacy.HERETIC,
        )
        assertEquals(Peerage.NONE, peerage)
        assertEquals(ProvincialOffice.NONE, office)
        assertEquals(null, provinceId)

        val spine = NationRank.spineLevel(peerage, office, seatCount = 175, legitimacy = Legitimacy.HERETIC)
        assertTrue(spine <= 4)
        assertEquals(4, spine)
    }

    @Test
    fun `동이(region 14) 郡治만 22개 가진 세력은 provincialOfficeOf 가 NONE`() {
        val (office, provinceId) = NationRank.provincialOfficeOf(
            seatsByProvince = mapOf(NationRank.DONGYI_REGION_ID to 22),
            totalSeatsByProvince = mapOf(NationRank.DONGYI_REGION_ID to 22),
            legitimacy = Legitimacy.ORTHODOX,
        )
        assertEquals(ProvincialOffice.NONE, office)
        assertEquals(null, provinceId)
    }

    @Test
    fun `서주(전체 7) 하한 - 6개는 州牧, 4개는 刺史, 2개는 太守`() {
        val (office6, _) = NationRank.provincialOfficeOf(
            seatsByProvince = mapOf(5 to 6),
            totalSeatsByProvince = mapOf(5 to 7),
            legitimacy = Legitimacy.ORTHODOX,
        )
        assertEquals(ProvincialOffice.JUMOK, office6)

        val (office4, _) = NationRank.provincialOfficeOf(
            seatsByProvince = mapOf(5 to 4),
            totalSeatsByProvince = mapOf(5 to 7),
            legitimacy = Legitimacy.ORTHODOX,
        )
        assertEquals(ProvincialOffice.JASA, office4)

        val (office2, _) = NationRank.provincialOfficeOf(
            seatsByProvince = mapOf(5 to 2),
            totalSeatsByProvince = mapOf(5 to 7),
            legitimacy = Legitimacy.ORTHODOX,
        )
        assertEquals(ProvincialOffice.TAESU, office2)
    }

    @Test
    fun `비율 80퍼 지만 보유 4개인 가상 州(전체 5)는 하한 5 미달이라 刺史로 떨어진다`() {
        val (office, _) = NationRank.provincialOfficeOf(
            seatsByProvince = mapOf(1 to 4),
            totalSeatsByProvince = mapOf(1 to 5),
            legitimacy = Legitimacy.ORTHODOX,
        )
        assertEquals(ProvincialOffice.JASA, office)
    }

    @Test
    fun `compute 는 전 축을 한 번에 낸다`() {
        val rank = NationRank.compute(
            seatCount = 41,
            seatsByProvince = mapOf(1 to 41),
            totalSeatsByProvince = mapOf(1 to 50),
            legitimacy = Legitimacy.ORTHODOX,
            holdsEmperor = true,
        )
        assertEquals(Peerage.WANG, rank.peerage)
        assertEquals(ProvincialOffice.JUMOK, rank.provincialOffice)
        assertEquals(1, rank.provinceId)
        assertEquals(CentralOffice.SEUNGSANG, rank.centralOffice)
        assertEquals("", rank.banditLabel)
        assertEquals(6, rank.level)
    }
}
