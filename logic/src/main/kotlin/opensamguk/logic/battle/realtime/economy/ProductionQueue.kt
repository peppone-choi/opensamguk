package opensamguk.logic.battle.realtime.economy

data class ProductionRecipe(
    val kind: HousingKind, val perTickCost: ResourceBundle?, val durationTicks: Long?,
    val status: ParameterStatus = ParameterStatus.PROPOSED,
)
enum class ProductionStatus { ACTIVE, COMPLETED, CANCELLED }
data class ResourceDebit(val recordId: String, val storeId: String, val owner: String, val amount: ResourceBundle)
data class ProductionOrder(
    val id: String, val owner: String, val producerId: String, val storeId: String, val sourceId: String,
    val recipe: ProductionRecipe, val target: Long, val sequence: Long,
    val completed: Long = 0, val progress: Long = 0,
    val debits: List<ResourceDebit> = emptyList(), val populationDebit: PopulationDebit? = null,
    val completedPeople: List<PopulationDebit> = emptyList(),
    val status: ProductionStatus = ProductionStatus.ACTIVE,
) { internal val completedDebitIds: List<String> get() = completedPeople.map { it.debitRecordId } }

/** The instruction consumes a turn once; target units advance automatically without more turns. */
fun EconomyLedger.enqueue(
    id: String, owner: String, orderId: String, producerId: String, storeId: String, sourceId: String,
    recipe: ProductionRecipe, target: Long, mode: EconomyMode, params: EconomyParams,
) = apply(id, key("enqueue", owner, orderId, producerId, storeId, sourceId, recipe, target, mode, params.fingerprint())) { s ->
    identifier(orderId); identifier(producerId); ensure(target > 0, EconomyCode.INVALID_INPUT)
    val turns = params.turns(mode, params.productionTurnCost)
    s.store(storeId, owner)
    ensure(orderId !in s.orders, EconomyCode.DUPLICATE_ID)
    ensure(s.orders.values.none { it.producerId == producerId && it.owner != owner }, EconomyCode.NOT_OWNER)
    val sequence = Math.incrementExact(s.orders.values.maxOfOrNull { it.sequence } ?: 0)
    Change(s.copy(orders = s.orders + (orderId to ProductionOrder(orderId, owner, producerId, storeId, sourceId, recipe, target, sequence))), turns)
}

/** A caller-provided connectivity snapshot only selects the population source, never credits resources. */
fun EconomyLedger.advanceProduction(id: String, orderId: String, connected: Boolean?, params: EconomyParams) =
    apply(id, key("productionTick", orderId, connected, params.fingerprint())) { original ->
        val order = original.orders.required(orderId)
        ensure(order.status != ProductionStatus.COMPLETED, EconomyCode.ALREADY_COMPLETED)
        ensure(order.status != ProductionStatus.CANCELLED, EconomyCode.ALREADY_CANCELLED)
        ensure(original.orders.values.none { it.producerId == order.producerId && it.status == ProductionStatus.ACTIVE && it.sequence < order.sequence }, EconomyCode.QUEUE_BLOCKED)
        ensure(connected != null, EconomyCode.NOT_DECIDED)
        ensure(order.recipe.status == ParameterStatus.CONFIRMED && order.recipe.perTickCost != null && order.recipe.durationTicks != null, EconomyCode.NOT_DECIDED)
        val duration = order.recipe.durationTicks!!; ensure(duration > 0, EconomyCode.INVALID_INPUT)
        original.checkHousing(order.owner, orderId, params)
        val cost = order.recipe.perTickCost!!
        val store = original.store(order.storeId, order.owner)
        val balance = store.stock - cost
        var s = original
        val debit = order.populationDebit ?: s.reservePerson(order.owner, order.sourceId, connected!!, key(order.id, order.completed)).let {
            s = it.first; it.second
        }
        val progress = Math.incrementExact(order.progress)
        val resourceDebit = ResourceDebit(id, order.storeId, order.owner, cost)
        val complete = progress == duration
        var next = order.copy(progress = progress, debits = order.debits + resourceDebit, populationDebit = debit)
        if (complete) {
            val unitId = key("produced", orderId, order.completed)
            ensure(unitId !in s.units, EconomyCode.DUPLICATE_ID)
            s = s.copy(units = s.units + (unitId to HousingUnit(unitId, order.owner, order.recipe.kind)))
            val completed = Math.incrementExact(order.completed)
            next = next.copy(completed = completed, progress = 0, debits = emptyList(), populationDebit = null,
                completedPeople = order.completedPeople + debit,
                status = if (completed == order.target) ProductionStatus.COMPLETED else ProductionStatus.ACTIVE)
        }
        Change(s.copy(stores = s.stores + (store.id to store.copy(stock = balance)), spent = s.spent + cost,
            orders = s.orders + (orderId to next)))
    }

fun EconomyLedger.cancelProduction(id: String, owner: String, orderId: String, mode: EconomyMode, params: EconomyParams) =
    apply(id, key("cancelProduction", owner, orderId, mode, params.fingerprint())) { initial ->
        val order = initial.orders.required(orderId); ensure(order.owner == owner, EconomyCode.NOT_OWNER)
        ensure(order.status != ProductionStatus.COMPLETED, EconomyCode.ALREADY_COMPLETED)
        ensure(order.status != ProductionStatus.CANCELLED, EconomyCode.ALREADY_CANCELLED)
        val turns = params.turns(mode, params.cancelTurnCost)
        var s = initial
        order.debits.forEach { debit ->
            val store = s.stores[debit.storeId] ?: throw Rejected(EconomyCode.NOT_DECIDED)
            ensure(store.owner == debit.owner, EconomyCode.NOT_DECIDED)
            s = s.copy(stores = s.stores + (store.id to store.copy(stock = store.stock + debit.amount)), spent = s.spent - debit.amount)
        }
        order.populationDebit?.let { s = s.refundPerson(it) }
        Change(s.copy(orders = s.orders + (orderId to order.copy(status = ProductionStatus.CANCELLED, progress = 0, debits = emptyList(), populationDebit = null))), turns)
    }

/** Target/recipe changes are important instructions, never an implicit per-unit charge. */
fun EconomyLedger.changeProduction(id: String, owner: String, orderId: String, recipe: ProductionRecipe, target: Long, mode: EconomyMode, params: EconomyParams) =
    apply(id, key("changeProduction", owner, orderId, recipe, target, mode, params.fingerprint())) { s ->
        val order = s.orders.required(orderId); ensure(order.owner == owner, EconomyCode.NOT_OWNER)
        ensure(order.status == ProductionStatus.ACTIVE && target > order.completed, EconomyCode.INVALID_INPUT)
        // Repricing an already partially produced unit has no confirmed rule.
        ensure(order.progress == 0 && order.populationDebit == null, EconomyCode.NOT_DECIDED)
        val turns = params.turns(mode, params.productionTurnCost)
        Change(s.copy(orders = s.orders + (orderId to order.copy(recipe = recipe, target = target))), turns)
    }
