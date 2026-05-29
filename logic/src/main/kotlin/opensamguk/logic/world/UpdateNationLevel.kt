package opensamguk.logic.world

import opensamguk.common.constants.GameConst

/**
 * A3 — `UpdateNationLevel` (0-9 monotonic level engine). Faithful port of PHP grand truth
 * `legacy/devsam-core/hwe/sammo/Event/Action/UpdateNationLevel.php:26-225`.
 *
 * The Action runs every month (the F2-owned `month/1000` `true` row names it before
 * `ProvideNPCTroopLeader`). It (NL1) computes each nation's TARGET level from its `LEVEL>=4`
 * city-count, (NL2) applies the per-level-up side-effect sequence (gold/rice grant + 작위 history
 * + aux lv7 unlock + nation_turn 휴식 seed), and (NL3) runs the two-stage unique-item lottery.
 *
 * **0-9 = APPEND** (plan §"Nation-level 0-9 decision"): levels 0..7 byte-match the legacy 8-entry
 * `nationLevelByCityCnt` (`UpdateNationLevel.php:41-50`); levels 8/9 are the two NEW tiers at the
 * thresholds 28/36 frozen in [GameConst.nationLevelByCityCnt09] (F7). The threshold walk, the
 * monotonic guard (`target>current`), and `levelDiff` math all extend by the existing arithmetic.
 *
 * NL1 is a pure target-computation core (no IO); NL2/NL3 extend this file with the side-effect
 * sequence + lottery (still pure — they emit a structured result the daemon flushes).
 */
object UpdateNationLevel {

    // ── NL1: 0-9 threshold walk ───────────────────────────────────────────────────────────────

    /**
     * PHP `:52-66` — the highest `cmpNationLevel` whose `cmpCityCnt <= cityCnt`.
     *
     * Iterates the [GameConst.nationLevelByCityCnt09] thresholds (column 2 of each `[name, chief,
     * cityCnt]` row) and `break`s on the first `cityCnt < cmpCityCnt`, keeping the last index that
     * passed. With the 0-based table starting at threshold 0 the loop always yields at least 0.
     */
    fun targetLevelByCityCnt(cityCnt: Int): Int {
        var level = 0
        for ((cmpLevel, row) in GameConst.nationLevelByCityCnt09.withIndex()) {
            val cmpCityCnt = (row[2] as Number).toInt()
            if (cityCnt < cmpCityCnt) break
            level = cmpLevel
        }
        return level
    }

    /**
     * PHP `SELECT nation, count(*) FROM city WHERE LEVEL>=4 GROUP BY nation` (`:34-37`).
     *
     * [cityOwnership] is the live `(cityId → nationId)` ownership; the LEVEL is the MAP/CityConst
     * STATIC level resolved from the active [cityConst] (F6), NOT the nation level. Cities the
     * active CityConst does not know (no `byId`) or whose map level is `<4` are excluded — exactly
     * the rows the PHP `WHERE LEVEL>=4` filters out (an absent/low row is simply not counted).
     *
     * NL5 confirms the semantics: lv4 ('이' = 이민족) cities owned by a Han nation DO count toward
     * its level (the legacy query is a literal `LEVEL>=4`, no 한족/이민족 filter).
     */
    fun cityCountsByNation(
        cityOwnership: List<Pair<Int, Int>>,
        cityConst: CityConstVariant,
    ): Map<Int, Int> {
        val counts = LinkedHashMap<Int, Int>()
        for ((cityId, nationId) in cityOwnership) {
            val level = cityConst.byId(cityId)?.level ?: continue
            if (level < 4) continue
            counts[nationId] = (counts[nationId] ?: 0) + 1
        }
        return counts
    }

    /**
     * The monotonic level-up decision for ONE nation (PHP `:60-66`).
     *
     * Returns null when the target does not exceed the current level (the PHP `if ($nationlevel >
     * $nation['level'])` guard — never demotes, never re-acts at the same level). Otherwise carries
     * the old/new level + `levelDiff` (which drives the NL3 lottery iteration count + the NL2
     * `level*1000` grant).
     */
    fun computeLevelUp(currentLevel: Int, cityCnt: Int): LevelUp? {
        val target = targetLevelByCityCnt(cityCnt)
        if (target <= currentLevel) return null
        return LevelUp(oldLevel = currentLevel, newLevel = target, levelDiff = target - currentLevel)
    }

    /** A single nation's monotonic level-up (PHP `$levelDiff = $nationlevel - $nation['level']`). */
    data class LevelUp(val oldLevel: Int, val newLevel: Int, val levelDiff: Int)
}
