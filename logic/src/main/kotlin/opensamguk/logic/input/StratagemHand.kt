package opensamguk.logic.input

import java.util.Collections

enum class StratagemCardType { FORTIFY, INSIGHT }

/** A stable instance contributed by a direct NPC person card. Negative IDs cannot collide with the base four. */
data class ContributedStratagemInstance(val sourceKey: String, val instanceId: Int, val type: StratagemCardType) {
    init { require(sourceKey.isNotBlank() && instanceId < 0) }
}

/** Base four instances stay compatible with v1 metadata; v2 tracks owned NPC contributions. */
class StratagemHand(
    val ownerGeneralId: Int,
    hand: List<Int>,
    drawPile: List<Int>,
    discard: List<Int>,
    val lastDrawPhase: Phase,
    extras: List<ContributedStratagemInstance> = emptyList(),
) {
    val hand: List<Int> = Collections.unmodifiableList(ArrayList(hand))
    val drawPile: List<Int> = Collections.unmodifiableList(ArrayList(drawPile))
    val discard: List<Int> = Collections.unmodifiableList(ArrayList(discard))
    val extras: List<ContributedStratagemInstance> = Collections.unmodifiableList(ArrayList(extras))

    init {
        require(ownerGeneralId > 0 && this.hand.size <= HAND_LIMIT)
        require(this.extras.map { it.sourceKey }.distinct().size == this.extras.size)
        require(this.extras.map { it.instanceId }.distinct().size == this.extras.size)
        val all = this.hand + this.drawPile + this.discard
        val owned = setOf(1, 2, 3, 4) + this.extras.map { it.instanceId }
        require(all.size == owned.size && all.toSet() == owned) { "Cards must partition the owned instances" }
    }

    fun cardType(id: Int): StratagemCardType = when (id) {
        1, 3 -> StratagemCardType.FORTIFY
        2, 4 -> StratagemCardType.INSIGHT
        else -> extras.singleOrNull { it.instanceId == id }?.type ?: throw IllegalArgumentException("Unknown stratagem instance")
    }

    /** Reconcile the direct holder's current NPC cards before drawing; release removes their instances. */
    fun withContributions(desired: Map<String, StratagemCardType>): StratagemHand {
        require(desired.keys.none { it.isBlank() })
        if (extras.size == desired.size && extras.all { desired[it.sourceKey] == it.type }) return this
        val retained = extras.filter { desired[it.sourceKey] == it.type }
        val retainedKeys = retained.mapTo(hashSetOf()) { it.sourceKey }
        val removedIds = extras.filter { it.sourceKey !in retainedKeys }.mapTo(hashSetOf()) { it.instanceId }
        var nextId = (extras.minOfOrNull { it.instanceId } ?: 0) - 1
        val added = desired.entries.filter { it.key !in retainedKeys }.sortedBy { it.key }.map { (key, type) ->
            ContributedStratagemInstance(key, nextId--, type)
        }
        return StratagemHand(ownerGeneralId, hand.filterNot { it in removedIds },
            drawPile.filterNot { it in removedIds } + added.map { it.instanceId },
            discard.filterNot { it in removedIds }, lastDrawPhase, retained + added)
    }

    fun advance(phase: Phase): StratagemHand {
        require(phase >= lastDrawPhase) { "Cannot draw in an older phase" }
        if (phase == lastDrawPhase) return this
        val pile = if (drawPile.isEmpty()) discard.sorted() else drawPile
        val remainingDiscard = if (drawPile.isEmpty()) emptyList() else discard
        val drawn = pile.first()
        return StratagemHand(ownerGeneralId, if (hand.size < HAND_LIMIT) hand + drawn else hand,
            pile.drop(1), if (hand.size < HAND_LIMIT) remainingDiscard else remainingDiscard + drawn, phase, extras)
    }

    fun consume(instanceId: Int): StratagemHand {
        require(instanceId in hand) { "Only an owned hand instance can be consumed" }
        return StratagemHand(ownerGeneralId, hand.filter { it != instanceId }, drawPile,
            discard + instanceId, lastDrawPhase, extras)
    }

    fun toMetaValue(): Map<String, Any> = linkedMapOf<String, Any>(
        "version" to if (extras.isEmpty()) 1 else 2,
        "ownerGeneralId" to ownerGeneralId,
        "hand" to hand,
        "drawPile" to drawPile,
        "discard" to discard,
        "lastDrawPhase" to lastDrawPhase.toMetaValue(),
    ).apply {
        if (extras.isNotEmpty()) put("extras", extras.map { mapOf(
            "sourceKey" to it.sourceKey, "instanceId" to it.instanceId, "type" to it.type.name) })
    }

    companion object {
        const val META_KEY = "hwihaStratagemHand"
        const val HAND_LIMIT = 3
        private val V1_FIELDS = setOf("version", "ownerGeneralId", "hand", "drawPile", "discard", "lastDrawPhase")

        fun initial(owner: Int, phase: Phase) =
            StratagemHand(owner, listOf(1, 2), listOf(3, 4), emptyList(), phase)

        fun read(meta: Map<String, Any?>, ownerGeneralId: Int): StratagemHand? {
            require(ownerGeneralId > 0)
            if (META_KEY !in meta) return null
            val row = meta[META_KEY] as? Map<*, *> ?: invalid()
            val version = row["version"] as? Int ?: invalid()
            require((version == 1 && row.keys == V1_FIELDS) ||
                (version == 2 && row.keys == V1_FIELDS + "extras")) { "Invalid stratagem hand fields" }
            val owner = row["ownerGeneralId"] as? Int ?: invalid()
            require(owner == ownerGeneralId) { "Stratagem hand owner mismatch" }
            fun ids(key: String) = (row[key] as? List<*>)?.map { it as? Int ?: invalid() } ?: invalid()
            val extras = if (version == 1) emptyList() else (row["extras"] as? List<*>)?.map { raw ->
                val value = raw as? Map<*, *> ?: invalid()
                require(value.keys == setOf("sourceKey", "instanceId", "type"))
                val type = (value["type"] as? String)?.let { name ->
                    StratagemCardType.entries.firstOrNull { it.name == name }
                } ?: invalid()
                ContributedStratagemInstance(value["sourceKey"] as? String ?: invalid(),
                    value["instanceId"] as? Int ?: invalid(), type)
            } ?: invalid()
            require(version != 2 || extras.isNotEmpty())
            return StratagemHand(owner, ids("hand"), ids("drawPile"), ids("discard"),
                Phase.read(row["lastDrawPhase"]), extras)
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid stratagem hand")
    }
}
