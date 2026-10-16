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
)
fun EconomyLedger.registerNode(id: String, nodeId: String, kind: NodeKind, resource: ResourceKind, remaining: Long?, params: EconomyParams) =
    apply(id, key("node", nodeId, kind, resource, remaining, params.fingerprint())) { s ->
        params.confirmed(); identifier(nodeId)
        ensure(remaining != null, EconomyCode.NOT_DECIDED); ensure(remaining!! >= 0, EconomyCode.INVALID_INPUT)
        ensure(nodeId !in s.nodes, EconomyCode.DUPLICATE_ID)
        Change(s.copy(nodes = s.nodes + (nodeId to ResourceNode(nodeId, kind, resource, remaining, remaining))))
    }

fun EconomyLedger.assignFarm(id: String, owner: String, nodeId: String, workerIds: Set<String>, params: EconomyParams) =
    apply(id, key("farmWorkers", owner, nodeId, workerIds.sorted(), params.fingerprint())) { s ->
        params.positive(params.farmWorkers)
        val node = s.nodes.required(nodeId); ensure(node.kind == NodeKind.FARM, EconomyCode.INVALID_INPUT)
        val previousOwners = node.workers.mapNotNull { s.carriers[it]?.owner }.toSet()
        ensure(previousOwners.isEmpty() || previousOwners == setOf(owner), EconomyCode.NOT_OWNER)
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
        Change(s.copy(nodes = s.nodes + (nodeId to node.copy(remaining = node.remaining - extracted, progress = progress)),
            carriers = s.carriers + (carrierId to carrier.copy(cargo = cargo))))
    }
