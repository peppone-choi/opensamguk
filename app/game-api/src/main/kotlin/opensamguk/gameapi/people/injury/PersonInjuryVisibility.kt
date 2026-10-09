package opensamguk.gameapi.people.injury

import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralRetainerReadEntity

/** Ordinary gameplay injury reads are private to an owned body and its single direct retainer cards. */
internal object PersonInjuryVisibility {
    fun audience(actor: GeneralReadEntity?, userId: Long?, worldId: Int?, people: List<GeneralReadEntity>,
                 cards: List<GeneralRetainerReadEntity>?): Set<Int> {
        if (actor == null || actor.id <= 0 || userId == null || userId <= 0 || actor.userId?.toLongOrNull() != userId ||
            worldId == null || worldId <= 0 || actor.worldId != worldId ||
            people.any { it.worldId != worldId } || cards?.any { it.worldId != worldId } == true) return emptySet()
        val direct = cards.orEmpty().filter { it.generalId != null }.groupBy { it.generalId }
            .filterValues { it.size == 1 && it.single().masterGeneralId == actor.id }.keys.filterNotNull()
        return direct.toSet() + actor.id
    }

    fun rate(person: GeneralReadEntity, audience: Set<Int>): Int? =
        person.injury.takeIf { person.id in audience && it in 0..100 }
}
