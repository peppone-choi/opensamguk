package opensamguk.logic.input

import opensamguk.logic.content.PersonBondState
import opensamguk.logic.content.PersonBond
import opensamguk.logic.content.PersonBondKind

object OathBonds {
    const val META_KEY = PersonBondState.META_KEY
    fun read(meta: Map<String, Any?>): Set<Int> {
        return PersonBondState.read(meta)?.bonds.orEmpty()
            .filter { it.kind == PersonBondKind.OATH }
            .map { bond -> bond.targetId.removePrefix("general:").toIntOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("Invalid oath bond") }.toSet()
    }
    fun withBond(meta: Map<String, Any?>, id: Int): Map<String, Any?> {
        require(id > 0)
        val current = PersonBondState.read(meta)?.bonds.orEmpty()
        val next = PersonBondState(current + PersonBond(PersonBondKind.OATH, "general:$id"))
        return meta + (META_KEY to next.toMetaValue())
    }
}
