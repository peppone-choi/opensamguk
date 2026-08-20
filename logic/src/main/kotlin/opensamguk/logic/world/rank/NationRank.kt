package opensamguk.logic.world.rank

/**
 * 정통성 — 국가가 漢 조정의 관작을 받을 자격이 있는지.
 *
 * `HERETIC`(황건)과 `BANDIT`(群盜)은 둘 다 官·爵이 없지만 출구가 다르다 — `BANDIT`은
 * 招安으로 `ORTHODOX` 전환이 가능하고(`sgz-08.txt:81` 장연), `HERETIC`은 漢의 天命을
 * 부정했으므로(`hhs-071.txt:13` 「蒼天已死，黃天當立」) 招安이 성립하지 않는다.
 *
 * **패러티 아님 · han 전용**, 근거: 스펙 2026-08-19-nation-rank-three-axis.md §3.
 */
enum class Legitimacy { ORTHODOX, SELF_STYLED, BANDIT, HERETIC }

/**
 * 爵(작위) — 天子가 내리는 세습 작위 사다리. 侯 3등급은 [ProvincialOffice]/[CentralOffice] 축과
 * 직교한다(§2①) — spine 에 자리가 없다. 公 이상만 spine 을 덮는다(§5).
 *
 * **패러티 아님 · han 전용**, 근거: 스펙 2026-08-19-nation-rank-three-axis.md §2①.
 */
enum class Peerage(val label: String) {
    NONE("무작"),
    JEONGHU("정후"),
    HYANGHU("향후"),
    HYEONHU("현후"),
    GONG("공"),
    WANG("왕"),
    HWANGJE("황제"),
}

/**
 * 地方官 — 州 안 郡治 장악도로 정해지는 지방관 등급.
 *
 * **패러티 아님 · han 전용**, 근거: 스펙 2026-08-19-nation-rank-three-axis.md §2②.
 */
enum class ProvincialOffice(val label: String) {
    NONE(""),
    TAESU("태수"),
    JASA("자사"),
    JUMOK("주목"),
}

/**
 * 中央官 — 天子를 옹립한 세력만 갖는 중앙관 등급.
 *
 * **패러티 아님 · han 전용**, 근거: 스펙 2026-08-19-nation-rank-three-axis.md §2②.
 */
enum class CentralOffice(val label: String) {
    NONE(""),
    GUGYEONG("구경"),
    SAMGONG("삼공"),
    DAEJANGGUN("대장군"),
    SEUNGSANG("승상"),
}

/**
 * 국가 등급 3축(爵·官·天子) 순수 계산 코어 — han 맵 전용 divergence.
 *
 * Spring·DB·RNG·`opensamguk.logic.domain.*` 를 참조하지 않는다. 입력·출력 전부 원시값이다.
 * 이 오브젝트가 매기는 모든 문턱은 PHP 패러티가 아니라 han 전용 밸런스값이다 — 근거는
 * 각 함수 KDoc 에 스펙 절 번호로 명시한다(스펙: 2026-08-19-nation-rank-three-axis.md).
 */
object NationRank {

    /**
     * 東夷 region id — han.json 의 region 14. 東夷는 漢의 州가 아니다(諸國을 담는 그릇, §9.5
     * 「漢 관제 밖 · 君長」12국). [provincialOfficeOf] 는 이 region 을 분자·분모 양쪽에서
     * 완전히 제외한다 — 東夷에는 刺史·州牧이 존재할 수 없다.
     *
     * **패러티 아님 · han 전용**, 근거: 스펙 2026-08-19-nation-rank-three-axis.md §2②-보정(a).
     */
    const val DONGYI_REGION_ID = 14

    /**
     * 郡治 수 문턱으로 爵을 매긴다. 皇帝는 **규모로 절대 도달 불가**(禪讓/僭號 명령 전용) —
     * `BANDIT` 은 官도 爵도 없다(§4).
     *
     * **패러티 아님 · han 전용**, 근거: 스펙 2026-08-19-nation-rank-three-axis.md §2① (亭侯 1 · 鄉侯 5 · 縣侯 13 · 公 28 · 王 41).
     */
    fun peerageOf(seatCount: Int, legitimacy: Legitimacy): Peerage {
        if (legitimacy == Legitimacy.BANDIT || legitimacy == Legitimacy.HERETIC) return Peerage.NONE
        return when {
            seatCount >= 41 -> Peerage.WANG
            seatCount >= 28 -> Peerage.GONG
            seatCount >= 13 -> Peerage.HYEONHU
            seatCount >= 5 -> Peerage.HYANGHU
            seatCount >= 1 -> Peerage.JEONGHU
            else -> Peerage.NONE
        }
    }

    /**
     * 州별 (보유 郡治 / 그 州 전체 郡治) 장악도로 가장 높은 등급의 州를 고른다. 등급이 같으면
     * 장악도가 높은 州, 장악도도 같으면 州 id 가 작은 쪽을 결정적으로 고른다. `BANDIT`·`HERETIC`
     * 은 官 축에 아예 못 들어간다(§3). [DONGYI_REGION_ID] 는 분자·분모 양쪽에서 완전히 제외한다(§2②-보정a).
     *
     * 최소 郡治 하한을 비율 문턱과 함께 건다(§2②-보정b) — 州 크기가 7~22 로 벌어져 비율만
     * 보면 작은 州의 다수 점유가 큰 州의 절대 점유와 동급 취급되는 것을 막는 하방 가드다.
     * 하한 미달이면 한 단계 아래로 떨어진다(州牧 조건인데 郡治 <5 면 刺史 조건을 다시 본다).
     *
     * **패러티 아님 · han 전용**, 근거: 스펙 2026-08-19-nation-rank-three-axis.md §2②
     * (郡治 ≥1 태수) · §2②-보정b (자사 = 비율 ≥50% AND 郡治 ≥3 · 주목 = 비율 ≥80% AND 郡治 ≥5).
     */
    fun provincialOfficeOf(
        seatsByProvince: Map<Int, Int>,
        totalSeatsByProvince: Map<Int, Int>,
        legitimacy: Legitimacy,
    ): Pair<ProvincialOffice, Int?> {
        if (legitimacy == Legitimacy.BANDIT || legitimacy == Legitimacy.HERETIC) return ProvincialOffice.NONE to null

        var best = ProvincialOffice.NONE
        var bestProvince: Int? = null
        var bestRatio = -1.0

        for (provinceId in seatsByProvince.keys.sorted()) {
            if (provinceId == DONGYI_REGION_ID) continue
            val seats = seatsByProvince[provinceId] ?: 0
            if (seats < 1) continue
            val total = totalSeatsByProvince[provinceId] ?: 0
            val ratio = if (total > 0) seats.toDouble() / total.toDouble() else 0.0

            val office = when {
                ratio >= 0.8 && seats >= 5 -> ProvincialOffice.JUMOK
                ratio >= 0.5 && seats >= 3 -> ProvincialOffice.JASA
                else -> ProvincialOffice.TAESU
            }

            val better = when {
                office.ordinal > best.ordinal -> true
                office.ordinal == best.ordinal && ratio > bestRatio -> true
                else -> false
            }
            if (better) {
                best = office
                bestProvince = provinceId
                bestRatio = ratio
            }
        }
        return best to bestProvince
    }

    /**
     * 天子를 옹립한 세력만 中央官을 갖는다(§2②·§3) — `holdsEmperor == false` 면 무조건 `NONE`.
     * `BANDIT`·`HERETIC` 은 官도 爵도 없으므로(§4) `holdsEmperor` 와 무관하게 방어적으로 `NONE`.
     *
     * **패러티 아님 · han 전용**, 근거: 스펙 2026-08-19-nation-rank-three-axis.md §2② (九卿 1 · 三公 13 · 大將軍 28 · 丞相 41) · §4.
     */
    fun centralOfficeOf(seatCount: Int, holdsEmperor: Boolean, legitimacy: Legitimacy = Legitimacy.ORTHODOX): CentralOffice {
        if (!holdsEmperor || legitimacy == Legitimacy.BANDIT || legitimacy == Legitimacy.HERETIC) return CentralOffice.NONE
        return when {
            seatCount >= 41 -> CentralOffice.SEUNGSANG
            seatCount >= 28 -> CentralOffice.DAEJANGGUN
            seatCount >= 13 -> CentralOffice.SAMGONG
            seatCount >= 1 -> CentralOffice.GUGYEONG
            else -> CentralOffice.NONE
        }
    }

    /**
     * `BANDIT`/`HERETIC` 의 자칭 라벨 사다리 — 官 축이 없는 대신 붙는 표시용 문자열. 둘은
     * 두 사다리는 **겹치지 않는다**(§3).
     *
     * `HERETIC`(黃巾) — 三十六方 체계와 張角의 실제 자칭. `hhs-071.txt:13`
     * 「遂置三十六方…大方萬餘人，小方六七千，各立渠帥」 + 「角稱『天公將軍』」.
     *
     * `BANDIT`(群盜) — 黑山 계열의 실제 호칭. `sgz-08.txt:81` 「其小帥孫輕、王當等」(小帥) ·
     * 「燕推牛角為帥」「必以燕為帥」(帥) · 「封其渠帥爲侯王者八十餘人」(`sgz-30.txt:14`, 渠帥 =
     * 漢 밖 집단 우두머리의 통칭).
     *
     * **「天公將軍」은 群盜 사다리에 넣지 않는다.** 그것은 太平道 三公將軍(天公·地公·人公,
     * `hhs-071.txt:13`·`sgz-46.txt:17`)의 정점이라 黃巾 전용이다. 黑山이 실제로 받은 것은
     * 자칭이 아니라 招安 관작이다(楊鳳=黑山校尉, 張燕=平難中郎將 → 平北將軍·安國亭侯).
     *
     * **패러티 아님 · han 전용**, 근거: 스펙 2026-08-19-nation-rank-three-axis.md §3·§5.
     */
    fun banditLabelOf(seatCount: Int, legitimacy: Legitimacy = Legitimacy.BANDIT): String {
        if (legitimacy == Legitimacy.HERETIC) {
            return when {
                seatCount >= 28 -> "천공장군"
                seatCount >= 13 -> "대방"
                seatCount >= 1 -> "거수"
                else -> ""
            }
        }
        return when {
            seatCount >= 28 -> "거수"
            seatCount >= 13 -> "수"
            seatCount >= 1 -> "소수"
            else -> ""
        }
    }

    /**
     * `nation.level` spine(0~7, han 도달 범위) — 官 spine(太守 2 · 刺史 3 · 州牧 4)과
     * 爵 spine(公 5 · 王 6 · 皇帝 7)의 최댓값. 侯 3등급은 spine 에 기여하지 않는다(직교, §2①).
     *
     * 이 함수는 `seatCount` 만 본다 — `seatCount == 0` 이면 1(호족)을 반환한다. 郡治조차 없고
     * 城도 없는 0(방랑군) 판정은 이 함수 밖, 호출자가 城 수로 가른다(§5).
     *
     * `BANDIT`·`HERETIC` 은 官 축이 없으므로 `seatCount` 문턱 1/13/28 로 직접 2/3/4 를 매기고
     * 4 를 넘지 않는다 — 도적·황건은 公/王/皇帝 라인을 타지 않는다(§4).
     *
     * **패러티 아님 · han 전용**, 근거: 스펙 2026-08-19-nation-rank-three-axis.md §5.
     */
    fun spineLevel(peerage: Peerage, office: ProvincialOffice, seatCount: Int, legitimacy: Legitimacy): Int {
        if (legitimacy == Legitimacy.BANDIT || legitimacy == Legitimacy.HERETIC) {
            return when {
                seatCount >= 28 -> 4
                seatCount >= 13 -> 3
                seatCount >= 1 -> 2
                else -> 1
            }
        }

        val officeSpine = when (office) {
            ProvincialOffice.JUMOK -> 4
            ProvincialOffice.JASA -> 3
            ProvincialOffice.TAESU -> 2
            ProvincialOffice.NONE -> if (seatCount == 0) 1 else 0
        }
        val peerageSpine = when (peerage) {
            Peerage.HWANGJE -> 7
            Peerage.WANG -> 6
            Peerage.GONG -> 5
            else -> 0
        }
        return maxOf(officeSpine, peerageSpine)
    }

    /** 3축 계산 결과 전체 — [meta] 로 flush 되는 값(§6)에 대응. */
    data class Rank(
        val level: Int,
        val peerage: Peerage,
        val provincialOffice: ProvincialOffice,
        val provinceId: Int?,
        val centralOffice: CentralOffice,
        val banditLabel: String,
    )

    /** [peerageOf]·[provincialOfficeOf]·[centralOfficeOf]·[banditLabelOf]·[spineLevel] 을 한 번에 낸다. */
    fun compute(
        seatCount: Int,
        seatsByProvince: Map<Int, Int>,
        totalSeatsByProvince: Map<Int, Int>,
        legitimacy: Legitimacy,
        holdsEmperor: Boolean,
    ): Rank {
        val peerage = peerageOf(seatCount, legitimacy)
        val (office, provinceId) = provincialOfficeOf(seatsByProvince, totalSeatsByProvince, legitimacy)
        val central = centralOfficeOf(seatCount, holdsEmperor, legitimacy)
        val bandit = if (legitimacy == Legitimacy.BANDIT || legitimacy == Legitimacy.HERETIC) {
            banditLabelOf(seatCount, legitimacy)
        } else {
            ""
        }
        val level = spineLevel(peerage, office, seatCount, legitimacy)
        return Rank(level, peerage, office, provinceId, central, bandit)
    }

    /**
     * 판정 순서(§3.4): `explicit` 이 enum 이름과 맞으면 그것 → 성향이 `태평도` 면 `HERETIC`
     * → 성향이 `도적` 이면 `BANDIT` → 그 외 `ORTHODOX`.
     *
     * **세력명이 아니라 성향(`typeCode`)으로 가른다.** 데이터에 이미 축이 있다 —
     * `태평도`(黃巾)·`오두미도`(五斗米道)·`도가`·`도적` 이 각각 별도 [NationTypeModule] 이다
     * (`logic/traits/ActionNationType.kt:86,108,244,350`). 시나리오 실측: `태평도` 1건
     * (1010 황건적), `도적` 11건, `오두미도` 11건(장로).
     *
     * **오두미도는 `BANDIT` 이 아니다**(§3.3). 종교세력이지만 실제 漢 관작을 보유했다 —
     * 유언의 督義司馬, 이후 漢寧太守·鎮民中郎將, 투항 후 鎮南將軍·閬中侯. 축은 종교 여부가
     * 아니라 **漢의 天命을 부정했는가**이고, 그 선을 넘은 것은 태평도뿐이다
     * (`data/corpus/hhs-071.txt:13` 「蒼天已死，黃天當立」).
     *
     * [nationName] 은 판정에 쓰지 않으나 호출부 로깅/진단용으로 남긴다.
     *
     * **패러티 아님 · han 전용**, 근거: 스펙 2026-08-19-nation-rank-three-axis.md §3.4.
     */
    fun legitimacyOf(nationName: String, typeCode: String, explicit: String? = null): Legitimacy {
        if (explicit != null) {
            Legitimacy.entries.firstOrNull { it.name == explicit }?.let { return it }
        }
        // typeCode 는 "태평도"(시나리오 원문) 또는 "che_태평도"(DB 저장형) 둘 다 온다.
        return when {
            typeCode.endsWith("태평도") -> Legitimacy.HERETIC
            typeCode.endsWith("도적") -> Legitimacy.BANDIT
            else -> Legitimacy.ORTHODOX
        }
    }
}
