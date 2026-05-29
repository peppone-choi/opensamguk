package opensamguk.logic.world

import opensamguk.common.constants.GameConst
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.NationTurn
import opensamguk.logic.log.HistoryTokens

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

    // ── NL2: per-level-up side-effect sequence (order load-bearing) ─────────────────────────────

    /** The 0-9 작위 name for [level] (PHP `getNationLevel`; column 0 of the F7 0-9 table). */
    fun levelText(level: Int): String = GameConst.nationLevelByCityCnt09[level][0] as String

    /**
     * Apply ONE nation's level-up side-effect sequence (PHP `:69-130`), returning a structured,
     * IO-free [LevelUpEffects] the daemon flushes (the same single-dirty-source contract the rest
     * of the monthly pipeline uses).
     *
     * The PHP ORDER is reproduced exactly (the G1 log-sequence gate target):
     *   1. `level=new`; `gold += newLevel*1000`; `rice += newLevel*1000`.
     *   2. resolve `lordName` (caller-supplied — PHP `SELECT name ... officer_level=12`),
     *      `oldNationLevelText`/`nationLevelText`.
     *   3. switch(newLevel): global + national 작위 history strings (HistoryTokens, F7); case 7/8/9
     *      ALSO set aux `can_국기변경=1`/`can_국호변경=1` (`meta['aux']`, insertion order preserved);
     *      levels 0/1 emit NO log (empty string).
     *   4. nation update (carried on the returned [LevelUpEffects.nation]).
     *   5. logger flush (the history strings are the flushable payload).
     *   6. nation_turn 휴식 seed: `chiefLevel in getNationChiefLevel(NEW level)..11` (`Util::range(a,
     *      12)` is HALF-OPEN) × `turnIdx 0..11` (`Util::range(maxChiefTurn=12)` → 0..11). insertIgnore.
     *
     * **8/9 (the APPEND):** HistoryTokens.nationLevelUp{Global,National} already routes 7/8/9 through
     * the case-7 옹립 pattern with the new level names — so 8/9 emit the new 작위 templates by the
     * existing arithmetic (no remap). The aux unlock also covers 8/9 (they inherit lv7's unlock).
     */
    fun applyLevelUp(
        nation: Nation,
        lordName: String,
        year: Int,
        month: Int,
        levelUp: LevelUp,
    ): LevelUpEffects {
        val newLevel = levelUp.newLevel
        val oldLevel = levelUp.oldLevel
        val grant = newLevel * 1000

        // (3) history strings + aux unlock. PHP switch: 7/8/9 → 옹립 + aux; 6 → 책봉; 5/4/3 → 임명;
        // 2 → 독립; 0/1 → no log. The HistoryTokens helpers return "" for the no-case levels.
        val oldText = levelText(oldLevel)
        val newText = levelText(newLevel)
        val globalLog = HistoryTokens.nationLevelUpGlobal(newLevel, nation.name, lordName, oldText, newText)
        val nationalLog = HistoryTokens.nationLevelUpNational(newLevel, nation.name, lordName, oldText, newText)

        // (1)+(3 aux): build the updated nation (level/gold/rice + aux unlock at 7/8/9).
        val updatedMeta: Map<String, Any?> = if (newLevel >= 7) {
            unlockChiefAux(nation.meta)
        } else {
            nation.meta
        }
        val updatedNation = nation.copy(
            level = newLevel,
            gold = nation.gold + grant,
            rice = nation.rice + grant,
            meta = updatedMeta,
        )

        // (6) nation_turn 휴식 seed for the REASSIGNED new level's chief range.
        val chiefStart = GameConst.getNationChiefLevel(newLevel) // never null for 0..9
        val turnSeed = ArrayList<NationTurn>()
        for (chiefLevel in chiefStart until 12) {                // Util::range(chiefStart, 12) half-open
            for (turnIdx in 0 until GameConst.maxChiefTurn) {    // Util::range(12) → 0..11
                turnSeed.add(
                    NationTurn(
                        nationId = nation.id,
                        officerLevel = chiefLevel,
                        turnIdx = turnIdx,
                        action = "휴식",
                        arg = null,
                        brief = "휴식",
                    )
                )
            }
        }

        return LevelUpEffects(
            nation = updatedNation,
            goldDelta = grant,
            riceDelta = grant,
            globalHistoryLog = globalLog,
            nationalHistoryLog = nationalLog,
            nationTurnSeed = turnSeed,
        )
    }

    /**
     * PHP `:107-111` — `$auxVal = Json::decode($nation['aux']); $auxVal['can_국기변경']=1;
     * $auxVal['can_국호변경']=1;`. In opensamguk `aux` rides `meta['aux']` (no dedicated column), so
     * this is a read-modify-write of the nested aux map, preserving the EXISTING key insertion order
     * (the byte-comparable jsonb source) and appending the two flags (or overwriting in place if
     * already present — PHP assignment semantics).
     */
    private fun unlockChiefAux(meta: Map<String, Any?>): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val oldAux = meta["aux"] as? Map<String, Any?> ?: emptyMap()
        val newAux = LinkedHashMap(oldAux)
        newAux["can_국기변경"] = 1
        newAux["can_국호변경"] = 1
        val newMeta = LinkedHashMap(meta)
        newMeta["aux"] = newAux
        return newMeta
    }

    /**
     * The IO-free result of one nation's level-up. The daemon applies [nation] (the level/gold/rice/
     * aux update), pushes [globalHistoryLog]/[nationalHistoryLog] (skipping empties), and
     * insertIgnore-s [nationTurnSeed]. [goldDelta]/[riceDelta] echo the `level*1000` grant for the
     * income/treasury bookkeeping.
     */
    data class LevelUpEffects(
        val nation: Nation,
        val goldDelta: Int,
        val riceDelta: Int,
        val globalHistoryLog: String,
        val nationalHistoryLog: String,
        val nationTurnSeed: List<NationTurn>,
    )
}
