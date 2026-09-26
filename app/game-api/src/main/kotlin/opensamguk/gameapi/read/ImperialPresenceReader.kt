package opensamguk.gameapi.read

import com.fasterxml.jackson.annotation.JsonInclude
import opensamguk.logic.imperial.ImperialLineStatus
import opensamguk.logic.imperial.ImperialPresenceProjection
import opensamguk.logic.imperial.ImperialWorldCodec
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class ImperialPresenceResponse(
    val status: String,
    val badges: List<ImperialPresenceBadgeResponse>,
)

data class ImperialPresenceBadgeResponse(
    val lineCode: String,
    val lineName: String,
    val emperorGeneralId: Int,
    val emperorCityId: Int,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val courtCityId: Int?,
)

/** Read the process world and its general positions from one database snapshot. */
@Component
class ImperialPresenceReader(
    private val worlds: WorldStateReadRepository,
    private val generals: GeneralReadRepository,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(): ImperialPresenceResponse {
        val world = worlds.findProcessWorld()
            ?: return ImperialPresenceResponse("STATE_UNAVAILABLE", emptyList())
        return try {
            val imperial = ImperialWorldCodec.read(world.meta)
                ?: return ImperialPresenceResponse("NOT_SEEDED", emptyList())
            val positions = imperial.houses.asSequence()
                .filter { it.status == ImperialLineStatus.ACTIVE }
                .mapNotNull { it.holderGeneralId }
                .mapNotNull { id -> generals.findById(id).orElse(null)?.let { id to it.cityId } }
                .toMap()
            val badges = ImperialPresenceProjection.badges(imperial, positions).map {
                ImperialPresenceBadgeResponse(it.lineCode, it.lineName, it.emperorGeneralId,
                    it.emperorCityId, it.courtCityId)
            }
            ImperialPresenceResponse("READY", badges)
        } catch (_: IllegalArgumentException) {
            ImperialPresenceResponse("STATE_UNAVAILABLE", emptyList())
        }
    }
}
