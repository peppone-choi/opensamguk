package opensamguk.gameapi.dto

/**
 * F3 — rankings read API DTOs (spec `2026-06-02-F3-rankings-spec.md`).
 *
 * Each data class is a CLIENT CONTRACT: the field names below are the exact camelCase JSON keys the
 * matching `web/game/app/game/rankings/<board>/page.tsx` TypeScript interface consumes. Do NOT rename —
 * the page renders rows in array order and treats `rank`/`id` as pre-assigned. game-api assembles these
 * purely from already-flushed read rows (general/nation/city) — never a game-state write.
 */

/** `/api/rankings/best-generals` — total-aptitude board (`total = leadership+strength+intel` DESC, tie id ASC). */
data class BestGeneral(
    val rank: Int,
    val generalId: Int,
    val name: String,
    /** Nation name, or "재야" for nationId 0. */
    val nation: String,
    /** Nation hex color, or "#000000" for nationId 0. */
    val nationColor: String,
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val politics: Int, // 정치/매력 (RTK14 divergence)
    val charm: Int, // 정치/매력 (RTK14 divergence)
    val total: Int,
)

/** `/api/rankings/generals` — flat all-general list (default order `experience` DESC; the page re-sorts client-side). */
data class GeneralRank(
    val rank: Int,
    val generalId: Int,
    val name: String,
    val nation: String,
    val nationColor: String,
    /** Raw `general.officer_level` (page renders the number verbatim — no remap). */
    val officerLevel: Int,
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val politics: Int, // 정치/매력 (RTK14 divergence)
    val charm: Int, // 정치/매력 (RTK14 divergence)
    val experience: Int,
    /** `devotion` = `general.dedication` (충성). */
    val devotion: Int,
    val crew: Int,
)

/** `/api/rankings/kingdoms` — nations (id≠0) by `power` proxy DESC, tie id ASC. */
data class KingdomRank(
    val rank: Int,
    val nationId: Int,
    val name: String,
    /** Nation hex color, verbatim from `nation.color`. */
    val color: String,
    /** `nation.level` (raw, 정렬/로직용). */
    val level: Int,
    /** 등급 한글명 = getNationLevelList()[level][0] (방랑군..천자). page 등급 컬럼에 raw 숫자 대신 표시. */
    val levelText: String,
    val gold: Int,
    val rice: Int,
    /** SUM(city.pop) over the nation's cities (no `nation.pop` column). */
    val pop: Long,
    /** Live `countByNationId(general)`. */
    val genNum: Int,
    /** OQ-3 proxy = SUM(general.crew) over the nation's generals (page header 병력). */
    val power: Long,
    /** Live `countByNationId(city)`. */
    val cityCount: Int,
    /** `city.name` of `nation.capital_city_id`, or "" if unset/missing. */
    val capitalName: String,
)

/**
 * `/api/rankings/npcs` — 빙의일람(a_npcList.php, fid 35). `npc_state = 1`(악령) gallery.
 *
 * 필드 집합은 legacy `a_npcList.php`의 12컬럼 그대로 이식:
 *   희생된 장수(name, NPC색) | 악령 이름(ownerName) | 레벨(explevel) | 국가 | 성격(personalText)
 *   | 특기(specialDomesticName / specialWarName) | 종능(total) | 통솔 | 무력 | 지력
 *   | 명성(experience raw) | 계급(dedication raw).
 * PHP는 명성/계급 컬럼에 raw experience/dedication 숫자를 그대로 출력한다(honor/dedLevelText 텍스트 아님 —
 * `<?= $general['experience'] ?>`, `<?= $general['dedication'] ?>`). 라벨만 명성/계급이고 값은 raw.
 *
 * 정렬 8종(PHP `$sortType`): 1 name asc · 2 nation asc · 3 sum desc · 4 leadership desc · 5 strength desc
 * · 6 intel desc · 7 experience desc · 8 dedication desc. SPA는 클라이언트 재정렬(page).
 *
 * BLOCKED(원천 부재 → 날조 금지, quarantine):
 *  - `ownerName`(악령 이름) = `general.owner_name` 컬럼이 opensamguk 스키마에 없다(GeneralListController도
 *    동일 사유로 ownerName=null 고정). 항상 null로 노출하고 FE는 "-"로 렌더한다.
 *
 * `crew`/`cityName`은 PHP a_npcList에 없는 잉여 필드지만 기존 계약(테스트)을 깨지 않기 위해 유지하고 FE는
 * 렌더하지 않는다(non-PHP 컬럼 제거 = page 렌더 단의 책임). No rank field(PHP도 순위 미부여).
 */
data class NpcGeneral(
    val generalId: Int,
    val name: String,
    /** NPC 타입(`general.npc_state`) — FE가 getNPCColor로 이름 색을 칠하는 데 사용(formatName 동치). */
    val npc: Int,
    val nation: String,
    val nationColor: String,
    val officerLevel: Int,
    /** 악령 이름 = `general.owner_name`. **BLOCKED**(컬럼 부재) → 항상 null, FE는 "-". */
    val ownerName: String?,
    /** 레벨(Lv) = stored `explevel`(meta) 또는 getExpLevel(experience) 재계산(GeneralList과 동일 규칙). */
    val explevel: Int,
    /** 성격 한글명 = `personalityNameOf(personal_code)`(None → '-'). */
    val personalText: String,
    /** 내정 특기 한글명 = `SpecialityHelper.domesticName(special_code)`(None → '-'). */
    val specialDomesticName: String,
    /** 전투 특기 한글명 = `SpecialityHelper.warName(special2_code)`(None → '-'). */
    val specialWarName: String,
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val politics: Int, // 정치/매력 (RTK14 divergence)
    val charm: Int, // 정치/매력 (RTK14 divergence)
    /** 종능 = leadership + strength + intel(PHP `leadership+strength+intel as sum`). */
    val total: Int,
    /** 명성 컬럼 값 = raw `general.experience`(PHP는 raw 숫자 출력). */
    val experience: Int,
    /** 계급 컬럼 값 = raw `general.dedication`(PHP는 raw 숫자 출력). `devotion` alias 유지. */
    val devotion: Int,
    val crew: Int,
    /** `city.name` of `general.city_id`, or "" if 0/missing. (non-PHP 잉여 필드 — FE 미렌더) */
    val cityName: String,
)

/**
 * `/api/rankings/kingdom-roster` — 세력일람(a_kingdomList.php, fid 15) ROSTER.
 *
 * `/api/rankings/kingdoms`(leaderboard)와 **별개 화면**. legacy `a_kingdomList.php`는 순위표가 아니라
 * 국가별 명부(성향/작위/국력 + officer_level 12~5 수뇌직책표 + 외교권자/조언자 + 속령 일람 + 장수 일람)와
 * 말미의 재야(nation 0) 섹션으로 구성된다.
 *
 * 정렬: 국가는 `power` DESC(PHP `uasort power <=>`), 각 국가의 장수는 `dedication` DESC(PHP
 * `ORDER BY dedication DESC`). 재야는 항상 마지막.
 *
 * lv8/9 작위 = 의도적 divergence(project_officer_ranks_extension) — 유지.
 */
data class KingdomRoster(
    /** 국력 DESC로 정렬된 국가 명부. */
    val nations: List<KingdomRosterNation>,
    /** 재야(nation 0) 섹션. 장수 없으면 generals 빈 배열. */
    val neutral: KingdomRosterNeutral,
)

/** 세력일람 국가 1개 카드. */
data class KingdomRosterNation(
    val nationId: Int,
    val name: String,
    /** `nation.color`(헤더 배경/대비색 원천 — FE에서 newColor 대비 계산). */
    val color: String,
    /**
     * 성향(nation type) 한글명. **BLOCKED**(nation type → 한글명 헬퍼가 :common/:logic에 미이식,
     * 이 번들 disjoint 범위에서 추가 불가) → raw `type_code` 노출, FE는 그대로 표시. 날조 금지.
     */
    val typeCode: String,
    /** 작위 = `nation.level`(raw, 정렬/로직). */
    val level: Int,
    /** 작위 한글명 = `getNationLevel(level)`(방랑군..황제; lv8/9는 divergence). */
    val levelText: String,
    /** 국력 = `nation.power`(PHP `$nation['power']` 직접 — leaderboard의 SUM(crew) proxy와 다름). */
    val power: Int,
    /** 장수 수 = `nation.gennum` 동치(countByNationId). */
    val genNum: Int,
    /** 속령 수 = count(city). */
    val cityCount: Int,
    /** officer_level 12→5 수뇌 직책표(8칸). 빈 직책은 name="-"/npc=0. PHP `$chiefs[$chiefLevel] ?? '-'`. */
    val chiefs: List<KingdomRosterChief>,
    /**
     * 외교권자 명단(checkSecretPermission==4). **BLOCKED**(general.permission 컬럼 부재 → 4/3 산출 불가)
     * → 항상 빈 배열. FE는 공란. 날조 금지.
     */
    val ambassadors: List<String>,
    /**
     * 조언자 수(checkSecretPermission==3). **BLOCKED**(동일 사유) → 항상 0. FE는 "0명".
     */
    val auditorCount: Int,
    /** 속령 일람(수도 cyan 강조용 capitalCityId 제공). PHP 속령 일람 행 — id ASC. */
    val cities: List<KingdomRosterCity>,
    /** 수도 city id(속령 일람에서 cyan 강조). 미설정 시 null. */
    val capitalCityId: Int?,
    /** 장수 일람 = dedication DESC 전 장수(name, npc색). */
    val generals: List<KingdomRosterGeneral>,
)

/** 수뇌 직책 1칸(officerLevelText + 해당 직책 장수). */
data class KingdomRosterChief(
    /** 직책 한글명 = `getOfficerLevelText(chiefLevel, nationLevel)`. */
    val officerLevelText: String,
    /** 직책 장수 이름(공석이면 "-"). */
    val name: String,
    /** 직책 장수 npc 타입(공석이면 0) — FE getNPCColor. */
    val npc: Int,
)

/** 속령 일람 1개. */
data class KingdomRosterCity(
    val cityId: Int,
    val name: String,
)

/** 장수 일람 1개(이름 + npc색). */
data class KingdomRosterGeneral(
    val name: String,
    val npc: Int,
)

/** 재야(nation 0) 섹션. */
data class KingdomRosterNeutral(
    val genNum: Int,
    val cityCount: Int,
    val cities: List<KingdomRosterCity>,
    val generals: List<KingdomRosterGeneral>,
)

/**
 * `/api/rankings/hall-of-fame` — F3 default is an empty list (`hall` empty in the 1010 capture). The page
 * renders an empty table without error. NOT fabricated; see spec OQ-5.
 */
data class HallRecord(
    val id: Int,
    val category: String,
    val name: String,
    val nation: String,
    val nationColor: String,
    val value: Double,
    val valueLabel: String,
    val achievedAt: String,
    val turn: Int,
)

/**
 * `/api/rankings/traffic` — F3 zero-fill (no `general_access_log` / online-tracking infra; spec OQ-2).
 * All counters 0, `history` empty. This is an explicit "no data source yet" zero-fill, NOT fabricated.
 */
data class TrafficSummary(
    val todayUnique: Int,
    val todayViews: Int,
    val weekUnique: Int,
    val weekViews: Int,
    val monthUnique: Int,
    val monthViews: Int,
    val peakConcurrent: Int,
    val currentOnline: Int,
    val history: List<TrafficStat>,
)

data class TrafficStat(
    val date: String,
    val uniqueVisitors: Int,
    val pageViews: Int,
    val avgSessionMin: Int,
    val peakConcurrent: Int,
)

/**
 * `/api/rankings/emperor` — F3 default is an empty list (no `emperior`/unification-history table; spec OQ-1).
 * The page renders an empty table. NOT fabricated.
 */
data class EmperorRecord(
    val id: Int,
    val name: String,
    val nation: String,
    val nationColor: String,
    val unifiedAt: String,
    val turn: Int,
    val year: Int,
    val month: Int,
    val generalCount: Int,
    val cityCount: Int,
)
