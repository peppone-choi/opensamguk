package opensamguk.gameapi.dto

/** IDs and approved typed facts only. The client formats messages for the selected language. */
data class GameEventTimeDto(val year: Int, val month: Int, val phase: Int, val ordinal: Int)
data class GameEventDto(
    val id: Long,
    val kind: String,
    val section: String,
    val occurredAt: GameEventTimeDto,
    val refs: Map<String, Any>,
    val facts: Map<String, Any>,
)
data class GameEventPage(val events: List<GameEventDto>, val nextCursor: String?)
