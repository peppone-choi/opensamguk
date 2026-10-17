package opensamguk.logic.domestic

import kotlin.test.*
import opensamguk.logic.domestic.BarracksProduction.Code
import opensamguk.logic.domestic.BarracksProduction.CountyInputs
import opensamguk.logic.domestic.BarracksProduction.Decision
import opensamguk.logic.domestic.BarracksProduction.META_KEY
import opensamguk.logic.domestic.BarracksProduction.Output
import opensamguk.logic.domestic.BarracksProduction.Params
import opensamguk.logic.domestic.BarracksProduction.Queue
import opensamguk.logic.domestic.BarracksProduction.Recipe
import opensamguk.logic.domestic.BarracksProduction.Status
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.Phase

class BarracksProductionTest {
    private val now = Phase(200, 1, 1)
    private val inputs = CountyInputs(100, 200, 1, 1, emptyList())
    private val stock = Resources(100, 100, 100, 100, 100)
    private val recipe = Recipe(Resources(2, 3, 4, 5, 6), 1, 3)
    private val params = Params("CONFIRMED", { recipe }, { 1 }, { if (Decision.CANCEL in it) 0 else 2 })
    private fun ordered(quantity: Long = 2, p: Params = params) = BarracksProduction.instruct(Queue(10, 1),
        1, "initial", "order", "infantry", 20, quantity, now, p)
    private fun step(queue: Queue, phase: Int, stock: Resources = this.stock, population: Int = 100,
        p: Params = params) = BarracksProduction.progress(queue, 1, now.plus(phase),
        inputs.copy(population = population), stock, p)

    @Test fun `typed codec preserves absent old metadata and exact integer width`() {
        assertNull(BarracksProduction.read(mapOf("keep" to 7), 10))
        val queue = step(ordered(Long.MAX_VALUE).queue, 1).queue
        assertEquals(queue, BarracksProduction.read(mapOf(META_KEY to queue.toMetaValue()), 10))
        val bad = queue.toMetaValue() + ("revision" to "1")
        assertFailsWith<IllegalArgumentException> { BarracksProduction.read(mapOf(META_KEY to bad), 10) }
        assertFailsWith<IllegalArgumentException> { BarracksProduction.read(mapOf(META_KEY to queue.toMetaValue()), 11) }
        assertFailsWith<IllegalArgumentException> { BarracksProduction.read(mapOf(META_KEY to (queue.toMetaValue() + ("extra" to 1))), 10) }
    }

    @Test fun `first instruction type and gathering target changes use the injected turn seam only`() {
        val decisions = mutableListOf<Set<Decision>>()
        val p = params.copy(turnCostPolicy = { decisions += it; 2 })
        val first = ordered(p = p)
        assertEquals(2, first.turnCost)
        assertEquals(setOf(Decision.INITIAL_ORDER), decisions.single())
        val changed = BarracksProduction.instruct(first.queue, 1, "change", "order", "archer", 30, 2, now, p)
        assertEquals(Code.APPLIED, changed.code)
        assertEquals(setOf(Decision.TROOP_CHANGE, Decision.TARGET_CHANGE), decisions.last())
        assertEquals(2, changed.turnCost)
        assertEquals(Code.ALREADY_APPLIED, BarracksProduction.instruct(changed.queue, 1,
            "change", "order", "archer", 30, 2, now, p).code)
        assertEquals(changed.queue, BarracksProduction.instruct(changed.queue, 1,
            "same", "order", "archer", 30, 2, now, p).queue)
        assertEquals(Code.NOT_DECIDED, ordered(p = Params()).code)
        assertEquals(Code.NOT_DECIDED, ordered(p = params.copy(turnCostPolicy = null)).code)
    }

    @Test fun `training reserves cost and population once then continues with zero personal turns`() {
        val queue = ordered().queue
        assertEquals(Code.NOT_DUE, step(queue, 0).code)
        val one = step(queue, 1)
        assertEquals(recipe.cost, one.debit)
        assertEquals(-1, one.populationDelta)
        assertEquals(0, one.turnCost)
        assertEquals(Code.NOT_DUE, step(one.queue, 1).code)
        assertEquals(Code.NOT_DUE, step(one.queue, 0).code)
        val two = step(one.queue, 2, Resources(), 0)
        assertEquals(Code.APPLIED, two.code)
        assertEquals(Resources(), two.debit)
        assertEquals(0, two.populationDelta)
        val three = step(two.queue, 3, Resources(), 0)
        assertEquals(1L, three.queue.orders.single().produced)
        assertEquals(listOf(Output("infantry", 20, 1)), three.queue.orders.single().outputs)
        assertEquals(0, three.turnCost)
        val four = step(three.queue, 4)
        assertEquals(recipe.cost, four.debit)
        assertEquals(-1, four.populationDelta)
    }

    @Test fun `no global population ceiling and formula receives the actual county development inputs`() {
        val developed = inputs.copy(population = 2_000_000, populationMax = 9_000_000,
            level = 7, completedWorks = listOf("BARRACKS"))
        var observed: CountyInputs? = null
        val p = params.copy(recipe = { recipe.copy(training = 1) }, capacityFormula = { observed = it; 5 })
        val result = BarracksProduction.progress(ordered(5).queue, 1, now.plus(1), developed, stock, p)
        assertEquals(developed, observed)
        assertEquals(5L, result.queue.orders.single().produced)
        assertEquals(Status.COMPLETED, result.queue.orders.single().status)
        assertEquals(Resources(10, 15, 20, 25, 30), result.debit)
        assertEquals(-5, result.populationDelta)
        assertEquals(0, result.turnCost)
    }

    @Test fun `null formula recipe or unconfirmed values never produce`() {
        for (p in listOf(Params(), params.copy(capacityFormula = null), params.copy(recipe = null),
            params.copy(recipe = { null }), params.copy(capacityFormula = { null }), params.copy(status = "PROPOSED"))) {
            val result = step(ordered().queue, 1, p = p)
            assertEquals(Code.NOT_DECIDED, result.code)
            assertEquals(Resources(), result.debit)
            assertEquals(0, result.populationDelta)
            assertEquals(0L, result.queue.orders.single().produced)
        }
    }

    @Test fun `insufficient stock or population pauses without partial reservation and can resume`() {
        val queue = ordered().queue
        assertEquals(Code.INSUFFICIENT_STOCK, step(queue, 1, Resources()).code)
        val stopped = step(queue, 1, population = 0)
        assertEquals(Code.INSUFFICIENT_POPULATION, stopped.code)
        assertNull(stopped.queue.orders.single().reserved)
        assertEquals(Code.APPLIED, step(stopped.queue, 2).code)
    }

    @Test fun `partial training cancellation restores all reserved resources and population exactly once`() {
        val partial = step(ordered().queue, 1).queue
        val cancelled = BarracksProduction.cancel(partial, 1, "cancel", "order", params)
        assertEquals(Code.APPLIED, cancelled.code)
        assertEquals(recipe.cost, cancelled.credit)
        assertEquals(1, cancelled.populationDelta)
        assertNull(cancelled.queue.orders.single().reserved)
        assertEquals(Status.CANCELLED, cancelled.queue.orders.single().status)
        val retry = BarracksProduction.cancel(cancelled.queue, 1, "cancel", "order", params)
        assertEquals(Code.ALREADY_APPLIED, retry.code)
        assertEquals(Resources(), retry.credit)
        assertEquals(0, retry.populationDelta)
        assertEquals(Code.ALREADY_FINISHED, BarracksProduction.cancel(cancelled.queue, 1,
            "another", "order", params).code)
        assertEquals(Code.NOT_DECIDED, BarracksProduction.cancel(partial, 1,
            "unresolved", "order", params.copy(turnCostPolicy = null)).code)
    }

    @Test fun `completed production cannot be refunded and cancellation retains earlier completed output`() {
        val fast = params.copy(capacityFormula = { 4 })
        val partialSecond = step(ordered().queue, 1, p = fast)
        assertEquals(1L, partialSecond.queue.orders.single().produced)
        val cancelled = BarracksProduction.cancel(partialSecond.queue, 1, "cancel", "order", params)
        assertEquals(recipe.cost, cancelled.credit)
        assertEquals(1L, cancelled.queue.orders.single().produced)
        val done = step(ordered(1).queue, 1, p = fast)
        assertEquals(Code.ALREADY_FINISHED, BarracksProduction.cancel(done.queue, 1, "cancel", "order", params).code)
    }

    @Test fun `ownership transfer freezes all existing debt and forbids refund to a new owner`() {
        val queue = step(ordered().queue, 1).queue
        val changed = BarracksProduction.progress(queue, 2, now.plus(2), inputs, stock, params)
        assertEquals(Code.OWNER_CHANGED, changed.code)
        assertTrue(changed.queue.frozen)
        assertEquals(Resources(), changed.credit)
        assertEquals(Code.OWNER_CHANGED, step(changed.queue, 3).code)
        assertEquals(Code.OWNER_CHANGED, BarracksProduction.cancel(queue, 2, "cancel", "order", params).code)
    }

    @Test fun `troop change does not rewrite already reserved costs and next production uses new recipe`() {
        val partial = step(ordered().queue, 1).queue
        val changed = BarracksProduction.instruct(partial, 1, "change", "order", "archer", 30, 2, now.plus(1), params)
        assertEquals("infantry", changed.queue.orders.single().reserved!!.troop)
        val finished = step(changed.queue, 2, p = params.copy(capacityFormula = { 2 }))
        val next = step(finished.queue, 3)
        assertEquals("archer", next.queue.orders.single().reserved!!.troop)
        assertEquals(30, next.queue.orders.single().reserved!!.target)
    }

    @Test fun `FIFO ordering is stable and identical inputs have identical serialized output`() {
        var queue = ordered(1).queue
        queue = BarracksProduction.instruct(queue, 1, "second", "second", "archer", 30, 1, now, params).queue
        fun trace(): String {
            var q = queue
            val rows = mutableListOf<Map<String, Any?>>()
            repeat(8) { i -> q = step(q, i + 1).queue; rows += q.toMetaValue() }
            return rows.toString()
        }
        assertEquals(trace(), trace())
        val first = step(queue, 1)
        assertNull(first.queue.orders[1].reserved)
    }
}
