package opensamguk.logic.input

/** A lone general fights the hostile commanders encountered on a march, without inventing a bugok. */
object PersonalEncounterBattle {
    const val RULE_VERSION = 1
    private val design get() = PersonalEncounterDesign.CANON

    data class Fighter(val generalId: Int, val leadership: Int, val strength: Int,
        val injury: Int, val fatigue: Int, val morale: Int) {
        init {
            require(generalId > 0 && leadership in 0..100 && strength in 0..100)
            require(injury in 0..100 && fatigue in 0..100 && morale in 0..100)
        }
    }

    enum class Outcome { WON, RETREATED, CAPTURED }
    data class Result(val outcome: Outcome, val rounds: Int, val attackerRemaining: Int,
        val defenderRemaining: Int, val defenderGeneralId: Int, val attackerInjury: Int,
        val defenderInjury: Int, val attackerFatigue: Int, val attackerMorale: Int)

    /** The strongest available commander answers the challenge; ties use general id. No RNG is consumed. */
    fun resolve(attacker: Fighter, defenders: List<Fighter>): Result {
        require(defenders.isNotEmpty() && defenders.none { it.generalId == attacker.generalId })
        val defender = defenders.sortedWith(compareByDescending<Fighter> { power(it) }.thenBy { it.generalId }).first()
        val attackerStart = (100 - attacker.injury).coerceAtLeast(1)
        val defenderStart = (100 - defender.injury).coerceAtLeast(1)
        var attackerHealth = attackerStart
        var defenderHealth = defenderStart
        var rounds = 0
        while (attackerHealth > 0 && defenderHealth > 0 && rounds < design.roundLimit) {
            rounds++
            defenderHealth -= (power(attacker) / design.attackDivisor -
                power(defender) / design.defenseDivisor).coerceAtLeast(design.damageFloor)
            if (defenderHealth > 0)
                attackerHealth -= (power(defender) / design.attackDivisor -
                    power(attacker) / design.defenseDivisor + design.defenderAdvantage).coerceAtLeast(design.damageFloor)
        }
        val outcome = when {
            defenderHealth <= 0 -> Outcome.WON
            attackerHealth <= 0 && (attacker.morale <= design.captureMoraleAtMost ||
                attacker.injury >= design.captureInjuryAtLeast) -> Outcome.CAPTURED
            else -> Outcome.RETREATED
        }
        return Result(outcome, rounds, attackerHealth.coerceAtLeast(0), defenderHealth.coerceAtLeast(0),
            defender.generalId, (attacker.injury + (attackerStart - attackerHealth.coerceAtLeast(0)) /
                design.attackerInjuryDivisor).coerceAtMost(100),
            (defender.injury + (defenderStart - defenderHealth.coerceAtLeast(0)) /
                design.defenderInjuryDivisor).coerceAtMost(100),
            (attacker.fatigue + rounds * design.fatiguePerRound).coerceAtMost(100),
            (attacker.morale + if (outcome == Outcome.WON) design.moraleOnWin
                else -rounds * design.moraleLossPerRound).coerceIn(0, 100))
    }

    private fun power(fighter: Fighter): Int =
        (fighter.strength * design.strengthWeight + fighter.leadership * design.leadershipWeight +
            fighter.morale / design.moraleDivisor - fighter.fatigue / design.fatigueDivisor -
            fighter.injury * design.injuryWeight).coerceAtLeast(design.powerFloor)
}
