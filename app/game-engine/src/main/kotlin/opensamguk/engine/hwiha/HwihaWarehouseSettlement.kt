package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.logic.economy.HwihaCountyWarehouse
import opensamguk.logic.economy.HwihaResources
import opensamguk.logic.input.RuleProfile

/** Internal settlement boundary. Callers must resolve authorized supply access before submitting a debit. */
class HwihaWarehouseSettlement(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder) {
    enum class Result { APPLIED, WRONG_RULE_PROFILE, NOT_COUNTY, OWNER_CHANGED, NOT_READY, INVALID_STATE,
        STALE_REVISION, INSUFFICIENT_STOCK, OVERFLOW }

    /** Revision comes from the persisted action: retrying it cannot spend again after a cold reload. */
    fun settle(countyId: Int, expectedNationId: Int, expectedRevision: Long,
        debit: HwihaResources, credit: HwihaResources = HwihaResources()): Result {
        if (world.ruleProfile != RuleProfile.HWIHA) return Result.WRONG_RULE_PROFILE
        if (countyId !in world.administrativeCountyIds) return Result.NOT_COUNTY
        val before = world.getCityById(countyId) ?: return Result.NOT_COUNTY
        if (expectedNationId < 0 || before.nationId != expectedNationId) return Result.OWNER_CHANGED
        val current = try { HwihaCountyWarehouse.read(before.meta, countyId) }
            catch (_: IllegalArgumentException) { return Result.INVALID_STATE }
            ?: return Result.NOT_READY
        if (current.revision != expectedRevision) return Result.STALE_REVISION
        // Check all debits against opening stock: credits cannot finance an otherwise invalid action.
        val remaining = current.stock.debit(debit) ?: return Result.INSUFFICIENT_STOCK
        val next = try { current.replace(remaining.credit(credit)) }
            catch (_: ArithmeticException) { return Result.OVERFLOW }
        val after = before.copy(meta = before.meta + (HwihaCountyWarehouse.META_KEY to next.toMetaValue()))
        if (world.applyCityDirtyFree(after) == null) return Result.NOT_COUNTY
        recorder.diffCity(PerTurnOverlay.toLogicCity(before), PerTurnOverlay.toLogicCity(after))
        return Result.APPLIED
    }
}
