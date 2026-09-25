package opensamguk.logic.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Confirmed numeric values shared by precheck and turn execution. */
data class DirectDesign(val conversionTrainingLoss: Int, val grainTradeMoney: Int,
    val grainTradeGrain: Int, val transportMaxAmount: Int) {
    init {
        require(conversionTrainingLoss in 0..100 && grainTradeMoney > 0 && grainTradeGrain > 0 &&
            transportMaxAmount > 0)
    }

    companion object {
        val CANON by lazy {
            val resource = checkNotNull(DirectDesign::class.java.classLoader
                .getResource("hwiha/hwiha-legacy-direct-v1.json"))
            val root = Json.parseToJsonElement(resource.readText()).jsonObject
            require(root.keys == setOf("schemaVersion", "ledgerId", "status", "note",
                "conversionTrainingLoss", "grainTradeMoney", "grainTradeGrain", "transportMaxAmount"))
            require(root.getValue("schemaVersion").jsonPrimitive.int == 1)
            require(root.getValue("ledgerId").jsonPrimitive.content == "hwiha-legacy-direct-v1")
            require(root.getValue("status").jsonPrimitive.content == "CONFIRMED")
            DirectDesign(root.getValue("conversionTrainingLoss").jsonPrimitive.int,
                root.getValue("grainTradeMoney").jsonPrimitive.int,
                root.getValue("grainTradeGrain").jsonPrimitive.int,
                root.getValue("transportMaxAmount").jsonPrimitive.int)
        }
    }
}
