package opensamguk.common.constants

/**
 * Scenario override layer + runtime-derived values.
 *
 * `effectiveConst = baseGameConst.mergeWith(scenario.config.const)`. Override replaces a scalar;
 * unspecified keys fall through to base GameConst. killturn/develcost are runtime-derived (CONFIRMED
 * source ResetHelper.php:264-267: killturn = 4800/turnterm, npcmode==1 -> intdiv(killturn,3);
 * develcost = (year-startyear+10)*2), NOT static — modeled as derived functions.
 *
 * NOTE: develCost must be RECOMPUTED per-turn (not cached once) in P1 — PHP recomputes it each turn
 * at func_gamerule.php:219.
 */
class EffectiveGameConst private constructor(private val override: ScenarioConstOverride) {
    fun getInt(key: String): Int = override.int(key) ?: baseInt(key)
    fun getDouble(key: String): Double = override.double(key) ?: baseDouble(key)

    private fun baseInt(key: String): Int = when (key) {
        "maxTurn" -> GameConst.maxTurn
        "develrate" -> GameConst.develrate
        "upgradeLimit" -> GameConst.upgradeLimit
        "maxLevel" -> GameConst.maxLevel
        else -> error("unknown GameConst int key: $key")
    }

    private fun baseDouble(key: String): Double = when (key) {
        "sabotageDefaultProb" -> GameConst.sabotageDefaultProb
        else -> error("unknown GameConst double key: $key")
    }

    companion object {
        fun of(override: Map<String, Any?>): EffectiveGameConst = EffectiveGameConst(ScenarioConstOverride(override))

        /** ResetHelper.php:264-265: killturn = 4800/turnterm; npcmode==1 -> intdiv(killturn,3). */
        fun killturn(turnterm: Int, npcmode: Int): Int {
            val base = 4800 / turnterm
            return if (npcmode == 1) base / 3 else base
        }

        /** ResetHelper.php:267 / func_gamerule.php:219: develcost = (year-startyear+10)*2. */
        fun develcost(year: Int, startYear: Int): Int = (year - startYear + 10) * 2
    }
}
