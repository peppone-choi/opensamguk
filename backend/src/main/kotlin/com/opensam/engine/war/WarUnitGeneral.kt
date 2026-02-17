package com.opensam.engine.war

import com.opensam.entity.General
import com.opensam.model.ArmType
import com.opensam.model.CrewType
import kotlin.math.min

class WarUnitGeneral(
    val general: General,
    nationTech: Float = 0f,
) : WarUnit(general.name, general.nationId) {

    init {
        crew = general.crew
        train = general.train.toInt()
        atmos = general.atmos.toInt()
        crewType = general.crewType.toInt()
        leadership = general.leadership.toInt()
        strength = general.strength.toInt()
        intel = general.intel.toInt()
        experience = general.experience
        dedication = general.dedication
        tech = nationTech
        injury = general.injury.toInt()
        rice = general.rice
        hp = crew
        maxHp = crew

        val unitCrewType = getCrewType()
        criticalChance = computeCriticalChance(unitCrewType)
        dodgeChance = unitCrewType.avoid / 100.0 * (train / 100.0)
    }

    /** Computed attack: stat + tech component. Train/atmos applied separately in war power. */
    override fun getBaseAttack(): Double {
        val unitCrewType = getCrewType()
        val ratioByArmType = when (unitCrewType.armType) {
            ArmType.WIZARD -> intel * 2.0 - 40.0
            ArmType.SIEGE -> leadership * 2.0 - 40.0
            else -> strength * 2.0 - 40.0
        }
        val ratio = when {
            ratioByArmType < 10.0 -> 10.0
            ratioByArmType > 100.0 -> 50.0 + ratioByArmType / 2.0
            else -> ratioByArmType
        }
        val attack = unitCrewType.attack + getTechAbil(tech)
        return attack * ratio / 100.0 * attackMultiplier
    }

    /** Computed defence: stat + tech component. Train applied separately in war power. */
    override fun getBaseDefence(): Double {
        val unitCrewType = getCrewType()
        val defence = unitCrewType.defence + getTechAbil(tech)
        val crewFactor = crew / 233.33 + 70.0
        return defence * crewFactor / 100.0 * defenceMultiplier
    }

    /** Legacy: HP > 0 AND rice > crew/100. */
    override fun continueWar(): Boolean {
        if (hp <= 0) return false
        if (rice <= hp / 100) return false
        return true
    }

    fun consumeRice(damageDealt: Int, isAttacker: Boolean = true, vsCity: Boolean = false) {
        val unitCrewType = getCrewType()
        var consumption = damageDealt / 100.0
        if (!isAttacker) consumption *= 0.8
        if (vsCity) consumption *= 0.8
        consumption *= unitCrewType.riceCost
        consumption *= getTechCost(tech)
        rice = (rice - consumption.toInt()).coerceAtLeast(0)
    }

    private fun getCrewType(): CrewType = CrewType.fromCode(crewType) ?: CrewType.FOOTMAN

    private fun computeCriticalChance(unitCrewType: CrewType): Double {
        val (mainStat, coef) = when (unitCrewType.armType) {
            ArmType.WIZARD -> intel to 0.4
            ArmType.SIEGE -> leadership to 0.4
            else -> strength to 0.5
        }
        val ratio = (mainStat - 65).coerceAtLeast(0) * coef
        return min(50.0, ratio) / 100.0
    }

    fun applyResults() {
        general.crew = hp.coerceAtLeast(0)
        general.rice = rice.coerceAtLeast(0)
        general.train = train.toShort()
        general.atmos = atmos.toShort()
        general.injury = injury.toShort()
    }
}
