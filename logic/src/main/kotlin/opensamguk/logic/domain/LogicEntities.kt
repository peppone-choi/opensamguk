package opensamguk.logic.domain

/** General as the logic layer sees it. `intel` (not intelligence) matches the DB column. */
data class General(
    val id: Int,
    val nationId: Int,
    val cityId: Int,
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val injury: Int,
    val experience: Double,   // raw accumulator (PHP increaseVar adds float, no per-add round); truncated → int only at flush (D1)
    val dedication: Double,   // same — see C2 resolve + D1 General row mapper
    val officerLevel: Int,
    val gold: Int,
    val rice: Int,
    val meta: Map<String, Any?> = linkedMapOf(),   // explevel, intel_exp, max_domestic_critical, killturn
)

/** City — comm/agri/supply_state/front_state/trust align to DB; meta holds region etc. */
data class City(
    val id: Int,
    val nationId: Int,
    val level: Int,
    val commerce: Int, val commerceMax: Int,
    val agriculture: Int, val agricultureMax: Int,
    val supplyState: Int,           // truthy = supplied
    val frontState: Int,            // 1|3 = front (debuff)
    val trust: Double,              // PHP schema.sql:202 trust FLOAT; che math uses trust/100.0 & trust/80.0 — port faithfully as Double
    val meta: Map<String, Any?> = linkedMapOf(),
)

data class Nation(val id: Int, val level: Int, val capitalCityId: Int?)

/** World env read by cost/debuff math. */
data class WorldEnv(val year: Int, val startYear: Int, val develCost: Int) {
    val relYear: Int get() = year - startYear
}
