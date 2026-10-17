package opensamguk.logic.battle.realtime.economy

enum class ResourceKind { GOLD, GRAIN, TIMBER, IRON, STONE, HORSES }
enum class NodeKind { DEPOSIT, FARM }
internal fun ResourceKind.bundle(n: Long): ResourceBundle = when (this) {
    ResourceKind.GOLD -> ResourceBundle(gold = n); ResourceKind.GRAIN -> ResourceBundle(grain = n)
    ResourceKind.TIMBER -> ResourceBundle(timber = n); ResourceKind.IRON -> ResourceBundle(iron = n)
    ResourceKind.STONE -> ResourceBundle(stone = n); ResourceKind.HORSES -> ResourceBundle(horses = n)
}
data class ResourceNode(
    val id: String, val kind: NodeKind, val resource: ResourceKind, val initial: Long,
    val remaining: Long, val workers: Set<String> = emptySet(), val progress: Long = 0,
    val owner: String? = null, val cycle: Long = 1, val produced: Long = 0,
    val reseedDebits: List<ResourceDebit> = emptyList(),
)
fun EconomyLedger.registerNode(id: String, nodeId: String, kind: NodeKind, resource: ResourceKind, remaining: Long?, params: EconomyParams, owner: String? = null) =
    apply(id, key("node", nodeId, kind, resource, remaining, params.fingerprint(), owner)) { s ->
        params.confirmed(); identifier(nodeId)
        val amount = if (kind == NodeKind.FARM) {
            ensure(remaining == null && resource == ResourceKind.GRAIN, EconomyCode.INVALID_INPUT)
            ensure(owner != null && owner.isNotBlank(), EconomyCode.INVALID_INPUT)
            params.positive(params.farmYield)
        } else {
            ensure(owner == null, EconomyCode.INVALID_INPUT)
            ensure(remaining != null, EconomyCode.NOT_DECIDED)
            ensure(remaining!! >= 0, EconomyCode.INVALID_INPUT)
            remaining
        }
        ensure(nodeId !in s.nodes, EconomyCode.DUPLICATE_ID)
        Change(s.copy(nodes = s.nodes + (nodeId to ResourceNode(nodeId, kind, resource, amount, amount, owner = owner))))
    }

/** Explicit rules seam only: automatic policy and additional time/turn costs remain undecided. */
fun EconomyLedger.reseedFarm(id: String, owner: String, nodeId: String, preferredStore: String, params: EconomyParams) =
    // Receipt keys describe the request, not mutable cycle state, so retries remain idempotent.
    apply(id, key("reseed", owner, nodeId, preferredStore, params.fingerprint())) { s ->
        val yield = params.positive(params.farmYield)
        val timber = params.positive(params.farmReseedTimber)
        val node = s.nodes.required(nodeId)
        ensure(node.kind == NodeKind.FARM, EconomyCode.INVALID_INPUT)
        ensure(node.owner == owner, EconomyCode.NOT_OWNER)
        ensure(node.remaining == 0L, EconomyCode.NOT_DEPLETED)
        val cost = ResourceKind.TIMBER.bundle(timber)
        val (paid, debits) = s.debitOwner(owner, preferredStore, cost, id)
        val next = node.copy(initial = Math.addExact(node.initial, yield), remaining = yield,
            cycle = Math.incrementExact(node.cycle), progress = 0, reseedDebits = node.reseedDebits + debits)
        Change(paid.copy(nodes = paid.nodes + (nodeId to next), spent = paid.spent + cost))
    }

fun EconomyLedger.assignFarm(id: String, owner: String, nodeId: String, workerIds: Set<String>, params: EconomyParams) =
    apply(id, key("farmWorkers", owner, nodeId, workerIds.sorted(), params.fingerprint())) { s ->
        params.positive(params.farmWorkers)
        val node = s.nodes.required(nodeId); ensure(node.kind == NodeKind.FARM, EconomyCode.INVALID_INPUT)
        ensure(node.owner == owner, EconomyCode.NOT_OWNER)
        workerIds.forEach { wid ->
            val w = s.carrier(wid, owner)
            ensure(w.kind == CarrierKind.WORKER, EconomyCode.CARRIER_NOT_ALLOWED)
            ensure(s.nodes.values.none { it.id != nodeId && wid in it.workers }, EconomyCode.INVALID_INPUT)
        }
        Change(s.copy(nodes = s.nodes + (nodeId to node.copy(workers = workerIds.toSortedSet()))))
    }

/** One safe simulation tick, supplied by the caller; no wall-clock or mode branch. */
fun EconomyLedger.gather(id: String, owner: String, nodeId: String, carrierId: String, params: EconomyParams) =
    apply(id, key("gather", owner, nodeId, carrierId, params.fingerprint())) { s ->
        val amount = params.positive(params.gatherAmount)
        val capacity = params.positive(params.carrierCapacity)
        val node = s.nodes.required(nodeId); val carrier = s.carrier(carrierId, owner)
        ensure(carrier.kind == CarrierKind.WORKER, EconomyCode.CARRIER_NOT_ALLOWED)
        ensure(node.remaining > 0, EconomyCode.NODE_DEPLETED)
        var progress = node.progress
        if (node.kind == NodeKind.FARM) {
            ensure(node.owner == owner, EconomyCode.NOT_OWNER)
            val needed = params.positive(params.farmWorkers); val ticks = params.positive(params.farmTicks)
            val workers = node.workers.count { wid -> s.carriers[wid]?.let { it.alive && it.owner == owner && it.kind == CarrierKind.WORKER } == true }.toLong()
            ensure(workers >= needed && carrierId in node.workers, EconomyCode.WORKERS_REQUIRED)
            progress = Math.incrementExact(progress)
            if (progress < ticks) return@apply Change(s.copy(nodes = s.nodes + (nodeId to node.copy(progress = progress))))
            progress = 0
        }
        val extracted = minOf(amount, node.remaining)
        val cargo = carrier.cargo + node.resource.bundle(extracted)
        ensure(cargo.total <= capacity, EconomyCode.CAPACITY_EXCEEDED)
        Change(s.copy(nodes = s.nodes + (nodeId to node.copy(remaining = node.remaining - extracted, progress = progress, produced = Math.addExact(node.produced, extracted))),
            carriers = s.carriers + (carrierId to carrier.copy(cargo = cargo))))
    }
