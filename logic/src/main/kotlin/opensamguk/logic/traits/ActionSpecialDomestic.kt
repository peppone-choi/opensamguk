package opensamguk.logic.traits

import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.stats.GeneralActionModuleSource

/**
 * Source #3 of the 9-source action stack — the 8 domestic specialties (내정특기), a faithful port of
 * PHP `ActionSpecialDomestic/` (each `che_*.php` `extends \sammo\BaseSpecial`).
 *
 * Research Unit 9: there are EXACTLY 8 effective domestic specialties (NOT ~30 — the "~30" conflates
 * domestic(8) + war(21)). Each overrides ONLY `onCalcDomestic`; ZERO RNG in the hooks (they are pure
 * value transforms). `None`(id 0) and `che_거상`(id 999) define no `onCalcDomestic` override in PHP, so
 * they fold as identity — we resolve them to `null` (skipped slot) in [SpecialDomesticRegistry].
 *
 * The uniform 6/8 buff (`che_경작`/`che_상재`/`che_발명`/`che_축성`/`che_수비`/`che_통찰`,
 * e.g. `che_경작.php:18-26`): on the matching `turnType`
 *   - varType 'score'   → value * 1.1
 *   - varType 'cost'    → value * 0.8
 *   - varType 'success' → value + 0.1
 * every other turnType / varType is identity.
 *
 * Two divergent members:
 *   - `che_인덕`(id 20, `che_인덕.php:18-26`): SAME uniform buff but gated on TWO turnTypes (`민심` OR `인구`).
 *   - `che_귀모`(id 31, `che_귀모.php:18-25`): OUTLIER — only `turnType == '계략' && varType == 'success'`
 *     → value + 0.2 (no score/cost effect).
 *
 * turnType ↔ specialty ↔ PHP `$type` (the stat the selection-weight reads — does NOT affect the hook):
 *   농업→경작(STAT_INTEL), 상업→상재(STAT_INTEL), 기술→발명(STAT_INTEL), 성벽→축성(STAT_STRENGTH),
 *   수비→수비(STAT_STRENGTH), 치안→통찰(STAT_STRENGTH), 민심|인구→인덕(STAT_LEADERSHIP),
 *   계략→귀모(STAT_LEADERSHIP+STRENGTH+INTEL). (통찰 GATES '치안' yet is STAT_STRENGTH-typed.)
 *
 * The Kotlin pipeline threads the action key through the `actionKey` param of
 * [GeneralActionModule.onCalcDomestic]; this is the PHP `$turnType` argument (`onCalcDomestic($turnType,
 * $varType, $value, $aux)`).
 */

/** PHP `$varType` literals threaded through `onCalcDomestic`. */
private const val SCORE = "score"
private const val COST = "cost"
private const val SUCCESS = "success"

/**
 * The uniform domestic buff shared by 7 of the 8 specialties (the 6 single-turnType ones + 인덕):
 * on a matching turnType, score×1.1 / cost×0.8 / success+0.1; all else identity.
 * Verbatim from `che_경작.php:18-26` (and the identical bodies of 상재/발명/축성/수비/통찰/인덕).
 *
 * @param specialName the PHP `$name` (e.g. '경작') — surfaced for parity/debug, not used in the hook.
 * @param matches     the turnType gate predicate (single key for 6, OR-of-two for 인덕).
 */
sealed class UniformDomesticSpecialty(
    val specialName: String,
    private val matches: (actionKey: String) -> Boolean,
) : GeneralActionModule {
    final override fun onCalcDomestic(
        general: General,
        actionKey: String,
        varType: String,
        value: Double,
        aux: Map<String, Any?>,
    ): Double {
        if (!matches(actionKey)) return value
        return when (varType) {
            SCORE -> value * 1.1
            COST -> value * 0.8
            SUCCESS -> value + 0.1
            else -> value
        }
    }
}

/** che_경작 (id 1) — 농업 (`che_경작.php`). */
object CheCultivate : UniformDomesticSpecialty("경작", { it == "농업" })

/** che_상재 (id 2) — 상업 (`che_상재.php`). */
object CheCommerce : UniformDomesticSpecialty("상재", { it == "상업" })

/** che_발명 (id 3) — 기술 (`che_발명.php`). */
object CheInvent : UniformDomesticSpecialty("발명", { it == "기술" })

/** che_축성 (id 10) — 성벽 (`che_축성.php`). */
object CheFortify : UniformDomesticSpecialty("축성", { it == "성벽" })

/** che_수비 (id 11) — 수비 (`che_수비.php`). */
object CheDefend : UniformDomesticSpecialty("수비", { it == "수비" })

/** che_통찰 (id 12) — gates 치안 yet STAT_STRENGTH-typed (`che_통찰.php`). */
object CheInsight : UniformDomesticSpecialty("통찰", { it == "치안" })

/** che_인덕 (id 20) — two-key gate 민심 OR 인구 (`che_인덕.php`). */
object CheVirtue : UniformDomesticSpecialty("인덕", { it == "민심" || it == "인구" })

/**
 * che_귀모 (id 31) — OUTLIER (`che_귀모.php:18-25`): only `turnType == '계략' && varType == 'success'`
 * → value + 0.2. No score/cost effect, single varType. (`$type` = LEADERSHIP+STRENGTH+INTEL.)
 */
object CheScheme : GeneralActionModule {
    const val SPECIAL_NAME: String = "귀모"

    override fun onCalcDomestic(
        general: General,
        actionKey: String,
        varType: String,
        value: Double,
        aux: Map<String, Any?>,
    ): Double = if (actionKey == "계략" && varType == SUCCESS) value + 0.2 else value
}

/**
 * Per-family [GeneralActionModuleSource] for source #3 (general `special` code). Resolves the persisted
 * `che_*` code to its module. `None`(id 0) and `che_거상`(id 999) have no `onCalcDomestic` override in PHP
 * → identity → resolved to `null` (a skipped slot) so the built stack carries only effective modules.
 *
 * The factory ([opensamguk.logic.stats.GeneralActionModuleFactory]) LOOKS THIS UP; we never edit the
 * factory body (Wave-3 shared-file protocol).
 */
object SpecialDomesticRegistry : GeneralActionModuleSource {
    private val byCode: Map<String, GeneralActionModule> = linkedMapOf(
        "che_경작" to CheCultivate,
        "che_상재" to CheCommerce,
        "che_발명" to CheInvent,
        "che_축성" to CheFortify,
        "che_수비" to CheDefend,
        "che_통찰" to CheInsight,
        "che_인덕" to CheVirtue,
        "che_귀모" to CheScheme,
        // che_거상 (id 999) and None (id 0) are inert (no onCalcDomestic) → omitted → resolve null.
    )

    override fun resolve(code: String?): GeneralActionModule? {
        if (code.isNullOrBlank() || code == "None") return null
        return byCode[code]
    }
}
