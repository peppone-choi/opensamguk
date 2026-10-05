package opensamguk.gameapi.read

import opensamguk.gameapi.dto.CountyGarrisonDto
import opensamguk.gameapi.dto.CountyIncomeDto
import opensamguk.logic.economy.CountyIncome
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.input.CityMilitaryState

/** Pure projections. Callers must establish ownership, world identity and current vision first. */
internal object CountyDetailProjection {
    fun garrison(city: CityReadEntity): CountyGarrisonDto? {
        if (CityMilitaryState.META_KEY !in city.meta) return null
        return try {
            val state = CityMilitaryState.read(city.meta)
            CountyGarrisonDto(state.troops, state.training, state.morale)
        } catch (_: IllegalArgumentException) { null }
    }

    fun income(city: CityReadEntity): CountyIncomeDto? = try {
        val warehouse = CountyWarehouse.read(city.meta, city.id)
        if (warehouse == null) CountyIncomeDto(0, 0) else {
            val amount = CountyIncome.monthly(CountyIncome.CountyState(city.nationId, city.population,
                city.commerce, city.commerceMax, city.agriculture, city.agricultureMax, city.supplyState != 0))
            CountyIncomeDto(amount.money, amount.grain)
        }
    } catch (_: IllegalArgumentException) { null }
      catch (_: ArithmeticException) { null }
}
