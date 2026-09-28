package opensamguk.logic.battle.realtime

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlin.math.abs

data class DuelRound(
    val index: Int,
    val challengerRoll: Int,
    val respondentRoll: Int,
    val challengerDamage: Int,
    val respondentDamage: Int,
    val challengerHealth: Int,
    val respondentHealth: Int,
)

data class DuelResult(
    val seed: Long,
    val challenger: GeneralStats,
    val respondent: GeneralStats,
    val accepted: Boolean,
    val rounds: List<DuelRound>,
    val winnerId: Int?,
    val moraleDeltas: Map<Int, Int>,
    val replayHash: String,
)

/** Personal encounters use this without a retinue; field battles may call it after an accepted offer. */
object TacticalDuel {
    private val rules get() = TacticalRules.CANON

    fun canOffer(challenger: GeneralStats): Boolean = challenger.strength >= rules.duelOfferMinimumStrength

    fun resolve(seed: Long, challenger: GeneralStats, respondent: GeneralStats,
                respondentHuman: Boolean, humanAccepted: Boolean? = null): DuelResult {
        require(challenger.id != respondent.id && canOffer(challenger))
        require(respondentHuman == (humanAccepted != null))
        val accepted = humanAccepted ?: (abs(challenger.strength - respondent.strength) <= rules.duelAiAcceptDifference)
        val rounds = mutableListOf<DuelRound>()
        var challengerHealth = challenger.strength + challenger.leadership
        var respondentHealth = respondent.strength + respondent.leadership
        if (accepted) {
            for (index in 0 until rules.duelRoundLimit) {
                if (challengerHealth == 0 || respondentHealth == 0) break
                val challengerRoll = roll(seed, challenger.id, respondent.id, index, 0)
                val respondentRoll = roll(seed, challenger.id, respondent.id, index, 1)
                val challengerDamage = damage(challenger, challengerRoll).coerceAtMost(respondentHealth)
                val respondentDamage = damage(respondent, respondentRoll).coerceAtMost(challengerHealth)
                challengerHealth -= respondentDamage
                respondentHealth -= challengerDamage
                rounds += DuelRound(index, challengerRoll, respondentRoll, challengerDamage,
                    respondentDamage, challengerHealth, respondentHealth)
                if (challengerHealth == 0 || respondentHealth == 0) break
            }
        }
        val winner = when {
            !accepted || challengerHealth == respondentHealth -> null
            challengerHealth > respondentHealth -> challenger.id
            else -> respondent.id
        }
        val morale = when (winner) {
            challenger.id -> mapOf(challenger.id to rules.duelWinnerMoraleBonus,
                respondent.id to -rules.duelLoserMoralePenalty)
            respondent.id -> mapOf(respondent.id to rules.duelWinnerMoraleBonus,
                challenger.id to -rules.duelLoserMoralePenalty)
            else -> emptyMap()
        }
        val hash = hash(seed, challenger, respondent, accepted, rounds, winner, morale)
        return DuelResult(seed, challenger, respondent, accepted, rounds, winner, morale, hash)
    }

    private fun damage(general: GeneralStats, roll: Int): Int =
        ((general.strength * rules.duelStrengthWeight + general.leadership * rules.duelLeadershipWeight + roll) /
            rules.duelDamageDivisor).coerceAtLeast(rules.duelMinimumDamage)

    private fun roll(seed: Long, challengerId: Int, respondentId: Int, round: Int, side: Int): Int {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeLong(seed); out.writeInt(challengerId); out.writeInt(respondentId)
            out.writeInt(round); out.writeInt(side)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
        return (digest[0].toInt() and 255) % rules.duelRollRange
    }

    private fun hash(seed: Long, challenger: GeneralStats, respondent: GeneralStats, accepted: Boolean,
                     rounds: List<DuelRound>, winnerId: Int?, morale: Map<Int, Int>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1); out.writeLong(seed)
            listOf(challenger, respondent).forEach { general ->
                out.writeInt(general.id); out.writeInt(general.leadership); out.writeInt(general.strength)
                out.writeInt(general.intelligence); out.writeInt(general.politics); out.writeInt(general.charisma)
            }
            out.writeBoolean(accepted); out.writeInt(rounds.size)
            rounds.forEach { round ->
                out.writeInt(round.index); out.writeInt(round.challengerRoll); out.writeInt(round.respondentRoll)
                out.writeInt(round.challengerDamage); out.writeInt(round.respondentDamage)
                out.writeInt(round.challengerHealth); out.writeInt(round.respondentHealth)
            }
            out.writeInt(winnerId ?: -1)
            out.writeInt(morale.size)
            morale.toSortedMap().forEach { (id, delta) -> out.writeInt(id); out.writeInt(delta) }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
