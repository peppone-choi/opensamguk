package opensamguk.logic.event

import opensamguk.logic.world.A3EventActions
import opensamguk.logic.world.AssignGeneralSpecialityAction
import opensamguk.logic.world.AutoDeleteInvaderAction
import opensamguk.logic.world.BlockScoutAction
import opensamguk.logic.world.ChangeCityAction
import opensamguk.logic.world.InvaderEndingAction
import opensamguk.logic.world.MergeInheritPointRankAction
import opensamguk.logic.world.ProcessIncomeAction
import opensamguk.logic.world.ProcessSemiAnnualAction
import opensamguk.logic.world.ProcessWarIncomeAction
import opensamguk.logic.world.RaiseDisasterAction
import opensamguk.logic.world.RandomizeCityTradeRateAction
import opensamguk.logic.world.UnblockScoutAction
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
        // A3 결정적(draw 0) 이벤트 리프 5종 — 정찰 차단/해제 토글, 침략자(이민족) 종료/자동정리, 도시 수치 패치.
        // 각 리프가 자기 PHP 클래스명으로 자기 등록한다(plan §append protocol).
        f = BlockScoutAction.register(f)        // "BlockScoutAction"
        f = UnblockScoutAction.register(f)      // "UnblockScoutAction"
        f = InvaderEndingAction.register(f)     // "InvaderEnding"
        f = AutoDeleteInvaderAction.register(f) // "AutoDeleteInvader"
        f = ChangeCityAction.register(f)        // "ChangeCity"
        f = DeleteEventAction.register(f)
        return f
    }
}
