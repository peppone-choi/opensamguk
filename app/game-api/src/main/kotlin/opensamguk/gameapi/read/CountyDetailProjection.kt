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

    // Draft test-first stage: no allocation is exposed until the canonical producer is connected.
    fun income(city: CityReadEntity): CountyIncomeDto? = null
}
