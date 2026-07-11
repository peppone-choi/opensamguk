package opensamguk.logic.war

import opensamguk.logic.war.trigger.WarUnitTriggerCaller

class ProductionWarBattleHooks(
    private val defenderNationRice: Double,
    private val citySupply: Boolean,
) : WarBattleHooks {
    override fun battleInitCaller(unit: WarUnit): WarUnitTriggerCaller? =
        (unit as? WarUnitGeneral)?.battleInitCaller()

    override fun battlePhaseCaller(unit: WarUnit): WarUnitTriggerCaller? =
        (unit as? WarUnitGeneral)?.battlePhaseCaller()

    override fun defenderNationRice(city: WarUnitCity): Double = defenderNationRice

    override fun citySupply(city: WarUnitCity): Boolean = citySupply

    override fun addTrain(unit: WarUnit, amount: Int) {
        (unit as? WarUnitGeneral)?.addTrain(amount)
    }

    override fun addLevelExp(unit: WarUnitGeneral, value: Double) {
        unit.addLevelExpBonus(value)
    }

    override fun heavyDecreaseWealth(city: WarUnitCity) {
        city.state.heavyDecreaseWealth()
    }

    override fun addConflict(city: WarUnitCity, attacker: WarUnitGeneral): Boolean {
        val conflict = ConflictMap.decode(city.state.city.conflict)
        val newConflict = conflict.addConflict(
            attackerNationId = attacker.getGeneral().nationId,
            dead = city.getDead(),
            cityHp = city.getHP(),
            isEmptyBefore = conflict.keysInOrder().isEmpty(),
        )
        city.state.updateConflict(conflict.encode())
        return newConflict
    }
}
