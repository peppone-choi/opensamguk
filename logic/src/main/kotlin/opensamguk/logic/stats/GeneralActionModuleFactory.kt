package opensamguk.logic.stats

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.UnitCatalog
import opensamguk.logic.domain.General
import opensamguk.logic.inheritance.InheritBuffModuleFactory
import opensamguk.logic.traits.OfficerLevelModule
import opensamguk.logic.war.specialty.CrewTypeWarModule

/**
 * A per-family registry that resolves a string code (the persisted DB column value — e.g. nation
 * `type_code`, general `special` / `personal` / `*_code`) to its concrete [GeneralActionModule], or
 * `null` for an absent / inert slot (PHP returns the `None` no-op which folds as identity; we skip it
 * outright so the built list contains only effective sources).
 *
 * **Wave-3 shared-file protocol:** this interface is DEFINED here (S-MODULES owns it); the trait/item
 * families (TRAITS-DOMESTIC / TRAITS-PERSONALITY / TRAITS-NATION / ITEMS) each implement it in THEIR
 * OWN registry file (`SpecialDomesticRegistry` / `PersonalityRegistry` / `NationTypeRegistry` /
 * `ItemRegistry`) and the factory only LOOKS THEM UP — no family ever edits this factory body.
 */
fun interface GeneralActionModuleSource {
    /** null / blank / "None" code → null (skipped slot). */
    fun resolve(code: String?): GeneralActionModule?
}

/**
 * Builds the ordered 9-source action-module list for a [General] in the exact PHP `getActionList()`
 * order (`General.php:787-799`):
 *
 *   array_merge([nationType, officerLevelObj, specialDomesticObj, specialWarObj, personalityObj,
 *                crewType, inheritBuffObj, scenarioEffect], itemObjs)
 *   where itemObjs = [horse, weapon, book, item].
 *
 * The source-CONSTRUCTION order (`General.php:100-132`) feeds this list. null entries are skipped
 * during the fold; this factory drops them eagerly so the returned list contains ONLY effective
 * modules in fold order. The officer module is ALWAYS present (its lbonus may be 0 = identity).
 *
 * P2-domestic stubs: `specialWar`, `crewType`, `inheritBuff`, `scenarioEffect` are identity (war /
 * crew-type sub-fold / inheritance / scenario effects are P4/P6) and are therefore omitted entirely.
 * They are listed in [GeneralActionPipeline.MODULE_ORDER] so the fold order stays pinned for when
 * those sources are populated in a later phase.
 *
 * The concrete domestic-specialty / personality / nation-type / item modules live in TRAITS-* / ITEMS;
 * they register into the per-family [GeneralActionModuleSource] registries this factory consumes —
 * never into this factory body.
 */
class GeneralActionModuleFactory(
    private val nationTypeRegistry: GeneralActionModuleSource,
    private val specialDomesticRegistry: GeneralActionModuleSource,
    private val personalityRegistry: GeneralActionModuleSource,
    private val itemRegistry: GeneralActionModuleSource,
    private val specialWarRegistry: GeneralActionModuleSource = GeneralActionModuleSource { null },
    private val scenarioEffectRegistry: GeneralActionModuleSource = GeneralActionModuleSource { null },
) {
    /**
     * @param general          supplies the four equip slots (horse/weapon/book/item) + officerLevel + cityId.
     * @param nationTypeCode   nation `type_code` (source #1) — null/None ⇒ skipped.
     * @param specialDomesticCode general `special` (source #3) — null/None ⇒ skipped.
     * @param specialWarCode   general `special2` (source #4) — null/None ⇒ skipped. The war specialty's
     *                         `onCalcStat` folds into EVERY stat read (e.g. `che_징병` = leadership +25%),
     *                         so it MUST be present for the AI's `getLeadership(false)` reward/recruit math
     *                         to match PHP `getActionList()` (`General.php:787-799`, position #4).
     * @param personalityCode  general `personal` (source #5) — null/None ⇒ skipped.
     * @param nationLevel      the general's nation level (feeds calcLeadershipBonus).
     * @param officerCity      the general's assigned officer city (drives the away-from-city demotion);
     *                         defaults to [General.cityId] (= no demotion) since `officer_city` is a
     *                         P4+ war-side column not yet on the entity.
     */
    fun build(
        general: General,
        nationTypeCode: String?,
        specialDomesticCode: String?,
        personalityCode: String?,
        nationLevel: Int,
        officerCity: Int = general.cityId,
        specialWarCode: String? = null,
        scenarioEffectCode: String? = null,
    ): List<GeneralActionModule> {
        val mods = ArrayList<GeneralActionModule>(12)

        // #1 nationType
        nationTypeRegistry.resolve(nationTypeCode)?.let { mods.add(it) }
        // #2 officer — ALWAYS present (lbonus may be 0)
        mods.add(OfficerLevelModule(general.officerLevel, nationLevel, officerCity, general.cityId))
        // #3 specialDomestic
        specialDomesticRegistry.resolve(specialDomesticCode)?.let { mods.add(it) }
        // #4 specialWar — the war specialty's onCalcStat folds into EVERY stat read (PHP getActionList #4).
        specialWarRegistry.resolve(specialWarCode)?.let { mods.add(it) }
        // #5 personality
        personalityRegistry.resolve(personalityCode)?.let { mods.add(it) }
        if (general.crewTypeId >= 1000) {
            UnitCatalog.byId(general.crewTypeId)?.let { mods.add(CrewTypeWarModule(it)) }
        }
        // #7 inherit — the inherit-buff module pair (general then war), folded from aux.inheritBuff
        //   (PHP General.php:125-128 builds inheritBuffObj; :787-799 merges it at slot #7). Resolves
        //   from general.meta triggerState/inheritBuff; null (no buff) ⇒ skipped (identity).
        InheritBuffModuleFactory.resolveAndCreate(general)?.let { (generalModule, warModule) ->
            mods.add(generalModule)
            mods.add(warModule)
        }
        scenarioEffectRegistry.resolve(scenarioEffectCode)?.let { mods.add(it) }
        // #9.. items: horse, weapon, book, item (in that exact order)
        itemRegistry.resolve(general.horse)?.let { mods.add(it) }
        itemRegistry.resolve(general.weapon)?.let { mods.add(it) }
        itemRegistry.resolve(general.book)?.let { mods.add(it) }
        itemRegistry.resolve(general.item)?.let { mods.add(it) }

        return mods
    }
}
