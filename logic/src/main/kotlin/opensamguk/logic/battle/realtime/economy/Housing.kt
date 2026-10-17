package opensamguk.logic.battle.realtime.economy

enum class HousingKind { WORKER, SOLDIER }
data class HousingBuilding(val id: String, val owner: String, val capacity: Long)
data class HousingUnit(val id: String, val owner: String, val kind: HousingKind)
fun EconomyLedger.addHousing(id: String, owner: String, buildingId: String, params: EconomyParams) =
    apply(id, key("housing", owner, buildingId, params.fingerprint())) { s ->
        identifier(owner); identifier(buildingId)
        val capacity = params.positive(params.housingPerBuilding)
        ensure(buildingId !in s.buildings, EconomyCode.DUPLICATE_ID)
        s.buildings.values.filter { it.owner == owner }.fold(capacity) { a, b -> Math.addExact(a, b.capacity) }
        Change(s.copy(buildings = s.buildings + (buildingId to HousingBuilding(buildingId, owner, capacity))))
    }
fun EconomyLedger.destroyHousing(id: String, buildingId: String) = apply(id, key("destroyHousing", buildingId)) { s ->
    s.buildings.required(buildingId)
    // C18: existing units survive overflow; only subsequent production pauses.
    Change(s.copy(buildings = s.buildings - buildingId))
}
/** Accepted reinforcement/initial-unit fact, not reinforcement admission or ticket generation. */
fun EconomyLedger.admitUnit(id: String, owner: String, unitId: String, kind: HousingKind) = apply(id, key("admitUnit", owner, unitId, kind)) { s ->
    identifier(owner); identifier(unitId); ensure(unitId !in s.units, EconomyCode.DUPLICATE_ID)
    Change(s.copy(units = s.units + (unitId to HousingUnit(unitId, owner, kind))))
}
fun EconomyLedger.leaveUnit(id: String, unitId: String) = apply(id, key("leaveUnit", unitId)) { s ->
    s.units.required(unitId)
    // Cargo destruction/escape must be accounted before removing a carrier-backed unit.
    ensure(s.carriers[unitId]?.let { !it.alive } != false, EconomyCode.INVALID_INPUT)
    Change(s.copy(units = s.units - unitId))
}
internal fun EconomyState.checkHousing(owner: String, orderId: String, params: EconomyParams) {
    params.confirmed(); ensure(params.reserveHousingDuringProduction != null, EconomyCode.NOT_DECIDED)
    val capacity = buildings.values.filter { it.owner == owner }.fold(0L) { a, b -> Math.addExact(a, b.capacity) }
    val used = units.values.count { it.owner == owner }.toLong()
    val reserved = if (params.reserveHousingDuringProduction == true) orders.values.count {
        it.id != orderId && it.owner == owner && it.status == ProductionStatus.ACTIVE && it.populationDebit != null
    }.toLong() else 0
    ensure(Math.addExact(used, reserved) < capacity, EconomyCode.INSUFFICIENT_HOUSING)
}
