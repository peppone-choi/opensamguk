package opensamguk.logic.input

import opensamguk.logic.economy.HwihaResources

/** All five resources are authoritative here; legacy gold/rice columns mirror them for old readers. */
object HwihaPortableStock {
    const val META_KEY = "hwihaPortableStock"

    fun read(meta: Map<String, Any?>, money: Int, grain: Int): HwihaResources {
        require(money >= 0 && grain >= 0)
        if (META_KEY !in meta) return HwihaResources(money.toLong(), grain.toLong())
        val row = meta[META_KEY] as? Map<*, *> ?: invalid()
        require(row.keys == setOf("version", "money", "grain", "iron", "timber", "horses"))
        require(exact(row["version"]) == 1L)
        val storedMoney = exact(row["money"])
        val storedGrain = exact(row["grain"])
        require(storedMoney == money.toLong() && storedGrain == grain.toLong()) { "portable stock mirror drift" }
        return HwihaResources(storedMoney, storedGrain, exact(row["iron"]),
            exact(row["timber"]), exact(row["horses"]))
    }

    fun withStock(meta: Map<String, Any?>, resources: HwihaResources): Map<String, Any?> =
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
