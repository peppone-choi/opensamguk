package opensamguk.logic.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The approved march tempo ledger supplies every direct forced-march magnitude. */
object ForcedMarchTempo {
    private val root by lazy {
        Json.parseToJsonElement(checkNotNull(javaClass.classLoader.getResource("campaign/march-tempo-targets-v1.json"))
            .readText()).jsonObject
    }
    private val forced get() = root.getValue("forcedMarch").jsonObject
    private val cost get() = forced.getValue("costMagnitude").jsonObject
    private val personal get() = root.getValue("personalTravel").jsonObject.getValue("forcedMarch").jsonObject
    val budgetMm: Long by lazy {
        personal.getValue("budgetCostKmPerTurn").jsonPrimitive.int.toLong().also { require(it > 0) } * 1_000_000L
    }
    val maxRouteDistanceMm: Long by lazy {
        personal.getValue("maxRouteDistanceKm").jsonPrimitive.int.toLong().also { require(it > 0) } * 1_000_000L
    }
    val maxEstimatedTurns: Long by lazy {
        personal.getValue("maxEstimatedTurns").jsonPrimitive.int.toLong().also { require(it > 0) }
    }
    val distanceMm: Long by lazy { cost.getValue("distanceKm").jsonPrimitive.int * 1_000_000L }
    val fatiguePerDistance: Int by lazy { cost.getValue("personalFatigueGain").jsonPrimitive.int }
    val moralePerDistance: Int by lazy { cost.getValue("personalMoraleLoss").jsonPrimitive.int }
}
