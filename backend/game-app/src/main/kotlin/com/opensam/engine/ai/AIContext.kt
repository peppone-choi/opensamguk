package com.opensam.engine.ai

import com.opensam.entity.*

data class AIContext(
    val world: WorldState,
    val general: General,
    val city: City,
    val nation: Nation?,
    val diplomacyState: DiplomacyState,
    val generalType: Int,
    val allCities: List<City>,
    val allGenerals: List<General>,
    val allNations: List<Nation>,
    val frontCities: List<City>,
    val rearCities: List<City>,
    val nationGenerals: List<General>,
)
