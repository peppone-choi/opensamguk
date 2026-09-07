package opensamguk.logic.war

import opensamguk.logic.war.trigger.WarUnitTriggerCaller

/**
 * F3-owned seam carrying the NON-DRAW, cross-area effects [processWarNG] invokes but does NOT own — so the
 * phase machine stays a PURE, draw-order-pure skeleton fully stubbable by the FP1 test and wired by B1.
 *
 * Two responsibilities:
 *  1. **Trigger-caller fetch ([battleInitCaller] / [battlePhaseCaller]).** PHP `processWar_NG` calls
 *     `$unit->getGeneral()->getBattle{Init,Phase}SkillTriggerList($unit)` (`:331,:340`). That seed+merge is
 *     the SINGLE indivisible pipeline op (F5 / `General.php:918-938`) — F3 CONSUMES it, never re-seeds. The
 *     real B1 hooks delegate to the pipeline; the FP1 test injects recording/empty callers. A `null` per-source
 *     caller is a [WarUnitTriggerCaller.merge] no-op.
 *  2. **The non-draw side effects** — the 진격/대결/전투결과/퇴각/전멸/분쟁 logs, [ConflictMap.addConflict]
 *     (A4), the defender-nation rice / city-supply context reads, and the addTrain / heavyDecreaseWealth /
 *     addLevelExp bookkeeping. NONE of these touch the shared war rng, so routing them through the seam keeps
 *     the draw stream identical whether stubbed (test) or live (B1).
 *
 * All methods default to inert / identity so [NOOP] is a complete throwaway implementation.
 */
interface WarBattleHooks {

    // --- (1) trigger-caller fetch (pipeline-owned; F3 consumes) -----------------------------------------

    /** PHP `$unit->getGeneral()->getBattleInitSkillTriggerList($unit)` — null = empty per-source caller. */
    fun battleInitCaller(unit: WarUnit): WarUnitTriggerCaller? = null

    /** PHP `$unit->getGeneral()->getBattlePhaseSkillTriggerList($unit)` (seeds base-7 + merges) — null = empty. */
    fun battlePhaseCaller(unit: WarUnit): WarUnitTriggerCaller? = null

    // --- (2) non-draw context reads / bookkeeping -------------------------------------------------------

    /** PHP `$city->getNationVar('rice')` — the defender nation's rice (병량 패퇴 check, `:268`). */
    fun defenderNationRice(city: WarUnitCity): Double = 1.0

    /** PHP `$city->getVar('supply')` — truthy = the city is on a supply line (`:268`). */
    fun citySupply(city: WarUnitCity): Boolean = false

    /** PHP `$unit->addTrain($n)` — general clamps to maxTrainByWar; base/city is a no-op (`:273,:298,:299`). */
    fun addTrain(unit: WarUnit, amount: Int) {}

    /** PHP `$attacker->addLevelExp($v)` — the conquerCity +1000 explevel bonus (`:438`). */
    fun addLevelExp(unit: WarUnitGeneral, value: Double) {}

    /** PHP `$city->heavyDecreaseWealth()` — the 병량 패퇴 agri/comm/secu ×0.5 (`:277`). */
    fun heavyDecreaseWealth(city: WarUnitCity) {}

    /** PHP `$city->addConflict()` (A4 ConflictMap) — returns `newConflict` (NO draws, pure arithmetic + sort). */
    fun addConflict(city: WarUnitCity, attacker: WarUnitGeneral): Boolean = false

    // --- (2) non-draw logs (Josa-backed; B1 wires BattleLogTokens) --------------------------------------

    /** The 진격 global+general log echoing warSeed (`:252-253`). */
    fun onAdvanceLog(attacker: WarUnitGeneral, city: WarUnitCity) {}

    /** The 대결/공격/수비 first-contact log (`:303-326`). */
    fun onContactLog(attacker: WarUnitGeneral, defender: WarUnit) {}

    /** The per-phase `<Y1>【name】</> <C>HP (-dead)</>` battle-detail log (`:381-393`). */
    fun onPhaseLog(attacker: WarUnitGeneral, defender: WarUnit, deadAttacker: Int, deadDefender: Int) {}

    /** PHP `$unit->logBattleResult()` (`:401-402,:425-426,:474-475`). */
    fun onBattleResultLog(unit: WarUnit) {}

    /** The 공격자 퇴각 log (`:412-419`). */
    fun onRetreatLog(attacker: WarUnitGeneral, defender: WarUnit, noRice: Boolean) {}

    /** The 수비자 전멸/패퇴 log (`:455-466`). */
    fun onDefenderDownLog(attacker: WarUnitGeneral, defender: WarUnit, noRice: Boolean) {}

    /** The 병량 패퇴 global+history log (`:279-284`). */
    fun onSupplyRout(attacker: WarUnitGeneral, city: WarUnitCity) {}

    /** The 분쟁 global history log on `newConflict` (`:493-497`). */
    fun onConflictLog(attacker: WarUnitGeneral, city: WarUnitCity) {}

    // --- (3) Phase 4X-C 출병 계획 봉인 — 페이즈 사이의 계획 정지 판정(draw 0). 기본 null = 오늘 동작 ------------

    /**
     * spec v4.1 §5: `addPhase()` 뒤·두 `continueWar()` 뒤에서 한 번만 불린다. non-null 이면 수비자가 무너지지 않은 페이즈에
     * 한해 자연 퇴각과 같은 함수를 타고, 무너진 페이즈면 다음 수비자로 넘어가지 않고 멈춘다. [NOOP]·프로덕션 훅은 null.
     */
    fun plannedStop(attacker: WarUnitGeneral, defender: WarUnit, phaseIndex: Int): opensamguk.logic.war.plan.PlanStop? = null

    companion object {
        /** A complete inert implementation — every method defaults to identity/no-op. */
        val NOOP: WarBattleHooks = object : WarBattleHooks {}
    }
}
