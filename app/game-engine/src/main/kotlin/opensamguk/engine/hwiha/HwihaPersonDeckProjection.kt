package opensamguk.engine.hwiha

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.content.HwihaCommonStratagemCards
import opensamguk.logic.content.HwihaPersonContributionState
import opensamguk.logic.input.HwihaStratagemCardType

/** Current direct NPC holdings only. Nested cards contribute to their immediate holder. */
object HwihaPersonDeckProjection {
    fun forHolder(world: InMemoryTurnWorld, holderId: Int): Map<String, HwihaStratagemCardType> {
        val catalogue = HwihaCommonStratagemCards.headers.entries.associate { it.value.id to it.key }
        val contributions = linkedMapOf<String, HwihaStratagemCardType>()
        for (card in world.listRetainers().filter { it.masterGeneralId == holderId && it.generalId != null }.sortedBy { it.id }) {
            val person = requireNotNull(world.getGeneralById(card.generalId!!)) { "dangling held person card" }
            if (person.npcState != 2 || !HwihaNpcDeploySelector.isUnowned(person.userId)) continue
            val ids = HwihaPersonContributionState.read(person.meta)?.stratagemCardIds.orEmpty()
            for (id in ids.sorted()) {
                val type = requireNotNull(catalogue[id]) { "unknown person stratagem contribution: $id" }
                require(contributions.put("${person.id}:$id", type) == null) { "duplicate held person card" }
            }
        }
        return contributions
    }
}
