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
    /** `nation.level` (page header 등급). */
    val level: Int,
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

/** `/api/rankings/npcs` — generals WHERE `npc_state = 1` (악령 gallery), total-aptitude DESC, tie id ASC. No rank field. */
data class NpcGeneral(
    val generalId: Int,
    val name: String,
    val nation: String,
    val nationColor: String,
    val officerLevel: Int,
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val experience: Int,
    /** `devotion` = `general.dedication`. */
    val devotion: Int,
    val crew: Int,
    /** `city.name` of `general.city_id`, or "" if 0/missing. */
    val cityName: String,
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
