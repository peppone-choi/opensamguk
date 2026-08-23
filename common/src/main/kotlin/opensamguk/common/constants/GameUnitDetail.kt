package opensamguk.common.constants

/**
 * Faithful port of `legacy/devsam-core/hwe/sammo/GameUnitDetail.php` (18-field positional tuple).
 *
 * attackCoef/defenceCoef = Map<Int, Double> mixing armType ints 0-6 AND specific unit-ids like
 * 1106/1100; lookup resolves specific-id before armType (see [resolveAttackCoef]/[resolveDefenceCoef]).
 * `info` is the final resolved list: base info[] with each constraint's getInfo() appended (per
 * GameUnitConstBase::_generate() lines 445-448).
 */
data class GameUnitDetail(
    val id: Int,
    val armType: Int,
    val name: String,
    val attack: Int,
    val defence: Int,
    val speed: Int,
    val avoid: Int,
    val magicCoef: Double,
    val cost: Int,
    val rice: Int,
    val reqConstraints: List<UnitConstraint>,
    val attackCoef: Map<Int, Double>,
    val defenceCoef: Map<Int, Double>,
    val info: List<String>,
    val initSkillTrigger: List<String>?,
    val phaseSkillTrigger: List<String>?,
    val iActionList: List<String>?,
) {
    /** Resolve attack coefficient against a defender: specific unit-id wins over armType. */
    fun resolveAttackCoef(defenderId: Int, defenderArmType: Int): Double =
        attackCoef[defenderId] ?: attackCoef[defenderArmType] ?: 1.0

    /** Resolve defence coefficient against an attacker: specific unit-id wins over armType. */
    fun resolveDefenceCoef(attackerId: Int, attackerArmType: Int): Double =
        defenceCoef[attackerId] ?: defenceCoef[attackerArmType] ?: 1.0

    /**
     * PHP `getAttackCoef(GameUnitDetail $oppose)` (GameUnitDetail.php:130-138): specific oppose-id
     * wins over oppose armType; default 1. Thin wrapper over [resolveAttackCoef].
     */
    fun getAttackCoef(oppose: GameUnitDetail): Double = resolveAttackCoef(oppose.id, oppose.armType)

    /**
     * PHP `getDefenceCoef(GameUnitDetail $oppose)` (GameUnitDetail.php:140-148): specific oppose-id
     * wins over oppose armType; default 1. Thin wrapper over [resolveDefenceCoef].
     */
    fun getDefenceCoef(oppose: GameUnitDetail): Double = resolveDefenceCoef(oppose.id, oppose.armType)

    /**
     * PHP `getComputedAttack(General $general, int $tech)` (GameUnitDetail.php:150-173).
     *
     * mainstat by armType: WIZARD→intel, SIEGE→leadership, MISC→(intel+leadership+strength)*2/3 pre-scaled,
     * else strength. ratio = mainstat*2 - 40 (MISC uses the *2/3-40 form); `ratio<10⇒10` FLOOR FIRST,
     * then `ratio>100⇒50+ratio/2` soft cap. att = base attack + getTechAbil(tech); result = att*ratio/100.
     *
     * The iAction-flagged stat resolution (getStrength(true,true,true) etc.) is the caller's (F2 WarUnit)
     * job; this takes the already-resolved values so :common stays free of :logic.
     */
    fun getComputedAttack(strength: Double, intel: Double, leadership: Double, tech: Int): Double {
        val ratio: Double = when (armType) {
            GameUnitConst.T_WIZARD -> intel * 2 - 40
            GameUnitConst.T_SIEGE -> leadership * 2 - 40
            GameUnitConst.T_MISC -> (intel + leadership + strength) * 2 / 3 - 40
            else -> strength * 2 - 40
        }.let { r ->
            var v = r
            if (v < 10) v = 10.0
            if (v > 100) v = 50 + v / 2
            v
        }
        val att = attack + getTechAbil(tech)
        return att * ratio / 100
    }

    /**
     * PHP `getComputedDefence(General $general, int $tech)` (GameUnitDetail.php:175-180).
     *
     * def = base defence + getTechAbil(tech); crewFactor = crew/(7000/30) + 70 (FLOAT division);
     * result = def * crewFactor / 100.
     */
    fun getComputedDefence(crew: Int, tech: Int): Double {
        val def = defence + getTechAbil(tech)
        val crewFactor = crew / (7000.0 / 30.0) + 70.0
        return def * crewFactor / 100
    }

    /**
     * PHP `getCriticalRatio(General $general)` (GameUnitDetail.php:182-213).
     *
     * T_CASTLE→0 (성벽은 필살 미사용). mainstat by armType: WIZARD→intel coef0.4, SIEGE→leadership coef0.4,
     * MISC→avg-of-3 coef0.4, else strength coef0.5. ratio = valueFit(mainstat-65, 0) * coef;
     * return min(50, ratio)/100.
     */
    fun getCriticalRatio(strength: Double, intel: Double, leadership: Double): Double {
        if (armType == GameUnitConst.T_CASTLE) return 0.0
        val mainstat: Double
        val coef: Double
        when (armType) {
            GameUnitConst.T_WIZARD -> { mainstat = intel; coef = 0.4 }
            GameUnitConst.T_SIEGE -> { mainstat = leadership; coef = 0.4 }
            GameUnitConst.T_MISC -> { mainstat = (intel + leadership + strength) / 3; coef = 0.4 }
            else -> { mainstat = strength; coef = 0.5 }
        }
        // Util::valueFit(mainstat-65, 0) = max(0.0, mainstat-65)
        val ratio = maxOf(0.0, mainstat - 65) * coef
        return minOf(50.0, ratio) / 100
    }
}

/**
 * Free functions ported from PHP `func_converter.php` so :common combat methods are self-contained
 * (PHP frozen historical baseline (ADR-LITE-042; not current product authority); do NOT reference :logic).
 */

/** func_converter.php:676-682 — getTechLevel = valueFit(floor(tech/1000), 0, maxTechLevel). */
private fun techLevel(tech: Int): Int {
    val raw = Math.floor(tech / 1000.0)
    return raw.coerceIn(0.0, GameConst.maxTechLevel.toDouble()).toInt()
}

/** func_converter.php:699-701 — getTechAbil = getTechLevel(tech) * 25. */
fun getTechAbil(tech: Int): Int = techLevel(tech) * 25

/** func_converter.php:703-705 — getTechCost = 1 + getTechLevel(tech) * 0.15. */
fun getTechCost(tech: Int): Double = 1.0 + techLevel(tech) * 0.15

/**
 * func_converter.php:712-741 — getDexLevelList thresholds. getDexLevel returns the LADDER INDEX
 * (the foreach `$dexLevel => [...]` key), 0..26, NOT the threshold value.
 */
private val dexLevelThresholds: IntArray = intArrayOf(
    0, 350, 1375, 3500, 7125, 12650, 20475, 31000, 44625, 61750, 82775, 108100, 138125,
    173250, 213875, 260400, 313225, 372750, 439375, 513500, 595525, 685850, 784875,
    893000, 1010625, 1138150, 1275975,
)

/** func_converter.php:762-775 — getDexLevel = highest ladder index whose threshold ≤ dex; dex<0→0. */
fun getDexLevel(dex: Int): Int {
    if (dex < 0) return 0
    var retVal = 0
    for (idx in dexLevelThresholds.indices) {
        if (dex < dexLevelThresholds[idx]) break
        retVal = idx
    }
    return retVal
}

/** func_converter.php:777-780 — getDexLog = (getDexLevel(d1) - getDexLevel(d2))/55 + 1 (divisor 55, +1 offset). */
fun getDexLog(dex1: Int, dex2: Int): Double = (getDexLevel(dex1) - getDexLevel(dex2)) / 55.0 + 1.0
