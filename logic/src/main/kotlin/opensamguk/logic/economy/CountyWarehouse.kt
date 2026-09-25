package opensamguk.logic.economy

/** Integer game-resource units, not a claim about historical currency or grain measures. */
data class Resources(
    val money: Long = 0, val grain: Long = 0, val iron: Long = 0,
    val timber: Long = 0, val horses: Long = 0,
) {
    init { require(listOf(money, grain, iron, timber, horses).all { it >= 0 }) }
    fun debit(cost: Resources): Resources? {
        if (money < cost.money || grain < cost.grain || iron < cost.iron ||
            timber < cost.timber || horses < cost.horses) return null
        return Resources(money-cost.money, grain-cost.grain, iron-cost.iron, timber-cost.timber, horses-cost.horses)
    }
    fun credit(amount: Resources) = Resources(
        Math.addExact(money, amount.money), Math.addExact(grain, amount.grain),
        Math.addExact(iron, amount.iron), Math.addExact(timber, amount.timber), Math.addExact(horses, amount.horses))
    fun toMetaValue(): Map<String, Any> = linkedMapOf(
        "money" to money, "grain" to grain, "iron" to iron, "timber" to timber, "horses" to horses)
}

/** Stored on the county seat's city row; control follows city.nationId, never a duplicated owner field. */
data class CountyWarehouse(val countyId: Int, val revision: Long, val stock: Resources) {
    init { require(countyId > 0 && revision >= 0) }
    fun replace(next: Resources) = copy(revision = Math.incrementExact(revision), stock = next)
    fun toMetaValue(): Map<String, Any> = linkedMapOf(
        "version" to 1, "countyId" to countyId, "revision" to revision, "stock" to stock.toMetaValue())
    companion object {
        const val META_KEY = "hwihaCountyWarehouse"
        fun read(meta: Map<String, Any?>, countyId: Int): CountyWarehouse? {
            require(countyId > 0)
            if (META_KEY !in meta) return null
            val row = meta[META_KEY] as? Map<*, *> ?: invalid()
            require(row.keys == setOf("version", "countyId", "revision", "stock"))
            require(exact(row["version"]) == 1L && exact(row["countyId"]) == countyId.toLong())
            val stock = row["stock"] as? Map<*, *> ?: invalid()
            require(stock.keys == setOf("money", "grain", "iron", "timber", "horses"))
            return CountyWarehouse(countyId, exact(row["revision"]), Resources(
                exact(stock["money"]), exact(stock["grain"]), exact(stock["iron"]),
                exact(stock["timber"]), exact(stock["horses"])))
        }
        // JSON decodes small integers as Int and large ones as Long. No lossy numeric conversion.
        private fun exact(value: Any?): Long = when (value) {
            is Int -> value.toLong()
            is Long -> value
            else -> invalid()
        }
        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid county warehouse")
    }
}
