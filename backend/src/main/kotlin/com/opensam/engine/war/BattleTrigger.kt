package com.opensam.engine.war

import kotlin.random.Random

data class BattleTriggerContext(
    val attacker: WarUnit,
    val defender: WarUnit,
    val rng: Random,
    var attackerDamage: Int = 0,
    var defenderDamage: Int = 0,
    var criticalActivated: Boolean = false,
    var dodgeActivated: Boolean = false,
    var magicActivated: Boolean = false,
    var magicReflected: Boolean = false,
    var criticalChanceBonus: Double = 0.0,
    var dodgeChanceBonus: Double = 0.0,
    var magicChanceBonus: Double = 0.0,
    var attackMultiplier: Double = 1.0,
    var defenceMultiplier: Double = 1.0,
    var dodgeDisabled: Boolean = false,
)

/**
 * Battle trigger with PRE/POST split.
 * PRE (시도/attempt): modifies chances BEFORE the roll.
 * POST (발동/execute): applies effects AFTER a successful roll.
 */
interface BattleTrigger {
    val code: String
    val priority: Int

    // PRE triggers - modify chance before roll
    fun onPreCritical(ctx: BattleTriggerContext): BattleTriggerContext = ctx
    fun onPreDodge(ctx: BattleTriggerContext): BattleTriggerContext = ctx
    fun onPreMagic(ctx: BattleTriggerContext): BattleTriggerContext = ctx

    // POST triggers - apply effects after successful roll
    fun onPostCritical(ctx: BattleTriggerContext): BattleTriggerContext = ctx
    fun onPostDodge(ctx: BattleTriggerContext): BattleTriggerContext = ctx
    fun onPostMagic(ctx: BattleTriggerContext): BattleTriggerContext = ctx

    // Damage calc (applied after all rolls)
    fun onDamageCalc(ctx: BattleTriggerContext): BattleTriggerContext = ctx
}

/** 필살: PRE +30% critical chance, disable dodge. */
object 필살Trigger : BattleTrigger {
    override val code = "필살"
    override val priority = 10
    override fun onPreCritical(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.criticalChanceBonus += 0.30
        ctx.dodgeDisabled = true
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

/** 저격: PRE +8% critical chance. */
object 저격Trigger : BattleTrigger {
    override val code = "저격"
    override val priority = 15
    override fun onPreCritical(ctx: BattleTriggerContext): BattleTriggerContext {
        ctx.criticalChanceBonus += 0.08
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

object BattleTriggerRegistry {
    private val triggers = listOf(
        필살Trigger, 회피Trigger, 반계Trigger, 신산Trigger,
        위압Trigger, 저격Trigger, 격노Trigger, 돌격Trigger,
    ).associateBy { it.code }

    fun get(code: String): BattleTrigger? = triggers[code]
}
