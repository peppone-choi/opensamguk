package opensamguk.logic.war

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.GameUnitDetail
import opensamguk.logic.domain.General
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.util.phpRound

/**
 * The MUTABLE working copy of the immutable [General] — the Immer-draft replacement PHP `WarUnitGeneral`
 * gets for free by holding a mutable `$general` object. Every battle mutation rides this holder; the
 * resolver (B1) records [snapshot] as a ChangeRecorder DELTA (never an inline DB write).
 *
 * Float accumulators (rice/experience/dedication/dex) match PHP `increaseVar`'s no-per-add-round contract —
 * they truncate/round ONLY where the PHP path does (finishBattle's rice→exp→ded `Util::round`).
 *
 * `crew`/`atmos`/`train`/`injury` and the `rank_*` battle counters mirror the PHP general columns/meta the
 * battle mutates. The dex accumulator rides `meta['dex{armType}']` (P2 convention, [GameUnitConst.T_CASTLE]
 * folds to [GameUnitConst.T_SIEGE]).
 */
class WarUnitGeneralState(initial: General) {
    var general: General = initial
        private set

    var crew: Int = initial.crew
        private set
    var gold: Double = initial.gold.toDouble()
        private set
    var rice: Double = initial.rice.toDouble()
        private set
    var atmos: Double = initial.atmos
        private set
    var train: Double = initial.train
        private set
    var injury: Int = initial.injury
        private set
    var experience: Double = initial.experience
        private set
    var dedication: Double = initial.dedication
        private set

    private var item: String = initial.item

    /** dex accumulators keyed by armType (1..5), seeded from meta['dex{armType}']. */
    private val dex: MutableMap<Int, Double> = run {
        val m = LinkedHashMap<Int, Double>()
        for (armType in 1..5) m[armType] = metaDouble(initial.meta, "dex$armType")
        m
    }

    private val initialRank: Map<String, Int> = RANK_KEYS.associateWith {
        (initial.meta[it] as? Number)?.toInt() ?: 0
    }

    private val rank: MutableMap<String, Int> = run {
        val m = LinkedHashMap<String, Int>()
        for (k in RANK_KEYS) m[k] = initialRank.getValue(k)
        m
    }

    // --- mutators (PHP increaseVar / increaseVarWithLimit / multiplyVarWithLimit / updateVar) -----------

    fun increaseCrew(delta: Int) { crew += delta }

    fun increaseGold(delta: Double) { gold += delta }
    /** PHP `increaseVarWithLimit('gold', delta, 0)` — lower-clamped (약탈발동 theft). */
    fun increaseGoldWithLimit(delta: Double, min: Double) { gold = maxOf(min, gold + delta) }

    fun increaseRice(delta: Double) { rice += delta }

    fun increaseRiceWithLimit(delta: Double, min: Double) {
        rice = maxOf(min, rice + delta)
    }

    fun increaseAtmosWithLimit(delta: Double, min: Double, max: Double) {
        atmos = maxOf(min, minOf(max, atmos + delta))
    }

    /** PHP `increaseVarWithLimit('train', delta, min, max)` — the WarUnitGeneral.addTrain mutator (`:81-84`). */
    fun increaseTrainWithLimit(delta: Double, min: Double, max: Double) {
        train = maxOf(min, minOf(max, train + delta))
    }

    fun setInjury(value: Int) { injury = value }

    fun increaseInjuryWithLimit(delta: Int, max: Int) {
        injury = minOf(max, injury + delta)
    }

    fun addExperience(delta: Double) { experience += delta }
    fun addDedication(delta: Double) { dedication += delta }

    fun multiplyAtmosWithLimit(factor: Double, max: Double) {
        atmos = minOf(max, atmos * factor)
    }

    /** PHP `addDex($crewType, $exp, false)` — affectTrainAtmos=false; CASTLE→SIEGE; armType<0 no-op. */
    fun addDex(crewType: GameUnitDetail, exp: Double) {
        var armType = crewType.armType
        if (armType == GameUnitConst.T_CASTLE) armType = GameUnitConst.T_SIEGE
        if (armType < 0) return
        var e = exp
        if (armType == GameUnitConst.T_WIZARD || armType == GameUnitConst.T_SIEGE) e *= 0.9
        dex[armType] = (dex[armType] ?: 0.0) + e
    }

    /** PHP `getDex($crewType)` — raw accumulator (NO iAction fold; the WarUnit applies the fold). CASTLE→SIEGE. */
    fun getDex(crewType: GameUnitDetail): Double {
        var armType = crewType.armType
        if (armType == GameUnitConst.T_CASTLE) armType = GameUnitConst.T_SIEGE
        return dex[armType] ?: 0.0
    }

    fun increaseRank(name: String, delta: Int) { rank[name] = (rank[name] ?: 0) + delta }
    fun getRank(name: String): Int = rank[name] ?: 0
    fun rankIncrements(): Map<String, Int> = rank.mapValues { (key, value) -> value - initialRank.getValue(key) }
        .filterValues { it != 0 }

    fun increaseMetaExp(key: String, delta: Int) {
        val cur = metaDouble(general.meta, key)
        general = general.copy(meta = withMetaLinked(general.meta, key to cur + delta))
    }

    fun deleteItem() { item = "None" }
    fun getItem(): String = item

    // --- finishBattle rounds (half-away Util::round, in PHP order) --------------------------------------

    fun roundRice() { rice = phpRound(rice).toDouble() }
    fun roundExperience() { experience = phpRound(experience).toDouble() }
    fun roundDedication() { dedication = phpRound(dedication).toDouble() }

    /**
     * Materialize the immutable [General] delta for the recorder. Carries the battle-mutated crew/rice/
     * atmos/train/injury/experience/dedication/item + the dex accumulators + the rank counters back onto
     * `meta` (insertion-ordered; delete-on-default handled by the recorder).
     */
    fun snapshot(): General {
        var meta = general.meta
        for ((armType, v) in dex) meta = withMetaLinked(meta, "dex$armType" to v)
        return general.copy(
            crew = crew,
            gold = phpRound(gold),
            rice = phpRound(rice),
            atmos = atmos,
            train = train,
            injury = injury,
            experience = experience,
            dedication = dedication,
            item = item,
            meta = meta,
        )
    }

    private fun withMetaLinked(meta: Map<String, Any?>, pair: Pair<String, Any?>): Map<String, Any?> {
        val next = LinkedHashMap(meta); next[pair.first] = pair.second; return next
    }

    companion object {
        /** The RankColumn names the battle path touches (PHP Enums/RankColumn.php cases). */
        val RANK_KEYS: List<String> = listOf(
            "warnum", "killnum", "deathnum", "occupied",
            "killcrew", "deathcrew", "killcrew_person", "deathcrew_person",
        )
    }
}
