package opensamguk.logic.input

/** Unattached treasure instances held by one general. Issuance is enforced over every inventory. */
object TreasureInventory {
    const val META_KEY = "treasureInventory"
    fun read(meta: Map<String, Any?>): Set<String> {
        val value = meta[META_KEY] ?: return emptySet()
        require(value is List<*>)
        val cards = value.map { it as? String ?: throw IllegalArgumentException("Invalid treasure inventory") }
        require(cards.all { it.startsWith("treasure:") && it.length > 9 } && cards.size == cards.toSet().size)
        return cards.toSet()
    }
    fun withCards(meta: Map<String, Any?>, cards: Set<String>): Map<String, Any?> {
        require(cards.all { it.startsWith("treasure:") && it.length > 9 })
        return meta + (META_KEY to cards.sorted())
    }
}
