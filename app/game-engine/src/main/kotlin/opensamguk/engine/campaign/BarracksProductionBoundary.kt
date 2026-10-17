package opensamguk.engine.campaign

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.logic.domestic.BarracksProduction
import opensamguk.logic.domestic.BarracksProduction.Code
import opensamguk.logic.domestic.BarracksProduction.Params
import opensamguk.logic.domestic.BarracksProduction.Queue
import opensamguk.logic.domestic.BarracksProduction.Result
import opensamguk.logic.domestic.CountyWorks
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.input.RuleProfile

/** No input handler: strategic decisions still require their personal-turn execution boundary. */
class BarracksProductionBoundary(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val params: Params = Params(),
) {
    fun run(): Map<Int, Code> {
        if (world.ruleProfile != RuleProfile.HWIHA) return emptyMap()
        return world.administrativeCountyIds.sorted().mapNotNull { countyId ->
            val city = world.getCityById(countyId) ?: return@mapNotNull null
            val queue = try { BarracksProduction.read(city.meta, countyId) }
                catch (_: IllegalArgumentException) { return@mapNotNull countyId to Code.INVALID_STATE }
                catch (_: ArithmeticException) { return@mapNotNull countyId to Code.OVERFLOW }
                ?: return@mapNotNull null
            countyId to progress(queue)
        }.toMap()
    }

    private fun progress(queue: Queue): Code {
        val city = world.getCityById(queue.countyId) ?: return Code.INVALID_STATE
        if (queue.frozen || city.nationId != queue.nationId) {
            val frozen = queue.copy(frozen = true, stopReason = Code.OWNER_CHANGED)
            if (queue != frozen) store(queue.countyId, frozen, city.population)
            return Code.OWNER_CHANGED
        }
        return try {
            val warehouse = CountyWarehouse.read(city.meta, queue.countyId)
                ?: return stop(queue, Code.INSUFFICIENT_STOCK)
            val works = CountyWorks.read(city.meta)?.completed?.map { it.work.name } ?: emptyList()
            val result = BarracksProduction.progress(queue, city.nationId, world.phaseNow(),
                BarracksProduction.CountyInputs(city.population, city.populationMax, city.level, city.supplyState, works),
                warehouse.stock, params)
            if (result.code != Code.APPLIED) {
                if (result.queue != queue) store(queue.countyId, result.queue, city.population)
                result.code
            } else applyPlan(queue, warehouse.revision, result)
        } catch (_: IllegalArgumentException) { stop(queue, Code.INVALID_STATE) }
          catch (_: ArithmeticException) { stop(queue, Code.OVERFLOW) }
    }

    /** Cancellation has its own unresolved turn-cost seam; no refund until it is decided. */
    fun cancel(countyId: Int, nationId: Int, commandId: String, orderId: String,
        expectedQueueRevision: Long, expectedWarehouseRevision: Long): Code {
        if (world.ruleProfile != RuleProfile.HWIHA || countyId !in world.administrativeCountyIds)
            return Code.INVALID_STATE
        val city = world.getCityById(countyId) ?: return Code.INVALID_STATE
        return try {
            val queue = BarracksProduction.read(city.meta, countyId) ?: return Code.NOT_FOUND
            if (queue.revision != expectedQueueRevision) return Code.INVALID_STATE
            if (city.nationId != nationId || queue.nationId != nationId) return Code.OWNER_CHANGED
            // A missing original ledger is not permission to recreate it or refund elsewhere.
            if (CountyWarehouse.read(city.meta, countyId) == null) return Code.NOT_DECIDED
            val result = BarracksProduction.cancel(queue, nationId, commandId, orderId, params)
            if (result.code != Code.APPLIED) return result.code
            // Positive personal-turn consumption must be wired by that future command's owner.
            if (result.turnCost != 0) return Code.NOT_DECIDED
            applyPlan(queue, expectedWarehouseRevision, result)
        } catch (_: IllegalArgumentException) { Code.INVALID_STATE }
          catch (_: ArithmeticException) { Code.OVERFLOW }
    }

    private fun applyPlan(before: Queue, warehouseRevision: Long, result: Result): Code {
        val city = world.getCityById(before.countyId) ?: return Code.INVALID_STATE
        val current = BarracksProduction.read(city.meta, before.countyId) ?: return Code.INVALID_STATE
        if (current != before || current.revision != before.revision) return Code.INVALID_STATE
        if (city.nationId != before.nationId || current.frozen) return Code.OWNER_CHANGED
        val population = Math.addExact(city.population, result.populationDelta)
        if (population < 0) return Code.INSUFFICIENT_POPULATION
        // Prepare every potentially failing value before CAS; record all writes in the same flush.
        result.queue.toMetaValue()
        val settlement = WarehouseSettlement(world, recorder).settle(before.countyId, before.nationId,
            warehouseRevision, result.debit, result.credit)
        val code = when (settlement) {
            WarehouseSettlement.Result.APPLIED -> Code.APPLIED
            WarehouseSettlement.Result.OWNER_CHANGED -> Code.OWNER_CHANGED
            WarehouseSettlement.Result.INSUFFICIENT_STOCK -> Code.INSUFFICIENT_STOCK
            WarehouseSettlement.Result.OVERFLOW -> Code.OVERFLOW
            else -> Code.INVALID_STATE
        }
        if (code != Code.APPLIED) return code
        // Re-read after settlement so the new warehouse revision cannot be overwritten by queue state.
        store(before.countyId, result.queue, population)
        return Code.APPLIED
    }

    private fun stop(queue: Queue, code: Code): Code {
        val city = world.getCityById(queue.countyId) ?: return Code.INVALID_STATE
        if (queue.stopReason != code) store(queue.countyId, queue.copy(stopReason = code), city.population)
        return code
    }

    private fun store(countyId: Int, queue: Queue, population: Int) {
        val before = checkNotNull(world.getCityById(countyId))
        val after = before.copy(population = population,
            meta = before.meta + (BarracksProduction.META_KEY to queue.toMetaValue()))
        checkNotNull(world.applyCityDirtyFree(after))
        recorder.diffCity(PerTurnOverlay.toLogicCity(before), PerTurnOverlay.toLogicCity(after))
    }
}
