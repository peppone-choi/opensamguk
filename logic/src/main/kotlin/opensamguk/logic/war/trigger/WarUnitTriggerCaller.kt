package opensamguk.logic.war.trigger

/**
 * Port target = PHP `sammo/WarUnitTriggerCaller.php:3-9`.
 *
 * The battle-side [TriggerCaller] whose [checkValidTrigger] gate accepts ONLY [BaseWarUnitTrigger]s — so a
 * non-war trigger cannot leak into the battle draw stream. This is the caller type the F5
 * `getBattlePhaseSkillTriggerList` seeds (with the FT0 base-7 classes) and merges per-source callers into.
 */
class WarUnitTriggerCaller(vararg triggerList: Trigger) : TriggerCaller(*triggerList) {
    override fun checkValidTrigger(trigger: Trigger): Boolean = trigger is BaseWarUnitTrigger
}
