package opensamguk.logic.world

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.NationTurn
import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.log.HistoryTokens
import opensamguk.logic.tick.MonthScopedRng
import opensamguk.logic.world.rank.Legitimacy
import opensamguk.logic.world.rank.NationRank
import kotlin.math.pow

/**
 * A3 — `UpdateNationLevel` (0-9 monotonic level engine). Faithful port of PHP frozen historical baseline (ADR-LITE-042; not current product authority)
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
    fun targetLevelByCityCnt(
        cityCnt: Int,
        thresholds: List<Int> = defaultCityThresholds,
    ): Int {
        var level = 0
        for ((cmpLevel, cmpCityCnt) in thresholds.withIndex()) {
            if (cityCnt < cmpCityCnt) break
            level = cmpLevel
        }
        return level
    }

    /** PHP 패러티 문턱(che 기본) — [GameConst.nationLevelByCityCnt09] 3번째 열. */
    val defaultCityThresholds: List<Int> =
        GameConst.nationLevelByCityCnt09.map { (it[2] as Number).toInt() }

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
            if (!cityConst.countsForNationLevel(level)) continue
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
    fun computeLevelUp(
        currentLevel: Int,
        cityCnt: Int,
        thresholds: List<Int> = defaultCityThresholds,
    ): LevelUp? {
        val target = targetLevelByCityCnt(cityCnt, thresholds)
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
     * the case-7 옹립 pattern with the new level names — so 8/9 emit the new 작위 템플릿 by the
     * existing arithmetic (no remap). The aux unlock also covers 8/9 (they inherit lv7's unlock).
     *
     * [titleUnlockLevel] 은 aux 국호/국기 해금 문턱이다. 기본 7 = PHP 패러티(case 7 이상)이라
     * che·기존 호출부는 완전 무변이고, han 만 `CityConstVariant.nationTitleUnlockLevel` = 5 를 넘긴다.
     */
    fun applyLevelUp(
        nation: Nation,
        lordName: String,
        year: Int,
        month: Int,
        levelUp: LevelUp,
        titleUnlockLevel: Int = 7,
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
        val updatedMeta: Map<String, Any?> = if (newLevel >= titleUnlockLevel) {
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

    // ── NL3: two-stage unique-item lottery (byte-match RNG split) ───────────────────────────────

    /**
     * PHP `:160-167` — `maxTrialCountByYear` walks [GameConst.maxUniqueItemLimit] (`[[targetYear,
     * targetTrialCnt], ...]`), `break`ing on the first `relYear < targetYear` and keeping the last
     * `targetTrialCnt` that passed. Starts at 1 (the PHP `$maxTrialCountByYear = 1` default).
     */
    fun maxTrialCountByYear(relYear: Int): Int {
        var maxTrial = 1
        for (row in GameConst.maxUniqueItemLimit) {
            val targetYear = row[0]
            val targetTrialCnt = row[1]
            if (relYear < targetYear) break
            maxTrial = targetTrialCnt
        }
        return maxTrial
    }

    /**
     * PHP `:175-184` — `score = belong + 10`; +60 if officer_level==12, +30 if ==11, +15 if >4;
     * `score *= 2 ** trialCnt`. `belong` rides `meta['belong']`.
     */
    fun lotteryScore(general: General, trialCnt: Int): Int {
        val belong = (general.meta["belong"] as? Number)?.toInt() ?: 0
        var score = belong + 10
        when {
            general.officerLevel == 12 -> score += 60
            general.officerLevel == 11 -> score += 30
            general.officerLevel > 4 -> score += 15
        }
        score *= 2.0.pow(trialCnt).toInt()
        return score
    }

    /**
     * The count of a general's already-owned NON-buyable (unique/rare) items — PHP `:170-173`
     * (`foreach getItems() as $item) if (!$item->isBuyable()) $trialCnt -= 1`).
     *
     * An item code is non-buyable iff its [GameConst.allItems] catalog cnt is `> 0` (the basic shop
     * items carry cnt 0 = buyable; the rares carry cnt > 0). 'None' (empty slot) is buyable. Iterates
     * the four equip slots (horse/weapon/book/item).
     */
    fun ownedNonBuyableCount(general: General): Int {
        val codes = listOf(
            "horse" to general.horse,
            "weapon" to general.weapon,
            "book" to general.book,
            "item" to general.item,
        )
        var count = 0
        for ((type, code) in codes) {
            if (code == "None") continue
            val cnt = GameConst.allItems[type]?.get(code) ?: continue
            if (cnt > 0) count++   // cnt>0 ⟺ !isBuyable (rare/unique)
        }
        return count
    }

    /**
     * The two-stage unique-item lottery (PHP `:134-222`). IO-free: the actual item grant is the
     * injected [giveRandomUniqueItem] seam (`func.php:1487` `giveRandomUniqueItem`, whose DB-wide
     * occupancy queries are a P6/infra concern). NL3 ports the byte-match-critical mechanics:
     *   1. eligibility (`killturn >= killturnEnv - 24*60/turnterm` FLOAT division; `npcType < 2`).
     *   2. per-general trialCnt = `min(maxTrialCountByYear(relYear), 4)` − owned non-buyables; skip ≤0.
     *   3. weight = [lotteryScore]; chief = the officer_level 12 general.
     *   4. stage-1 RNG = `MonthScopedRng.forNationLevelUp(hidden,y,m,nationID)`; loop `levelDiff`
     *      times: `choiceUsingWeightPair(weights)` → winner, UNSET it, derive the stage-2 RNG
     *      `MonthScopedRng.forGivenUnique(hidden,y,m,nationID,winnerID)`, call [giveRandomUniqueItem].
     *   5. chief `increaseInheritancePoint(unifier, 250*levelDiff)` (recorded as a delta — the P6
     *      inheritance-point persistence is a downstream seam).
     *
     * The eligible/weighted general ORDER is the input [generals] order (PHP query column order =
     * `general` PK ascending — the caller supplies generals in that order); `choiceUsingWeightPair`
     * over that ordered weight list is the byte-match draw.
     */
    fun runUniqueLottery(
        nationId: Int,
        year: Int,
        month: Int,
        startYear: Int,
        hiddenSeed: String,
        levelDiff: Int,
        killturnEnv: Int,
        turnterm: Int,
        generals: List<General>,
        giveRandomUniqueItem: (rng: RandUtil, winnerId: Int) -> Boolean,
    ): LotteryResult {
        // (1) eligibility — killturn cutoff (FLOAT division, NOT intdiv) + npc < 2.
        val cutoff = killturnEnv - (24.0 * 60.0 / turnterm)
        val eligible = generals.filter { g ->
            val killturn = (g.meta["killturn"] as? Number)?.toDouble() ?: Double.NEGATIVE_INFINITY
            killturn >= cutoff && g.npcType < 2
        }
        val eligibleIds = eligible.map { it.id }

        // (2)+(3) per-general trialCnt + weighted score; chief = the officer_level 12 general.
        val relYear = year - startYear
        val maxByYear = maxTrialCountByYear(relYear)
        var chiefId: Int? = null
        val weightPairs = ArrayList<Pair<Int, Int>>() // (generalId, score) — insertion order == draw order
        for (g in eligible) {
            if (g.officerLevel == 12) chiefId = g.id
            val trialCnt = minOf(maxByYear, GameConst.allItems.size) - ownedNonBuyableCount(g)
            if (trialCnt <= 0) continue
            weightPairs.add(g.id to lotteryScore(g, trialCnt))
        }

        // (4) two-stage RNG: stage-1 selection over the weight list, stage-2 per-winner item grant.
        val nationLevelUpRng = MonthScopedRng.forNationLevelUp(hiddenSeed, year, month, nationId)
        val remaining = ArrayList(weightPairs)
        val winners = ArrayList<Int>()
        val grants = ArrayList<Pair<Int, String>>() // (winnerId, givenUnique seed) — for the byte gate
        repeat(levelDiff) {
            if (remaining.isEmpty()) return@repeat
            val weights = remaining.map { it.first to it.second.toDouble() }
            val winnerId = nationLevelUpRng.choiceUsingWeightPair(weights)
            remaining.removeAll { it.first == winnerId } // unset the winner
            winners.add(winnerId)
            val givenUniqueRng = MonthScopedRng.forGivenUnique(hiddenSeed, year, month, nationId, winnerId)
            giveRandomUniqueItem(givenUniqueRng, winnerId)
            grants.add(winnerId to MonthScopedRng.givenUniqueSeed(hiddenSeed, year, month, nationId, winnerId))
        }

        // (5) chief inheritance-point delta (250 * levelDiff) — recorded, persistence downstream.
        val chiefDelta = if (chiefId != null) 250 * levelDiff else 0

        return LotteryResult(
            eligibleGeneralIds = eligibleIds,
            weightedGeneralIds = weightPairs.map { it.first },
            weightPairsInOrder = weightPairs,
            winnerIdsInOrder = winners,
            grants = grants,
            chiefId = chiefId,
            chiefInheritancePointDelta = chiefDelta,
        )
    }

    /**
     * The IO-free lottery outcome. [winnerIdsInOrder] are the per-iteration winners (each unset before
     * the next pick); [grants] pairs each winner with the `givenUnique` seed used for its item grant
     * (the stage-2 byte lineage). [chiefInheritancePointDelta] = `250 * levelDiff` (the P6 inheritance
     * write is downstream). [weightPairsInOrder] is the ordered `(generalId → score)` weight list the
     * stage-1 `choiceUsingWeightPair` drew over.
     */
    data class LotteryResult(
        val eligibleGeneralIds: List<Int>,
        val weightedGeneralIds: List<Int>,
        val weightPairsInOrder: List<Pair<Int, Int>>,
        val winnerIdsInOrder: List<Int>,
        val grants: List<Pair<Int, String>>,
        val chiefId: Int?,
        val chiefInheritancePointDelta: Int,
    )
}

/**
 * The world-context seam the daemon/G1 supplies for [UpdateNationLevelAction].
 */
interface UpdateNationLevelContext : EventActionContext {
    /** Active nations in ascending id order (PHP `SELECT * FROM nation WHERE level > 0 ORDER BY id`). */
    fun nations(): List<Nation>

    /** (cityId, nationId) for all cities. */
    fun cityOwnership(): List<Pair<Int, Int>>

    /** The active CityConst variant (F6 runtime selection). */
    fun cityConst(): CityConstVariant

    /** All generals in PK ascending order. */
    fun generals(): List<General>

    /** The hidden seed for month-scoped RNG derivation. */
    fun hiddenSeed(): String

    fun year(): Int
    fun month(): Int
    fun startYear(): Int

    /** The killturn baseline from game env. */
    fun killturnEnv(): Int

    fun turnterm(): Int

    /** The name of the officer_level==12 general of [nationId], or null if absent. */
    fun lordName(nationId: Int): String?

    /**
     * Applies the nation update, pushes global/national history logs (skip empty strings),
     * insertIgnore [nationTurnSeed].
     */
    fun applyNationLevelUp(effects: UpdateNationLevel.LevelUpEffects)

    /**
     * 3축 랭크 축(爵/官/천자)만 바뀌고 spine level 은 그대로인 경우의 meta 반영 seam.
     * level-up 이 없을 때도 爵(亭侯→鄉侯→縣侯)은 올라가므로 이 경로가 없으면 표시가 멈춘다.
     * che 에서는 호출되지 않는다.
     */
    fun applyNationRank(nation: Nation)

    /** The item grant seam; returns true if an item was granted. */
    fun giveRandomUniqueItem(rng: RandUtil, winnerId: Int): Boolean

    /** Applies the lottery results (inheritance point recording). */
    fun applyLotteryResult(nationId: Int, result: UpdateNationLevel.LotteryResult)
}

/**
 * The `UpdateNationLevel` leaf. Binds the GREEN pure core ([UpdateNationLevel]) to the live world
 * via [UpdateNationLevelContext].
 */
class UpdateNationLevelAction : EventAction {
    override fun run(ctx: EventActionContext) {
        val world = ctx as UpdateNationLevelContext
        val cc = world.cityConst()
        val ownership = world.cityOwnership()
        val cityCounts = UpdateNationLevel.cityCountsByNation(ownership, cc)
        // 3축 맵에서만 필요한 州별 郡治 분포. che 에서는 계산조차 하지 않는다.
        val seatsByNationProvince: Map<Int, Map<Int, Int>> =
            if (cc.supportsThreeAxisRank) seatsByNationProvince(ownership, cc) else emptyMap()
        val nationsWithAnyCity: Set<Int> =
            if (cc.supportsThreeAxisRank) ownership.asSequence().map { it.second }.toSet() else emptySet()

        for (nation in world.nations()) {
            val cityCnt = cityCounts[nation.id] ?: 0

            if (!cc.supportsThreeAxisRank) {
                // ── che (PHP 패러티) — 기존 경로 그대로. 호출 순서·인자·부수효과 순서 무변. ──
                val levelUp = UpdateNationLevel
                    .computeLevelUp(nation.level, cityCnt, cc.nationLevelCityThresholds)
                    ?: continue
                val lord = world.lordName(nation.id) ?: ""
                val effects = UpdateNationLevel.applyLevelUp(nation, lord, world.year(), world.month(), levelUp)
                world.applyNationLevelUp(effects)
                runLottery(world, nation.id, levelUp.levelDiff)
                continue
            }

            // ── han 3축(爵·官·天子) ──
            // 郡治가 0개인 호족과 도시 자체가 없는 방랑군을 구분한다.
            if (nation.level == 0 && nation.id !in nationsWithAnyCity) continue

            val seatsByProvince = seatsByNationProvince[nation.id] ?: emptyMap()
            val legitimacy = NationRank.legitimacyOf(
                nation.name,
                nation.typeCode,
                nation.meta["legitimacy"] as? String,
            )
            val holdsEmperor = nation.meta["holdsEmperor"].let { it == true || it == 1 || it == "1" }
            val rank = NationRank.compute(
                seatCount = cityCnt,
                seatsByProvince = seatsByProvince,
                totalSeatsByProvince = cc.seatCountByProvince,
                legitimacy = legitimacy,
                holdsEmperor = holdsEmperor,
            )
            val nationWithAxes = nation.copy(meta = withRankAxes(nation.meta, rank, legitimacy))

            // spine level 은 城 수 문턱이 아니라 3축 최댓값이 정한다. 단조 가드는 그대로.
            if (rank.level > nation.level) {
                val levelUp = UpdateNationLevel.LevelUp(nation.level, rank.level, rank.level - nation.level)
                val lord = world.lordName(nation.id) ?: ""
                val effects = UpdateNationLevel.applyLevelUp(
                    nationWithAxes, lord, world.year(), world.month(), levelUp, cc.nationTitleUnlockLevel,
                )
                world.applyNationLevelUp(effects)
                runLottery(world, nation.id, levelUp.levelDiff)
            } else if (nationWithAxes.meta != nation.meta) {
                world.applyNationRank(nationWithAxes)
            }
        }
    }

    private fun runLottery(world: UpdateNationLevelContext, nationId: Int, levelDiff: Int) {
        val lottery = UpdateNationLevel.runUniqueLottery(
            nationId = nationId,
            year = world.year(),
            month = world.month(),
            startYear = world.startYear(),
            hiddenSeed = world.hiddenSeed(),
            levelDiff = levelDiff,
            killturnEnv = world.killturnEnv(),
            turnterm = world.turnterm(),
            generals = world.generals(),
            giveRandomUniqueItem = { rng: RandUtil, winnerId: Int -> world.giveRandomUniqueItem(rng, winnerId) },
        )
        world.applyLotteryResult(nationId, lottery)
    }

    /** 세력 → (州 → 그 세력이 가진 郡治 수). [UpdateNationLevel.cityCountsByNation] 과 같은 필터를 쓴다. */
    private fun seatsByNationProvince(
        cityOwnership: List<Pair<Int, Int>>,
        cityConst: CityConstVariant,
    ): Map<Int, Map<Int, Int>> {
        val out = LinkedHashMap<Int, LinkedHashMap<Int, Int>>()
        for ((cityId, nationId) in cityOwnership) {
            val city = cityConst.byId(cityId) ?: continue
            if (!cityConst.countsForNationLevel(city.level)) continue
            val perProvince = out.getOrPut(nationId) { LinkedHashMap() }
            perProvince[city.region] = (perProvince[city.region] ?: 0) + 1
        }
        return out
    }

    /** 3축 결과를 meta 에 얹는다 — 기존 키 삽입 순서 보존, 있으면 제자리 덮어쓰기(PHP 대입 의미론). */
    private fun withRankAxes(
        meta: Map<String, Any?>,
        rank: NationRank.Rank,
        legitimacy: Legitimacy,
    ): Map<String, Any?> {
        val out = LinkedHashMap(meta)
        out["rankPeerage"] = rank.peerage.name
        out["rankProvincialOffice"] = rank.provincialOffice.name
        out["rankProvinceId"] = rank.provinceId
        out["rankCentralOffice"] = rank.centralOffice.name
        if (rank.banditLabel.isNotEmpty()) out["rankBanditLabel"] = rank.banditLabel
        out["legitimacy"] = legitimacy.name
        return out
    }
}
