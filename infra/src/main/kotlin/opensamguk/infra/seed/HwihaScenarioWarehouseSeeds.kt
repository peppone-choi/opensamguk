package opensamguk.infra.seed

import java.util.Collections
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.RuleProfile

data class HwihaWarehouseSeed(
    val topologyRevision: String,
    val topologyHash: String,
    val warehouses: Map<Int, Resources>,
)

/** Explicit game-design inventory only. Map identity and fresh-world checks belong to the importer. */
object HwihaScenarioWarehouseSeeds {
    fun decode(root: Map<String, Any?>, profile: RuleProfile?): HwihaWarehouseSeed? {
        if ("hwihaWarehouses" !in root) return null
        require(profile == RuleProfile.HWIHA) { "hwihaWarehouses requires HWIHA" }
        val declaration = root["hwihaWarehouses"] as? Map<*, *> ?: invalid()
        require(declaration.keys == setOf("version", "units", "source", "topologyRevision", "topologyHash", "warehouses"))
        require(declaration["version"] is Int && declaration["version"] == 1)
        require(declaration["units"] == "game-resource-v1" && declaration["source"] == "GAME_DESIGN")
        val revision = (declaration["topologyRevision"] as? String)?.takeIf { it.isNotBlank() } ?: invalid()
        val hash = (declaration["topologyHash"] as? String)?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: invalid()
        val rows = declaration["warehouses"] as? List<*> ?: invalid()
        val inventories = linkedMapOf<Int, Resources>()
        for (raw in rows) {
            val row = raw as? Map<*, *> ?: invalid()
            require(row.keys == setOf("countyId", "stock"))
            val county = (row["countyId"] as? Int)?.takeIf { it > 0 } ?: invalid()
            val stock = row["stock"] as? Map<*, *> ?: invalid()
            require(stock.keys == setOf("money", "grain", "iron", "timber", "horses"))
            val resources = Resources(quantity(stock["money"]), quantity(stock["grain"]),
                quantity(stock["iron"]), quantity(stock["timber"]), quantity(stock["horses"]))
            require(inventories.put(county, resources) == null) { "Duplicate warehouse county" }
        }
        return HwihaWarehouseSeed(revision, hash, Collections.unmodifiableMap(inventories.toSortedMap()))
    }

    private fun quantity(value: Any?): Long = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> invalid()
    }.also { require(it >= 0) }

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid HWIHA warehouse seed")
}
