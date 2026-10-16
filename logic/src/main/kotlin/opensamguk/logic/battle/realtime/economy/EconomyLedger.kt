package opensamguk.logic.battle.realtime.economy

import java.security.MessageDigest

/** Separate from the five-resource strategic warehouse: stone is confirmed only in this contract. */
data class ResourceBundle(
    val gold: Long = 0, val grain: Long = 0, val timber: Long = 0,
    val iron: Long = 0, val stone: Long = 0, val horses: Long = 0,
) {
    init { require(values().all { it >= 0 }) }
    internal fun values() = listOf(gold, grain, timber, iron, stone, horses)
    val total: Long get() = values().fold(0L, Math::addExact)
    internal fun minimum(b: ResourceBundle) = from(values().zip(b.values(), ::minOf))
    operator fun plus(b: ResourceBundle) = from(values().zip(b.values()) { a, c -> Math.addExact(a, c) })
    operator fun minus(b: ResourceBundle): ResourceBundle {
        ensure(values().zip(b.values()).all { (a, c) -> a >= c }, EconomyCode.INSUFFICIENT_STOCK)
        return from(values().zip(b.values()) { a, c -> a - c })
    }
    internal fun retained(n: Long, d: Long) = from(values().map {
        // Quotient/remainder avoids overflow when retaining a fraction of a large cargo.
        Math.addExact(Math.multiplyExact(it / d, n), java.math.BigInteger.valueOf(it % d)
            .multiply(java.math.BigInteger.valueOf(n)).divide(java.math.BigInteger.valueOf(d)).longValueExact())
    })
    companion object {
        val ZERO = ResourceBundle()
        private fun from(v: List<Long>) = ResourceBundle(v[0], v[1], v[2], v[3], v[4], v[5])
    }
}

enum class EconomyCode {
    APPLIED, ALREADY_APPLIED, NOT_DECIDED, INVALID_INPUT, NOT_FOUND, DUPLICATE_ID,
    COMMAND_CONFLICT, NOT_OWNER, OWNER_CHANGED, INSUFFICIENT_STOCK, INSUFFICIENT_RESERVE,
    INSUFFICIENT_POPULATION, INSUFFICIENT_HOUSING, NODE_DEPLETED, WORKERS_REQUIRED,
    CARRIER_NOT_ALLOWED, CAPACITY_EXCEEDED, NOT_AT_DESTINATION, ALREADY_COMPLETED,
    ALREADY_CANCELLED, QUEUE_BLOCKED, ROUTE_BUSY, ROUTE_STOPPED,
}
data class EconomyResult(val ledger: EconomyLedger, val code: EconomyCode, val turnsSpent: Long = 0)
data class EconomyStore(val id: String, val owner: String, val stock: ResourceBundle, val kind: String = "warehouse")
internal data class Receipt(val command: String, val turns: Long)
internal data class Change(val state: EconomyState, val turns: Long = 0)
internal class Rejected(val code: EconomyCode) : RuntimeException()
internal fun ensure(condition: Boolean, code: EconomyCode) { if (!condition) throw Rejected(code) }
internal fun key(vararg fields: Any?): String = fields.joinToString("") {
    val s = if (it is Iterable<*>) "list:" + key(*it.toList().toTypedArray()) else it.toString()
    "${s.length}:$s"
}
internal fun identifier(id: String) { ensure(id.isNotBlank(), EconomyCode.INVALID_INPUT) }
internal fun EconomyState.store(id: String, owner: String): EconomyStore {
    val value = stores[id] ?: throw Rejected(EconomyCode.NOT_FOUND)
    ensure(value.owner == owner, EconomyCode.NOT_OWNER)
    return value
}
internal fun EconomyState.carrier(id: String, owner: String): CargoCarrier {
    val value = carriers[id] ?: throw Rejected(EconomyCode.NOT_FOUND)
    ensure(value.owner == owner, EconomyCode.NOT_OWNER)
    ensure(value.alive, EconomyCode.INVALID_INPUT)
    return value
}
internal fun <T> Map<String, T>.required(id: String): T = this[id] ?: throw Rejected(EconomyCode.NOT_FOUND)
/** Production pays from its general's ledger, not an invented per-building warehouse limit. */
internal fun EconomyState.debitOwner(owner: String, preferred: String, cost: ResourceBundle, commandId: String): Pair<EconomyState, List<ResourceDebit>> {
    store(preferred, owner)
    val available = stores.values.filter { it.owner == owner }.fold(ResourceBundle.ZERO) { a, b -> a + b.stock }
    available - cost // Validate the complete debit before planning any source record.
    var remaining = cost
    var next = stores
    val records = mutableListOf<ResourceDebit>()
    stores.values.filter { it.owner == owner }.sortedWith(compareBy<EconomyStore> { it.id != preferred }.thenBy { it.id }).forEach { source ->
        val amount = source.stock.minimum(remaining)
        if (amount != ResourceBundle.ZERO) {
            next = next + (source.id to source.copy(stock = source.stock - amount))
            remaining -= amount
            records += ResourceDebit(key(commandId, source.id), source.id, owner, amount)
        }
    }
    ensure(remaining == ResourceBundle.ZERO, EconomyCode.INSUFFICIENT_STOCK)
    return copy(stores = next) to records.toList()
}
internal data class EconomyState(
    val stores: Map<String, EconomyStore>, val population: Map<String, PopulationSource>,
    val imported: ResourceBundle,
    val nodes: Map<String, ResourceNode> = emptyMap(),
    val carriers: Map<String, CargoCarrier> = emptyMap(),
    val ground: Map<String, GroundCargo> = emptyMap(),
    val buildings: Map<String, HousingBuilding> = emptyMap(),
    val units: Map<String, HousingUnit> = emptyMap(),
    val reserves: Map<String, ReserveBatch> = emptyMap(),
    val orders: Map<String, ProductionOrder> = emptyMap(),
    val routes: Map<String, SupplyRoute> = emptyMap(),
    val spent: ResourceBundle = ResourceBundle.ZERO,
    val lost: ResourceBundle = ResourceBundle.ZERO,
    val escaped: ResourceBundle = ResourceBundle.ZERO,
    val receipts: Map<String, Receipt> = emptyMap(),
)

/** Immutable in-memory rules. Every command returns a new ledger, or the identical ledger on rejection.
 * No campaign mutations, turn clock, graph policy, random source or engine dependency is hidden here.
 */
class EconomyLedger internal constructor(internal val state: EconomyState) {
    constructor(stores: List<EconomyStore>, population: List<PopulationSource> = emptyList()) : this(seed(stores, population))
    fun store(id: String): EconomyStore? = state.stores[id]
    fun stock(owner: String): ResourceBundle = state.stores.values.filter { it.owner == owner }
        .fold(ResourceBundle.ZERO) { a, b -> a + b.stock }
    fun node(id: String): ResourceNode? = state.nodes[id]?.let { it.copy(workers = it.workers.toSet()) }
    fun cargo(id: String): CargoCarrier? = state.carriers[id]
    fun ground(id: String): GroundCargo? = state.ground[id]
    fun order(id: String): ProductionOrder? = state.orders[id]?.let { it.copy(debits = it.debits.toList(), completedPeople = it.completedPeople.toList()) }
    fun route(id: String): SupplyRoute? = state.routes[id]
    fun source(id: String): PopulationSource? = state.population[id]
    fun reserve(owner: String): Long = state.reserves.values.filter { it.owner == owner }.fold(0L) { a, b -> Math.addExact(a, b.remaining) }
    fun housingUsed(owner: String): Long = state.units.values.count { it.owner == owner }.toLong()
    fun housingCapacity(owner: String): Long = state.buildings.values.filter { it.owner == owner }.fold(0L) { a, b -> Math.addExact(a, b.capacity) }
    fun totalTurns(): Long = state.receipts.values.fold(0L) { a, b -> Math.addExact(a, b.turns) }

    internal fun apply(id: String, command: String, transform: (EconomyState) -> Change): EconomyResult {
        try {
            identifier(id)
            state.receipts[id]?.let {
                return EconomyResult(this, if (it.command == command) EconomyCode.ALREADY_APPLIED else EconomyCode.COMMAND_CONFLICT)
            }
            val changed = transform(state)
            val next = changed.state.copy(receipts = changed.state.receipts + (id to Receipt(command, changed.turns)))
            return EconomyResult(EconomyLedger(next), EconomyCode.APPLIED, changed.turns)
        } catch (e: Rejected) { return EconomyResult(this, e.code) }
        catch (_: ArithmeticException) { return EconomyResult(this, EconomyCode.INVALID_INPUT) }
    }

    fun transfer(id: String, owner: String, from: String, to: String, amount: ResourceBundle, params: EconomyParams): EconomyResult =
        apply(id, key("transfer", owner, from, to, amount, params.fingerprint())) { s ->
            params.confirmed(); ensure(params.transferEnabled == true, EconomyCode.NOT_DECIDED)
            val a = s.store(from, owner); val b = s.stores.required(to)
            ensure(from != to && amount.total > 0, EconomyCode.INVALID_INPUT)
            Change(s.copy(stores = s.stores + (from to a.copy(stock = a.stock - amount)) + (to to b.copy(stock = b.stock + amount))))
        }

    /** Canonical ordering includes receipts and pending deductions, not only visible balances. */
    fun canonical(): String = buildString {
        append(key("economy-ledger", state.imported, state.spent, state.lost, state.escaped))
        fun rows(name: String, values: Map<String, *>) {
            append(key(name)); values.toSortedMap().forEach { (id, value) -> append(key(id, value)) }
        }
        rows("stores", state.stores); rows("population", state.population)
        rows("nodes", state.nodes.mapValues { (_, n) -> key(n.id, n.kind, n.resource, n.initial, n.remaining, n.workers.sorted(), n.progress) })
        rows("carriers", state.carriers); rows("ground", state.ground)
        rows("buildings", state.buildings); rows("units", state.units)
        rows("reserves", state.reserves); rows("orders", state.orders)
        rows("routes", state.routes); rows("receipts", state.receipts)
    }
    fun sha256(): String = MessageDigest.getInstance("SHA-256").digest(canonical().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        private fun seed(stores: List<EconomyStore>, population: List<PopulationSource>): EconomyState {
            require(stores.map { it.id }.distinct().size == stores.size)
            require(population.map { it.id }.distinct().size == population.size)
            require(population.all { it.available == it.initial }) { "Seed population must start before ledger debits" }
            require(stores.all { it.id.isNotBlank() && it.owner.isNotBlank() && it.kind.isNotBlank() })
            return EconomyState(stores.associateBy { it.id }, population.associateBy { it.id }, stores.fold(ResourceBundle.ZERO) { a, b -> a + b.stock })
        }
    }
}
