package opensamguk.logic.war.plan

import opensamguk.logic.war.WarBattleHooks
import opensamguk.logic.war.WarUnit
import opensamguk.logic.war.WarUnitCity
import opensamguk.logic.war.WarUnitGeneral
import opensamguk.logic.war.trigger.WarUnitTriggerCaller

/** 모든 훅을 위임하는 베이스 — 계획·리플레이 훅이 프로덕션 훅을 감싼다(draw 0, 호출 순서 불변). */
open class DelegatingWarBattleHooks(protected val delegate: WarBattleHooks) : WarBattleHooks {
    override fun battleInitCaller(unit: WarUnit): WarUnitTriggerCaller? = delegate.battleInitCaller(unit)
    override fun battlePhaseCaller(unit: WarUnit): WarUnitTriggerCaller? = delegate.battlePhaseCaller(unit)
    override fun defenderNationRice(city: WarUnitCity): Double = delegate.defenderNationRice(city)
    override fun citySupply(city: WarUnitCity): Boolean = delegate.citySupply(city)
    override fun addTrain(unit: WarUnit, amount: Int) = delegate.addTrain(unit, amount)
    override fun addLevelExp(unit: WarUnitGeneral, value: Double) = delegate.addLevelExp(unit, value)
    override fun heavyDecreaseWealth(city: WarUnitCity) = delegate.heavyDecreaseWealth(city)
    override fun addConflict(city: WarUnitCity, attacker: WarUnitGeneral): Boolean = delegate.addConflict(city, attacker)
    override fun onAdvanceLog(attacker: WarUnitGeneral, city: WarUnitCity) = delegate.onAdvanceLog(attacker, city)
    override fun onContactLog(attacker: WarUnitGeneral, defender: WarUnit) = delegate.onContactLog(attacker, defender)
    override fun onPhaseLog(attacker: WarUnitGeneral, defender: WarUnit, deadAttacker: Int, deadDefender: Int) =
        delegate.onPhaseLog(attacker, defender, deadAttacker, deadDefender)
    override fun onBattleResultLog(unit: WarUnit) = delegate.onBattleResultLog(unit)
    override fun onRetreatLog(attacker: WarUnitGeneral, defender: WarUnit, noRice: Boolean) = delegate.onRetreatLog(attacker, defender, noRice)
    override fun onDefenderDownLog(attacker: WarUnitGeneral, defender: WarUnit, noRice: Boolean) = delegate.onDefenderDownLog(attacker, defender, noRice)
    override fun onSupplyRout(attacker: WarUnitGeneral, city: WarUnitCity) = delegate.onSupplyRout(attacker, city)
    override fun onConflictLog(attacker: WarUnitGeneral, city: WarUnitCity) = delegate.onConflictLog(attacker, city)
    override fun plannedStop(attacker: WarUnitGeneral, defender: WarUnit, phaseIndex: Int): PlanStop? = delegate.plannedStop(attacker, defender, phaseIndex)
}

/** 봉인된 계획의 정지 판정 — 상태 읽기만(`getCrew`/`getAtmos`), draw 0. */
class PlannedWarBattleHooks(delegate: WarBattleHooks, private val plan: SealedBattlePlan, private val crewBefore: Int) : DelegatingWarBattleHooks(delegate) {
    override fun plannedStop(attacker: WarUnitGeneral, defender: WarUnit, phaseIndex: Int): PlanStop? =
        BattlePlanRules.plannedStop(plan, phaseIndex, crewBefore, attacker.getCrew(), attacker.getAtmos())
}

/**
 * 리플레이 기록 — 위임 + 기록(draw 0). `onPhaseLog` 마다 페이즈 한 줄(id 만), `onRetreatLog` 로 퇴각, `onDefenderDownLog` 로
 * 「마지막 상대가 무너짐」(다음 `onPhaseLog` 가 리셋), `plannedStop` 결과를 초안에 남긴다(spec §5 result 단일 규칙의 입력).
 */
class ReplayRecordingHooks(delegate: WarBattleHooks, private val draft: BattleReplayDraft) : DelegatingWarBattleHooks(delegate) {
    private var pendingContact = false

    override fun onContactLog(attacker: WarUnitGeneral, defender: WarUnit) {
        pendingContact = true
        delegate.onContactLog(attacker, defender)
    }

    override fun onPhaseLog(attacker: WarUnitGeneral, defender: WarUnit, deadAttacker: Int, deadDefender: Int) {
        val (kind, id) = when (defender) {
            is WarUnitGeneral -> "general" to defender.getGeneral().id
            is WarUnitCity -> "city" to defender.state.city.id
            else -> "city" to 0
        }
        draft.phases += ReplayPhase(
            index = attacker.getPhase() + 1, defId = id, defKind = kind, contact = pendingContact,
            deadAttacker = deadAttacker, deadDefender = deadDefender, crewAttacker = attacker.getCrew(), hpDefender = defender.getHP(),
        )
        pendingContact = false
        draft.lastDefenderDown = false
        draft.deadAttacker += deadAttacker
        draft.deadDefender += deadDefender
        delegate.onPhaseLog(attacker, defender, deadAttacker, deadDefender)
    }

    override fun onRetreatLog(attacker: WarUnitGeneral, defender: WarUnit, noRice: Boolean) {
        draft.retreat = true
        delegate.onRetreatLog(attacker, defender, noRice)
    }

    override fun onDefenderDownLog(attacker: WarUnitGeneral, defender: WarUnit, noRice: Boolean) {
        draft.lastDefenderDown = true
        delegate.onDefenderDownLog(attacker, defender, noRice)
    }

    override fun plannedStop(attacker: WarUnitGeneral, defender: WarUnit, phaseIndex: Int): PlanStop? {
        val stop = delegate.plannedStop(attacker, defender, phaseIndex)
        if (stop != null && draft.stop == null) { draft.stop = stop; draft.stopAtPhase = phaseIndex }
        return stop
    }
}
