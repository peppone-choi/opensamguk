package opensamguk.logic.war.specialty

import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.stats.GeneralActionModuleSource
import opensamguk.logic.world.SpecialityHelper

/**
 * Per-family [GeneralActionModuleSource] for source #4 of the 9-source action stack (`specialWar` —
 * `General.php:787-799` slot #4), a faithful sibling to the GREEN P2
 * [opensamguk.logic.traits.SpecialDomesticRegistry].
 *
 * Port target = PHP `ActionSpecialWar/` (21 files = 20 selectable war specials + `None.php`); each war
 * special `extends \sammo\BaseSpecial implements iAction`. The 20 selectable ids are exactly
 * [SpecialityHelper.WAR] (the GREEN selection table — already present since P2). AW1 owns this dispatch
 * body; each module REGISTERS by id (the lone per-module touch — AW2/AW3 add their leaf into [byCode],
 * they do NOT widen the dispatch logic). The factory ([opensamguk.logic.stats.GeneralActionModuleFactory])
 * LOOKS THIS UP — we never edit the factory body (Wave-3 shared-file protocol).
 *
 * `None`(id 0) extends BaseSpecial with no battle-hook override (`None.php:7-18`) → folds as identity →
 * resolved to `null` (a skipped slot), exactly as SpecialDomesticRegistry resolves None → null. An
 * unknown / blank / null code → null. Resolution is a pure map lookup (ZERO RNG).
 */
object SpecialWarRegistry : GeneralActionModuleSource {

    /**
     * The id → module map. AW1 seeds it empty (only None handling); AW2 (stat-folds) + AW3
     * (multipliers/trigger-injection) register their leaves here by id. `linkedMapOf` preserves the
     * GameConst.availableSpecialWar / [SpecialityHelper.WAR] order for parity/debug.
     */
    private val byCode: Map<String, GeneralActionModule> = linkedMapOf(
        // --- AW2: stat-delta specialties (onCalcStat / onCalcOpposeStat) ---
        "che_필살" to ChePilsal,
        "che_견고" to CheGyeongo,
        "che_신중" to CheSinjung,
        "che_집중" to CheJipjung,
        "che_환술" to CheHwansul,
        "che_신산" to CheSinsan,
        "che_의술" to CheUisul,
        "che_귀병" to CheGwibyeong,
        "che_궁병" to CheGungbyeong,
        "che_반계" to CheBangye,
        "che_징병" to CheJingbyeong,
        // --- AW3: warpower-multiplier / trigger-injection specialties ---
        "che_격노" to CheGyeokno,
        "che_무쌍" to CheMussang,
        "che_공성" to CheGongseong,
        "che_기병" to CheGibyeong,
        "che_보병" to CheBobyeong,
        "che_돌격" to CheDolgyeok,
        "che_저격" to CheJeogyeok,
        "che_위압" to CheWiap,
        "che_척사" to CheCheoksa,
        // None(id 0) is inert (no battle-hook override) → omitted → resolve null.
    )

    /** The declared registrable key set = the 20 [SpecialityHelper.WAR] ids (the modules behind them
     *  land across AW2/AW3; None is the only inert extra). */
    fun registrableIds(): Set<String> = SpecialityHelper.WAR.map { it.id }.toSet()

    override fun resolve(code: String?): GeneralActionModule? {
        if (code.isNullOrBlank() || code == "None") return null
        return byCode[code]
    }
}
