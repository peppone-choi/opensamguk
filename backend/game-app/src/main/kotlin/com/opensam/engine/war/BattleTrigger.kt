package com.opensam.engine.war

import com.opensam.model.CrewType
import kotlin.math.floor
import kotlin.random.Random

data class BattleTriggerContext(
    val attacker: WarUnit,
    val defender: WarUnit,
    val rng: Random,
    var attackerDamage: Int = 0,
    var defenderDamage: Int = 0,

    // Critical hit state
    var criticalActivated: Boolean = false,
    var criticalChanceBonus: Double = 0.0,

    // Dodge state
    var dodgeActivated: Boolean = false,
    var dodgeChanceBonus: Double = 0.0,
    var dodgeDisabled: Boolean = false,

    // Magic/stratagem state
    var magicActivated: Boolean = false,
    var magicReflected: Boolean = false,
    var magicChanceBonus: Double = 0.0,
    var magicDamageMultiplier: Double = 1.0,
    var magicFailed: Boolean = false,
    var magicFailDamage: Double = 0.0,

    // Damage multipliers
    var attackMultiplier: Double = 1.0,
    var defenceMultiplier: Double = 1.0,

    // Snipe state (저격발동)
    var snipeActivated: Boolean = false,
    var snipeWoundAmount: Int = 0,

    // Injury state (부상무효)
    var injuryImmune: Boolean = false,

    // Counter state (반격)
    var counterDamageRatio: Double = 0.0,

    // Morale state (사기진작)
    var moraleBoost: Int = 0,

    // Phase info
    var phaseNumber: Int = 0,
    var isVsCity: Boolean = false,

    // Battle logs from triggers
    val battleLogs: MutableList<String> = mutableListOf(),

    var healAmount: Int = 0,
    var rageDamageStack: Double = 0.0,
    var intimidated: Boolean = false,
    var newOpponent: Boolean = false,
    var plunderActivated: Boolean = false,
    var plunderRatio: Double = 0.0,
    var snipeImmune: Boolean = false,
    var blockPhasesRemaining: Int = 0,
    var suppressActive: Boolean = false,

    var criticalDisabled: Boolean = false,
    var magicDisabled: Boolean = false,
    var intimidatePhasesRemaining: Int = 0,
    var rageActivationCount: Int = 0,
    var rageExtraPhases: Int = 0,
    var banmokDamageBonus: Double = 0.9,
    var plunderGold: Int = 0,
    var plunderRice: Int = 0,
    var retreatInjuryImmune: Boolean = false,
)

private fun Random.nextBool(probability: Double): Boolean =
    probability > 0.0 && nextDouble() < probability.coerceAtMost(1.0)

private fun BattleTriggerContext.rollCriticalDamageMultiplier(): Double =
    rng.nextDouble(1.3, 2.0)

/**
 * Battle trigger with PRE/POST split (legacy parity).
 *
 * PRE (시도/attempt): modifies chances BEFORE the roll.
 * POST (발동/execute): applies effects AFTER a successful roll.
 *
 * Additional hooks:
 * - onBattleInit: fired once at engagement start
 * - onMagicFail: fired when stratagem attempt fails
 * - onInjuryCheck: fired before wound roll
 * - onPostDamage: fired after damage is applied
 */
interface BattleTrigger {
    val code: String
    val priority: Int

    // Battle init - fired once at engagement start (legacy: getBattleInitSkillTriggerList)
    fun onBattleInit(ctx: BattleTriggerContext): BattleTriggerContext = ctx

    // PRE triggers - modify chance before roll (legacy: 시도)
    fun onPreCritical(ctx: BattleTriggerContext): BattleTriggerContext = ctx
    fun onPreDodge(ctx: BattleTriggerContext): BattleTriggerContext = ctx
    fun onPreMagic(ctx: BattleTriggerContext): BattleTriggerContext = ctx

    // POST triggers - apply effects after successful roll (legacy: 발동)
    fun onPostCritical(ctx: BattleTriggerContext): BattleTriggerContext = ctx
    fun onPostDodge(ctx: BattleTriggerContext): BattleTriggerContext = ctx
    fun onPostMagic(ctx: BattleTriggerContext): BattleTriggerContext = ctx

    // Stratagem fail - fired when magic attempt fails (legacy: 계략실패)
    fun onMagicFail(ctx: BattleTriggerContext): BattleTriggerContext = ctx

    // Damage calc (applied after all rolls)
    fun onDamageCalc(ctx: BattleTriggerContext): BattleTriggerContext = ctx

    // Injury check - fired before wound roll
    fun onInjuryCheck(ctx: BattleTriggerContext): BattleTriggerContext = ctx

    // Post damage - fired after damage is applied (counter, morale, etc.)
    fun onPostDamage(ctx: BattleTriggerContext): BattleTriggerContext = ctx
}

// ========== Existing triggers (enhanced) ==========

/** 필살: PRE +30% critical chance, disable dodge. */
object 필살Trigger : BattleTrigger {
    override val code = "필살"
    override val priority = 10
    override fun onPreCritical(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.criticalChanceBonus += 0.30
        ctx.dodgeDisabled = true
        ctx.battleLogs.add("필살 발동 준비!")
        return ctx
    }
}

/** 회피: PRE +8% dodge chance. */
object 회피Trigger : BattleTrigger {
    override val code = "회피"
    override val priority = 10
    override fun onPreDodge(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.dodgeChanceBonus += 0.08
        return ctx
    }
}

/** 반계: POST reflect magic damage back. */
object 반계Trigger : BattleTrigger {
    override val code = "반계"
    override val priority = 10
    override fun onPostMagic(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.magicActivated) {
            ctx.magicReflected = true
            ctx.battleLogs.add("반계 발동! 계략을 되돌린다!")
        }
        return ctx
    }
}

/** 신산: PRE +20% magic chance. */
object 신산Trigger : BattleTrigger {
    override val code = "신산"
    override val priority = 10
    override fun onPreMagic(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.magicChanceBonus += 0.20
        return ctx
    }
}

/** 위압: damage calc ×1.05 attack. */
object 위압Trigger : BattleTrigger {
    override val code = "위압"
    override val priority = 20
    override fun onDamageCalc(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.attackMultiplier *= 1.05
        return ctx
    }
}

/**
 * 저격: PRE +8% critical chance.
 * POST critical: apply wound to defender (legacy: che_저격발동 applies wounds).
 */
object 저격Trigger : BattleTrigger {
    override val code = "저격"
    override val priority = 15
    override fun onPreCritical(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.criticalChanceBonus += 0.08
        return ctx
    }

    override fun onPostCritical(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.criticalActivated && ctx.defender is WarUnitGeneral) {
            // Legacy: snipe proc applies wound (2-6 turns)
            val woundChance = ctx.attacker.strength / 200.0
            if (ctx.rng.nextDouble() < woundChance) {
                ctx.snipeActivated = true
                ctx.snipeWoundAmount = ctx.rng.nextInt(2, 7)
                ctx.battleLogs.add("저격 발동! 적장에게 부상을 입혔다!")
            }
        }
        return ctx
    }
}

/** 격노: damage calc ×1.2 attack. */
object 격노Trigger : BattleTrigger {
    override val code = "격노"
    override val priority = 10
    override fun onDamageCalc(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.attackMultiplier *= 1.2
        return ctx
    }
}

/** 돌격: damage calc ×1.15 attack. */
object 돌격Trigger : BattleTrigger {
    override val code = "돌격"
    override val priority = 10
    override fun onDamageCalc(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.attackMultiplier *= 1.15
        return ctx
    }
}

// ========== New triggers ==========

/** 화공: PRE magic +15%, magic damage ×1.2. */
object 화공Trigger : BattleTrigger {
    override val code = "화공"
    override val priority = 10
    override fun onPreMagic(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.magicChanceBonus += 0.15
        ctx.magicDamageMultiplier *= 1.2
        return ctx
    }
}

/** 기습: PRE crit +5%, dodge +5%. Phase 0 bonus doubled. */
object 기습Trigger : BattleTrigger {
    override val code = "기습"
    override val priority = 10
    override fun onPreCritical(ctx: BattleTriggerContext): BattleTriggerContext {
        val bonus = if (ctx.phaseNumber == 0) 0.10 else 0.05
        ctx.criticalChanceBonus += bonus
        return ctx
    }

    override fun onPreDodge(ctx: BattleTriggerContext): BattleTriggerContext {
        val bonus = if (ctx.phaseNumber == 0) 0.10 else 0.05
        ctx.dodgeChanceBonus += bonus
        return ctx
    }
}

/** 매복: PRE dodge +8%. */
object 매복Trigger : BattleTrigger {
    override val code = "매복"
    override val priority = 10
    override fun onPreDodge(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.dodgeChanceBonus += 0.08
        return ctx
    }
}

/** 방어: PRE dodge +15%, defence ×1.1. */
object 방어Trigger : BattleTrigger {
    override val code = "방어"
    override val priority = 10
    override fun onPreDodge(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.dodgeChanceBonus += 0.15
        return ctx
    }

    override fun onDamageCalc(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.defenceMultiplier *= 1.1
        return ctx
    }
}

/**
 * 귀모: PRE magic +25%, magic damage ×1.3.
 * On magic fail: self-damage (legacy: warMagicFailDamage).
 */
object 귀모Trigger : BattleTrigger {
    override val code = "귀모"
    override val priority = 10
    override fun onPreMagic(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.magicChanceBonus += 0.25
        ctx.magicDamageMultiplier *= 1.3
        return ctx
    }

    override fun onMagicFail(ctx: BattleTriggerContext): BattleTriggerContext {
        // Failed stratagem causes self-damage proportional to intel
        ctx.magicFailDamage += ctx.attacker.intel * 0.5
        ctx.battleLogs.add("계략 실패! 역효과 발생!")
        return ctx
    }
}

/** 공성: damage calc ×1.3 when attacking city. */
object 공성Trigger : BattleTrigger {
    override val code = "공성"
    override val priority = 10
    override fun onDamageCalc(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.isVsCity) {
            ctx.attackMultiplier *= 1.3
            ctx.battleLogs.add("공성 발동! 성벽에 큰 피해!")
        }
        return ctx
    }
}

/** 철벽: PRE dodge +12%, init injury immune. */
object 철벽Trigger : BattleTrigger {
    override val code = "철벽"
    override val priority = 10
    override fun onBattleInit(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.injuryImmune = true
        return ctx
    }

    override fun onPreDodge(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.dodgeChanceBonus += 0.12
        return ctx
    }

    override fun onDamageCalc(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.defenceMultiplier *= 1.1
        return ctx
    }
}

/** 분투: damage calc ×1.05. When HP < 50%, ×1.15 instead. */
object 분투Trigger : BattleTrigger {
    override val code = "분투"
    override val priority = 10
    override fun onDamageCalc(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.attacker.hp < ctx.attacker.maxHp / 2) {
            ctx.attackMultiplier *= 1.15
            ctx.battleLogs.add("분투 발동! 사력을 다한다!")
        } else {
            ctx.attackMultiplier *= 1.05
        }
        return ctx
    }
}

/** 용병: damage calc ×1.05, morale boost +2 per phase. */
object 용병Trigger : BattleTrigger {
    override val code = "용병"
    override val priority = 10
    override fun onDamageCalc(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.attackMultiplier *= 1.05
        return ctx
    }

    override fun onPostDamage(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.moraleBoost += 2
        return ctx
    }
}

/** 견고: init injury immune, defence ×1.05. Legacy: che_부상무효 from 견고. */
object 견고Trigger : BattleTrigger {
    override val code = "견고"
    override val priority = 10
    override fun onBattleInit(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.injuryImmune = true
        ctx.battleLogs.add("견고 발동! 부상 무효!")
        return ctx
    }

    override fun onInjuryCheck(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.injuryImmune = true
        return ctx
    }

    override fun onDamageCalc(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.defenceMultiplier *= 1.05
        return ctx
    }
}

/** 수군: damage calc ×1.05. */
object 수군Trigger : BattleTrigger {
    override val code = "수군"
    override val priority = 10
    override fun onDamageCalc(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.attackMultiplier *= 1.05
        return ctx
    }
}

/** 연사: damage calc ×1.08. */
object 연사Trigger : BattleTrigger {
    override val code = "연사"
    override val priority = 10
    override fun onDamageCalc(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.attackMultiplier *= 1.08
        return ctx
    }
}

/** 반격: post damage - reflect 20% of received damage. */
object 반격Trigger : BattleTrigger {
    override val code = "반격"
    override val priority = 10
    override fun onPostDamage(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.counterDamageRatio += 0.20
        ctx.battleLogs.add("반격 발동!")
        return ctx
    }
}

/** 사기진작: post damage - morale boost +3 per phase. */
object 사기진작Trigger : BattleTrigger {
    override val code = "사기진작"
    override val priority = 10
    override fun onPostDamage(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.moraleBoost += 3
        ctx.battleLogs.add("사기진작 발동!")
        return ctx
    }
}

/** 부상무효: injury check - prevent wounds. */
object 부상무효Trigger : BattleTrigger {
    override val code = "부상무효"
    override val priority = 10
    override fun onInjuryCheck(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.injuryImmune = true
        return ctx
    }
}

object Che반계Trigger : BattleTrigger {
    override val code = "che_반계"
    override val priority = 10
    override fun onPreMagic(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.suppressActive) {
            return ctx
        }

        val opponentTryingMagic = (ctx.attacker.magicChance + ctx.magicChanceBonus) > 0.0
        if (!opponentTryingMagic) {
            return ctx
        }

        if (ctx.rng.nextBool(0.4)) {
            ctx.magicReflected = true
            ctx.magicChanceBonus -= 1.0
            ctx.banmokDamageBonus = (ctx.banmokDamageBonus + 0.1).coerceAtMost(1.5)
            ctx.battleLogs.add("반계 발동! 계략을 되돌린다!")
        }
        return ctx
    }

    override fun onPostMagic(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.magicReflected) {
            ctx.magicDamageMultiplier *= ctx.banmokDamageBonus
        }
        return ctx
    }
}

/** 백우선(반계): 30% counter magic, +0.4 banmok damage bonus. */
object Che백우선반계Trigger : BattleTrigger {
    override val code = "che_백우선반계"
    override val priority = 10
    override fun onPreMagic(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.suppressActive) return ctx
        val opponentTryingMagic = (ctx.attacker.magicChance + ctx.magicChanceBonus) > 0.0
        if (!opponentTryingMagic) return ctx
        if (ctx.rng.nextBool(0.3)) {
            ctx.magicReflected = true
            ctx.magicChanceBonus -= 1.0
            ctx.banmokDamageBonus = (ctx.banmokDamageBonus + 0.4).coerceAtMost(1.5)
            ctx.battleLogs.add("백우선 반계 발동! 계략을 되돌린다!")
        }
        return ctx
    }
    override fun onPostMagic(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.magicReflected) {
            ctx.magicDamageMultiplier *= ctx.banmokDamageBonus
        }
        return ctx
    }
}

/** 충차: +50% siege damage, consumable. */
object Che충차Trigger : BattleTrigger {
    override val code = "che_충차"
    override val priority = 10
    override fun onDamageCalc(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.isVsCity) {
            ctx.attackMultiplier *= 1.5
            ctx.battleLogs.add("충차 발동! 성벽에 강한 피해!")
        }
        return ctx
    }
}

object Che공성Trigger : BattleTrigger {
    override val code = "che_공성"
    override val priority = 10
    override fun onDamageCalc(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.isVsCity) {
            ctx.attackMultiplier *= 2.0
            ctx.battleLogs.add("공성 발동! 성벽에 막대한 피해!")
        }
        return ctx
    }
}

object Che돌격Trigger : BattleTrigger {
    override val code = "che_돌격"
    override val priority = 10
    override fun onBattleInit(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.phaseNumber += 2

        val attackerType = CrewType.fromCode(ctx.attacker.crewType)
        val defenderType = CrewType.fromCode(ctx.defender.crewType)
        if (attackerType != null && defenderType != null) {
            val attackCoef = attackerType.getAttackCoef(defenderType)
            if (attackCoef >= 1.0) {
                ctx.newOpponent = true
            }
        }

        return ctx
    }

    override fun onDamageCalc(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.attackMultiplier *= 1.05
        return ctx
    }
}

object Che견고Trigger : BattleTrigger {
    override val code = "che_견고"
    override val priority = 10
    override fun onBattleInit(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.injuryImmune = true
        ctx.battleLogs.add("견고 발동! 부상 무효!")
        return ctx
    }
    override fun onInjuryCheck(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.injuryImmune = true
        return ctx
    }
    // NOTE: Defence multiplier [1, 0.9] is now handled in SpecialModifiers che_견고
    // via onCalcOpposeStat(warPower * 0.9), matching core2026 exactly.
    // No defenceMultiplier adjustment needed here.
}

object Che위압Trigger : BattleTrigger {
    override val code = "che_위압"
    override val priority = 20
    override fun onBattleInit(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.intimidated = true
        ctx.dodgeDisabled = true
        ctx.criticalDisabled = true
        ctx.magicDisabled = true
        ctx.intimidatePhasesRemaining = 1
        if (ctx.defender is WarUnitGeneral) {
            ctx.defender.atmos = (ctx.defender.atmos - 5).coerceAtLeast(0)
        }
        ctx.battleLogs.add("위압 발동! 적이 위축되었다!")
        return ctx
    }

    override fun onPreCritical(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.criticalDisabled) {
            ctx.criticalChanceBonus -= 1.0
        }
        return ctx
    }

    override fun onPreMagic(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.magicDisabled) {
            ctx.magicChanceBonus -= 1.0
        }
        return ctx
    }

    override fun onDamageCalc(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.intimidatePhasesRemaining > 0) {
            ctx.attackMultiplier = 0.0
            ctx.intimidatePhasesRemaining -= 1
        }
        return ctx
    }
}

object Che저격Trigger : BattleTrigger {
    override val code = "che_저격"
    override val priority = 15
    override fun onBattleInit(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.snipeImmune) {
            return ctx
        }
        if (ctx.newOpponent && ctx.rng.nextBool(0.5)) {
            ctx.snipeActivated = true
            ctx.snipeWoundAmount = ctx.rng.nextInt(20, 41)
            ctx.moraleBoost += 20
            ctx.battleLogs.add("저격 발동! 적장에게 부상을 입혔다!")
        }
        return ctx
    }
}

object Che필살Trigger : BattleTrigger {
    override val code = "che_필살"
    override val priority = 10
    // NOTE: No onPreCritical bonus here — the +0.3 criticalChance is already
    // set as a stat by che_필살 in SpecialModifiers.onCalcStat.
    // Core2026's che_필살시도 uses self.getComputedCriticalRatio() which already includes the +0.3.
    override fun onPostCritical(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.criticalActivated) {
            ctx.attackMultiplier *= ctx.rollCriticalDamageMultiplier()
            ctx.battleLogs.add("필살 발동! 치명타 피해가 증폭된다!")
        }
        return ctx
    }
}

object Che의술Trigger : BattleTrigger {
    override val code = "che_의술"
    override val priority = 10
    override fun onPostDamage(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.rng.nextBool(0.4)) {
            ctx.defenderDamage = floor(ctx.defenderDamage * 0.7).toInt()
            if (ctx.attacker is WarUnitGeneral) {
                ctx.attacker.injury = 0
            }
            ctx.battleLogs.add("의술 발동! 피해를 줄이고 부상을 회복했다!")
        }
        return ctx
    }
}

object Che격노Trigger : BattleTrigger {
    override val code = "che_격노"
    override val priority = 10
    override fun onPostCritical(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.suppressActive) {
            return ctx
        }
        if (ctx.criticalActivated && ctx.rng.nextBool(0.5)) {
            activateRage(ctx, reactedToCritical = true)
        }
        return ctx
    }

    override fun onPostDodge(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.suppressActive) {
            return ctx
        }
        if (ctx.dodgeActivated && ctx.rng.nextBool(0.25)) {
            activateRage(ctx, reactedToCritical = false)
        }
        return ctx
    }

    override fun onDamageCalc(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.rageActivationCount > 0) {
            // Core2026: self.multiplyWarPowerMultiply(self.criticalDamage())
            // criticalDamage() = rng.nextRange(1.3, 2.0)
            ctx.attackMultiplier *= ctx.rollCriticalDamageMultiplier()
        }
        return ctx
    }

    private fun activateRage(ctx: BattleTriggerContext, reactedToCritical: Boolean) {
        ctx.rageActivationCount += 1
        ctx.rageDamageStack = 0.2 * ctx.rageActivationCount
        if (ctx.rng.nextBool(0.5)) {
            ctx.rageExtraPhases += 1
            ctx.battleLogs.add(
                if (reactedToCritical) "격노 발동! 상대 필살에 진노하여 추가 페이즈를 얻었다!"
                else "격노 발동! 상대 회피 시도에 진노하여 추가 페이즈를 얻었다!"
            )
        } else {
            ctx.battleLogs.add(
                if (reactedToCritical) "격노 발동! 상대 필살에 분노가 쌓인다!"
                else "격노 발동! 상대 회피 시도에 분노가 쌓인다!"
            )
        }
    }
}

object Che척사Trigger : BattleTrigger {
    override val code = "che_척사"
    override val priority = 10
    override fun onDamageCalc(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.isVsCity) {
            ctx.attackMultiplier *= 1.2
            ctx.battleLogs.add("척사 발동!")
        }
        return ctx
    }
}

object Che약탈TryTrigger : BattleTrigger {
    override val code = "che_약탈_try"
    override val priority = 10
    override fun onBattleInit(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.newOpponent && ctx.rng.nextBool(0.2)) {
            ctx.plunderActivated = true
            ctx.plunderRatio = 0.1
        }
        return ctx
    }
}

object Che약탈FireTrigger : BattleTrigger {
    override val code = "che_약탈_fire"
    override val priority = 10
    override fun onPostDamage(ctx: BattleTriggerContext): BattleTriggerContext {
        if (!ctx.plunderActivated || ctx.plunderRatio <= 0.0) {
            return ctx
        }

        val self = ctx.attacker as? WarUnitGeneral ?: return ctx
        val oppose = ctx.defender as? WarUnitGeneral ?: return ctx

        val theftGold = floor(oppose.general.gold * ctx.plunderRatio).toInt().coerceAtLeast(0)
        val theftRice = floor(oppose.general.rice * ctx.plunderRatio).toInt().coerceAtLeast(0)

        oppose.general.gold = (oppose.general.gold - theftGold).coerceAtLeast(0)
        oppose.general.rice = (oppose.general.rice - theftRice).coerceAtLeast(0)
        oppose.rice = oppose.general.rice

        self.general.gold += theftGold
        self.general.rice += theftRice
        self.rice = self.general.rice

        ctx.plunderGold += theftGold
        ctx.plunderRice += theftRice
        ctx.battleLogs.add("약탈 발동! 금 $theftGold, 쌀 $theftRice 를 빼앗았다!")
        ctx.plunderActivated = false
        ctx.plunderRatio = 0.0

        return ctx
    }
}

object Che사기Trigger : BattleTrigger {
    override val code = "che_사기"
    override val priority = 10
    override fun onBattleInit(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.attacker.atmos = (ctx.attacker.atmos + 30).coerceAtMost(100)
        ctx.battleLogs.add("사기 발동! 아군 사기가 상승했다!")
        return ctx
    }
}

object Che퇴각부상무효Trigger : BattleTrigger {
    override val code = "che_퇴각부상무효"
    override val priority = 10
    override fun onBattleInit(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.retreatInjuryImmune = true
        return ctx
    }

    override fun onInjuryCheck(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.retreatInjuryImmune) {
            ctx.injuryImmune = true
        }
        return ctx
    }
}

object Che부적Trigger : BattleTrigger {
    override val code = "che_부적"
    override val priority = 10
    override fun onBattleInit(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.snipeImmune = true
        ctx.injuryImmune = true
        return ctx
    }

    override fun onInjuryCheck(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.injuryImmune = true
        return ctx
    }
}

object Che저지Trigger : BattleTrigger {
    override val code = "che_저지"
    override val priority = 10

    override fun onBattleInit(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.blockPhasesRemaining == 0) {
            ctx.blockPhasesRemaining = 1
            if (ctx.phaseNumber == 0) {
                ctx.blockPhasesRemaining += 1
            }
        }
        return ctx
    }

    override fun onPreCritical(ctx: BattleTriggerContext): BattleTriggerContext {
        return ctx
    }

    override fun onPreDodge(ctx: BattleTriggerContext): BattleTriggerContext {
        return ctx
    }

    override fun onDamageCalc(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.blockPhasesRemaining > 0) {
            ctx.attackMultiplier = 0.0
            ctx.defenderDamage = 0
            ctx.blockPhasesRemaining -= 1
            ctx.battleLogs.add("저지 발동! 전투를 봉쇄했다!")
        }
        return ctx
    }
}

object Che진압Trigger : BattleTrigger {
    override val code = "che_진압"
    override val priority = 10

    override fun onBattleInit(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.suppressActive = true
        return ctx
    }

    override fun onPostMagic(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.suppressActive) {
            ctx.magicReflected = false
        }
        return ctx
    }

    override fun onPostDodge(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.suppressActive) {
            ctx.rageDamageStack = 0.0
        }
        return ctx
    }
}

object Che훈련InitTrigger : BattleTrigger {
    override val code = "che_훈련Init"
    override val priority = 10
    override fun onBattleInit(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.attacker.train = (ctx.attacker.train + 40).coerceAtMost(110)
        return ctx
    }
}

object BattleTriggerRegistry {
    private val triggers = listOf(
        // Existing
        필살Trigger, 회피Trigger, 반계Trigger, 신산Trigger,
        위압Trigger, 저격Trigger, 격노Trigger, 돌격Trigger,
        // New combat triggers
        화공Trigger, 기습Trigger, 매복Trigger, 방어Trigger,
        귀모Trigger, 공성Trigger, 철벽Trigger, 분투Trigger,
        용병Trigger, 견고Trigger, 수군Trigger, 연사Trigger,
        // Standalone effect triggers
        반격Trigger, 사기진작Trigger, 부상무효Trigger,
        // Core2026 (che_) triggers
        Che반계Trigger, Che공성Trigger, Che돌격Trigger, Che견고Trigger,
        Che위압Trigger, Che저격Trigger, Che필살Trigger, Che의술Trigger,
        Che격노Trigger, Che척사Trigger,
        Che약탈TryTrigger, Che약탈FireTrigger, Che부적Trigger, Che저지Trigger,
        Che진압Trigger, Che훈련InitTrigger, Che사기Trigger, Che퇴각부상무효Trigger,
        Che백우선반계Trigger, Che충차Trigger,
    ).associateBy { it.code }

    fun get(code: String): BattleTrigger? = triggers[code]

    fun allCodes(): Set<String> = triggers.keys
}
