package opensamguk.logic.world.rank

/**
 * 세력이 서 있는 축 — 수뇌(officerLevel 12~5) 관직표를 고른다.
 *
 * **패러티 아님 · han 전용**, 근거: 스펙 2026-08-19-nation-rank-three-axis.md §9.3.
 */
enum class RankTrack { WANDERING, HOJOK, TAESU, JASA, JUMOK, GONG, WANG, HWANGJE, BANDIT, HERETIC }

/**
 * 城이 무엇인가 — 공통칸(officerLevel 4~0) 관직표를 고른다.
 *
 * **패러티 아님 · han 전용**, 근거: 스펙 2026-08-19-nation-rank-three-axis.md §9.5.
 */
enum class CityKind { GUN, WANGGUK, SOKGUK, DONGYI, YEONGHYEON, JANGHYEON }

/**
 * 수뇌·공통칸 관직명 표(officerLevel 0~12) — `NationRank.Rank` 의 3축이 낳는 귀결(§9).
 *
 * **확실성 표기 (날조 금지 규약, §9.3).** 표의 **관직명 자체**는 續漢書 百官志 소재로 사료
 * 근거가 있다. 그러나 **어느 관직이 12~5 중 몇 번 칸에 앉는가**는 사료가 정한 서열이 아니라
 * **게임 밸런스 배치**다 — 百官志는 秩(질록)을 적지 서열표를 주지 않는다. 배치는
 * `패러티 아님 · han 전용 밸런스값`이고 바뀌어도 사료 위반이 아니다. 이름을 바꾸는 것은
 * 사료 문제이니 근거 없이 바꾸지 마라.
 *
 * Spring·DB·RNG·`opensamguk.logic.domain.*` 를 참조하지 않는다.
 */
object OfficerTitles {

    /**
     * `NationRank.Rank` 의 spine·爵·정통성에서 [RankTrack] 을 고른다(§9.3의 "세력 축").
     * 공(公) 이상은 爵이 官을 덮고(§2① spine 우선순위와 동형), 그 아래는 지방관 축,
     * 지방관도 없으면 spine 1(호족)/0(방랑군)으로 떨어진다.
     */
    fun trackOf(rank: NationRank.Rank, legitimacy: Legitimacy): RankTrack {
        if (legitimacy == Legitimacy.HERETIC) return RankTrack.HERETIC
        if (legitimacy == Legitimacy.BANDIT) return RankTrack.BANDIT
        return when {
            rank.peerage == Peerage.HWANGJE -> RankTrack.HWANGJE
            rank.peerage == Peerage.WANG -> RankTrack.WANG
            rank.peerage == Peerage.GONG -> RankTrack.GONG
            rank.provincialOffice == ProvincialOffice.JUMOK -> RankTrack.JUMOK
            rank.provincialOffice == ProvincialOffice.JASA -> RankTrack.JASA
            rank.provincialOffice == ProvincialOffice.TAESU -> RankTrack.TAESU
            rank.level >= 1 -> RankTrack.HOJOK
            else -> RankTrack.WANDERING
        }
    }

    /**
     * 수뇌 관직표(§9.3) — index 0 = officerLevel 12(군주) … index 7 = officerLevel 5.
     * `null` = 표에 없는 칸(群盜·호족·방랑군의 10~5, §9.3 "群盜 행이 비는 것은 누락이 아니라
     * 설계다" — 典略 `sgz-08.txt:81` 「黑山、黃巾諸帥，本非冠蓋，自相號字」).
     *
     * 근거: 續漢書 百官志五(郡·王國) `data/corpus/baiguan.txt:438`, 皇甫嵩傳(黃巾 三十六方)
     * `data/corpus/hhs-071.txt:13`, 張燕傳(黑山 招安) `data/corpus/sgz-08.txt:81`.
     */
    private val CHIEF_TABLE: Map<RankTrack, List<String?>> = mapOf(
        RankTrack.TAESU to listOf("태수", "군승", "도위", "공조", "장사", "주부", "오관연", "독우"),
        RankTrack.JASA to listOf("자사", "별가종사", "교위", "치중종사", "도위", "주부", "부군국종사", "공조서좌"),
        RankTrack.JUMOK to listOf("주목", "별가종사", "중랑장", "치중종사", "교위", "주부", "종사중랑", "부군국종사"),
        RankTrack.GONG to listOf("공", "상", "중위", "낭중령", "복", "치서", "알자", "대부"),
        RankTrack.WANG to listOf("왕", "상", "위장군", "낭중령", "중위", "부", "복", "알자"),
        RankTrack.HWANGJE to listOf("황제", "승상", "표기장군", "사공", "거기장군", "태위", "위장군", "사도"),
        RankTrack.HERETIC to listOf("천공장군", "지공장군", "인공장군", "대방", "방", "소방", "거수", "제자"),
        RankTrack.BANDIT to listOf("수", "소수", null, null, null, null, null, null),
        RankTrack.HOJOK to listOf("영주", "참모", null, null, null, null, null, null),
        RankTrack.WANDERING to listOf("두목", "부두목", null, null, null, null, null, null),
    )

    /**
     * officerLevel 12~5 의 관직명. `centralOffice != NONE` 이면 **12번 칸만** 중앙관 라벨로
     * 대체하고 11~5 는 표 그대로다(§9.4) — 천자를 낀 조조는 spine 이 魏公/魏王이어도 실직함은
     * 丞相이었고 그 아래는 魏國 속관 그대로였다.
     *
     * 표에 없는 칸은 빈 문자열이 아니라 `null` 을 돌려준다 — 호출자가 "-" 로 표시한다.
     */
    fun chiefTitle(track: RankTrack, officerLevel: Int, centralOffice: CentralOffice): String? {
        require(officerLevel in 5..12) { "officerLevel out of chief range: $officerLevel" }
        if (officerLevel == 12 && centralOffice != CentralOffice.NONE) return centralOffice.label
        val idx = 12 - officerLevel
        return CHIEF_TABLE.getValue(track)[idx]
    }

    /**
     * 공통칸 관직표(§9.5) — index 0 = officerLevel 4 … index 4 = officerLevel 0.
     *
     * 근거: 續漢書 百官志五 「相如太守。其長史，如郡丞」(`data/corpus/baiguan.txt:438`) — 王國의
     * 相/長史 가 郡의 太守/郡丞 에 대응한다는 사료의 직접 대응관계. 縣令/縣長 은 같은 志의
     * 「萬戶以上為令，不滿為長」. 屬國都尉·東夷 君長 은 續漢書가 太守/相과 별도로 두는 계통이다.
     * 2번 칸(功曹/縣尉/侯)의 배치는 §9.3 과 같은 han 전용 밸런스값이다.
     */
    private val COMMON_TABLE: Map<CityKind, List<String?>> = mapOf(
        CityKind.GUN to listOf("태수", "군승", "공조", "일반", "재야"),
        CityKind.WANGGUK to listOf("상", "장사", "공조", "일반", "재야"),
        CityKind.SOKGUK to listOf("속국도위", "승", "후", "일반", "재야"),
        CityKind.DONGYI to listOf("군장", "소군장", null, "일반", "재야"),
        CityKind.YEONGHYEON to listOf("현령", "현승", "현위", "일반", "재야"),
        CityKind.JANGHYEON to listOf("현장", "현승", "현위", "일반", "재야"),
    )

    /** officerLevel 4~0 의 관직명. 표에 없는 칸은 `null`. */
    fun commonTitle(kind: CityKind, officerLevel: Int): String? {
        require(officerLevel in 0..4) { "officerLevel out of common range: $officerLevel" }
        val idx = 4 - officerLevel
        return COMMON_TABLE.getValue(kind)[idx]
    }

    /**
     * 城이 어느 [CityKind] 인지 판정한다(§9.5). 순서가 중요하다 — "요동속국"은 "국"으로도
     * 끝나므로 속국을 먼저 본다. 東夷(region [NationRank.DONGYI_REGION_ID]) 는 jun/cityLevel
     * 보다 우선한다 — 漢 행정단위가 아니라 三國志 東夷傳의 君長 계통이기 때문이다.
     */
    fun cityKindOf(jun: String, cityLevel: Int, region: Int): CityKind = when {
        region == NationRank.DONGYI_REGION_ID -> CityKind.DONGYI
        cityLevel == 10 -> CityKind.YEONGHYEON
        cityLevel == 11 -> CityKind.JANGHYEON
        jun.endsWith("속국") -> CityKind.SOKGUK
        jun.endsWith("국") -> CityKind.WANGGUK
        else -> CityKind.GUN
    }
}
