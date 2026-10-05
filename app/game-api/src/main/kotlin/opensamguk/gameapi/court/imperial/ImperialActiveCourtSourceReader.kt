package opensamguk.gameapi.court.imperial

import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.ImperialPresenceReader
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.input.Phase
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.Collections

/**
 * Internal ACTIVE-only source. The proxied presence reader joins this read-only transaction;
 * its codec, artifact and spatial validation remain the authority for presence badges.
 */
@Component
class ImperialActiveCourtSourceReader(
    private val worlds: WorldStateReadRepository,
    private val presence: ImperialPresenceReader,
    private val cities: CityReadRepository,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(): ImperialActiveCourtSource = try {
        val world = requireNotNull(worlds.findProcessWorld())
        require(world.id > 0 && world.currentYear > 0)
        val context = ImperialActiveCourtContext(world.id,
            Phase(world.currentYear, world.currentMonth, world.currentPhase))
        val approved = presence.read()
        when (approved.status) {
            "NOT_SEEDED" -> {
                require(approved.badges.isEmpty())
                ImperialActiveCourtSource(ImperialActiveCourtSourceStatus.NOT_SEEDED, context)
            }
            "READY" -> {
                val courtNames = approved.badges.mapNotNull { it.courtCityId }.distinct().associateWith { id ->
                    val city = requireNotNull(cities.findById(id).orElse(null))
                    require(city.id == id && city.worldId == world.id)
                    city.name.takeIf { it.isNotBlank() }
                }
                val lines = approved.badges.map { badge ->
                    ImperialActiveCourtLine(badge, badge.courtCityId?.let { courtNames[it] })
                }
                ImperialActiveCourtSource(ImperialActiveCourtSourceStatus.READY, context,
                    Collections.unmodifiableList(lines))
            }
            else -> unavailable()
        }
    } catch (_: IllegalArgumentException) {
        unavailable()
    } catch (_: IllegalStateException) {
        unavailable()
    } catch (cause: ResponseStatusException) {
        // The world repository reports malformed WorldFormat as CONFLICT. Other failures propagate.
        if (cause.statusCode == HttpStatus.CONFLICT) unavailable() else throw cause
    }

    private fun unavailable() = ImperialActiveCourtSource(ImperialActiveCourtSourceStatus.UNAVAILABLE)
}
