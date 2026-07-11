package opensamguk.gameapi.read

import opensamguk.logic.domestic.calcCityGoldIncome
import opensamguk.logic.domestic.calcCityRiceIncome
import opensamguk.logic.domestic.calcCityWallRiceIncome
import opensamguk.logic.domestic.getGoldIncome
import opensamguk.logic.domestic.getOutcome
import opensamguk.logic.domestic.getRiceIncome
import opensamguk.logic.domestic.getWallIncome
import opensamguk.logic.domain.City
import opensamguk.logic.domain.Nation
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.traits.NationTypeRegistry
import opensamguk.logic.util.phpRound

class NationFinanceReadProjector {
    private val pipeline = GeneralActionPipeline()

    fun city(city: City, nation: Nation, officerCount: Int): CityFinanceProjection? {
        val rate = (nation.meta["rate"] as? Number)?.toDouble() ?: return null
        val type = NationTypeRegistry.resolve(nation.typeCode)
        return CityFinanceProjection(
            goldIncome = phpRound(rate / 20 * calcCityGoldIncome(city, officerCount, city.id == nation.capitalCityId, nation.level, type, pipeline)),
            riceIncome = phpRound(rate / 20 * calcCityRiceIncome(city, officerCount, city.id == nation.capitalCityId, nation.level, type, pipeline)),
            farmIncome = phpRound(rate / 20 * calcCityWallRiceIncome(city, officerCount, city.id == nation.capitalCityId, nation.level, type, pipeline)),
        )
    }

    fun nation(cities: List<City>, nation: Nation, officerCounts: Map<Int, Int>, dedications: List<Double>): NationFinanceProjection {
        val rate = (nation.meta["rate"] as? Number)?.toDouble()
        val bill = (nation.meta["bill"] as? Number)?.toDouble()
        val type = NationTypeRegistry.resolve(nation.typeCode)
        val goldIncome = rate?.let { phpRound(getGoldIncome(cities, nation.capitalCityId ?: 0, nation.level, it, type, pipeline, officerCounts)) }
        val warIncome = rate?.let { cities.sumOf { city -> opensamguk.logic.domestic.calcCityWarGoldIncome(city, type, pipeline) } }
        val riceIncome = rate?.let { phpRound(getRiceIncome(cities, nation.capitalCityId ?: 0, nation.level, it, type, pipeline, officerCounts)) }
        val farmIncome = rate?.let { phpRound(getWallIncome(cities, nation.capitalCityId ?: 0, nation.level, it, type, pipeline, officerCounts)) }
        val outcome = bill?.let { getOutcome(it, dedications) }
        return NationFinanceProjection(
            goldIncome = goldIncome,
            warIncome = warIncome,
            riceIncome = riceIncome,
            farmIncome = farmIncome,
            outcome = outcome,
            goldBudget = if (goldIncome != null && warIncome != null && outcome != null) nation.gold + goldIncome + warIncome - outcome else null,
            goldBudgetDiff = if (goldIncome != null && warIncome != null && outcome != null) goldIncome + warIncome - outcome else null,
            riceBudget = if (riceIncome != null && farmIncome != null && outcome != null) nation.rice + riceIncome + farmIncome - outcome else null,
            riceBudgetDiff = if (riceIncome != null && farmIncome != null && outcome != null) riceIncome + farmIncome - outcome else null,
        )
    }
}

data class CityFinanceProjection(
    val goldIncome: Int,
    val riceIncome: Int,
    val farmIncome: Int,
)

data class NationFinanceProjection(
    val goldIncome: Int?,
    val warIncome: Int?,
    val riceIncome: Int?,
    val farmIncome: Int?,
    val outcome: Int?,
    val goldBudget: Int?,
    val goldBudgetDiff: Int?,
    val riceBudget: Int?,
    val riceBudgetDiff: Int?,
)
