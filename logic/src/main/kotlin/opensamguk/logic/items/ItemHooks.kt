package opensamguk.logic.items

import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.util.phpRound
import opensamguk.logic.util.valueFit

/**
 * The extra-hook + override-stat items in P2-DOMESTIC scope — faithful ports of the non-pure-declarative
 * `ActionItem` PHP classes (research Unit 11).
 *
 * IN domestic scope: `onCalcDomestic` (납금박산로 / 조달주판 / 계략 books), `onCalcStat` (능력치 year-scaled,
 * 동작 addDex multiplier, parent-first +stat decorators), `onCalcStrategic` (평만지장도 delay).
 *
 * OUT of domestic scope (STUBBED identity here, research Unit 11 decision): the war-side hooks
 * `getBattlePhaseSkillTriggerList` / `getWarPowerMultiplier` (단궁/백상 …) and the warMagicTrialProb /
 * killRice / initWarPhase `onCalcStat` deltas (변도론/백상/기주마/삼략) — none touch a DOMESTIC stat so they
 * fold as identity for P2; the parent BaseStatItem +stat IS active. `che_보물_도기.onArbitraryAction`
 * (장비매매) is wired in CMD-TRADE TR2, not here.
 *
 * Registration: each builder is appended into [ItemRegistry.extraHookBuilders] (the ONE registry table
 * shared with IT1) at class-load via [registerItemHooks] — the S-MODULES factory body is NOT edited.
 *
 * GameConst::$maxTechLevel = 12 (GameConstBase.php:82) — the ability-item year-scale clamp ceiling.
 */
private const val MAX_TECH_LEVEL = 12

/**
 * che_내정_납금박산로 (BaseItem.onCalcDomestic, che_내정_납금박산로.php:15-20):
 * the 8 domestic turnTypes get success +0.15; everything else identity.
 */
class NapgeumBaksanro : GeneralActionModule {
    val name = "납금박산로(내정)"
    val info = "[내정] 내정 성공률 +15%p"

    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double {
        if (actionKey in TURN_TYPES && varType == "success") return value + 0.15
        return value
    }

    companion object {
        private val TURN_TYPES = setOf("상업", "농업", "기술", "성벽", "수비", "치안", "민심", "인구")
    }
}

/** che_조달_주판 (che_조달_주판.php:15-21): 조달 success +0.2 AND score *2. */
class JodalJupan : GeneralActionModule {
    val name = "주판(조달)"
    val info = "[내정] 물자조달 성공 확률 +20%p, 물자조달 획득량 +100%p"

    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double {
        if (actionKey == "조달") {
            if (varType == "success") return value + 0.2
            if (varType == "score") return value * 2
        }
        return value
    }
}

/**
 * che_계략_삼략 (che_계략_삼략.php:16-31): 계략 success +0.2 (domestic). The warMagicTrialProb/
 * warMagicSuccessProb `onCalcStat` +0.1 are war-side — STUBBED identity in domestic scope.
 */
class SamryakBook : GeneralActionModule {
    val name = "삼략(계략)"
    val info = "[계략] 화계·탈취·파괴·선동 : 성공률 +20%p<br>[전투] 계략 시도 확률 +10%p, 계략 성공 확률 +10%p"

    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double {
        if (actionKey == "계략" && varType == "success") return value + 0.2
        return value
    }
}

/**
 * The 능력치 ability items (che_능력치_무력_두강주 / 지력_이강주 / 통솔_보령압주, lines 19-26):
 * NOT BaseStatItem — they fully OVERRIDE onCalcStat with a YEAR-SCALED bonus:
 *   if statName === statType: value + 5 + Util::valueFit(intdiv(relYear, 4), 0, maxTechLevel)
 * relYear is read from `game_env` (year - startyear) in PHP; supplied here via [relYear].
 */
class AbilityItem(
    val code: String,
    val statType: String,
    val statNick: String,
    private val relYear: Int,
) : GeneralActionModule {
    val name = "$statNick"
    val info = "[능력치] $statNick +5 +(4년마다 +1)"

    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double {
        if (statName == statType) {
            // intdiv(relYear, 4): PHP integer division truncates toward zero; valueFit → [0, 12].
            val scaled = valueFit((relYear / 4).toDouble(), 0.0, MAX_TECH_LEVEL.toDouble()).toInt()
            return value + 5 + scaled
        }
        return value
    }
}

/** che_숙련_동작 (che_숙련_동작.php:14-19): addDex *1.20 (MULTIPLICATIVE). */
class DongjakItem : GeneralActionModule {
    val name = "동작(숙련)"
    val info = "숙련 +20%"

    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double =
        if (statName == "addDex") value * 1.20 else value
}

/** che_전략_평만지장도 (che_전략_평만지장도.php:12-17): strategic delay → Util::round(value*0.80). */
class PyeongmanItem : GeneralActionModule {
    val name = "평만지장도(전략)"
    val info = "[전략] 국가전략 사용시 재사용 대기 기간 -20%"

    override fun onCalcStrategic(general: General, varType: String, value: Double): Double =
        if (varType == "delay") phpRound(value * 0.80).toDouble() else value
}

/**
 * A parent-first decorator over a [BaseStatItemModule] (변도론/백상/기주마): PHP calls
 * `parent::onCalcStat(...)` FIRST (the base +stat) THEN applies its own delta (`BaseStatItem.php` subclass
 * pattern, base+decorator). In P2-DOMESTIC scope every subclass delta targets a WAR-only stat
 * (warMagicTrialProb +0.02 / killRice *1.1 / initWarPhase ±1) — NONE is a domestic stat, so the deltas are
 * STUBBED identity here (research Unit 11 decision: war-side hooks out of domestic scope). Only the parent
 * +stat survives the fold for any domestic stat. The decorator structure + the info-string concatenation
 * are preserved so the parent-first ordering stays byte-faithful when the war stats are populated later.
 */
class ParentFirstStatItem(
    private val base: BaseStatItemModule,
    private val infoSuffix: String,
) : GeneralActionModule {
    val name: String get() = base.name
    val info: String = base.info + infoSuffix

    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double {
        // parent FIRST (base +stat); the war-only subclass delta is identity in domestic scope.
        return base.onCalcStat(general, statName, value, aux)
    }
}

/**
 * Build the declarative base for a parent-first decorator from its code (reuses the IT1 token parser via a
 * throwaway registry resolve). Returns the parsed [BaseStatItemModule]; never null for a 명마/무기/서적 code.
 */
private fun statBaseOf(code: String): BaseStatItemModule =
    ItemRegistry().let { r ->
        // resolve via the plain declarative path (no extra-hook builder for these codes after we strip them)
        val tokens = code.split('_')
        val category = tokens[tokens.size - 3]
        val (nick, type) = when (category) {
            "명마" -> "통솔" to "leadership"
            "무기" -> "무력" to "strength"
            "서적" -> "지력" to "intel"
            else -> error("not a stat item: $code")
        }
        BaseStatItemModule(code, nick, type, tokens[tokens.size - 2].toInt(), tokens[tokens.size - 1])
    }

/**
 * Register every IT2 extra-hook item into [ItemRegistry.extraHookBuilders]. Idempotent (overwrites by key).
 * Invoked once from the registry's class init so the single shared `ItemRegistry` resolves the full
 * P2-domestic item surface.
 */
fun registerItemHooks() {
    val m = ItemRegistry.extraHookBuilders
    m["che_내정_납금박산로"] = { NapgeumBaksanro() }
    m["che_조달_주판"] = { JodalJupan() }
    m["che_계략_삼략"] = { SamryakBook() }
    m["che_숙련_동작"] = { DongjakItem() }
    m["che_전략_평만지장도"] = { PyeongmanItem() }
    // ability items — year-scaled override (relYear from the owning registry's provider)
    m["che_능력치_무력_두강주"] = { r -> AbilityItem("che_능력치_무력_두강주", "strength", "무력", r.relYearProvider()) }
    m["che_능력치_지력_이강주"] = { r -> AbilityItem("che_능력치_지력_이강주", "intel", "지력", r.relYearProvider()) }
    m["che_능력치_통솔_보령압주"] = { r -> AbilityItem("che_능력치_통솔_보령압주", "leadership", "통솔", r.relYearProvider()) }
    // parent-first decorators — base +stat (war-only subclass delta stubbed identity in domestic scope)
    m["che_서적_03_변도론"] = { ParentFirstStatItem(statBaseOf("che_서적_03_변도론"), "<br>[전투] 계략 시도 확률 +2%p") }
    m["che_명마_07_백상"] = { ParentFirstStatItem(statBaseOf("che_명마_07_백상"), "<br>[전투] 공격력 +20%, 소모 군량 +10%, 공격 시 페이즈 -1") }
    m["che_명마_07_기주마"] = { ParentFirstStatItem(statBaseOf("che_명마_07_기주마"), "<br>[전투] 공격 시 페이즈 +1") }
}
