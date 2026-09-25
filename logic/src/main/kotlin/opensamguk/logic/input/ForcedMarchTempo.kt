package opensamguk.logic.input

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.logic.world.LandMarchMetricSnapshot

/** The approved march tempo ledger supplies every direct forced-march magnitude. */
object ForcedMarchTempo {
    private val root by lazy {
        Json.parseToJsonElement(checkNotNull(javaClass.classLoader.getResource("hwiha/march-tempo-targets-v1.json"))
            .readText()).jsonObject
    }
    private val forced get() = root.getValue("forcedMarch").jsonObject
    private val cost get() = forced.getValue("costMagnitude").jsonObject
    val budgetMm: Long by lazy {
        require(root.getValue("baseSpeedKmPerTurn").jsonPrimitive.int * 1_000_000L == LandMarchMetricSnapshot.NORMAL_BUDGET_MM)
        BigDecimal.valueOf(LandMarchMetricSnapshot.NORMAL_BUDGET_MM)
            .multiply(BigDecimal(forced.getValue("speedFactor").jsonPrimitive.content)).longValueExact()
    }
    val distanceMm: Long by lazy { cost.getValue("distanceKm").jsonPrimitive.int * 1_000_000L }
    val fatiguePerDistance: Int by lazy { cost.getValue("personalFatigueGain").jsonPrimitive.int }
    val moralePerDistance: Int by lazy { cost.getValue("personalMoraleLoss").jsonPrimitive.int }
}
