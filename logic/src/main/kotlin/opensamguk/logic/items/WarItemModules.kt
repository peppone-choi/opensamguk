package opensamguk.logic.items

import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.war.WarStatName
import opensamguk.logic.war.WarUnitCity
import opensamguk.logic.war.specialty.triggers.CheGyeoknoBaldong
import opensamguk.logic.war.specialty.triggers.CheGyeoknoSido
import opensamguk.logic.war.specialty.triggers.CheJeogyeokBaldong
import opensamguk.logic.war.specialty.triggers.CheJeogyeokSido
import opensamguk.logic.war.specialty.triggers.CheBusangMuhyo
import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.WarUnit
import opensamguk.logic.war.trigger.WarUnitTriggerCaller
import opensamguk.logic.war.trigger.triggers.EventChungchaItemConsume
import opensamguk.logic.war.trigger.triggers.WarActivateSkills
import opensamguk.logic.war.WarUnit as ConcreteWarUnit

/**
 * AI1 (A2) — the WAR-side `ActionItem` modules. POPULATES the previously-identity war hooks of
 * [ItemHooks] / [ItemRegistry] (the `getBattlePhaseSkillTriggerList` / `getBattleInitSkillTriggerList` /
 * `getWarPowerMultiplier` / war-side `onCalcStat`) — faithful ports of PHP `sammo/ActionItem/` PHP classes
 * (research Unit 9 / plan AREA A2).
 *
 * **Reuse, not duplicate (plan A2 ← A1 specialty pattern):** the trigger-injecting items REUSE the A1
 * canonical war-specialty trigger classes ([opensamguk.logic.war.specialty.triggers]) with a DISTINCT item
 * `raiseType` (`TYPE_ITEM + TYPE_DEDUP_TYPE_BASE*nnn`) so the item-version and the TYPE_NONE specialty-version
 * COEXIST in the merged caller (decision #4 — the raiseType-suffixed `getUniqueID` does not dedup them). The
 * `event_전투특기_*` items are 1:1 CLONES of the war specialties → they resolve to the SAME A1 canonical
 * module object (the body is ported ONCE in A1; A2 only registers the alias id). ZERO RNG in any hook (the
 * draws live in the injected triggers + the phase machine).
 *
 * Per-item DEDUP*nnn raiseType offsets are the PHP literals (`che_저격_비도.php`=305, `che_무기_07_맥궁.php`=107,
 * `che_부적_태현청생부.php`=303, `event_전투특기_견고.php`=404). They keep an item-injected trigger distinct from
 * the same specialty's TYPE_NONE trigger AND from a different item injecting the same trigger.
 */

/** PHP `che_저격_비도` — a pure BaseItem injecting che_저격시도/발동 at TYPE_ITEM+DEDUP*305 (50% 저격). */
object BidoItem : GeneralActionModule {
    private val raiseType = BaseWarUnitTrigger.TYPE_ITEM + BaseWarUnitTrigger.TYPE_DEDUP_TYPE_BASE * 305

    override fun getBattlePhaseSkillTriggerList(unit: WarUnit): WarUnitTriggerCaller =
        WarUnitTriggerCaller(
            CheJeogyeokSido(unit, raiseType, 0.5, 20.0, 40.0),
            CheJeogyeokBaldong(unit, raiseType),
        )
}

/**
 * PHP `che_무기_07_맥궁 extends \sammo\BaseStatItem` — the declarative 무력 +7 base (so `.statValue==7` stays
 * reachable, exactly as the plain stat item) that ALSO injects che_저격시도/발동 at TYPE_ITEM+DEDUP*107 (20%
 * 저격). Modeled as a SUBCLASS of [BaseStatItemModule] (PHP `extends`) so the parent +stat fold AND the
 * P2 `BaseStatItemModule` identity are preserved, then layered with the war injection.
 */
class MaekgungItem(base: BaseStatItemModule) :
    BaseStatItemModule(base.code, base.statNick, base.statType, base.statValue, base.rawName) {
    private val raiseType = BaseWarUnitTrigger.TYPE_ITEM + BaseWarUnitTrigger.TYPE_DEDUP_TYPE_BASE * 107

    override fun getBattlePhaseSkillTriggerList(unit: WarUnit): WarUnitTriggerCaller =
        WarUnitTriggerCaller(
            CheJeogyeokSido(unit, raiseType, 0.2, 20.0, 40.0),
            CheJeogyeokBaldong(unit, raiseType),
        )
}

/**
 * PHP `che_부적_태현청생부` — onCalcStat `injuryProb -= 1` (부상 없음); init+phase inject che_부상무효(*303) and
 * WarActivateSkills(`저격불가`, *303). Init has BOTH (부상무효 + 저격불가); phase has ONLY 저격불가
 * (`che_부적_태현청생부.php:38-49`).
 */
object TaehyeonItem : GeneralActionModule {
    private val raiseType = BaseWarUnitTrigger.TYPE_ITEM + BaseWarUnitTrigger.TYPE_DEDUP_TYPE_BASE * 303

    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double =
        if (statName == WarStatName.INJURY_PROB) value - 1 else value

    override fun getBattleInitSkillTriggerList(unit: WarUnit): WarUnitTriggerCaller =
        WarUnitTriggerCaller(
            CheBusangMuhyo(unit, raiseType),
            WarActivateSkills(unit, raiseType, false, "저격불가"),
        )

    override fun getBattlePhaseSkillTriggerList(unit: WarUnit): WarUnitTriggerCaller =
        WarUnitTriggerCaller(
            WarActivateSkills(unit, raiseType, false, "저격불가"),
        )
}

/**
 * PHP `che_격노_구정신단경` — getWarPowerMultiplier `[1 + 0.05*격노cnt, 1]` (cross-phase 격노 log count, a SMALLER
 * stack than the 격노 specialty's 0.2); injects che_격노시도(TYPE_ITEM)/격노발동. Note the per-stack 0.05 vs the
 * specialty 0.2 — a deliberate value divergence (`che_격노_구정신단경.php:24` vs `che_격노.php`).
 */
object GujeongItem : GeneralActionModule {
    override fun getWarPowerMultiplier(unit: WarUnit): Pair<Double, Double> {
        val activatedCnt = (unit as? ConcreteWarUnit)?.hasActivatedSkillOnLog("격노") ?: 0
        return (1 + 0.05 * activatedCnt) to 1.0
    }

    override fun getBattlePhaseSkillTriggerList(unit: WarUnit): WarUnitTriggerCaller =
        WarUnitTriggerCaller(
            CheGyeoknoSido(unit, BaseWarUnitTrigger.TYPE_ITEM),
            CheGyeoknoBaldong(unit),
        )
}

/**
 * PHP `event_충차` — a CONSUMABLE BaseItem; getWarPowerMultiplier `[1.5, 1]` only vs WarUnitCity (성벽 +50%);
 * injects event_충차아이템소모(TYPE_CONSUMABLE_ITEM) which decrements the per-general remaining-ram aux and
 * consumes the held item as a flush-DELTA on exhaustion. The `onArbitraryAction` 장비매매 purchase-seed
 * (`event_충차.php:24-34`, sets REMAIN_KEY=2) is CMD-TRADE plumbing, out of the battle hook surface here.
 */
object ChungchaItem : GeneralActionModule {
    override fun getWarPowerMultiplier(unit: WarUnit): Pair<Double, Double> {
        val oppose = (unit as? ConcreteWarUnit)?.getOppose()
        return if (oppose is WarUnitCity) 1.5 to 1.0 else 1.0 to 1.0
    }

    override fun getBattlePhaseSkillTriggerList(unit: WarUnit): WarUnitTriggerCaller =
        WarUnitTriggerCaller(
            EventChungchaItemConsume(unit, BaseWarUnitTrigger.TYPE_CONSUMABLE_ITEM),
        )
}
