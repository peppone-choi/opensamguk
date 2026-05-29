package opensamguk.logic.traits

import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.stats.GeneralActionModuleSource
import opensamguk.logic.util.phpRound

/**
 * Source #1 of the 9-source action stack — the 15 nation types (국가타입), a faithful port of PHP
 * `ActionNationType/` (each `che_X.php` `extends \sammo\BaseNation`, which `use`s the `DefaultAction`
 * identity trait + a `getInfo()` override returning `"{$pros} {$cons}"`).
 *
 * There are EXACTLY 15 entries (research): 13 effective `che_*` types + the inert `None`(id 0) and
 * `che_중립`(neutral). Each effective type overrides some combination of:
 *   - `onCalcDomestic($turnType, $varType, $value, $aux)`  — per-turnType score×/cost×/success+ buffs.
 *   - `onCalcNationalIncome($type, $amount)`               — gold/rice/pop multipliers; the pop branch is
 *     ALWAYS gated `&& $amount > 0` (a negative/zero pop ratio is untouched). This hook is folded over
 *     the NATION-TYPE source ONLY (research Unit 12 asymmetry — `GeneralActionPipeline.nationIncomeFold`).
 *   - `onCalcStrategic($turnType, $varType, $value)`       — only 음양가 / 종횡가 override it:
 *       음양가  delay      = Util::round($value * 4 / 3)                       (`che_음양가.php:39-44`)
 *       종횡가  delay      = Util::round($value * 3 / 4)                       (`che_종횡가.php:39-47`)
 *       종횡가  globalDelay= Util::round($value / 2)
 *     `Util::round` returns an int (half-away-from-zero, `Util.php:14-17`); we apply [phpRound] and widen
 *     back to Double for the fold accumulator. ORDER MATTERS: the rounding happens mid-fold inside the
 *     module exactly as in PHP, so a downstream consumer's own round sees this already-rounded value.
 *
 * `getInfo()` (`BaseNation.php:14-18`) = `"{$pros} {$cons}"` — a SINGLE space join (NOT the TS
 * '장점:X / 단점:Y'). `None` and `che_중립` set `name='-'`, `pros=''`, `cons=''` and define no hook
 * override → they fold as identity; [NationTypeRegistry] resolves them to `null` (a skipped slot) so the
 * built stack carries only effective modules. The neutral object [CheNeutral] is still exposed for the
 * `getInfo()` / identity-fold parity test.
 *
 * The Kotlin pipeline threads the PHP `$turnType` as the `actionKey` param of
 * [GeneralActionModule.onCalcDomestic]; the strategic `$turnType` is dropped by the interface (the
 * modules never read it — `GeneralActionPipeline.onCalcStrategic` passes only `varType`).
 */

private const val SCORE = "score"
private const val COST = "cost"
private const val SUCCESS = "success"
private const val DELAY = "delay"
private const val GLOBAL_DELAY = "globalDelay"

/**
 * Shared base carrying the PHP `$name` + the `getInfo()` `"{pros} {cons}"` string (single space). The
 * hooks default to identity (the `DefaultAction` trait); each concrete type overrides only what its PHP
 * class overrides.
 */
sealed class NationTypeModule(
    val typeName: String,
    pros: String,
    cons: String,
) : GeneralActionModule {
    /** PHP `BaseNation::getInfo()` = "{$pros} {$cons}" — single-space join, verbatim. */
    val info: String = "$pros $cons"
}

/** che_덕가 (`che_덕가.php`) — 치안↑ 인구↑ 민심↑ / 쌀수입↓ 수성↓. */
object CheDeokga : NationTypeModule("덕가", "치안↑ 인구↑ 민심↑", "쌀수입↓ 수성↓") {
    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double {
        when (actionKey) {
            "치안" -> {
                if (varType == SCORE) return value * 1.1
                if (varType == COST) return value * 0.8
            }
            "민심", "인구" -> {
                if (varType == SCORE) return value * 1.1
                if (varType == COST) return value * 0.8
            }
            "수비", "성벽" -> {
                if (varType == SCORE) return value * 0.9
                if (varType == COST) return value * 1.2
            }
        }
        return value
    }

    override fun onCalcNationalIncome(general: General, type: String, value: Double): Double {
        if (type == "rice") return value * 0.9
        if (type == "pop" && value > 0) return value * 1.2
        return value
    }
}

/** che_도가 (`che_도가.php`) — 인구↑ / 기술↓ 치안↓. */
object CheDoga : NationTypeModule("도가", "인구↑", "기술↓ 치안↓") {
    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double {
        when (actionKey) {
            "기술" -> {
                if (varType == SCORE) return value * 0.9
                if (varType == COST) return value * 1.2
            }
            "치안" -> {
                if (varType == SCORE) return value * 0.9
                if (varType == COST) return value * 1.2
            }
        }
        return value
    }

    override fun onCalcNationalIncome(general: General, type: String, value: Double): Double {
        if (type == "pop" && value > 0) return value * 1.2
        return value
    }
}

/** che_도적 (`che_도적.php`) — 계략↑ / 금수입↓ 치안↓ 민심↓. ADDITIVE 계략 success+0.1. */
object CheDojeok : NationTypeModule("도적", "계략↑", "금수입↓ 치안↓ 민심↓") {
    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double {
        when (actionKey) {
            "치안" -> {
                if (varType == SCORE) return value * 0.9
                if (varType == COST) return value * 1.2
            }
            "민심", "인구" -> {
                if (varType == SCORE) return value * 0.9
                if (varType == COST) return value * 1.2
            }
            "계략" -> {
                if (varType == SUCCESS) return value + 0.1
            }
        }
        return value
    }

    override fun onCalcNationalIncome(general: General, type: String, value: Double): Double {
        if (type == "gold") return value * 0.9
        return value
    }
}

/** che_명가 (`che_명가.php`) — 기술↑ 인구↑ / 쌀수입↓ 수성↓. */
object CheMyeongga : NationTypeModule("명가", "기술↑ 인구↑", "쌀수입↓ 수성↓") {
    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double {
        when (actionKey) {
            "기술" -> {
                if (varType == SCORE) return value * 1.1
                if (varType == COST) return value * 0.8
            }
            "수비", "성벽" -> {
                if (varType == SCORE) return value * 0.9
                if (varType == COST) return value * 1.2
            }
        }
        return value
    }

    override fun onCalcNationalIncome(general: General, type: String, value: Double): Double {
        if (type == "rice") return value * 0.9
        if (type == "pop" && value > 0) return value * 1.2
        return value
    }
}

/** che_묵가 (`che_묵가.php`) — 수성↑ / 기술↓. NO income override → identity income. */
object CheMukga : NationTypeModule("묵가", "수성↑", "기술↓") {
    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double {
        when (actionKey) {
            "수비", "성벽" -> {
                if (varType == SCORE) return value * 1.1
                if (varType == COST) return value * 0.8
            }
            "기술" -> {
                if (varType == SCORE) return value * 0.9
                if (varType == COST) return value * 1.2
            }
        }
        return value
    }
}

/** che_법가 (`che_법가.php`) — 금수입↑ 치안↑ / 인구↓ 민심↓. */
object CheBeopga : NationTypeModule("법가", "금수입↑ 치안↑", "인구↓ 민심↓") {
    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double {
        when (actionKey) {
            "치안" -> {
                if (varType == SCORE) return value * 1.1
                if (varType == COST) return value * 0.8
            }
            "민심", "인구" -> {
                if (varType == SCORE) return value * 0.9
                if (varType == COST) return value * 1.2
            }
        }
        return value
    }

    override fun onCalcNationalIncome(general: General, type: String, value: Double): Double {
        if (type == "gold") return value * 1.1
        if (type == "pop" && value > 0) return value * 0.8
        return value
    }
}

/** che_병가 (`che_병가.php`) — 기술↑ 수성↑ / 인구↓ 민심↓. */
object CheByeongga : NationTypeModule("병가", "기술↑ 수성↑", "인구↓ 민심↓") {
    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double {
        when (actionKey) {
            "기술" -> {
                if (varType == SCORE) return value * 1.1
                if (varType == COST) return value * 0.8
            }
            "수비", "성벽" -> {
                if (varType == SCORE) return value * 1.1
                if (varType == COST) return value * 0.8
            }
            "민심", "인구" -> {
                if (varType == SCORE) return value * 0.9
                if (varType == COST) return value * 1.2
            }
        }
        return value
    }

    override fun onCalcNationalIncome(general: General, type: String, value: Double): Double {
        if (type == "pop" && value > 0) return value * 0.8
        return value
    }
}

/** che_불가 (`che_불가.php`) — 민심↑ 수성↑ / 금수입↓. */
object CheBulga : NationTypeModule("불가", "민심↑ 수성↑", "금수입↓") {
    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double {
        when (actionKey) {
            "민심", "인구" -> {
                if (varType == SCORE) return value * 1.1
                if (varType == COST) return value * 0.8
            }
            "수비", "성벽" -> {
                if (varType == SCORE) return value * 1.1
                if (varType == COST) return value * 0.8
            }
        }
        return value
    }

    override fun onCalcNationalIncome(general: General, type: String, value: Double): Double {
        if (type == "gold") return value * 0.9
        return value
    }
}

/** che_오두미도 (`che_오두미도.php`) — 쌀수입↑ 인구↑ / 기술↓ 수성↓ 농상↓. */
object CheOdumido : NationTypeModule("오두미도", "쌀수입↑ 인구↑", "기술↓ 수성↓ 농상↓") {
    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double {
        when (actionKey) {
            "기술" -> {
                if (varType == SCORE) return value * 0.9
                if (varType == COST) return value * 1.2
            }
            "수비", "성벽" -> {
                if (varType == SCORE) return value * 0.9
                if (varType == COST) return value * 1.2
            }
            "농업", "상업" -> {
                if (varType == SCORE) return value * 0.9
                if (varType == COST) return value * 1.2
            }
        }
        return value
    }

    override fun onCalcNationalIncome(general: General, type: String, value: Double): Double {
        if (type == "rice") return value * 1.1
        if (type == "pop" && value > 0) return value * 1.2
        return value
    }
}

/** che_유가 (`che_유가.php`) — 농상↑ 민심↑ / 쌀수입↓. */
object CheYuga : NationTypeModule("유가", "농상↑ 민심↑", "쌀수입↓") {
    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double {
        when (actionKey) {
            "농업", "상업" -> {
                if (varType == SCORE) return value * 1.1
                if (varType == COST) return value * 0.8
            }
            "민심", "인구" -> {
                if (varType == SCORE) return value * 1.1
                if (varType == COST) return value * 0.8
            }
        }
        return value
    }

    override fun onCalcNationalIncome(general: General, type: String, value: Double): Double {
        if (type == "rice") return value * 0.9
        return value
    }
}

/** che_음양가 (`che_음양가.php`) — 농상↑ 인구↑ / 기술↓ 전략↓. delay = Util::round(v*4/3). */
object CheEumyangga : NationTypeModule("음양가", "농상↑ 인구↑", "기술↓ 전략↓") {
    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double {
        when (actionKey) {
            "농업", "상업" -> {
                if (varType == SCORE) return value * 1.1
                if (varType == COST) return value * 0.8
            }
            "기술" -> {
                if (varType == SCORE) return value * 0.9
                if (varType == COST) return value * 1.2
            }
        }
        return value
    }

    override fun onCalcNationalIncome(general: General, type: String, value: Double): Double {
        if (type == "pop" && value > 0) return value * 1.2
        return value
    }

    /** che_음양가.php:39-44 — delay = Util::round($value * 4 / 3); other varTypes identity. */
    override fun onCalcStrategic(general: General, varType: String, value: Double): Double {
        if (varType == DELAY) return phpRound(value * 4 / 3).toDouble()
        return value
    }
}

/** che_종횡가 (`che_종횡가.php`) — 전략↑ 수성↑ / 금수입↓ 농상↓. delay=round(v*3/4), globalDelay=round(v/2). */
object CheJonghoengga : NationTypeModule("종횡가", "전략↑ 수성↑", "금수입↓ 농상↓") {
    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double {
        when (actionKey) {
            "수비", "성벽" -> {
                if (varType == SCORE) return value * 1.1
                if (varType == COST) return value * 0.8
            }
            "농업", "상업" -> {
                if (varType == SCORE) return value * 0.9
                if (varType == COST) return value * 1.2
            }
        }
        return value
    }

    override fun onCalcNationalIncome(general: General, type: String, value: Double): Double {
        if (type == "gold") return value * 0.9
        return value
    }

    /** che_종횡가.php:39-47 — delay = Util::round($value * 3 / 4); globalDelay = Util::round($value / 2). */
    override fun onCalcStrategic(general: General, varType: String, value: Double): Double {
        if (varType == DELAY) return phpRound(value * 3 / 4).toDouble()
        if (varType == GLOBAL_DELAY) return phpRound(value / 2).toDouble()
        return value
    }
}

/** che_태평도 (`che_태평도.php`) — 인구↑ 민심↑ / 기술↓ 수성↓. */
object CheTaepyeongdo : NationTypeModule("태평도", "인구↑ 민심↑", "기술↓ 수성↓") {
    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double {
        when (actionKey) {
            "민심", "인구" -> {
                if (varType == SCORE) return value * 1.1
                if (varType == COST) return value * 0.8
            }
            "기술" -> {
                if (varType == SCORE) return value * 0.9
                if (varType == COST) return value * 1.2
            }
            "수비", "성벽" -> {
                if (varType == SCORE) return value * 0.9
                if (varType == COST) return value * 1.2
            }
        }
        return value
    }

    override fun onCalcNationalIncome(general: General, type: String, value: Double): Double {
        if (type == "pop" && value > 0) return value * 1.2
        return value
    }
}

/**
 * che_중립 (`che_중립.php`) — the neutral nation type: name='-', pros/cons='' and NO hook override
 * (folds as identity). PHP's `None` (`None.php`) is structurally identical. Exposed for the `getInfo()`
 * / identity-fold parity test; [NationTypeRegistry] resolves both `che_중립` and `None` to `null`.
 */
object CheNeutral : NationTypeModule("-", "", "")

/**
 * Per-family [GeneralActionModuleSource] for source #1 (nation `type_code`). Resolves the persisted
 * `che_*` code to its nation-type module. `None` and `che_중립` define no hook override in PHP → they
 * fold as identity → resolved to `null` (a skipped slot) so the built stack carries only effective
 * sources.
 *
 * **Wave-3 shared-file protocol:** the factory ([opensamguk.logic.stats.GeneralActionModuleFactory])
 * LOOKS THIS UP; we never edit the factory body.
 */
object NationTypeRegistry : GeneralActionModuleSource {
    private val byCode: Map<String, GeneralActionModule> = linkedMapOf(
        "che_덕가" to CheDeokga,
        "che_도가" to CheDoga,
        "che_도적" to CheDojeok,
        "che_명가" to CheMyeongga,
        "che_묵가" to CheMukga,
        "che_법가" to CheBeopga,
        "che_병가" to CheByeongga,
        "che_불가" to CheBulga,
        "che_오두미도" to CheOdumido,
        "che_유가" to CheYuga,
        "che_음양가" to CheEumyangga,
        "che_종횡가" to CheJonghoengga,
        "che_태평도" to CheTaepyeongdo,
        // che_중립 (CheNeutral) and None are inert (no hook override) → omitted → resolve null.
    )

    override fun resolve(code: String?): GeneralActionModule? {
        if (code.isNullOrBlank() || code == "None") return null
        return byCode[code]
    }
}
