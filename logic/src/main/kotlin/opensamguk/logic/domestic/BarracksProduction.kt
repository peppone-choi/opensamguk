package opensamguk.logic.domestic

import opensamguk.logic.economy.Resources
import opensamguk.logic.input.Phase

/** County-seat production only. No direct conscription action or implicit balance policy. */
object BarracksProduction {
    const val META_KEY = "barracksProduction"
    enum class Status { ACTIVE, COMPLETED, CANCELLED }
    enum class Code { APPLIED, ALREADY_APPLIED, NOT_DECIDED, OWNER_CHANGED, INSUFFICIENT_STOCK,
        INSUFFICIENT_POPULATION, INVALID_STATE, NOT_FOUND, ALREADY_FINISHED, NOT_DUE, OVERFLOW }
    enum class Decision { INITIAL_ORDER, TROOP_CHANGE, TARGET_CHANGE, QUANTITY_CHANGE, CANCEL }
    data class CountyInputs(val population: Int, val populationMax: Int, val level: Int,
        val supplyState: Int, val completedWorks: List<String>)
    data class Recipe(val cost: Resources, val population: Int, val training: Int) {
        init { require(population > 0 && training > 0) }
    }
    /** Only a confirmed injected formula may produce. Null is never a guessed default. */
    data class Params(val status: String = "PROPOSED",
        val recipe: ((String) -> Recipe?)? = null,
        val capacityFormula: ((CountyInputs) -> Int?)? = null,
        val turnCostPolicy: ((Set<Decision>) -> Int?)? = null) {
        init { require(status in setOf("PROPOSED", "CONFIRMED")) }
    }
    data class Reserved(val troop: String, val target: Int, val recipe: Recipe, val progress: Int) {
        init { require(troop.isNotBlank() && target > 0 && progress in 0 until recipe.training) }
        fun toMetaValue(): Map<String, Any> = linkedMapOf("troop" to troop, "target" to target,
            "cost" to recipe.cost.toMetaValue(), "population" to recipe.population,
            "training" to recipe.training, "progress" to progress)
    }
    /** Durable completed output, retaining troop and gathering destination; never re-debited. */
    data class Output(val troop: String, val target: Int, val count: Long) {
        init { require(troop.isNotBlank() && target > 0 && count > 0) }
        fun toMetaValue(): Map<String, Any> = linkedMapOf("troop" to troop, "target" to target, "count" to count)
    }
    data class Order(val id: String, val troop: String, val target: Int, val quantity: Long,
        val requestedAt: Phase, val status: Status = Status.ACTIVE, val produced: Long = 0,
        val reserved: Reserved? = null, val outputs: List<Output> = emptyList()) {
        init {
            require(id.isNotBlank() && troop.isNotBlank() && target > 0 && quantity > 0)
            require(produced in 0..quantity)
            require(outputs.fold(0L) { n, o -> Math.addExact(n, o.count) } == produced)
            require(outputs.map { it.troop to it.target }.distinct().size == outputs.size)
            require((status == Status.COMPLETED) == (produced == quantity))
            require(reserved == null || status == Status.ACTIVE)
        }
        fun toMetaValue(): Map<String, Any?> = linkedMapOf("id" to id, "troop" to troop, "target" to target,
            "quantity" to quantity, "requestedAt" to requestedAt.toMetaValue(), "status" to status.name,
            "produced" to produced, "reserved" to reserved?.toMetaValue(), "outputs" to outputs.map { it.toMetaValue() })
    }
    data class Queue(val countyId: Int, val nationId: Int, val revision: Long = 0,
        val orders: List<Order> = emptyList(), val commands: List<String> = emptyList(),
        val lastBoundary: Phase? = null, val stopReason: Code? = null, val frozen: Boolean = false) {
        init {
            require(countyId > 0 && nationId > 0 && revision >= 0)
            require(orders.map { it.id }.distinct().size == orders.size)
            require(commands.all { it.isNotBlank() } && commands.distinct().size == commands.size)
            require(orders.count { it.reserved != null } <= 1)
            require(orders.none { it.reserved != null } ||
                orders.indexOfFirst { it.reserved != null } == orders.indexOfFirst { it.status == Status.ACTIVE })
        }
        fun toMetaValue(): Map<String, Any?> = linkedMapOf("version" to 1, "countyId" to countyId,
            "nationId" to nationId, "revision" to revision, "orders" to orders.map { it.toMetaValue() },
            "commands" to commands, "lastBoundary" to lastBoundary?.toMetaValue(),
            "stopReason" to stopReason?.name, "frozen" to frozen)
    }
    /** Applying this plan (warehouse CAS, population and queue) is one daemon/flush transaction. */
    data class Result(val code: Code, val queue: Queue, val debit: Resources = Resources(),
        val credit: Resources = Resources(), val populationDelta: Int = 0, val turnCost: Int = 0)

    fun instruct(queue: Queue, nationId: Int, commandId: String, orderId: String, troop: String,
        target: Int, quantity: Long, now: Phase, params: Params): Result {
        require(commandId.isNotBlank() && orderId.isNotBlank() && troop.isNotBlank() && target > 0 && quantity > 0)
        if (queue.frozen || nationId != queue.nationId) return Result(Code.OWNER_CHANGED, queue)
        if (commandId in queue.commands) return Result(Code.ALREADY_APPLIED, queue)
        val old = queue.orders.singleOrNull { it.id == orderId }
        if (old != null && old.status != Status.ACTIVE) return Result(Code.ALREADY_FINISHED, queue)
        if (old != null && quantity < Math.addExact(old.produced, if (old.reserved == null) 0L else 1L))
            return Result(Code.INVALID_STATE, queue)
        val decisions = if (old == null) listOf(Decision.INITIAL_ORDER) else buildList {
            if (old.troop != troop) add(Decision.TROOP_CHANGE)
            if (old.target != target) add(Decision.TARGET_CHANGE)
            if (old.quantity != quantity) add(Decision.QUANTITY_CHANGE)
        }
        if (decisions.isEmpty()) return Result(Code.ALREADY_APPLIED, queue)
        val cost = params.turnCostPolicy?.invoke(decisions.toSet())
        if (params.status != "CONFIRMED" || cost == null) return Result(Code.NOT_DECIDED, queue)
        require(cost > 0)
        val next = old?.copy(troop = troop, target = target, quantity = quantity,
            status = if (old.produced == quantity) Status.COMPLETED else Status.ACTIVE)
            ?: Order(orderId, troop, target, quantity, now)
        val orders = if (old == null) queue.orders + next else queue.orders.map { if (it.id == orderId) next else it }
        return Result(Code.APPLIED, queue.copy(revision = Math.incrementExact(queue.revision), orders = orders,
            commands = queue.commands + commandId, stopReason = null), turnCost = cost)
    }

    fun cancel(queue: Queue, nationId: Int, commandId: String, orderId: String, params: Params): Result {
        require(commandId.isNotBlank())
        if (queue.frozen || nationId != queue.nationId) return Result(Code.OWNER_CHANGED, queue)
        if (commandId in queue.commands) return Result(Code.ALREADY_APPLIED, queue)
        val order = queue.orders.singleOrNull { it.id == orderId } ?: return Result(Code.NOT_FOUND, queue)
        if (order.status != Status.ACTIVE) return Result(Code.ALREADY_FINISHED, queue)
        val cost = params.turnCostPolicy?.invoke(setOf(Decision.CANCEL))
        if (params.status != "CONFIRMED" || cost == null) return Result(Code.NOT_DECIDED, queue)
        require(cost >= 0)
        val next = order.copy(status = Status.CANCELLED, reserved = null)
        return Result(Code.APPLIED, queue.copy(revision = Math.incrementExact(queue.revision),
            orders = queue.orders.map { if (it.id == orderId) next else it }, commands = queue.commands + commandId,
            stopReason = null), credit = order.reserved?.recipe?.cost ?: Resources(),
            populationDelta = order.reserved?.recipe?.population ?: 0, turnCost = cost)
    }

    fun progress(queue: Queue, nationId: Int, now: Phase, inputs: CountyInputs, stock: Resources,
        params: Params): Result {
        if (queue.frozen || nationId != queue.nationId) return Result(Code.OWNER_CHANGED,
            queue.copy(frozen = true, stopReason = Code.OWNER_CHANGED))
        if (queue.lastBoundary != null && queue.lastBoundary >= now) return Result(Code.NOT_DUE, queue)
        val index = queue.orders.indexOfFirst { it.status == Status.ACTIVE }
        if (index < 0 || queue.orders[index].requestedAt >= now) return Result(Code.NOT_DUE, queue)
        val order = queue.orders[index]
        val recipe = order.reserved?.recipe ?: params.recipe?.invoke(order.troop)
        val speed = params.capacityFormula?.invoke(inputs)
        if (params.status != "CONFIRMED" || recipe == null || speed == null)
            return Result(Code.NOT_DECIDED, queue.copy(stopReason = Code.NOT_DECIDED))
        require(speed >= 0)
        if (speed == 0) return Result(Code.NOT_DUE, queue)
        return advance(queue, index, order, now, inputs.population, stock, params, speed)
    }

    private fun advance(queue: Queue, index: Int, order: Order, now: Phase, population: Int,
        stock: Resources, params: Params, speed: Int): Result {
        var next = order
        var remaining = speed
        var debit = Resources()
        var people = 0
        var reason: Code? = null
        while (remaining > 0 && next.status == Status.ACTIVE) {
            val recipe = next.reserved?.recipe ?: params.recipe?.invoke(next.troop)
            if (recipe == null) { reason = Code.NOT_DECIDED; break }
            val previous = next.reserved
            if (previous == null) {
                val total = debit.credit(recipe.cost)
                if (stock.debit(total) == null) { reason = Code.INSUFFICIENT_STOCK; break }
                val totalPeople = Math.addExact(people, recipe.population)
                if (population < totalPeople) { reason = Code.INSUFFICIENT_POPULATION; break }
                debit = total
                people = totalPeople
            }
            val reserved = previous ?: Reserved(next.troop, next.target, recipe, 0)
            val progress = minOf(remaining, recipe.training - reserved.progress)
            remaining -= progress
            if (progress < recipe.training - reserved.progress) {
                next = next.copy(reserved = reserved.copy(progress = reserved.progress + progress))
            } else {
                val produced = Math.incrementExact(next.produced)
                val existing = next.outputs.singleOrNull { it.troop == reserved.troop && it.target == reserved.target }
                val outputs = if (existing == null) next.outputs + Output(reserved.troop, reserved.target, 1)
                    else next.outputs.map { if (it == existing) it.copy(count = Math.incrementExact(it.count)) else it }
                next = next.copy(produced = produced, outputs = outputs, reserved = null,
                    status = if (produced == next.quantity) Status.COMPLETED else Status.ACTIVE)
            }
        }
        if (next == order) return Result(reason ?: Code.NOT_DUE, queue.copy(stopReason = reason))
        return Result(Code.APPLIED, queue.copy(revision = Math.incrementExact(queue.revision),
            orders = queue.orders.mapIndexed { i, item -> if (i == index) next else item }, lastBoundary = now,
            stopReason = reason), debit = debit, populationDelta = -people)
    }

    fun read(meta: Map<String, Any?>, countyId: Int): Queue? {
        if (META_KEY !in meta) return null
        val row = obj(meta[META_KEY], setOf("version", "countyId", "nationId", "revision", "orders", "commands",
            "lastBoundary", "stopReason", "frozen"))
        require(number(row["version"]) == 1L && int(row["countyId"]) == countyId)
        val orders = list(row["orders"]).map { raw ->
            val o = obj(raw, setOf("id", "troop", "target", "quantity", "requestedAt", "status", "produced", "reserved", "outputs"))
            val reserved = o["reserved"]?.let {
                val r = obj(it, setOf("troop", "target", "cost", "population", "training", "progress"))
                Reserved(text(r["troop"]), int(r["target"]), Recipe(resources(r["cost"]),
                    int(r["population"]), int(r["training"])), int(r["progress"]))
            }
            val outputs = list(o["outputs"]).map {
                val r = obj(it, setOf("troop", "target", "count"))
                Output(text(r["troop"]), int(r["target"]), number(r["count"]))
            }
            Order(text(o["id"]), text(o["troop"]), int(o["target"]), number(o["quantity"]),
                phase(o["requestedAt"]), Status.valueOf(text(o["status"])), number(o["produced"]), reserved, outputs)
        }
        return Queue(countyId, int(row["nationId"]), number(row["revision"]), orders,
            list(row["commands"]).map { text(it) }, row["lastBoundary"]?.let { phase(it) },
            row["stopReason"]?.let { Code.valueOf(text(it)) }, row["frozen"] as? Boolean ?: invalid())
    }
    private fun phase(raw: Any?): Phase {
        val row = obj(raw, setOf("year", "month", "phase"))
        return Phase(int(row["year"]), int(row["month"]), int(row["phase"]))
    }
    private fun resources(raw: Any?): Resources {
        val row = obj(raw, setOf("money", "grain", "iron", "timber", "horses"))
        return Resources(number(row["money"]), number(row["grain"]), number(row["iron"]),
            number(row["timber"]), number(row["horses"]))
    }
    private fun obj(raw: Any?, keys: Set<String>): Map<*, *> = (raw as? Map<*, *>)?.also { require(it.keys == keys) } ?: invalid()
    private fun list(raw: Any?): List<*> = raw as? List<*> ?: invalid()
    private fun text(raw: Any?): String = (raw as? String)?.also { require(it.isNotBlank()) } ?: invalid()
    private fun number(raw: Any?): Long = when (raw) { is Int -> raw.toLong(); is Long -> raw; else -> invalid() }
    private fun int(raw: Any?): Int = number(raw).also { require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) }.toInt()
    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid barracks production state")
}
