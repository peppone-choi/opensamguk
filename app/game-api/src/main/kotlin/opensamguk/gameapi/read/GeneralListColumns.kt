package opensamguk.gameapi.read

import opensamguk.gameapi.dto.GeneralListRow

/**
 * W3 GeneralList — PHP `API/Nation/GeneralList.php`의 `$viewColumns` + `$customViewColumns` 순서·권한
 * tier·remap을 그대로 옮긴 **컬럼 계약**. `column` 키 순서가 곧 `list` 각 행의 셀 순서다(W9 CityList의
 * `cityArgsList` 계약과 동형).
 *
 * PHP 컬럼 결정 로직(GeneralList.php:279-299):
 *   1. viewColumns를 선언 순서대로 순회 — reqPermission > 호출자 permission 이면 skip.
 *   2. columnRemap에 있으면 새 키로(`special`→`specialDomestic`, `special2`→`specialWar`,
 *      `refresh_score(_total)`→camel, `aux`→null=출력 안 함).
 *   3. customViewColumns를 선언 순서대로 순회 — 동일 권한 필터.
 *
 * §2 BLOCKED(opensamguk 원천 부재 → **컬럼 자체를 노출하지 않음**, fabricate 금지):
 *   - dex1..dex5            : general 컬럼/meta 미존재.
 *   - defence_train         : 원천 미검증.
 *   - refresh_score(_total) : general_access_log 테이블 부재.
 *   - autorun_limit         : aux 컬럼/meta 미존재.
 *   PHP `$viewColumns`/`$customViewColumns`엔 있으나 위 키들은 본 목록에서 생략한다. (ownerName은
 *   컬럼으로는 유지하되 값이 항상 null — owner_name 원천 부재.)
 *   `aux`(remap→null)와 `owner_name`(reqPermission 9, 우리 permission 최대 2이라 영원히 숨김)도 PHP와
 *   동일하게 출력 컬럼에서 제외된다.
 */
object GeneralListColumns {

    /** rank_data 일괄 조회용 전투-통계 type 목록(RankColumn backing value와 byte-동일). */
    val RANK_TYPES: List<String> =
        listOf("warnum", "killnum", "deathnum", "killcrew", "deathcrew", "firenum")

    /**
     * (컬럼 키, 필요 권한). PHP `$viewColumns`(remap 적용·BLOCKED 생략) + `$customViewColumns`를 한 줄로
     * 이어붙인 **선언 순서**. 호출부는 `reqPermission <= permission`만 추려 column 목록을 만든다.
     *
     * 순서는 패러티 타깃 — PHP 선언 순서를 절대 재배열하지 않는다.
     */
    private val COLUMN_ORDER: List<Pair<String, Int>> = listOf(
        // ── viewColumns (P0) ───────────────────────────────────────────────────
        "no" to 0,
        "name" to 0,
        "nation" to 0,
        "npc" to 0,
        "injury" to 0,
        "leadership" to 0,
        "strength" to 0,
        "intel" to 0,
        "explevel" to 0,
        "dedlevel" to 0,
        "gold" to 0,
        "rice" to 0,
        "killturn" to 0,
        "picture" to 0,
        "imgsvr" to 0,
        "age" to 0,
        "specialDomestic" to 0, // remap: special → specialDomestic
        "specialWar" to 0,      // remap: special2 → specialWar
        "personal" to 0,
        "belong" to 0,
        "troop" to 0,
        "city" to 0,
        "specage" to 0,
        "specage2" to 0,
        // ── viewColumns (P1) ───────────────────────────────────────────────────
        "leadership_exp" to 1,
        "strength_exp" to 1,
        "intel_exp" to 1,
        // dex1..dex5 — §2 BLOCKED, 생략.
        "experience" to 1,
        "dedication" to 1,
        "officer_level" to 1,
        "officer_city" to 1,
        // defence_train — §2 BLOCKED, 생략.
        "crewtype" to 1,
        "crew" to 1,
        "train" to 1,
        "atmos" to 1,
        "turntime" to 1,
        "horse" to 1,
        "weapon" to 1,
        "book" to 1,
        "item" to 1,
        "recent_war" to 1,
        // aux(remap→null) / owner_name(reqPerm 9) — PHP도 출력 안 함, 생략.
        // refresh_score_total / refresh_score — §2 BLOCKED, 생략.
        // RANK(P1)
        "warnum" to 1,
        "killnum" to 1,
        "deathnum" to 1,
        "killcrew" to 1,
        "deathcrew" to 1,
        "firenum" to 1,
        // ── customViewColumns ──────────────────────────────────────────────────
        "officerLevel" to 0,
        "officerLevelText" to 0,
        "lbonus" to 0,
        "ownerName" to 0,
        "honorText" to 0,
        "dedLevelText" to 0,
        "bill" to 0,
        "reservedCommand" to 1,
        // autorun_limit — §2 BLOCKED, 생략.
    )

    /** 호출자 권한으로 필터된 컬럼 키 목록(선언 순서 보존). */
    fun columnsForPermission(permission: Int): List<String> =
        COLUMN_ORDER.filter { (_, req) -> req <= permission }.map { it.first }

    /**
     * 한 행의 한 컬럼 셀 값을 추출(PHP specialViewFilter + 평탄화). column 키 → 행 필드 매핑.
     * BLOCKED 키는 COLUMN_ORDER에 없어 호출되지 않는다(방어적으로 null 반환).
     *
     * `officerLevel`(custom 컬럼): PHP getOfficerLevel(GeneralList.php:133-143) — level>=5 또는 호출자
     * permission>=1 이면 raw, 아니면 1로 마스킹. 따라서 호출자 `permission`을 인자로 받는다.
     */
    fun cell(row: GeneralListRow, column: String, permission: Int): Any? = when (column) {
        // viewColumns(P0)
        "no" -> row.no
        "name" -> row.name
        "nation" -> row.nation
        "npc" -> row.npc
        "injury" -> row.injury
        "leadership" -> row.leadership
        "strength" -> row.strength
        "intel" -> row.intel
        "explevel" -> row.explevel
        "dedlevel" -> row.dedlevel
        "gold" -> row.gold
        "rice" -> row.rice
        "killturn" -> row.killturn
        "picture" -> row.picture
        "imgsvr" -> row.imgsvr
        "age" -> row.age
        "specialDomestic" -> row.specialDomestic
        "specialWar" -> row.specialWar
        "personal" -> row.personal
        "belong" -> row.belong
        "troop" -> row.troop
        "city" -> row.city
        "specage" -> row.specage
        "specage2" -> row.specage2
        // viewColumns(P1)
        "leadership_exp" -> row.leadershipExp
        "strength_exp" -> row.strengthExp
        "intel_exp" -> row.intelExp
        "experience" -> row.experience
        "dedication" -> row.dedication
        "officer_level" -> row.officerLevel
        "officer_city" -> row.officerCity
        "crewtype" -> row.crewtype
        "crew" -> row.crew
        "train" -> row.train
        "atmos" -> row.atmos
        "turntime" -> row.turntime
        "horse" -> row.horse
        "weapon" -> row.weapon
        "book" -> row.book
        "item" -> row.item
        "recent_war" -> row.recentWar
        // RANK
        "warnum" -> row.warnum
        "killnum" -> row.killnum
        "deathnum" -> row.deathnum
        "killcrew" -> row.killcrew
        "deathcrew" -> row.deathcrew
        "firenum" -> row.firenum
        // customViewColumns
        "officerLevel" -> getOfficerLevel(row.officerLevel, permission)
        "officerLevelText" -> row.officerLevelText
        "lbonus" -> row.lbonus
        "ownerName" -> row.ownerName
        "honorText" -> row.honorText
        "dedLevelText" -> row.dedLevelText
        "bill" -> row.bill
        "reservedCommand" -> row.reservedCommand
        else -> null
    }

    /**
     * PHP `getOfficerLevel`(GeneralList.php:133-143)의 충실 이식 — 행의 officer_level을 호출자 권한에 따라
     * 마스킹. `level>=5`(수뇌)면 그대로, `permission>=1`(관직자 이상 호출자)이면 그대로, 둘 다 아니면 1.
     * (officerLevel custom 컬럼 + officerLevelText 둘 다 이 마스킹된 레벨을 쓴다.)
     */
    fun getOfficerLevel(officerLevel: Int, permission: Int): Int = when {
        officerLevel >= 5 -> officerLevel
        permission >= 1 -> officerLevel
        else -> 1
    }
}
