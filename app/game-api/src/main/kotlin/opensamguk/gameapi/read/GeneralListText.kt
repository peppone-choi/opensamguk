package opensamguk.gameapi.read

/**
 * W3 GeneralList — PHP `func_converter.php`의 `getOfficerLevelText` / `getHonor` 충실 이식
 * (read-only DTO enrichment 전용; game-api ONLY — §7). 패러티 타깃은 반환 문자열 byte-동일성이다.
 *
 * `getDedLevelText`/`getBillByLevel`/`getExpLevel`/`getDedLevel`/`calcLeadershipBonus`는 이미
 * `:logic`(DomesticHelpers, OfficerLevelModule)에 이식돼 있어 여기서 중복 정의하지 않고 그대로 재사용한다.
 * 이 헬퍼는 아직 어디에도 이식되지 않은 두 함수만 담는다.
 */
object GeneralListText {

    /**
     * PHP `getOfficerLevelText($officerLevel, $nlevel=8)` (func_converter.php:522-565)의 충실 이식.
     *
     * 규칙:
     *   - officer_level 0..4면 nlevel을 0으로 강제(`if ($officerLevel >= 0 && $officerLevel <= 4) $nlevel = 0;`)
     *     → 재야/일반/종사/군사/태수 같은 하위 직위는 국가 레벨과 무관한 공통 명칭.
     *   - code = nlevel * 100 + officerLevel 로 키를 만들어 명칭 표를 조회.
     *   - 표에 없으면 '-'(PHP `?? '-'`).
     *
     * opensamguk 국가 레벨은 0..9로 확장(의도적 divergence — project_officer_ranks_extension)되었으나,
     * PHP 표는 nlevel 1..8만 정의한다. 표에 없는 (nlevel, officerLevel) 조합은 PHP와 동일하게 '-'로
     * 떨어지며, 이는 패러티 위반이 아니다(레거시 표에 없던 키 = '-'가 정답).
     *
     * 주: officerLevel 5..12는 nlevel을 키에 반영하므로, 호출부가 반드시 해당 장수 국가의 level을 넘긴다.
     */
    fun officerLevelText(officerLevel: Int, nationLevel: Int): String {
        // PHP: 0..4는 nlevel을 0으로 강제(국가 레벨 무관 공통 직위).
        val nlevel = if (officerLevel in 0..4) 0 else nationLevel
        val code = nlevel * 100 + officerLevel
        return OFFICER_LEVEL_TEXT[code] ?: "-"
    }

    /**
     * PHP `getHonor($experience)` (func_converter.php:612-629)의 충실 이식 — 경험치 구간별 명예 칭호.
     * 경계값/순서는 PHP elseif 사슬과 byte-동일(`< 640` 부터 그 이상은 '구세주').
     */
    fun honor(experience: Int): String = when {
        experience < 640 -> "전무"
        experience < 2560 -> "무명"
        experience < 5760 -> "신동"
        experience < 10240 -> "약간"
        experience < 16000 -> "평범"
        experience < 23040 -> "지역적"
        experience < 31360 -> "전국적"
        experience < 40960 -> "세계적"
        experience < 45000 -> "유명"
        experience < 51840 -> "명사"
        experience < 55000 -> "호걸"
        experience < 64000 -> "효웅"
        experience < 77440 -> "영웅"
        else -> "구세주"
    }

    /**
     * PHP `getOfficerLevelText`의 명칭 표(func_converter.php:525-564)를 그대로 옮긴 것.
     * 키 = nlevel * 100 + officerLevel. 8xx(nlevel 8)~1xx(nlevel 1) + 0..4(공통).
     */
    private val OFFICER_LEVEL_TEXT: Map<Int, String> = mapOf(
        812 to "군주", 811 to "참모", 810 to "제1장군", 809 to "제1모사",
        808 to "제2장군", 807 to "제2모사", 806 to "제3장군", 805 to "제3모사",

        712 to "황제", 612 to "왕",
        711 to "승상", 611 to "광록훈",
        710 to "표기장군", 610 to "좌장군",
        709 to "사공", 609 to "상서령",
        708 to "거기장군", 608 to "우장군",
        707 to "태위", 607 to "중서령",
        706 to "위장군", 606 to "전장군",
        705 to "사도", 605 to "비서령",

        512 to "공", 412 to "주목",
        511 to "광록대부", 411 to "태사령",
        510 to "안국장군", 410 to "아문장군",
        509 to "집금오", 409 to "낭중",
        508 to "파로장군", 408 to "호군",
        507 to "소부", 407 to "종사중랑",

        312 to "주자사", 212 to "군벌",
        311 to "주부", 211 to "참모",
        310 to "편장군", 210 to "비장군",
        309 to "간의대부", 209 to "부참모",

        112 to "영주", 12 to "두목",
        111 to "참모", 11 to "부두목",

        4 to "태수",
        3 to "군사",
        2 to "종사",
        1 to "일반",
        0 to "재야",
    )
}
