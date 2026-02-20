package com.opensam.shared.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScenarioData(
    val title: String = "",
    val startYear: Int = 180,
    val life: Int = 0,
    val fiction: Int = 0,
    val history: List<String> = emptyList(),
    val nation: List<List<Any>> = emptyList(),
    val diplomacy: List<List<Any>> = emptyList(),
    val general: List<List<Any?>> = emptyList(),
    @JsonProperty("general_ex")
    val generalEx: List<List<Any?>> = emptyList(),
    val events: List<List<Any>> = emptyList(),
    val map: ScenarioMap? = null,
    val const: Map<String, Any> = emptyMap(),
    val stat: ScenarioStat? = null,
    val iconPath: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScenarioMap(
    val mapName: String = "che",
    val unitSet: String = "che",
    val scenarioEffect: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScenarioStat(
    val total: Int = 165,
    val min: Int = 15,
    val max: Int = 80,
    val npcTotal: Int = 150,
    val npcMax: Int = 75,
    val npcMin: Int = 10,
    val chiefMin: Int = 65,
)

data class ScenarioInfo(
    val code: String,
    val title: String,
    val startYear: Int,
)
