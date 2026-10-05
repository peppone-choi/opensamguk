package opensamguk.gameapi.season

import com.fasterxml.jackson.annotation.JsonInclude
import opensamguk.logic.input.Phase
import opensamguk.logic.season.Season
import opensamguk.logic.season.SeasonalEventKind

enum class WorldSeasonStatus { READY, NOT_SEEDED, UNAVAILABLE }
enum class WorldSeasonPassageStatus { READY, UNAVAILABLE }

/** Season and passage are world-public; seasonal events are narrowed to the actor nation's current counties. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class WorldSeasonDto(
    val status: WorldSeasonStatus,
    val reason: String?,
    val now: Phase?,
    val season: Season?,
    val phaseOfYear: Int?,
    val passageStatus: WorldSeasonPassageStatus,
    val closedEdges: List<WorldSeasonClosedEdgeDto>?,
    val eventsStatus: WorldSeasonStatus,
    val events: List<WorldSeasonEventDto>?,
)

/** C5 accepted candidate; only real traversal edge ids, no geometry. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class WorldSeasonClosedEdgeDto(val edgeId: String, val endpoints: List<String>, val label: String?)

/** logic `SeasonalOccurrence` as is. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class WorldSeasonEventDto(val countyId: Int, val kind: SeasonalEventKind, val effect: WorldSeasonEffectDto)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class WorldSeasonEffectDto(
    val trust: Int,
    val population: Int,
    val agriculture: Int,
    val displaced: Int,
    val passageClosed: Boolean,
)

data class WorldSeasonErrorDto(val error: WorldSeasonErrorDetailDto)
data class WorldSeasonErrorDetailDto(val code: String, val message: String)
