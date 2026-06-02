package opensamguk.logic.event

import opensamguk.logic.world.A3EventActions
import opensamguk.logic.world.AssignGeneralSpecialityAction
import opensamguk.logic.world.MergeInheritPointRankAction
import opensamguk.logic.world.ProcessIncomeAction
import opensamguk.logic.world.ProcessSemiAnnualAction
import opensamguk.logic.world.ProcessWarIncomeAction
import opensamguk.logic.world.RaiseDisasterAction
import opensamguk.logic.world.RandomizeCityTradeRateAction
import opensamguk.logic.world.UpdateCitySupplyAction

/**
 * P6 / Task 5 — Single registrar that chains ALL Action leaf registrations into [EventActionFactory].
 *
 * The engine config calls [WorldActions.register] once instead of manually chaining each family
 * registrar. Every leaf listed here is the lone per-family touch-point on F2-owned code (plan §append
 * protocol); this object merely assembles them.
 */
object WorldActions {
    fun register(factory: EventActionFactory): EventActionFactory {
        var f = factory
        f = LightActions.register(f)
        f = A3EventActions.register(f)
        f = BettingActions.register(f)
        f = ProcessIncomeAction.register(f)
        f = ProcessWarIncomeAction.register(f)
        f = RandomizeCityTradeRateAction.register(f)
        f = ProcessSemiAnnualAction.register(f)
        f = MergeInheritPointRankAction.register(f)
        f = UpdateCitySupplyAction.register(f)
        f = RaiseDisasterAction.register(f)
        f = AssignGeneralSpecialityAction.register(f)
        f = DeleteEventAction.register(f)
        return f
    }
}
