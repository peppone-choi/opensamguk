package com.opensam.engine.war

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
)

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
    override fun onPostMagic(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.magicActivated && ctx.rng.nextDouble() < 0.4) {
            ctx.magicReflected = true
            ctx.magicDamageMultiplier *= 1.9
            ctx.battleLogs.add("반계 발동! 계략을 되돌린다!")
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
    override fun onDamageCalc(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.defenceMultiplier *= 1.1
        return ctx
    }
}

object Che위압Trigger : BattleTrigger {
    override val code = "che_위압"
    override val priority = 20
    override fun onBattleInit(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.intimidated = true
        ctx.battleLogs.add("위압 발동! 적이 위축되었다!")
        return ctx
    }
}

object Che저격Trigger : BattleTrigger {
    override val code = "che_저격"
    override val priority = 15
    override fun onBattleInit(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.newOpponent && ctx.rng.nextDouble() < 0.5) {
            ctx.snipeActivated = true
            ctx.snipeWoundAmount = ctx.rng.nextInt(2, 7)
            ctx.moraleBoost += 20
            ctx.battleLogs.add("저격 발동! 적장에게 부상을 입혔다!")
        }
        return ctx
    }
}

object Che필살Trigger : BattleTrigger {
    override val code = "che_필살"
    override val priority = 10
    override fun onPreCritical(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.criticalChanceBonus += 0.30
        return ctx
    }
    override fun onPostCritical(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.criticalActivated) {
            ctx.dodgeDisabled = true
            ctx.battleLogs.add("필살 발동! 회피 불가!")
        }
        return ctx
    }
}

object Che의술Trigger : BattleTrigger {
    override val code = "che_의술"
    override val priority = 10
    override fun onPostDamage(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.rng.nextDouble() < 0.4) {
            ctx.healAmount += (ctx.attacker.maxHp * 0.05).toInt()
            ctx.battleLogs.add("의술 발동! 부대를 치료했다!")
        }
        return ctx
    }
}

object Che격노Trigger : BattleTrigger {
    override val code = "che_격노"
    override val priority = 10
    override fun onPostDodge(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.dodgeActivated) {
            ctx.rageDamageStack += 0.20
            ctx.battleLogs.add("격노 발동! 분노가 쌓인다!")
        }
        return ctx
    }
    override fun onDamageCalc(ctx: BattleTriggerContext): BattleTriggerContext {
        if (ctx.rageDamageStack > 0) {
            ctx.attackMultiplier *= (1.0 + ctx.rageDamageStack)
        }
        return ctx
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
    ).associateBy { it.code }

    fun get(code: String): BattleTrigger? = triggers[code]

    fun allCodes(): Set<String> = triggers.keys
}
