package opensamguk.gameapi.read

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
}
