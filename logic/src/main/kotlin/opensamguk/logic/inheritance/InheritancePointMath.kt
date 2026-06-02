package opensamguk.logic.inheritance

import opensamguk.common.constants.GameConst
import opensamguk.common.constants.GameUnitConst

object InheritancePointMath {

    fun compute(
        general: GeneralProxy,
        key: InheritanceKey,
        isUnited: Boolean,
        forceCalc: Boolean = false,
    ): Double? {
        if (general.owner == 0) return 0.0
        if (general.npc >= 2) return 0.0

        val type = InheritanceKeyRegistry[key]
        val storeType = type.storeType

        if (storeType == true || (isUnited && !forceCalc)) {
            return null
        }

        if (isUnited && key != InheritanceKey.PREVIOUS) {
            return 0.0
        }

        if (storeType is List<*>) {
            val subtype = storeType[0] as String
            val subkey = storeType[1] as String
            if (subtype == "rank") {
                return general.getRankVar(subkey) * type.pointCoeff
            }
            throw IllegalArgumentException("$subtype 은 참조 할 수 없는 유산 세부키임")
        }

        if (storeType == false) {
            return when (key) {
                InheritanceKey.DEX -> computeDex(general, type.pointCoeff)
                InheritanceKey.BETTING -> computeBetting(general, type.pointCoeff)
                InheritanceKey.MAX_BELONG -> computeMaxBelong(general, type.pointCoeff)
                else -> throw IllegalArgumentException("$key 는 유산 추출기를 보유하고 있지 않음")
            }
        }

        throw IllegalArgumentException("$storeType 은 올바르지 않은 유산 키임")
    }

    private fun computeDex(general: GeneralProxy, coeff: Double): Double {
        var totalDex = 0
        for (armType in GameUnitConst.allType().keys) {
            val subDex = general.getVar("dex$armType")
            val dexLimit = GameConst.dexLimit
            if (subDex > dexLimit) {
                totalDex += (subDex - dexLimit) / 3
                totalDex += dexLimit
            } else {
                totalDex += subDex
            }
        }
        return totalDex * coeff
    }

    private fun computeBetting(general: GeneralProxy, coeff: Double): Double {
        val betWin = general.getRankVar("betwin")
        val betWinGold = general.getRankVar("betwingold")
        val betGold = general.getRankVar("betgold")
        val betWinRate = betWinGold.toDouble() / maxOf(1000, betGold)
        return betWin * coeff * (betWinRate * betWinRate)
    }

    private fun computeMaxBelong(general: GeneralProxy, coeff: Double): Double {
        val belong = general.getVar("belong")
        val auxMaxBelong = (general.getAuxVar("max_belong") as? Number)?.toInt() ?: 0
        return maxOf(belong, auxMaxBelong) * coeff
    }
}
