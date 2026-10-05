package opensamguk.gameapi.people

import opensamguk.gameapi.dto.DirectoryAptitudes
import opensamguk.gameapi.dto.DirectoryStats
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralRetainerReadEntity
import opensamguk.logic.input.Aptitude
import opensamguk.logic.input.LordStatus
import opensamguk.logic.input.PersonPolicyState

/** Pure projections. Callers must establish ownership and world identity first. */
internal object PersonDetailProjection {
    const val SELF = "SELF"
    const val RETINUE = "RETINUE"
    const val SAME_NATION = "SAME_NATION"
    const val OTHER = "OTHER"
    const val UNKNOWN = "UNKNOWN"

    /**
     * SELF → the actor's direct RETINUE card → same positive nation → OTHER. Nation 0 is never shared.
     * Duplicate cards for one general cannot decide the relation, so it stays UNKNOWN (never guessed as RETINUE).
     */
    fun relation(actor: GeneralReadEntity, target: GeneralReadEntity, targetCards: List<GeneralRetainerReadEntity>): String = when {
        target.id == actor.id -> SELF
        targetCards.size > 1 -> UNKNOWN
        targetCards.singleOrNull()?.masterGeneralId == actor.id -> RETINUE
        actor.nationId > 0 && target.nationId == actor.nationId -> SAME_NATION
        else -> OTHER
    }

    /** Same public rule as the people directory: a verified person policy and five non-negative abilities. */
    fun stats(g: GeneralReadEntity): DirectoryStats? {
        val policy = runCatching { PersonPolicyState.read(g.meta) }.getOrNull()
        val values = listOf(g.leadership, g.strength, g.intel, g.politics, g.charm)
        return if (policy != null && values.all { it >= 0 }) DirectoryStats(g.leadership, g.strength, g.intel, g.politics, g.charm) else null
    }

    fun aptitudes(stats: DirectoryStats?): DirectoryAptitudes? = stats?.let {
        runCatching { Aptitude.compute(Aptitude.Stats(it.leadership, it.strength, it.intel, it.politics, it.charm)) }
            .getOrNull()?.let { a -> DirectoryAptitudes(a.command, a.administration, a.strategy, a.envoy) }
    }

    /** LORD from the persisted lord flag, RETAINER when bound to a master card, else FREE; malformed state is unknown. */
    fun role(g: GeneralReadEntity, masterGeneralId: Int?): String? = runCatching {
        when { LordStatus.read(g.meta) -> "LORD"; masterGeneralId != null -> "RETAINER"; else -> "FREE" }
    }.getOrNull()

    /** Injury is a 0..100 rate; only its presence is exposed. It is not a recovery countdown. */
    fun injured(g: GeneralReadEntity): Boolean? = g.injury.takeIf { it in 0..100 }?.let { it > 0 }
}
