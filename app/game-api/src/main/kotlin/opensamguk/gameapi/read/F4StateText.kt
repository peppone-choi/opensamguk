package opensamguk.gameapi.read

import opensamguk.common.constants.GameConst

/**
 * F4 — shared READ-only state-text / permission projection helper (the tiny Tier-0 the F4 read
 * controllers consume; spec `2026-06-03-F4-action-pages-spec.md` build_order Wave-B "Shared
 * DTO/serializer helpers build FIRST").
 *
 * Pure functions, no Spring/DB. Every Korean label here is reproduced byte-for-byte from the legacy
 * PHP/Vue (locked rule 4 — verbatim Korean parity); the comments cite where.
 */
object F4StateText {

    /** 재야 nation name when nationId == 0 (PHP `재야` neutral join, mirrors RankReadService). */
    const val NEUTRAL_NATION_NAME = "재야"

    /** 재야 / neutral color `#000000` (mirrors RankReadService). */
    const val NEUTRAL_NATION_COLOR = "#000000"

    /**
     * 외교 letter state text — verbatim from the legacy diplomacy letter Vue
     * (`제안됨`/`승인됨`/`거부됨`/`대체됨`). The DB enum is
     * `diplomacy_letter_state { PROPOSED, ACTIVATED, CANCELLED, REPLACED }`.
     */
    fun letterStateText(state: String): String = when (state.uppercase()) {
        "PROPOSED" -> "제안됨"
        "ACTIVATED" -> "승인됨"
        "CANCELLED" -> "거부됨"
        "REPLACED" -> "대체됨"
        else -> state
    }

    /**
     * permission tier from officer_level — mirrors the precheck `GeneralResolver` projection
     * (officer_level >= 5 → 2 수뇌(showSecret); >= 1 국가소속(일반); 0 → 0 재야/방랑).
     * Used by board/troop/chief permission gates.
     */
    fun permissionFromOfficerLevel(officerLevel: Int): Int = when {
        officerLevel >= 5 -> 2
        officerLevel >= 1 -> 1
        else -> 0
    }

    /**
     * 회의실 / 기밀실 board title — verbatim. `secret == true` → 기밀실, else 회의실.
     */
    fun boardTitle(secret: Boolean): String = if (secret) "기밀실" else "회의실"

    /**
     * The 8 nation chief posts and their officer levels (lv 12 down to 5), legacy `getNationOfficerLevelText`
     * order. The labels are the canonical 직책 names; the page renders the post grid in this exact order.
     */
    val CHIEF_POSTS: List<ChiefPostMeta> = listOf(
        ChiefPostMeta(12, "군주"),
        ChiefPostMeta(11, "참모"),
        ChiefPostMeta(10, "종사"),
        ChiefPostMeta(9, "별가"),
        ChiefPostMeta(8, "치중"),
        ChiefPostMeta(7, "장사"),
        ChiefPostMeta(6, "주부"),
        ChiefPostMeta(5, "정로장군"),
    )

    data class ChiefPostMeta(val officerLevel: Int, val title: String)

    /**
     * 역사 PHP `getOfficerLevelText($officerLevel, $nlevel)` 비교를 보존한 동결 회귀(ADR-LITE-042; 현재 제품 정본 아님) —
     * 국가 레벨(`nlevel`)별 직책 한글명. officer_level 0..4는 국가 레벨과 무관(`nlevel=0`으로 강제)하므로
     * 항상 재야/일반/종사/군사/태수. 코드 = `nlevel*100 + officerLevel`. 미정의 코드는 '-'.
     *
     * ChiefCenter 칸의 `officerLevelText`(PHP `getOfficerLevelText(officer_level, nationLevel)`)에 쓴다.
     * 라벨은 PHP 테이블에서 byte-for-byte 그대로(locked rule 3 — 한글 패러티).
     */
    fun officerLevelText(officerLevel: Int, nationLevel: Int = 8): String {
        // PHP: officer_level 0..4 → nlevel 0 강제(국가 레벨 무관 공통 직책).
        val nlevel = if (officerLevel in 0..4) 0 else nationLevel
        val code = nlevel * 100 + officerLevel
        return OFFICER_LEVEL_TEXT[code] ?: "-"
    }

    /**
     * `getOfficerLevelText` 룩업 테이블 — func_converter.php:525-564 그대로(코드 → 직책명).
     * 국가 레벨 8(제국)부터 1(영주국)까지의 12/11/…/5 직책 + 공통 0..4.
     */
    private val OFFICER_LEVEL_TEXT: Map<Int, String> = linkedMapOf(
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

    /**
     * GetConst 직렬화용 — 국가레벨 무관 기본 직책 맵(officerLevel → 한글명). 와이어 모양은 동결된
     * 역사 프론트 참고 `hwe/ts/utilGame/formatOfficerLevelText.ts`와 동일(ADR-LITE-042; 현재 제품 정본 아님):
     * nlevel=8 기본열(코드 812..805 = 군주/참모/제1장군/…/제3모사) + 공통 0..4(태수/군사/종사/일반/재야).
     * 데이터 원천은 위 [OFFICER_LEVEL_TEXT] 단일 테이블(PHP func_converter.php:522-565) — 별도 사본 금지.
     */
    fun officerLevelTextDefault(): Map<Int, String> {
        val out = linkedMapOf<Int, String>()
        for (lv in 12 downTo 5) OFFICER_LEVEL_TEXT[800 + lv]?.let { out[lv] = it }
        for (lv in 4 downTo 0) OFFICER_LEVEL_TEXT[lv]?.let { out[lv] = it }
        return out
    }

    /**
     * GetConst 직렬화용 — 국가레벨(7..0)별 수뇌(officerLevel 12..5) 직책 맵. 와이어 모양은
     * `formatOfficerLevelText.ts`의 `OfficerLevelMapByNationLevel`과 동일. PHP 테이블에 없는 코드
     * (예: 506)는 키 자체를 생략한다 — 동결된 역사 PHP 기준은 '-'를 돌려주고(TS는 기본열 폴백),
     * 이 ADR-LITE-042 회귀 범위에서 해당 동작을 보존한다. PHP가 현재 제품 정본이라는 뜻은 아니다.
     * 데이터 원천은 [OFFICER_LEVEL_TEXT] 단일 테이블.
     */
    fun officerLevelTextByNationLevel(): Map<Int, Map<Int, String>> {
        val out = linkedMapOf<Int, Map<Int, String>>()
        for (nlevel in 7 downTo 0) {
            val inner = linkedMapOf<Int, String>()
            for (lv in 12 downTo 5) OFFICER_LEVEL_TEXT[nlevel * 100 + lv]?.let { inner[lv] = it }
            if (inner.isNotEmpty()) out[nlevel] = inner
        }
        return out
    }

    /**
     * 사령부 명령 팔레트의 카테고리 → 명령 코드 목록. PHP `GameConst::$availableChiefCommand`
     * (GameConstBase.php:378-415) byte-for-byte. ChiefCenter `commandList`(=`getChiefCommandTable`)의
     * 정본 순서/카테고리 원천 — 컨트롤러가 각 코드를 CommandRegistry로 풀어 표시 메타를 만든다.
     */
    val CHIEF_COMMAND_TABLE: List<Pair<String, List<String>>> =
        GameConst.availableChiefCommand.map { (category, commands) -> category to commands }

    /**
     * 토너먼트 type → display text. Verbatim from `b_tournament.php` switch
     * (`전력전`/`통솔전`/`일기토`/`설전`); tnmt_type is the legacy `convertTournamentType` int.
     */
    fun tournamentTypeText(tnmtType: Int): String = when (tnmtType) {
        0 -> "전력전"
        1 -> "통솔전"
        2 -> "일기토"
        3 -> "설전"
        else -> "전력전"
    }

    /** The 4 ranking-type labels rendered on the tournament/betting bracket page (verbatim, fixed order). */
    val RANKING_TYPES: List<String> = listOf("전력전", "통솔전", "일기토", "설전")

    /**
     * 역사 PHP `getHonor($experience)` 비교를 보존한 동결 회귀(ADR-LITE-042; 현재 제품 정본 아님) — 경험치 구간별 명성 한글명.
     * 경계값/라벨 모두 byte-for-byte 그대로(locked rule 3 — 한글 패러티). FrontGeneralInfo.honorText에 쓴다.
     * `experience`는 실 컬럼이므로 파생값 계산은 날조가 아니다.
     */
    fun honorText(experience: Int): String = when {
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
}
