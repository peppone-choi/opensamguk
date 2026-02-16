package com.opensam.engine.war

import com.opensam.entity.General

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
    }

    /** Computed attack: stat + tech component. Train/atmos applied separately in war power. */
    override fun getBaseAttack(): Double {
        val statBonus = (strength * 0.7 + leadership * 0.3)
        val techBonus = 1.0 + tech / 1000.0
        return statBonus * techBonus * attackMultiplier
    }

    /** Computed defence: stat + tech component. Train applied separately in war power. */
    override fun getBaseDefence(): Double {
        val statBonus = (leadership * 0.5 + strength * 0.3 + intel * 0.2)
        val techBonus = 1.0 + tech / 1000.0
        return statBonus * techBonus * defenceMultiplier
    }

    /** Legacy: HP > 0 AND rice > crew/100. */
    override fun continueWar(): Boolean {
        if (hp <= 0) return false
        if (rice <= hp / 100) return false
        return true
    }

    fun consumeRice(damageDealt: Int) {
        val consumption = (damageDealt / 100.0).coerceAtLeast(1.0).toInt()
        rice = (rice - consumption).coerceAtLeast(0)
    }

    fun applyResults() {
        general.crew = hp.coerceAtLeast(0)
        general.rice = rice.coerceAtLeast(0)
        general.train = train.toShort()
        general.atmos = atmos.toShort()
        general.injury = injury.toShort()
    }
}
