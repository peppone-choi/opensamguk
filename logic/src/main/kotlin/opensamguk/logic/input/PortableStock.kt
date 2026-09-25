package opensamguk.logic.input

import opensamguk.logic.economy.Resources

/** Five-resource ledger. Existing gold/rice columns remain current when older systems change them. */
object PortableStock {
    const val META_KEY = "hwihaPortableStock"

    fun read(meta: Map<String, Any?>, money: Int, grain: Int): Resources {
        require(money >= 0 && grain >= 0)
        if (META_KEY !in meta) return Resources(money.toLong(), grain.toLong())
        val row = meta[META_KEY] as? Map<*, *> ?: invalid()
        require(row.keys == setOf("version", "money", "grain", "iron", "timber", "horses"))
        require(exact(row["version"]) == 1L)
        exact(row["money"])
        exact(row["grain"])
        return Resources(money.toLong(), grain.toLong(), exact(row["iron"]),
            exact(row["timber"]), exact(row["horses"]))
    }

    fun withStock(meta: Map<String, Any?>, resources: Resources): Map<String, Any?> =
        meta + (META_KEY to mapOf("version" to 1, "money" to resources.money,
            "grain" to resources.grain, "iron" to resources.iron,
            "timber" to resources.timber, "horses" to resources.horses))

    fun checkedColumn(value: Long): Int {
        require(value in 0..Int.MAX_VALUE.toLong())
        return value.toInt()
    }

    private fun exact(value: Any?): Long = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> invalid()
    }
    private fun invalid(): Nothing = throw IllegalArgumentException("invalid portable stock")
}
