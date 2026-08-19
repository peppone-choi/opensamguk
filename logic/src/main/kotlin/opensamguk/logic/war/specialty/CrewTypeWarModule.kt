package opensamguk.logic.war.specialty

import opensamguk.common.constants.GameUnitDetail
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.war.trigger.WarUnit
import opensamguk.logic.war.trigger.WarUnitTriggerCaller
import opensamguk.logic.war.trigger.triggers.CheBangeoryeokJeunga5p

/**
 * Source #6 of the action stack — the CREW-TYPE war triggers (PHP `General.php:787-799` slot #6 = `crewType`;
 * `GameUnitDetail::getBattle{Init,Phase}SkillTriggerList` builds them from the crewType's
 * `initSkillTrigger` / `phaseSkillTrigger` name lists, `GameUnitDetail.php:239-275`).
 *
 * **Why this exists (P4 port gap closed by G1b).** Every footman crewType (1100..) carries
 * `phaseSkillTrigger = ['che_방어력증가5p']` — the defender's +5% defence (oppose `multiplyWarPowerMultiply(1/1.05)`
 * at FINAL+200). Until this module, the crewType source contributed NO battle triggers, so a footman defender
 * never reduced the attacker's warpower — desyncing per-phase damage from the PHP grand truth (battle-01:
 * Kotlin over-killed by ~4%/phase). The draw STREAM was unaffected (this trigger draws nothing), so only the
 * post-state numeric gate caught it.
 *
 * Pure map lookup over the crewType's declared trigger names → the concrete [opensamguk.logic.war.trigger]
 * classes. ZERO RNG. Unknown names are skipped (identity — che 런타임 불변, 이 배틀 결과 자체는 바뀌지 않는다)
 * BUT logged once via [warnUnknownOnce] so a `units.json` 오타·미구현 트리거가 조용히 사라지지 않는다.
 */
class CrewTypeWarModule(private val crewType: GameUnitDetail) : GeneralActionModule {

    override fun getBattleInitSkillTriggerList(unit: WarUnit): WarUnitTriggerCaller? =
        buildCaller(crewType.initSkillTrigger, unit)

    override fun getBattlePhaseSkillTriggerList(unit: WarUnit): WarUnitTriggerCaller? =
        buildCaller(crewType.phaseSkillTrigger, unit)

    private fun buildCaller(names: List<String>?, unit: WarUnit): WarUnitTriggerCaller? {
        if (names.isNullOrEmpty()) return null
        val triggers = names.mapNotNull { name -> build(name, unit) }
        if (triggers.isEmpty()) return null
        return WarUnitTriggerCaller(*triggers.toTypedArray())
    }

    private fun build(name: String, unit: WarUnit): opensamguk.logic.war.trigger.BaseWarUnitTrigger? = when (name) {
        "che_방어력증가5p" -> CheBangeoryeokJeunga5p(unit)
        else -> {
            warnUnknownOnce(name)
            null
        }
    }

    companion object {
        // ponytail: 이름당 한 번만 stderr 경고 — 매 전투 스킵마다 스팸을 내지 않는다.
        private val warnedNames = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        private fun warnUnknownOnce(name: String) {
            if (warnedNames.add(name)) {
                System.err.println("[CrewTypeWarModule] units.json 스킬 트리거 미구현/오타 (조용히 스킵됨): $name")
            }
        }
    }
}
