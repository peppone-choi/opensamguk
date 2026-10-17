package opensamguk.logic.battle.realtime.economy

enum class CarrierKind { WORKER, TRANSPORT, SOLDIER }
data class CargoCarrier(val id: String, val owner: String, val kind: CarrierKind, val cargo: ResourceBundle = ResourceBundle.ZERO, val alive: Boolean = true)
data class GroundCargo(val id: String, val originalOwner: String, val cargo: ResourceBundle)
fun EconomyLedger.registerCarrier(id: String, owner: String, carrierId: String, kind: CarrierKind) =
    apply(id, key("carrier", owner, carrierId, kind)) { s ->
        identifier(owner); identifier(carrierId); ensure(carrierId !in s.carriers, EconomyCode.DUPLICATE_ID)
        if (kind != CarrierKind.TRANSPORT) {
            val unit = s.units.required(carrierId); ensure(unit.owner == owner, EconomyCode.NOT_OWNER)
            ensure(unit.kind == (if (kind == CarrierKind.WORKER) HousingKind.WORKER else HousingKind.SOLDIER), EconomyCode.INVALID_INPUT)
        }
        Change(s.copy(carriers = s.carriers + (carrierId to CargoCarrier(carrierId, owner, kind))))
    }
internal fun CargoCarrier.canCarry() { ensure(kind != CarrierKind.SOLDIER, EconomyCode.CARRIER_NOT_ALLOWED) }
internal fun EconomyState.freeCarrier(id: String) {
    ensure(routes.values.none { it.carrierId == id }, EconomyCode.ROUTE_BUSY)
}
fun EconomyLedger.loadCargo(id: String, owner: String, carrierId: String, storeId: String, amount: ResourceBundle, params: EconomyParams) =
    apply(id, key("load", owner, carrierId, storeId, amount, params.fingerprint())) { s ->
        val capacity = params.positive(params.carrierCapacity)
        val carrier = s.carrier(carrierId, owner); carrier.canCarry(); s.freeCarrier(carrierId)
        val store = s.store(storeId, owner); ensure(amount.total > 0, EconomyCode.INVALID_INPUT)
        val cargo = carrier.cargo + amount; ensure(cargo.total <= capacity, EconomyCode.CAPACITY_EXCEEDED)
        Change(s.copy(stores = s.stores + (storeId to store.copy(stock = store.stock - amount)), carriers = s.carriers + (carrierId to carrier.copy(cargo = cargo))))
    }
/** Distance and elapsed time are trusted simulation observations, not a wire command parser. */
fun EconomyLedger.depositCargo(id: String, owner: String, carrierId: String, storeId: String, distance: Long?, elapsedTicks: Long?, params: EconomyParams) =
    apply(id, key("deposit", owner, carrierId, storeId, distance, elapsedTicks, params.fingerprint())) { s ->
        params.confirmed()
        ensure(params.collectionKinds != null && params.collectionRadius != null && params.depositTicks != null && distance != null && elapsedTicks != null, EconomyCode.NOT_DECIDED)
        ensure(params.collectionRadius!! >= 0 && params.depositTicks!! >= 0 && distance!! >= 0 && elapsedTicks!! >= 0, EconomyCode.INVALID_INPUT)
        val carrier = s.carrier(carrierId, owner); carrier.canCarry(); s.freeCarrier(carrierId)
        val store = s.store(storeId, owner)
        ensure(store.kind in params.collectionKinds!!, EconomyCode.NOT_DECIDED)
        ensure(distance!! <= params.collectionRadius!! && elapsedTicks!! >= params.depositTicks!!, EconomyCode.NOT_AT_DESTINATION)
        Change(s.copy(stores = s.stores + (storeId to store.copy(stock = store.stock + carrier.cargo)), carriers = s.carriers + (carrierId to carrier.copy(cargo = ResourceBundle.ZERO))))
    }
fun EconomyLedger.hitCarrier(id: String, carrierId: String) = apply(id, key("hit", carrierId)) { s ->
    val c = s.carriers.required(carrierId); ensure(c.alive, EconomyCode.INVALID_INPUT)
    Change(s) // Damage is owned by combat; a non-destructive hit never drops cargo.
}
fun EconomyLedger.destroyCarrier(id: String, carrierId: String, params: EconomyParams) =
    apply(id, key("destroy", carrierId, params.fingerprint())) { s ->
        val carrier = s.carriers.required(carrierId); ensure(carrier.alive, EconomyCode.INVALID_INPUT)
        var kept = ResourceBundle.ZERO
        if (carrier.cargo != ResourceBundle.ZERO) {
            params.confirmed()
            val n = params.dropRetainedNumerator; val d = params.dropRetainedDenominator
            ensure(n != null && d != null, EconomyCode.NOT_DECIDED)
            ensure(d!! > 0 && n!! in 0..d, EconomyCode.INVALID_INPUT)
            // No rounding policy has been approved: fractional resource units stay undecided.
            ensure(carrier.cargo.values().all { java.math.BigInteger.valueOf(it).multiply(java.math.BigInteger.valueOf(n!!)).mod(java.math.BigInteger.valueOf(d)) == java.math.BigInteger.ZERO }, EconomyCode.NOT_DECIDED)
            kept = carrier.cargo.retained(n!!, d!!)
        }
        val groundId = key("ground", carrierId)
        ensure(groundId !in s.ground, EconomyCode.DUPLICATE_ID)
        Change(s.copy(carriers = s.carriers + (carrierId to carrier.copy(cargo = ResourceBundle.ZERO, alive = false)),
            ground = if (kept == ResourceBundle.ZERO) s.ground else s.ground + (groundId to GroundCargo(groundId, carrier.owner, kept)),
            lost = s.lost + (carrier.cargo - kept), units = s.units - carrierId,
            nodes = s.nodes.mapValues { (_, node) -> node.copy(workers = node.workers - carrierId) }))
    }
fun EconomyLedger.pickupCargo(id: String, owner: String, carrierId: String, groundId: String, params: EconomyParams) =
    apply(id, key("pickup", owner, carrierId, groundId, params.fingerprint())) { s ->
        val capacity = params.positive(params.carrierCapacity)
        val carrier = s.carrier(carrierId, owner); carrier.canCarry(); s.freeCarrier(carrierId)
        val ground = s.ground.required(groundId)
        val cargo = carrier.cargo + ground.cargo; ensure(cargo.total <= capacity, EconomyCode.CAPACITY_EXCEEDED)
        Change(s.copy(carriers = s.carriers + (carrierId to carrier.copy(cargo = cargo)), ground = s.ground - groundId))
    }
/** Only an accepted physical escape-arrival fact may call this. Retreat initiation is not recovery. */
fun EconomyLedger.escapeCargo(id: String, carrierId: String, reachedExit: Boolean?) = apply(id, key("escape", carrierId, reachedExit)) { s ->
    ensure(reachedExit != null, EconomyCode.NOT_DECIDED); ensure(reachedExit == true, EconomyCode.NOT_AT_DESTINATION)
    val carrier = s.carriers.required(carrierId); ensure(carrier.alive, EconomyCode.INVALID_INPUT)
    Change(s.copy(carriers = s.carriers + (carrierId to carrier.copy(cargo = ResourceBundle.ZERO, alive = false)),
        escaped = s.escaped + carrier.cargo, units = s.units - carrierId,
        nodes = s.nodes.mapValues { (_, node) -> node.copy(workers = node.workers - carrierId) }))
}
