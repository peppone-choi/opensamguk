package com.opensam.engine.war

import com.opensam.model.ArmType
import kotlin.math.pow

abstract class WarUnit(
    val name: String,
    val nationId: Long,
) {
    var hp: Int = 0
    var maxHp: Int = 0
    var crew: Int = 0
    var train: Int = 0
    var atmos: Int = 0
    var crewType: Int = 0
    var leadership: Int = 0
    var strength: Int = 0
    var intel: Int = 0
    var experience: Int = 0
    var dedication: Int = 0
    var tech: Float = 0f
    var injury: Int = 0

    var attackMultiplier: Double = 1.0
    var defenceMultiplier: Double = 1.0
    var criticalChance: Double = 0.05
    var dodgeChance: Double = 0.05
    var magicChance: Double = 0.0
    var magicDamageMultiplier: Double = 1.0

    var rice: Int = 0

    var activatedSkills: MutableList<String> = mutableListOf()
    var isAlive: Boolean = true

    abstract fun getBaseAttack(): Double
    abstract fun getBaseDefence(): Double

    /**
     * Get dex (경험치) for the given arm type.
     * Legacy: GeneralBase::getDex() returns dex{armType}, castle maps to siege.
     */
    open fun getDexForArmType(armType: ArmType): Int = 0

    fun calcBattleOrder(): Double {
        val totalStat = (leadership + strength + intel) / 3.0
        val totalCrew = crew / 1000.0 * (train * atmos).toDouble().pow(1.5) / 10000.0
        return totalStat + totalCrew / 100.0
    }

    fun takeDamage(damage: Int) {
        hp -= damage
        if (hp <= 0) {
            hp = 0
            isAlive = false
        }
    }

    /** Returns true if this unit can continue fighting. */
    open fun continueWar(): Boolean = hp > 0

    fun beginPhase() {
        activatedSkills.clear()
    }
}
