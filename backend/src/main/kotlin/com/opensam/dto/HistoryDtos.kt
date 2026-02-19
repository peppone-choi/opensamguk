package com.opensam.dto

import com.opensam.entity.Message
import com.opensam.entity.YearbookHistory

data class YearbookNationSummary(
    val id: Long,
    val name: String,
    val color: String,
    val territoryCount: Int,
    val generalCount: Int?,
    val cities: List<String>,
)

data class YearbookSummaryResponse(
    val worldId: Long,
    val year: Int,
    val month: Int,
    val nations: List<YearbookNationSummary>,
    val keyEvents: List<MessageResponse>,
) {
    companion object {
        fun from(
            worldId: Long,
            yearbook: YearbookHistory,
            keyEvents: List<Message>,
        ): YearbookSummaryResponse {
            val nations = yearbook.nations.map { nation ->
                val cities = (nation["cities"] as? List<*>)
                    ?.mapNotNull { it?.toString() }
                    ?: emptyList()
                val id = (nation["id"] as? Number)?.toLong() ?: 0L
                val name = nation["name"]?.toString() ?: "-"
                val color = nation["color"]?.toString() ?: "#6b7280"
                val generalCount = (nation["generalCount"] as? Number)?.toInt()

                YearbookNationSummary(
                    id = id,
                    name = name,
                    color = color,
                    territoryCount = cities.size,
                    generalCount = generalCount,
                    cities = cities,
                )
            }

            return YearbookSummaryResponse(
                worldId = worldId,
                year = yearbook.year.toInt(),
                month = yearbook.month.toInt(),
                nations = nations,
                keyEvents = keyEvents.map { MessageResponse.from(it) },
            )
        }
    }
}
