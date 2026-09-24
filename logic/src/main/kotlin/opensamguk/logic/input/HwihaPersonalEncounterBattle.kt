package opensamguk.logic.input

/** A lone general fights the hostile commanders encountered on a march, without inventing a bugok. */
object HwihaPersonalEncounterBattle {
    const val RULE_VERSION = 1

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
        while (attackerHealth > 0 && defenderHealth > 0 && rounds < 8) {
            rounds++
            defenderHealth -= (power(attacker) / 9 - power(defender) / 24).coerceAtLeast(4)
            if (defenderHealth > 0)
                attackerHealth -= (power(defender) / 9 - power(attacker) / 24 + 3).coerceAtLeast(4)
        }
        val outcome = when {
            defenderHealth <= 0 -> Outcome.WON
            attackerHealth <= 0 && (attacker.morale <= 40 || attacker.injury >= 50) -> Outcome.CAPTURED
            else -> Outcome.RETREATED
        }
        return Result(outcome, rounds, attackerHealth.coerceAtLeast(0), defenderHealth.coerceAtLeast(0),
            defender.generalId, (attacker.injury + (attackerStart - attackerHealth.coerceAtLeast(0)) / 5).coerceAtMost(100),
            (defender.injury + (defenderStart - defenderHealth.coerceAtLeast(0)) / 6).coerceAtMost(100),
            (attacker.fatigue + rounds * 3).coerceAtMost(100),
            (attacker.morale + if (outcome == Outcome.WON) 5 else -rounds * 4).coerceIn(0, 100))
    }

    private fun power(fighter: Fighter): Int =
        (fighter.strength * 2 + fighter.leadership + fighter.morale / 4 -
            fighter.fatigue / 2 - fighter.injury).coerceAtLeast(10)
}
